package com.miller.controltv

import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

/**
 * Remote control session over port 6466 (Android TV Remote v2).
 * Callbacks run on background threads; the UI must post them to the main thread.
 */
class RemoteClient(
    private val tls: Tls,
    private val host: String,
    private val port: Int = 6466,
    private val listener: Listener
) {
    interface Listener {
        fun onReady()
        fun onDisconnected(error: String?, needsPairing: Boolean)
        fun onVolume(level: Int, max: Int, muted: Boolean)
        fun onCurrentApp(packageName: String) {}
        /** A text field got focus on the TV (its keyboard opened). */
        fun onTextField(value: String, label: String) {}
        fun onRemoteError() {}
    }

    companion object {
        // PING | KEY | IME | VOICE | POWER | VOLUME | APP_LINK
        private const val FEATURES = 1 or 2 or 4 or 8 or 32 or 64 or 512
        const val SHORT = 3
        const val START_LONG = 1
        const val END_LONG = 2
        const val VOICE_CHUNK = 8 * 1024
    }

    private val sender = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var running = false
    @Volatile var ready = false
        private set
    @Volatile private var gotMessage = false
    @Volatile private var active = FEATURES

    // IME (keyboard) state, as reported by the TV
    @Volatile private var imeCounter = 0
    @Volatile private var fieldCounter = 0
    @Volatile private var hasImeCounter = false
    @Volatile private var hasFieldCounter = false

    // Voice
    @Volatile private var voiceWaiter: ((Int?) -> Unit)? = null

    fun connect() {
        running = true
        Thread({ runLoop() }, "tv-remote").start()
    }

    private fun runLoop() {
        var error: String? = null
        var needsPairing = false
        var connected = false
        try {
            val s = try {
                tls.connect(host, port)
            } catch (e: SSLHandshakeException) {
                needsPairing = true
                throw e
            }
            connected = true
            socket = s
            val input = s.inputStream
            while (running) {
                val msg = ProtoMessage.parse(Framing.read(input))
                gotMessage = true
                handle(msg)
            }
        } catch (e: Exception) {
            if (running) {
                error = e.message ?: e.javaClass.simpleName
                // An unknown certificate gets the socket closed right after the
                // handshake, before the TV sends anything.
                if (connected && !gotMessage) needsPairing = true
            }
        } finally {
            val wasRunning = running
            running = false
            ready = false
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            voiceWaiter?.let { w -> voiceWaiter = null; w(null) }
            if (wasRunning) listener.onDisconnected(error, needsPairing)
        }
    }

    private fun markReady() {
        if (!ready) {
            ready = true
            listener.onReady()
        }
    }

    private fun handle(msg: ProtoMessage) {
        when {
            msg.has(1) -> {
                // RemoteConfigure: keep only the features the TV supports
                val tvFeatures = msg.message(1)?.int(1) ?: 0
                active = if (tvFeatures != 0) FEATURES and tvFeatures else FEATURES
                val info = ProtoWriter()
                    .string(1, Build.MODEL ?: "Android")
                    .string(2, Build.MANUFACTURER ?: "Android")
                    .varint(3, 1)
                    .string(4, "1")
                    .string(5, "atvremote")
                    .string(6, "1.0.0")
                send(ProtoWriter().message(1, ProtoWriter().varint(1, active).message(2, info)))
            }
            msg.has(2) -> {
                send(ProtoWriter().message(2, ProtoWriter().varint(1, active)))
                // Some TVs never send RemoteStart; the session works after set-active.
                markReady()
            }
            msg.has(3) -> listener.onRemoteError()
            msg.has(8) -> {
                val v = msg.message(8)?.int(1) ?: 0
                send(ProtoWriter().message(9, ProtoWriter().varint(1, v)))
            }
            msg.has(40) -> markReady()
            msg.has(50) -> {
                val v = msg.message(50) ?: return
                listener.onVolume(v.int(7), v.int(6), v.long(8) != 0L)
            }
            msg.has(20) -> {
                // RemoteImeKeyInject: app_info(1){counter=1, app_package=12}, text_field_status(2){counter_field=1}
                val inject = msg.message(20) ?: return
                val app = inject.message(1)
                val field = inject.message(2)
                val counter = app?.int(1) ?: 0
                val fc = field?.int(1) ?: 0
                imeCounter = counter
                fieldCounter = fc
                hasImeCounter = counter != 0
                hasFieldCounter = fc != 0
                val pkg = app?.string(12)
                if (!pkg.isNullOrEmpty()) listener.onCurrentApp(pkg)
            }
            msg.has(22) -> {
                // RemoteImeShowRequest: remote_text_field_status(2){counter_field=1, value=2, label=6}
                val status = msg.message(22)?.message(2)
                if (status != null) {
                    fieldCounter = status.int(1)
                    hasFieldCounter = true
                    listener.onTextField(status.string(2) ?: "", status.string(6) ?: "")
                }
            }
            msg.has(21) -> {
                val b = msg.message(21) ?: return
                if (!hasImeCounter) imeCounter = b.int(1)
                if (!hasFieldCounter) fieldCounter = b.int(2)
            }
            msg.has(30) -> {
                // RemoteVoiceBegin from the TV: the voice session is ready
                val id = msg.message(30)?.int(1) ?: 0
                val w = voiceWaiter
                if (w != null) {
                    voiceWaiter = null
                    send(ProtoWriter().message(30, ProtoWriter().varint(1, id)))
                    w(id)
                }
            }
        }
    }

    private fun send(w: ProtoWriter) {
        val s = socket ?: return
        if (sender.isShutdown) return
        val bytes = w.toByteArray()
        try {
            sender.execute {
                try {
                    Framing.write(s.outputStream, bytes)
                } catch (_: Exception) {
                    // the read loop notices a broken connection
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    fun key(code: Int, direction: Int = SHORT) {
        send(ProtoWriter().message(10, ProtoWriter().varint(1, code).varint(2, direction)))
    }

    fun openLink(link: String) {
        send(ProtoWriter().message(90, ProtoWriter().string(1, link)))
    }

    /** Writes [text] into the focused text field on the TV, through the TV keyboard. */
    fun text(text: String) {
        if (text.isEmpty()) return
        val obj = ProtoWriter()
            .varint(1, text.length)
            .varint(2, text.length)
            .string(3, text)
        val edit = ProtoWriter().varint(1, 1).message(2, obj)
        send(
            ProtoWriter().message(
                21,
                ProtoWriter().varint(1, imeCounter).varint(2, fieldCounter).message(3, edit)
            )
        )
    }

    val supportsVoice: Boolean get() = (active and 8) != 0

    /**
     * Starts a voice session (like holding the mic button on the TV remote).
     * [onReady] gets the session id, or null if the TV does not answer.
     */
    fun startVoice(onReady: (Int?) -> Unit) {
        voiceWaiter = onReady
        key(KeyCodes.SEARCH)
        main.postDelayed({
            val w = voiceWaiter
            if (w === onReady) {
                voiceWaiter = null
                w(null)
            }
        }, 2500)
    }

    /** 16-bit PCM, mono, 8 kHz. Chunks must be at least 8 KB. */
    fun sendVoice(session: Int, pcm: ByteArray) {
        val data = if (pcm.size < VOICE_CHUNK) pcm.copyOf(VOICE_CHUNK) else pcm
        send(ProtoWriter().message(31, ProtoWriter().varint(1, session).bytes(2, data)))
    }

    fun endVoice(session: Int) {
        send(ProtoWriter().message(32, ProtoWriter().varint(1, session)))
    }

    fun close() {
        running = false
        ready = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        sender.shutdown()
    }
}
