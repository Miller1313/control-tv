package com.miller.controltv

import android.os.Build
import java.util.concurrent.Executors
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

/**
 * Remote control session over port 6466 (Android TV Remote v2).
 * All network work happens on background threads; callbacks are invoked on
 * those threads, so the UI must post them to the main thread.
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
    }

    companion object {
        // Feature mask used by the official remote: ping, key, IME, power, volume, app link
        private const val FEATURES = 622
        const val SHORT = 3
        const val START_LONG = 1
        const val END_LONG = 2
    }

    private val sender = Executors.newSingleThreadExecutor()
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var running = false
    @Volatile var ready = false
        private set
    @Volatile private var gotMessage = false
    @Volatile private var imeCounter = -1
    @Volatile private var fieldCounter = -1

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
            if (wasRunning) listener.onDisconnected(error, needsPairing)
        }
    }

    private fun handle(msg: ProtoMessage) {
        when {
            msg.has(1) -> {
                // RemoteConfigure: answer with our features and device info
                val info = ProtoWriter()
                    .string(1, Build.MODEL ?: "Android")
                    .string(2, Build.MANUFACTURER ?: "Android")
                    .varint(3, 1)
                    .string(4, "1")
                    .string(5, "atvremote")
                    .string(6, "1.0.0")
                send(ProtoWriter().message(1, ProtoWriter().varint(1, FEATURES).message(2, info)))
            }
            msg.has(2) -> {
                // RemoteSetActive
                send(ProtoWriter().message(2, ProtoWriter().varint(1, FEATURES)))
            }
            msg.has(8) -> {
                // Ping -> pong with the same value
                val v = msg.message(8)?.int(1) ?: 0
                send(ProtoWriter().message(9, ProtoWriter().varint(1, v)))
            }
            msg.has(40) -> {
                if (!ready) {
                    ready = true
                    listener.onReady()
                }
            }
            msg.has(50) -> {
                val v = msg.message(50) ?: return
                listener.onVolume(v.int(7), v.int(6), v.long(8) != 0L)
            }
            msg.has(20) -> {
                // IME key inject: carries the app in the foreground
                val pkg = msg.message(20)?.message(1)?.string(12)
                if (!pkg.isNullOrEmpty()) listener.onCurrentApp(pkg)
            }
            msg.has(21) -> {
                val b = msg.message(21) ?: return
                imeCounter = b.int(1)
                fieldCounter = b.int(2)
            }
        }
        // Some TVs never send RemoteStart; consider the session ready after set-active.
        if (!ready && msg.has(2)) {
            ready = true
            listener.onReady()
        }
    }

    private fun send(w: ProtoWriter) {
        val s = socket ?: return
        if (sender.isShutdown) return
        val bytes = w.toByteArray()
        try { sender.execute {
            try {
                Framing.write(s.outputStream, bytes)
            } catch (_: Exception) {
                // read loop will notice the broken connection
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    fun key(code: Int, direction: Int = SHORT) {
        send(ProtoWriter().message(10, ProtoWriter().varint(1, code).varint(2, direction)))
    }

    fun openLink(link: String) {
        send(ProtoWriter().message(90, ProtoWriter().string(1, link)))
    }

    /**
     * Types text into the focused field on the TV. Uses the IME channel when the
     * TV has told us its counters; otherwise falls back to individual key presses.
     */
    fun text(text: String) {
        if (text.isEmpty()) return
        if (imeCounter >= 0 && fieldCounter >= 0) {
            val obj = ProtoWriter()
                .varint(1, text.length - 1)
                .varint(2, text.length - 1)
                .string(3, text)
            val edit = ProtoWriter().varint(1, 1).message(2, obj)
            send(
                ProtoWriter().message(
                    21,
                    ProtoWriter().varint(1, imeCounter).varint(2, fieldCounter).message(3, edit)
                )
            )
        } else {
            for (c in text) {
                val k = KeyCodes.forChar(c) ?: continue
                if (k.second) {
                    key(KeyCodes.SHIFT_LEFT, START_LONG)
                    key(k.first)
                    key(KeyCodes.SHIFT_LEFT, END_LONG)
                } else {
                    key(k.first)
                }
            }
        }
    }

    fun close() {
        running = false
        ready = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        sender.shutdown()
    }
}
