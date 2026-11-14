package com.miller.controltv

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

class MainActivity : Activity(), RemoteClient.Listener {

    private lateinit var ui: Ui
    private lateinit var storage: Storage
    private lateinit var tls: Tls
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var client: RemoteClient? = null
    private var device: TvDevice? = null
    private var autoRetry = true
    private var currentApp: String? = null
    private var resumed = false
    private var design = 0

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var tvName: TextView
    private lateinit var volumeLabel: TextView
    private var navHolder: FrameLayout? = null
    private var navToggle: FrameLayout? = null
    private lateinit var appsHolder: LinearLayout
    private lateinit var nowPlayingText: TextView
    private lateinit var nowPlayingButton: View
    private lateinit var historyHolder: LinearLayout
    private lateinit var shortcutsHolder: LinearLayout
    private lateinit var searchField: EditText
    private lateinit var voiceBubble: LinearLayout
    private lateinit var voiceBubbleText: TextView
    private lateinit var routeBanner: LinearLayout
    private lateinit var routeBannerText: TextView

    private val pages = mutableListOf<View>()
    private val tabs = mutableListOf<LinearLayout>()

    private lateinit var buttons: MutableList<CustomButton>
    private lateinit var apps: MutableList<AppShortcut>

    // keyboard
    private var keyboardSheet: Dialog? = null
    private var tvFieldText = ""
    private var tvFieldLabel = ""
    private var lastAutoKeyboard = 0L

    // voice
    @Volatile private var voiceRecording = false
    private var voicePressed = false
    private var voiceSession: Int? = null

    // app launching
    private var lastLinkAt = 0L
    private var recordingRoute: MutableList<Int>? = null
    private var routeTarget = -1

    companion object {
        private const val REQ_SPEECH = 42
        private const val REQ_MIC = 7
    }

    // ================================================================ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = Storage(this)
        Palette.apply(Themes.all[storage.theme])
        design = storage.design
        ui = Ui(this)
        if (design == 2) {
            ui.family = "monospace"
            ui.roundness = 0.3f
        }
        applyWindowColors()
        tls = Tls(this)
        buttons = storage.loadButtons()
        apps = storage.loadApps()
        setContentView(buildLayout())
        selectTab(0)

        worker.execute { tls.keyStore() }

        val last = storage.lastDevice
        if (last != null) connectTo(last) else main.postDelayed({ showDevicePicker() }, 300)
    }

    @Suppress("DEPRECATION")
    private fun applyWindowColors() {
        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG
        var flags = window.decorView.systemUiVisibility
        flags = if (Palette.LIGHT) {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
        window.decorView.systemUiVisibility = flags
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceRecording = false
        client?.close()
        worker.shutdown()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        val d = device
        if (d != null && client == null) connectTo(d)
    }

    override fun onPause() {
        super.onPause()
        resumed = false
    }

    /** The phone's own volume buttons control the TV while connected. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val c = client
        if (storage.phoneVolumeKeys && c != null && c.ready &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            c.key(if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) KeyCodes.VOLUME_UP else KeyCodes.VOLUME_DOWN)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (storage.phoneVolumeKeys && client?.ready == true &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) return true
        return super.onKeyUp(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SPEECH && resultCode == RESULT_OK) {
            val q = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            searchField.setText(q)
            selectTab(2)
            youtubeSearch(q)
        }
    }

    // ================================================================ layout

    private fun buildLayout(): View {
        val frame = FrameLayout(this)
        frame.setBackgroundColor(Palette.BG)

        val root = ui.vertical()
        root.addView(header())

        val content = FrameLayout(this)
        content.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        listOf(controlPage(), appsPage(), searchPage()).forEach { page ->
            val scroll = ScrollView(this)
            scroll.isVerticalScrollBarEnabled = false
            scroll.isFillViewport = true
            scroll.addView(page)
            pages.add(scroll)
            content.addView(scroll)
        }
        root.addView(content)
        root.addView(bottomNav())
        frame.addView(root)

        // Voice bubble (shown while the phone is the TV's microphone)
        voiceBubble = ui.horizontal()
        voiceBubble.gravity = Gravity.CENTER
        voiceBubble.background = ui.gradient(Palette.ACCENT, Palette.ACCENT2, 40)
        voiceBubble.setPadding(ui.dp(22), ui.dp(14), ui.dp(22), ui.dp(14))
        val mic = ui.icon(R.drawable.ic_mic, Color.WHITE, 22)
        mic.layoutParams = LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)).apply { rightMargin = ui.dp(10) }
        voiceBubble.addView(mic)
        voiceBubbleText = ui.text("Escuchando…", 15f, Color.WHITE, true)
        voiceBubble.addView(voiceBubbleText)
        voiceBubble.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = ui.dp(96) }
        voiceBubble.elevation = ui.dp(8).toFloat()
        voiceBubble.visibility = View.GONE
        frame.addView(voiceBubble)

        // Route recording banner
        routeBanner = ui.vertical(16)
        routeBanner.background = ui.rounded(Palette.SURFACE2, 22, Palette.ACCENT)
        routeBanner.elevation = ui.dp(8).toFloat()
        val bannerTitle = ui.horizontal()
        val dot = ui.icon(R.drawable.ic_record, Palette.RED, 14)
        dot.layoutParams = LinearLayout.LayoutParams(ui.dp(14), ui.dp(14)).apply { rightMargin = ui.dp(8) }
        bannerTitle.addView(dot)
        bannerTitle.addView(ui.text("Grabando ruta", 15f, Palette.TEXT, true))
        routeBanner.addView(bannerTitle)
        routeBannerText = ui.text("", 13f, Palette.MUTED)
        routeBannerText.setPadding(0, ui.dp(6), 0, ui.dp(12))
        routeBanner.addView(routeBannerText)
        routeBanner.addView(
            ui.grid(
                listOf(
                    ui.button("Cancelar", Palette.SURFACE3, heightDp = 44) { finishRoute(false) },
                    ui.button("Listo", Palette.ACCENT, heightDp = 44) { finishRoute(true) },
                ), 2
            )
        )
        routeBanner.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ).apply { setMargins(ui.dp(14), ui.dp(80), ui.dp(14), 0) }
        routeBanner.visibility = View.GONE
        frame.addView(routeBanner)
        return frame
    }

    private fun header(): View {
        val row = ui.horizontal()
        row.setPadding(ui.dp(20), ui.dp(18), ui.dp(16), ui.dp(8))

        val texts = ui.vertical()
        texts.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val nameRow = ui.horizontal()
        tvName = ui.text("Control TV", 20f, Palette.TEXT, true)
        tvName.maxLines = 1
        nameRow.addView(tvName)
        val chevron = ui.icon(R.drawable.ic_chevron, Palette.MUTED, 18)
        chevron.rotation = 180f
        chevron.layoutParams = LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { leftMargin = ui.dp(4) }
        nameRow.addView(chevron)
        nameRow.setOnClickListener { showDevicePicker() }

        val statusRow = ui.horizontal()
        statusRow.setPadding(0, ui.dp(4), 0, 0)
        statusDot = View(this)
        statusDot.background = ui.oval(Palette.MUTED)
        statusDot.layoutParams = LinearLayout.LayoutParams(ui.dp(7), ui.dp(7)).apply { rightMargin = ui.dp(7) }
        statusText = ui.text("Sin conectar", 13f, Palette.MUTED)
        statusText.maxLines = 1
        statusRow.addView(statusDot)
        statusRow.addView(statusText)
        statusRow.setOnClickListener {
            val d = device
            if (d != null) connectTo(d) else showDevicePicker()
        }
        texts.addView(nameRow)
        texts.addView(statusRow)
        row.addView(texts)

        row.addView(ui.iconButton(R.drawable.ic_palette, 44, Palette.SURFACE2, iconDp = 21) { showPersonalize() })
        row.addView(hGap(8))
        row.addView(ui.iconButton(R.drawable.ic_input, 44, Palette.SURFACE2, iconDp = 21) { showInputs() })
        row.addView(hGap(8))
        val powerBg = (Palette.RED and 0x00FFFFFF) or 0x2E000000
        row.addView(ui.iconButton(R.drawable.ic_power, 44, powerBg, Palette.RED, 21) { key(KeyCodes.POWER) })
        return row
    }

    private fun hGap(dp: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(ui.dp(dp), 1)
        return v
    }

    private fun flexGap(): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        return v
    }

    private fun bottomNav(): View {
        val bar = ui.horizontal()
        bar.background = ui.rounded(Palette.SURFACE, 26, Palette.STROKE)
        bar.setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
        bar.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(14)) }
        val items = listOf(
            Pair("Control", R.drawable.ic_remote),
            Pair("Apps", R.drawable.ic_apps),
            Pair("Buscar", R.drawable.ic_search),
        )
        items.forEachIndexed { i, item ->
            val t = ui.horizontal()
            t.gravity = Gravity.CENTER
            t.minimumHeight = ui.dp(46)
            t.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val ic = ui.icon(item.second, Palette.MUTED, 20)
            ic.layoutParams = LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)).apply { rightMargin = ui.dp(8) }
            t.addView(ic)
            t.addView(ui.text(item.first, 14f, Palette.MUTED, true))
            ui.press(t, false, null) { selectTab(i) }
            tabs.add(t)
            bar.addView(t)
        }
        return bar
    }

    private fun selectTab(index: Int) {
        pages.forEachIndexed { i, p -> p.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, t ->
            val on = i == index
            t.background = if (on) ui.gradient(Palette.ACCENT, Palette.ACCENT2, 20) else null
            val color = if (on) Palette.on(Palette.ACCENT) else Palette.MUTED
            (t.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(color)
            (t.getChildAt(1) as TextView).setTextColor(color)
        }
        if (index == 1) renderNowPlaying()
    }

    // ================================================================ control page (5 designs)

    private fun controlPage(): View {
        volumeLabel = ui.text("VOL", 12f, Palette.MUTED, true)
        return when (design) {
            1 -> zenPage()
            2 -> retroPage()
            3 -> gamepadPage()
            4 -> oneHandPage()
            else -> orbitPage()
        }
    }

    private fun pageBox(): LinearLayout {
        val page = ui.vertical()
        page.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(16))
        return page
    }

    private fun navToggleButton(size: Int): FrameLayout {
        val b = ui.iconButton(if (storage.touchpad) R.drawable.ic_dpad else R.drawable.ic_touch, size) {
            toggleTouchpad()
        }
        navToggle = b
        return b
    }

    // ---- Design 0: Órbita
    private fun orbitPage(): View {
        val page = pageBox()
        page.addView(
            ui.grid(
                listOf(
                    ui.iconButton(R.drawable.ic_back, 52) { key(KeyCodes.BACK) },
                    ui.iconButton(R.drawable.ic_home, 52) { key(KeyCodes.HOME) },
                    micButton(52, Palette.ACCENT),
                    navToggleButton(52),
                    ui.iconButton(R.drawable.ic_more, 52) { showMore() },
                ), 5, 6, center = true
            )
        )
        page.addView(ui.space(18))
        val holder = FrameLayout(this)
        holder.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(270))
        navHolder = holder
        page.addView(holder)
        renderNav()

        page.addView(ui.space(18))
        page.addView(
            ui.grid(
                listOf(
                    pill(KeyCodes.VOLUME_DOWN, KeyCodes.VOLUME_UP, volumeLabel, R.drawable.ic_mute) { key(KeyCodes.MUTE) },
                    pill(KeyCodes.CHANNEL_DOWN, KeyCodes.CHANNEL_UP, ui.text("CH", 12f, Palette.MUTED, true), null, null),
                ), 2, 12
            )
        )
        page.addView(ui.space(16))
        page.addView(mediaRow())
        page.addView(ui.space(12))
        page.addView(
            ui.grid(
                listOf(
                    ui.button("Teclado", Palette.SURFACE, iconRes = R.drawable.ic_keyboard) { showKeyboard() },
                    ui.button("Ajustes TV", Palette.SURFACE, iconRes = R.drawable.ic_tune) { key(KeyCodes.SETTINGS) },
                ), 2
            )
        )
        return page
    }

    // ---- Design 1: Zen (gesture-first)
    private fun zenPage(): View {
        val page = pageBox()
        page.addView(
            ui.grid(
                listOf(
                    ui.iconButton(R.drawable.ic_back, 52, Palette.SURFACE) { key(KeyCodes.BACK) },
                    ui.iconButton(R.drawable.ic_home, 52, Palette.SURFACE) { key(KeyCodes.HOME) },
                    micButton(52, Palette.ACCENT),
                    ui.iconButton(R.drawable.ic_keyboard, 52, Palette.SURFACE) { showKeyboard() },
                    ui.iconButton(R.drawable.ic_more, 52, Palette.SURFACE) { showMore() },
                ), 5, 6, center = true
            )
        )
        page.addView(ui.space(16))
        val pad = touchpad()
        val padHolder = FrameLayout(this)
        padHolder.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        padHolder.minimumHeight = ui.dp(320)
        pad.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        padHolder.addView(pad)
        page.addView(padHolder)
        page.addView(ui.space(14))
        val row = ui.horizontal()
        row.gravity = Gravity.CENTER
        row.addView(ui.button("−", Palette.SURFACE, heightDp = 52, sizeSp = 22f, repeat = true) { key(KeyCodes.VOLUME_DOWN) }
            .also { it.layoutParams = LinearLayout.LayoutParams(0, ui.dp(52), 1f) })
        row.addView(hGap(8))
        row.addView(ui.iconButton(R.drawable.ic_mute, 52, Palette.SURFACE) { key(KeyCodes.MUTE) })
        row.addView(hGap(8))
        row.addView(ui.iconButton(R.drawable.ic_play_pause, 52, Palette.TEXT, Palette.BG) { key(KeyCodes.PLAY_PAUSE) })
        row.addView(hGap(8))
        row.addView(ui.button("+", Palette.SURFACE, heightDp = 52, sizeSp = 22f, repeat = true) { key(KeyCodes.VOLUME_UP) }
            .also { it.layoutParams = LinearLayout.LayoutParams(0, ui.dp(52), 1f) })
        page.addView(row)
        return page
    }

    // ---- Design 2: Retro (physical remote)
    private fun retroPage(): View {
        val page = pageBox()
        page.gravity = Gravity.CENTER_HORIZONTAL
        val body = ui.vertical(18)
        body.background = ui.gradient(Palette.SURFACE2, Palette.SURFACE, 40).apply {
            setStroke(ui.dp(1), Palette.STROKE)
        }
        body.layoutParams = LinearLayout.LayoutParams(ui.dp(312), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        fun rk(label: String, bg: Int = Palette.SURFACE3, repeat: Boolean = false, tap: () -> Unit) =
            ui.button(label, bg, heightDp = 42, sizeSp = 12f, repeat = repeat, onTap = tap)

        body.addView(ui.grid(listOf(
            rk("ATRÁS") { key(KeyCodes.BACK) },
            rk("INICIO") { key(KeyCodes.HOME) },
            rk("MENÚ") { key(KeyCodes.MENU) },
        ), 3, 8))
        body.addView(ui.space(16))
        body.addView(centered(crossPad(62, circle = false)))
        body.addView(ui.space(16))

        fun rocker(label: TextView, up: Int, down: Int): View {
            val c = ui.vertical()
            c.gravity = Gravity.CENTER_HORIZONTAL
            c.background = ui.rounded(Palette.SURFACE3, 20)
            c.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
            c.addView(ui.button("+", Palette.SURFACE3, heightDp = 46, sizeSp = 20f, repeat = true) { key(up) })
            label.gravity = Gravity.CENTER
            label.setPadding(0, ui.dp(4), 0, ui.dp(4))
            c.addView(label)
            c.addView(ui.button("−", Palette.SURFACE3, heightDp = 46, sizeSp = 20f, repeat = true) { key(down) })
            return c
        }
        val middle = ui.vertical()
        middle.gravity = Gravity.CENTER
        middle.addView(centered(micButton(54, Palette.ACCENT)))
        middle.addView(ui.space(10))
        middle.addView(centered(ui.iconButton(R.drawable.ic_mute, 46, Palette.SURFACE3, circle = false) { key(KeyCodes.MUTE) }))
        body.addView(ui.grid(listOf(
            rocker(volumeLabel, KeyCodes.VOLUME_UP, KeyCodes.VOLUME_DOWN),
            middle,
            rocker(ui.text("CH", 12f, Palette.MUTED, true), KeyCodes.CHANNEL_UP, KeyCodes.CHANNEL_DOWN),
        ), 3, 10))
        body.addView(ui.space(16))
        body.addView(ui.grid(listOf(
            ui.iconButton(R.drawable.ic_rew, 44, Palette.SURFACE3, circle = false, repeat = true) { key(KeyCodes.REWIND) },
            ui.iconButton(R.drawable.ic_play_pause, 44, Palette.ACCENT, circle = false) { key(KeyCodes.PLAY_PAUSE) },
            ui.iconButton(R.drawable.ic_ff, 44, Palette.SURFACE3, circle = false, repeat = true) { key(KeyCodes.FAST_FORWARD) },
        ), 3, 8, center = true))
        body.addView(ui.space(12))
        body.addView(ui.grid(listOf(
            rk("TECLADO") { showKeyboard() },
            rk("MÁS") { showMore() },
        ), 2, 8))
        page.addView(body)
        return page
    }

    // ---- Design 3: Gamepad
    private fun gamepadPage(): View {
        val page = pageBox()
        fun sh(label: String, code: Int) =
            ui.button(label, Palette.SURFACE2, heightDp = 44, sizeSp = 12f, repeat = true) { key(code) }
        page.addView(ui.grid(listOf(
            sh("VOL −", KeyCodes.VOLUME_DOWN), sh("VOL +", KeyCodes.VOLUME_UP),
            sh("CH −", KeyCodes.CHANNEL_DOWN), sh("CH +", KeyCodes.CHANNEL_UP),
        ), 4, 8))
        page.addView(ui.space(22))

        val pads = ui.horizontal()
        pads.gravity = Gravity.CENTER
        pads.addView(crossPad(48, circle = true))
        pads.addView(flexGap())

        fun face(letter: String, color: Int, tap: () -> Unit): View {
            val b = ui.button(letter, color, heightDp = 48, sizeSp = 18f, onTap = tap)
            b.background = ui.ripple(ui.oval(color))
            b.layoutParams = LinearLayout.LayoutParams(ui.dp(48), ui.dp(48))
            return b
        }
        val yMic = micButton(48, Palette.YELLOW)
        pads.addView(grid3(arrayOf(
            null, yMic, null,
            face("X", Palette.BLUE) { key(KeyCodes.HOME) }, null, face("B", Palette.RED) { key(KeyCodes.BACK) },
            null, face("A", Palette.GREEN) { key(KeyCodes.DPAD_CENTER) }, null,
        ), 48))
        page.addView(pads)

        val legend = ui.text("A = OK · B = Atrás · X = Inicio · Y = Voz (mantén)", 12f, Palette.MUTED)
        legend.gravity = Gravity.CENTER
        legend.setPadding(0, ui.dp(14), 0, ui.dp(18))
        page.addView(ui.matchWidth(legend))

        page.addView(ui.grid(listOf(
            ui.button("SELECT · Teclado", Palette.SURFACE, heightDp = 40, sizeSp = 12f) { showKeyboard() },
            ui.button("START · Play", Palette.SURFACE, heightDp = 40, sizeSp = 12f) { key(KeyCodes.PLAY_PAUSE) },
        ), 2, 10))
        page.addView(ui.space(14))
        page.addView(mediaRow())
        page.addView(ui.space(12))
        page.addView(ui.grid(listOf(
            ui.button("Silencio", Palette.SURFACE, iconRes = R.drawable.ic_mute) { key(KeyCodes.MUTE) },
            ui.button("Más", Palette.SURFACE, iconRes = R.drawable.ic_more) { showMore() },
        ), 2))
        return page
    }

    // ---- Design 4: One hand
    private fun oneHandPage(): View {
        val page = pageBox()
        val spacer = View(this)
        spacer.layoutParams = LinearLayout.LayoutParams(1, 0, 1f)
        page.addView(spacer)
        page.addView(mediaRow())
        page.addView(ui.space(14))

        val row = ui.horizontal()
        row.gravity = Gravity.BOTTOM
        val vol = ui.vertical()
        vol.gravity = Gravity.CENTER_HORIZONTAL
        vol.background = ui.rounded(Palette.SURFACE, 30)
        vol.setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
        vol.addView(ui.button("+", Palette.SURFACE2, heightDp = 50, sizeSp = 22f, repeat = true) { key(KeyCodes.VOLUME_UP) }
            .also { it.layoutParams = LinearLayout.LayoutParams(ui.dp(50), ui.dp(50)) })
        val muteBox = ui.vertical()
        muteBox.gravity = Gravity.CENTER
        muteBox.setPadding(0, ui.dp(10), 0, ui.dp(10))
        val mi = ui.icon(R.drawable.ic_mute, Palette.MUTED, 18)
        mi.layoutParams = LinearLayout.LayoutParams(ui.dp(18), ui.dp(18))
        muteBox.addView(mi)
        volumeLabel.gravity = Gravity.CENTER
        muteBox.addView(volumeLabel)
        ui.press(muteBox, false, null) { key(KeyCodes.MUTE) }
        vol.addView(muteBox)
        vol.addView(ui.button("−", Palette.SURFACE2, heightDp = 50, sizeSp = 22f, repeat = true) { key(KeyCodes.VOLUME_DOWN) }
            .also { it.layoutParams = LinearLayout.LayoutParams(ui.dp(50), ui.dp(50)) })
        row.addView(vol)
        row.addView(hGap(12))
        val holder = FrameLayout(this)
        holder.layoutParams = LinearLayout.LayoutParams(0, ui.dp(236), 1f)
        navHolder = holder
        row.addView(holder)
        page.addView(row)
        renderNav(228)

        page.addView(ui.space(14))
        page.addView(
            ui.grid(
                listOf(
                    ui.iconButton(R.drawable.ic_more, 52) { showMore() },
                    ui.iconButton(R.drawable.ic_keyboard, 52) { showKeyboard() },
                    navToggleButton(52),
                    micButton(52, Palette.ACCENT),
                    ui.iconButton(R.drawable.ic_home, 52) { key(KeyCodes.HOME) },
                    ui.iconButton(R.drawable.ic_back, 52) { key(KeyCodes.BACK) },
                ), 6, 4, center = true
            )
        )
        return page
    }

    // ---- shared pieces

    private fun centered(v: View): View {
        val h = LinearLayout(this)
        h.gravity = Gravity.CENTER_HORIZONTAL
        h.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        h.addView(v)
        return h
    }

    /** 3×3 grid of fixed cells; null cells stay empty. */
    private fun grid3(cells: Array<View?>, cellDp: Int): View {
        val g = ui.vertical()
        for (r in 0 until 3) {
            val row = ui.horizontal()
            for (c in 0 until 3) {
                val holder = FrameLayout(this)
                holder.layoutParams = LinearLayout.LayoutParams(ui.dp(cellDp + 6), ui.dp(cellDp + 6))
                val v = cells[r * 3 + c]
                if (v != null) {
                    v.layoutParams = FrameLayout.LayoutParams(ui.dp(cellDp), ui.dp(cellDp), Gravity.CENTER)
                    holder.addView(v)
                }
                row.addView(holder)
            }
            g.addView(row)
        }
        return g
    }

    /** Plus-shaped D-pad. */
    private fun crossPad(cellDp: Int, circle: Boolean): View {
        fun arrow(rot: Float, code: Int) = ui.iconButton(
            R.drawable.ic_chevron, cellDp, Palette.SURFACE3, iconDp = (cellDp * 0.5).toInt(),
            circle = circle, repeat = true, rotation = rot
        ) { key(code) }
        val ok = ui.button("OK", Palette.ACCENT, heightDp = cellDp, sizeSp = 15f) { key(KeyCodes.DPAD_CENTER) }
        if (circle) ok.background = ui.ripple(ui.oval(Palette.ACCENT))
        ui.press(ok, false, { longOk() }) { key(KeyCodes.DPAD_CENTER) }
        return grid3(arrayOf(
            null, arrow(0f, KeyCodes.DPAD_UP), null,
            arrow(270f, KeyCodes.DPAD_LEFT), ok, arrow(90f, KeyCodes.DPAD_RIGHT),
            null, arrow(180f, KeyCodes.DPAD_DOWN), null,
        ), cellDp)
    }

    private fun mediaRow(): View {
        val media = ui.horizontal()
        media.gravity = Gravity.CENTER
        media.background = ui.rounded(Palette.SURFACE, 30)
        media.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
        media.addView(ui.iconButton(R.drawable.ic_prev, 46, Palette.SURFACE) { key(KeyCodes.PREVIOUS) })
        media.addView(flexGap())
        media.addView(ui.iconButton(R.drawable.ic_rew, 46, Palette.SURFACE, repeat = true) { key(KeyCodes.REWIND) })
        media.addView(flexGap())
        media.addView(ui.iconButton(R.drawable.ic_play_pause, 56, Palette.TEXT, Palette.BG, 26) { key(KeyCodes.PLAY_PAUSE) })
        media.addView(flexGap())
        media.addView(ui.iconButton(R.drawable.ic_ff, 46, Palette.SURFACE, repeat = true) { key(KeyCodes.FAST_FORWARD) })
        media.addView(flexGap())
        media.addView(ui.iconButton(R.drawable.ic_next, 46, Palette.SURFACE) { key(KeyCodes.NEXT) })
        return media
    }

    /** Horizontal [−  label  +] pill. */
    private fun pill(down: Int, up: Int, label: TextView, midIcon: Int?, midTap: (() -> Unit)?): View {
        val p = ui.horizontal()
        p.gravity = Gravity.CENTER
        p.background = ui.rounded(Palette.SURFACE, 30)
        p.setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
        val minus = ui.button("−", Palette.SURFACE2, heightDp = 48, sizeSp = 22f, repeat = true) { key(down) }
        minus.layoutParams = LinearLayout.LayoutParams(ui.dp(48), ui.dp(48))
        val plus = ui.button("+", Palette.SURFACE2, heightDp = 48, sizeSp = 22f, repeat = true) { key(up) }
        plus.layoutParams = LinearLayout.LayoutParams(ui.dp(48), ui.dp(48))
        val mid = ui.vertical()
        mid.gravity = Gravity.CENTER
        mid.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        if (midIcon != null && midTap != null) {
            val ic = ui.icon(midIcon, Palette.MUTED, 18)
            ic.layoutParams = LinearLayout.LayoutParams(ui.dp(18), ui.dp(18))
            mid.addView(ic)
            ui.press(mid, false, null, midTap)
        }
        label.gravity = Gravity.CENTER
        mid.addView(label)
        p.addView(minus)
        p.addView(mid)
        p.addView(plus)
        return p
    }

    private var navSizeDp = 262

    private fun toggleTouchpad() {
        storage.touchpad = !storage.touchpad
        (navToggle?.getChildAt(0) as? ImageView)?.setImageResource(
            if (storage.touchpad) R.drawable.ic_dpad else R.drawable.ic_touch
        )
        renderNav(navSizeDp)
        toast(if (storage.touchpad) "Modo touchpad: desliza para moverte, toca para OK" else "Modo flechas")
    }

    private fun renderNav(sizeDp: Int = 262) {
        navSizeDp = sizeDp
        val h = navHolder ?: return
        h.removeAllViews()
        h.addView(if (storage.touchpad) touchpad() else dpad(sizeDp))
    }

    private fun dpad(sizeDp: Int): View {
        val size = ui.dp(sizeDp)
        val ring = FrameLayout(this)
        ring.layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        ring.background = ui.oval(Palette.SURFACE, Palette.STROKE)
        val arrowDp = (sizeDp * 0.29).toInt()
        fun arrow(rot: Float, gravity: Int, code: Int): View {
            val b = ui.iconButton(
                R.drawable.ic_chevron, arrowDp, Palette.SURFACE, Palette.TEXT, (arrowDp * 0.4).toInt(),
                repeat = true, rotation = rot
            ) { key(code) }
            val m = ui.dp(6)
            b.layoutParams = FrameLayout.LayoutParams(ui.dp(arrowDp), ui.dp(arrowDp), gravity).apply { setMargins(m, m, m, m) }
            return b
        }
        ring.addView(arrow(0f, Gravity.TOP or Gravity.CENTER_HORIZONTAL, KeyCodes.DPAD_UP))
        ring.addView(arrow(180f, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, KeyCodes.DPAD_DOWN))
        ring.addView(arrow(270f, Gravity.START or Gravity.CENTER_VERTICAL, KeyCodes.DPAD_LEFT))
        ring.addView(arrow(90f, Gravity.END or Gravity.CENTER_VERTICAL, KeyCodes.DPAD_RIGHT))

        val ok = FrameLayout(this)
        val okBg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Palette.ACCENT, Palette.ACCENT2))
        okBg.shape = GradientDrawable.OVAL
        ok.background = ui.ripple(okBg)
        val okDp = (sizeDp * 0.4).toInt()
        ok.layoutParams = FrameLayout.LayoutParams(ui.dp(okDp), ui.dp(okDp), Gravity.CENTER)
        val okText = ui.text("OK", 20f, Palette.on(Palette.ACCENT), true)
        okText.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        )
        ok.addView(okText)
        ui.press(ok, false, { longOk() }) { key(KeyCodes.DPAD_CENTER) }
        ring.addView(ok)
        return ring
    }

    /** Long press on OK = long press on the TV remote (context menus). */
    private fun longOk() {
        val c = client
        if (c == null || !c.ready) {
            notConnected(); return
        }
        c.key(KeyCodes.DPAD_CENTER, RemoteClient.START_LONG)
        main.postDelayed({ c.key(KeyCodes.DPAD_CENTER, RemoteClient.END_LONG) }, 700)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun touchpad(): View {
        val pad = FrameLayout(this)
        pad.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        pad.background = ui.rounded(Palette.SURFACE, 32, Palette.STROKE)
        val hint = ui.vertical()
        hint.gravity = Gravity.CENTER
        hint.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        )
        val ic = ui.icon(R.drawable.ic_touch, Palette.SURFACE3, 40)
        ic.layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(40))
        hint.addView(ic)
        val hintText = ui.text("Desliza para moverte\nToca para OK · mantén para menú", 13f, Palette.MUTED)
        hintText.gravity = Gravity.CENTER
        hintText.setPadding(0, ui.dp(10), 0, 0)
        hint.addView(hintText)
        pad.addView(hint)

        val step = ui.dp(46).toFloat()
        var anchorX = 0f
        var anchorY = 0f
        var moved = false
        var downTime = 0L
        val longRunnable = Runnable {
            if (!moved) {
                pad.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                longOk()
                moved = true
            }
        }
        pad.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    anchorX = ev.x
                    anchorY = ev.y
                    moved = false
                    downTime = System.currentTimeMillis()
                    main.postDelayed(longRunnable, 550)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - anchorX
                    val dy = ev.y - anchorY
                    if (abs(dx) >= step || abs(dy) >= step) {
                        main.removeCallbacks(longRunnable)
                        moved = true
                        if (abs(dx) > abs(dy)) {
                            key(if (dx > 0) KeyCodes.DPAD_RIGHT else KeyCodes.DPAD_LEFT)
                        } else {
                            key(if (dy > 0) KeyCodes.DPAD_DOWN else KeyCodes.DPAD_UP)
                        }
                        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        anchorX = ev.x
                        anchorY = ev.y
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longRunnable)
                    if (!moved && System.currentTimeMillis() - downTime < 450) {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        key(KeyCodes.DPAD_CENTER)
                    }
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longRunnable)
            }
            true
        }
        return pad
    }

    // ================================================================ voice

    /** Hold-to-talk button: the phone becomes the TV remote's microphone. */
    @SuppressLint("ClickableViewAccessibility")
    private fun micButton(sizeDp: Int, bg: Int): FrameLayout {
        val b = ui.iconButton(R.drawable.ic_mic, sizeDp, bg, Palette.on(bg), (sizeDp * 0.44).toInt()) {}
        b.setOnClickListener(null)
        b.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    voiceDown()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    voiceUp()
                }
            }
            true
        }
        return b
    }

    private fun showVoice(text: String) {
        voiceBubbleText.text = text
        voiceBubble.visibility = View.VISIBLE
    }

    private fun hideVoice() {
        voiceBubble.visibility = View.GONE
    }

    private fun voiceDown() {
        val c = client
        if (c == null || !c.ready) {
            notConnected(); return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            toast("Permite el micrófono y vuelve a mantener el botón")
            return
        }
        if (!c.supportsVoice) {
            dictate(); return
        }
        voicePressed = true
        showVoice("Conectando con el TV…")
        c.startVoice { id ->
            main.post {
                if (id == null) {
                    hideVoice()
                    // The TV did not open a voice session: use the phone's own recogniser instead.
                    if (voicePressed) {
                        voicePressed = false
                        dictate()
                    }
                } else if (!voicePressed) {
                    c.endVoice(id)
                    hideVoice()
                } else {
                    voiceSession = id
                    startRecorder(c, id)
                    showVoice("Escuchando… suelta para terminar")
                }
            }
        }
    }

    private fun voiceUp() {
        voicePressed = false
        if (voiceSession != null) {
            voiceSession = null
            voiceRecording = false // the recorder thread sends the rest and ends the session
            showVoice("Enviando…")
            main.postDelayed({ hideVoice() }, 900)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecorder(c: RemoteClient, session: Int) {
        val rate = 8000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, RemoteClient.VOICE_CHUNK * 2)
            )
        } catch (e: Exception) {
            c.endVoice(session)
            hideVoice()
            toast("No se pudo usar el micrófono")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            c.endVoice(session)
            hideVoice()
            toast("No se pudo usar el micrófono")
            return
        }
        voiceRecording = true
        Thread({
            val buf = ByteArray(RemoteClient.VOICE_CHUNK)
            var filled = 0
            try {
                rec.startRecording()
                while (voiceRecording) {
                    val n = rec.read(buf, filled, buf.size - filled)
                    if (n < 0) break
                    filled += n
                    if (filled == buf.size) {
                        c.sendVoice(session, buf.copyOf())
                        filled = 0
                    }
                }
                if (filled > 0) c.sendVoice(session, buf.copyOf(filled))
            } catch (_: Exception) {
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
                c.endVoice(session)
            }
        }, "voice").start()
    }

    /** Phone speech recogniser → YouTube search. */
    private fun dictate() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "¿Qué quieres ver o escuchar?")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_SPEECH)
        } catch (_: ActivityNotFoundException) {
            toast("Tu teléfono no tiene reconocimiento de voz")
        }
    }

    // ================================================================ apps page

    private fun appsPage(): View {
        val page = pageBox()
        page.addView(ui.text("Tus apps", 22f, Palette.TEXT, true))
        val sub = ui.text("Toca para abrirla en el TV. Mantén presionada para editar.", 13f, Palette.MUTED)
        sub.setPadding(0, ui.dp(4), 0, ui.dp(18))
        page.addView(sub)
        appsHolder = ui.vertical()
        page.addView(appsHolder)
        renderApps()

        page.addView(ui.space(22))
        val card = ui.vertical(16)
        card.background = ui.rounded(Palette.SURFACE, 22, Palette.STROKE)
        val cap = ui.text("ABIERTA AHORA EN EL TV", 11f, Palette.MUTED, true)
        cap.letterSpacing = 0.08f
        card.addView(cap)
        nowPlayingText = ui.text("—", 16f, Palette.TEXT, true)
        nowPlayingText.setPadding(0, ui.dp(6), 0, ui.dp(12))
        card.addView(nowPlayingText)
        nowPlayingButton = ui.button("Guardar como botón", Palette.SURFACE2, iconRes = R.drawable.ic_add) {
            addCurrentApp()
        }
        card.addView(nowPlayingButton)
        page.addView(card)
        renderNowPlaying()
        return page
    }

    private fun renderApps() {
        appsHolder.removeAllViews()
        val views = mutableListOf<View>()
        apps.forEachIndexed { i, a ->
            views.add(ui.appTile(a.name, a.color, { editApp(i, null) }) { launchApp(a) })
        }
        views.add(ui.addTile("Añadir") { showAddApp() })
        appsHolder.addView(ui.grid(views, 4, 14, center = true))
    }

    private fun renderNowPlaying() {
        if (!::nowPlayingButton.isInitialized) return
        val pkg = currentApp
        nowPlayingText.text = if (pkg == null) "Abre cualquier app en el TV y aparecerá aquí" else appLabel(pkg)
        nowPlayingButton.visibility = if (pkg == null) View.GONE else View.VISIBLE
    }

    private fun appLabel(pkg: String): String =
        AppCatalog.apps.firstOrNull { it.link.endsWith("id=$pkg") }?.name
            ?: apps.firstOrNull { it.link.endsWith("id=$pkg") }?.name
            ?: pkg

    private fun launchApp(a: AppShortcut) {
        val c = client
        if (c == null || !c.ready) {
            notConnected(); return
        }
        if (a.route.isNotEmpty()) {
            toast("Abriendo ${a.name}…")
            c.key(KeyCodes.HOME)
            a.route.forEachIndexed { i, code ->
                main.postDelayed({ client?.key(code) }, 1600L + i * 380L)
            }
        } else {
            lastLinkAt = System.currentTimeMillis()
            c.openLink(a.link)
        }
    }

    // ---- route recording (for apps that cannot be opened by link)

    private fun startRoute(index: Int) {
        val c = client
        if (c == null || !c.ready) {
            notConnected(); return
        }
        recordingRoute = mutableListOf()
        routeTarget = index
        selectTab(0)
        c.key(KeyCodes.HOME)
        updateRouteBanner()
        routeBanner.visibility = View.VISIBLE
    }

    private fun updateRouteBanner() {
        val n = recordingRoute?.size ?: 0
        routeBannerText.text = "El TV fue a Inicio. Usa el control para llegar a la app y presiona OK sobre ella. " +
            "Luego toca Listo. Pasos grabados: $n"
    }

    private fun finishRoute(save: Boolean) {
        val route = recordingRoute
        recordingRoute = null
        routeBanner.visibility = View.GONE
        if (!save || route == null) return
        if (route.isEmpty()) {
            toast("No se grabó ningún paso"); return
        }
        val a = apps.getOrNull(routeTarget) ?: return
        apps[routeTarget] = a.copy(route = route.toList())
        storage.saveApps(apps)
        renderApps()
        toast("Ruta guardada: ${route.size} pasos")
    }

    // ================================================================ search page

    private fun searchPage(): View {
        val page = pageBox()
        page.addView(ui.text("Buscar", 22f, Palette.TEXT, true))
        val sub = ui.text("Canciones, videos, series o películas.", 13f, Palette.MUTED)
        sub.setPadding(0, ui.dp(4), 0, ui.dp(16))
        page.addView(sub)
        val row = ui.horizontal()
        searchField = ui.input("¿Qué quieres ver o escuchar?")
        searchField.imeOptions = EditorInfo.IME_ACTION_SEARCH
        searchField.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                youtubeSearch(searchField.text.toString()); true
            } else false
        }
        searchField.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(searchField)
        row.addView(hGap(10))
        row.addView(ui.iconButton(R.drawable.ic_mic, 50, Palette.ACCENT) { dictate() })
        page.addView(row)
        page.addView(ui.space(10))
        page.addView(
            ui.grid(
                listOf(
                    ui.button("YouTube", 0xFFFF0033.toInt()) { youtubeSearch(searchField.text.toString()) },
                    ui.button("Google TV", Palette.ACCENT) { googleTvSearch(searchField.text.toString()) },
                ), 2
            )
        )

        page.addView(ui.title("Recientes"))
        historyHolder = ui.vertical()
        page.addView(historyHolder)
        renderHistory()

        page.addView(ui.title("Atajos"))
        shortcutsHolder = ui.vertical()
        page.addView(shortcutsHolder)
        renderShortcuts()
        val hint = ui.text("Mantén presionado un atajo para editarlo.", 12f, Palette.MUTED)
        hint.setPadding(ui.dp(4), ui.dp(10), 0, 0)
        page.addView(hint)
        return page
    }

    private fun renderHistory() {
        historyHolder.removeAllViews()
        val h = storage.history()
        if (h.isEmpty()) {
            val t = ui.text("Tus búsquedas aparecerán aquí.", 13f, Palette.MUTED)
            t.setPadding(ui.dp(4), 0, 0, 0)
            historyHolder.addView(t)
            return
        }
        val chips = mutableListOf<View>()
        h.forEach { q -> chips.add(ui.chip(q) { searchField.setText(q); youtubeSearch(q) }) }
        chips.add(ui.chip("Borrar", Palette.SURFACE, Palette.MUTED) { storage.clearHistory(); renderHistory() })
        historyHolder.addView(ui.flow(chips))
    }

    private fun renderShortcuts() {
        shortcutsHolder.removeAllViews()
        val views = mutableListOf<View>()
        buttons.forEachIndexed { i, b ->
            views.add(ui.button(b.label, b.color, onLongPress = { editShortcut(i) }) { runShortcut(b) })
        }
        views.add(ui.button("Nuevo", Palette.SURFACE, Palette.MUTED, iconRes = R.drawable.ic_add) { editShortcut(-1) })
        shortcutsHolder.addView(ui.grid(views, 2))
    }

    // ================================================================ actions

    private fun key(code: Int) {
        val c = client
        if (c == null || !c.ready) {
            notConnected(); return
        }
        recordingRoute?.let {
            if (code != KeyCodes.VOLUME_UP && code != KeyCodes.VOLUME_DOWN && code != KeyCodes.MUTE) {
                it.add(code)
                updateRouteBanner()
            }
        }
        c.key(code)
    }

    private fun link(url: String) {
        val c = client
        if (c == null || !c.ready) {
            notConnected(); return
        }
        lastLinkAt = System.currentTimeMillis()
        c.openLink(url)
    }

    private fun sendText(t: String) {
        if (t.isBlank()) return
        val c = client
        if (c == null || !c.ready) {
            notConnected(); return
        }
        c.text(t)
    }

    private fun youtubeSearch(q: String) {
        if (q.isBlank()) {
            toast("Escribe qué quieres buscar"); return
        }
        storage.addHistory(q.trim())
        renderHistory()
        link("https://www.youtube.com/results?search_query=" + URLEncoder.encode(q.trim(), "UTF-8"))
    }

    private fun googleTvSearch(q: String) {
        if (q.isBlank()) {
            toast("Escribe qué quieres buscar"); return
        }
        storage.addHistory(q.trim())
        renderHistory()
        key(KeyCodes.SEARCH)
        main.postDelayed({ sendText(q.trim()) }, 1800)
        main.postDelayed({ key(KeyCodes.ENTER) }, 2600)
    }

    private fun runShortcut(b: CustomButton) {
        when (b.type) {
            ActionType.YOUTUBE_SEARCH -> youtubeSearch(b.value)
            ActionType.LINK -> link(b.value.trim())
            ActionType.KEY -> b.value.toIntOrNull()?.let { key(it) }
            ActionType.TEXT -> sendText(b.value)
        }
    }

    private fun notConnected() {
        toast("No hay conexión con el TV")
        device?.let { if (client == null) connectTo(it) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun linkFailedHint() {
        toast("Tu TV no dejó abrir esa app directamente. Mantén presionado su botón y usa \"Grabar ruta\".")
    }

    // ================================================================ connection

    private fun setStatus(text: String, color: Int) {
        statusText.text = text
        statusText.setTextColor(color)
        statusDot.background = ui.oval(color)
    }

    private fun connectTo(d: TvDevice) {
        client?.close()
        client = null
        device = d
        tvName.text = d.name
        setStatus("Conectando…", Palette.YELLOW)
        val c = RemoteClient(tls, d.host, d.port, this)
        client = c
        worker.execute {
            tls.keyStore()
            main.post { if (client === c) c.connect() }
        }
    }

    private fun connectedStatus() {
        val app = currentApp
        setStatus(if (app == null) "Conectado" else "Conectado · ${appLabel(app)}", Palette.GREEN)
    }

    override fun onReady() {
        main.post {
            autoRetry = true
            device?.let { storage.lastDevice = it }
            connectedStatus()
        }
    }

    override fun onDisconnected(error: String?, needsPairing: Boolean) {
        main.post {
            client = null
            val afterLink = System.currentTimeMillis() - lastLinkAt < 5000
            if (afterLink) {
                // The TV drops the session when it cannot open a link: reconnect quietly.
                lastLinkAt = 0
                linkFailedHint()
                device?.let { connectTo(it) }
                return@post
            }
            if (needsPairing) {
                setStatus("Necesita emparejar", Palette.YELLOW)
                device?.let { startPairing(it) }
            } else {
                setStatus("Desconectado · toca para reconectar", Palette.RED)
                if (autoRetry) {
                    autoRetry = false
                    main.postDelayed({ device?.let { if (client == null) connectTo(it) } }, 1500)
                }
            }
        }
    }

    override fun onRemoteError() {
        main.post {
            if (System.currentTimeMillis() - lastLinkAt < 5000) {
                lastLinkAt = 0
                linkFailedHint()
            }
        }
    }

    override fun onVolume(level: Int, max: Int, muted: Boolean) {
        main.post {
            volumeLabel.text = when {
                muted -> "SILENCIO"
                max > 0 -> "VOL $level"
                else -> "VOL"
            }
        }
    }

    override fun onCurrentApp(packageName: String) {
        main.post {
            currentApp = packageName
            if (client?.ready == true) connectedStatus()
            renderNowPlaying()
        }
    }

    override fun onTextField(value: String, label: String) {
        main.post {
            tvFieldText = value
            tvFieldLabel = label
            // The TV opened its keyboard: open ours so the user can type from the phone.
            val now = System.currentTimeMillis()
            if (storage.autoKeyboard && resumed && keyboardSheet?.isShowing != true && now - lastAutoKeyboard > 4000) {
                lastAutoKeyboard = now
                showKeyboard()
            }
        }
    }

    // ================================================================ sheets

    private fun showPersonalize() {
        ui.sheet("Personalizar", "Elige un tema de colores y un diseño para el control.") { box, d ->
            box.addView(ui.title("Temas"))
            val cards = Themes.all.mapIndexed { i, t -> themeCard(t, i == storage.theme) {
                storage.theme = i
                d.dismiss()
                recreate()
            } }
            box.addView(ui.grid(cards, 2, 10))
            box.addView(ui.title("Diseños"))
            Designs.all.forEachIndexed { i, ds ->
                val row = optionRow(ds.name, ds.description, if (i == storage.design) R.drawable.ic_check else R.drawable.ic_remote) {
                    storage.design = i
                    d.dismiss()
                    recreate()
                }
                if (i == storage.design) row.background = ui.ripple(ui.rounded(Palette.SURFACE2, 18, Palette.ACCENT))
                box.addView(row)
            }
        }
    }

    private fun themeCard(t: Theme, selected: Boolean, onTap: () -> Unit): View {
        val bg = Color.parseColor(t.bg)
        val card = ui.vertical(12)
        val base = GradientDrawable()
        base.setColor(bg)
        base.cornerRadius = ui.dp(18).toFloat()
        base.setStroke(ui.dp(if (selected) 3 else 1), if (selected) Palette.ACCENT else Palette.STROKE)
        card.background = ui.ripple(base)
        val preview = ui.horizontal()
        fun dot(c: String, size: Int): View {
            val v = View(this)
            v.background = ui.oval(Color.parseColor(c))
            v.layoutParams = LinearLayout.LayoutParams(ui.dp(size), ui.dp(size)).apply { rightMargin = ui.dp(6) }
            return v
        }
        preview.addView(dot(t.accent, 22))
        preview.addView(dot(t.accent2, 16))
        preview.addView(dot(t.surface2, 16))
        card.addView(preview)
        val name = ui.text(t.name, 14f, Color.parseColor(t.text), true)
        name.setPadding(0, ui.dp(10), 0, 0)
        card.addView(name)
        ui.press(card, false, null, onTap)
        return card
    }

    private fun showInputs() {
        ui.sheet("Fuente de entrada", "Elige a qué entrada cambiar el TV") { box, d ->
            val list = listOf(
                Pair("HDMI 1", KeyCodes.HDMI_1), Pair("HDMI 2", KeyCodes.HDMI_2),
                Pair("HDMI 3", KeyCodes.HDMI_3), Pair("HDMI 4", KeyCodes.HDMI_4),
                Pair("AV", KeyCodes.COMPOSITE_1), Pair("Componente", KeyCodes.COMPONENT_1),
                Pair("TV / Antena", KeyCodes.ANTENNA_CABLE),
            ).map { item ->
                ui.button(item.first, Palette.SURFACE2, heightDp = 56, iconRes = R.drawable.ic_input) {
                    key(item.second); d.dismiss()
                }
            }
            box.addView(ui.grid(list, 2))
            box.addView(ui.space(12))
            box.addView(ui.matchWidth(ui.button("Abrir menú de entradas del TV", Palette.ACCENT) {
                key(KeyCodes.TV_INPUT); d.dismiss()
            }))
            val note = ui.text(
                "Si una entrada no responde, usa el menú del TV: algunos modelos solo aceptan ese.",
                12f, Palette.MUTED
            )
            note.setPadding(ui.dp(2), ui.dp(12), 0, 0)
            box.addView(note)
        }
    }

    private fun toggleRow(label: () -> String, icon: Int, flip: () -> Unit): View {
        val b = ui.button(label(), Palette.SURFACE2, iconRes = icon) {}
        ui.press(b, false, null) {
            flip()
            (b.getChildAt(1) as TextView).text = label()
        }
        return ui.matchWidth(b)
    }

    private fun showMore() {
        ui.sheet("Más opciones") { box, d ->
            box.addView(
                ui.grid(
                    listOf(
                        ui.button("Subtítulos") { key(KeyCodes.CAPTIONS) },
                        ui.button("Guía") { key(KeyCodes.GUIDE) },
                        ui.button("Menú") { key(KeyCodes.MENU) },
                        ui.button("Asistente") { key(KeyCodes.ASSIST); d.dismiss() },
                        ui.button("Info") { key(KeyCodes.INFO) },
                        ui.button("Canal anterior", sizeSp = 13f) { key(KeyCodes.LAST_CHANNEL) },
                    ), 3
                )
            )
            box.addView(ui.space(10))
            box.addView(ui.matchWidth(ui.button("Personalizar temas y diseños", Palette.ACCENT, iconRes = R.drawable.ic_palette) {
                d.dismiss(); showPersonalize()
            }))
            box.addView(ui.title("Configuración"))
            box.addView(toggleRow({
                "Volumen con botones del teléfono: " + if (storage.phoneVolumeKeys) "Sí" else "No"
            }, R.drawable.ic_volume) { storage.phoneVolumeKeys = !storage.phoneVolumeKeys })
            box.addView(ui.space(10))
            box.addView(toggleRow({
                "Abrir teclado cuando el TV lo pida: " + if (storage.autoKeyboard) "Sí" else "No"
            }, R.drawable.ic_keyboard) { storage.autoKeyboard = !storage.autoKeyboard })
            box.addView(ui.space(10))
            box.addView(ui.matchWidth(ui.button("Cambiar de televisor", Palette.SURFACE2, iconRes = R.drawable.ic_tv) {
                d.dismiss(); showDevicePicker()
            }))
            box.addView(ui.space(10))
            box.addView(ui.matchWidth(ui.button("Volver a emparejar", Palette.SURFACE2, iconRes = R.drawable.ic_bolt) {
                d.dismiss()
                val dev = device
                if (dev == null) {
                    showDevicePicker()
                } else {
                    client?.close()
                    client = null
                    worker.execute {
                        tls.reset()
                        tls.keyStore()
                        main.post { startPairing(dev) }
                    }
                }
            }))
        }
    }

    private fun showKeyboard() {
        keyboardSheet?.let { if (it.isShowing) return }
        val subtitle = buildString {
            append("Escribe y toca Enviar: el texto aparece en el campo seleccionado del TV.")
            if (tvFieldLabel.isNotBlank()) append("\nCampo del TV: ").append(tvFieldLabel)
            if (tvFieldText.isNotBlank()) append("\nTexto actual en el TV: ").append(tvFieldText)
        }
        val dlg = ui.sheet("Teclado", subtitle) { box, sheet ->
            val field = ui.input("Escribe aquí…")
            field.imeOptions = EditorInfo.IME_ACTION_SEND
            fun send(enter: Boolean) {
                val t = field.text.toString()
                if (t.isNotEmpty()) sendText(t)
                if (enter) main.postDelayed({ key(KeyCodes.ENTER) }, 350)
                field.setText("")
                if (enter) sheet.dismiss()
            }
            field.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEND) {
                    send(true); true
                } else false
            }
            box.addView(field)
            box.addView(ui.space(12))
            box.addView(
                ui.grid(
                    listOf(
                        ui.button("Borrar", iconRes = R.drawable.ic_backspace, repeat = true) { key(KeyCodes.DEL) },
                        ui.button("Enviar") { send(false) },
                        ui.button("Enviar y buscar", Palette.ACCENT, sizeSp = 13f) { send(true) },
                    ), 3
                )
            )
            field.requestFocus()
        }
        dlg.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        keyboardSheet = dlg
    }

    private fun showDevicePicker() {
        val found = mutableListOf<TvDevice>()
        var listRef: LinearLayout? = null
        var emptyRef: TextView? = null
        var sheetRef: Dialog? = null

        val discovery = Discovery(this) { tv ->
            main.post {
                val list = listRef ?: return@post
                if (found.none { it.host == tv.host }) {
                    if (found.isEmpty()) emptyRef?.let { list.removeView(it) }
                    found.add(tv)
                    val row = ui.button(tv.name + "\n" + tv.host, Palette.SURFACE2, heightDp = 60, iconRes = R.drawable.ic_tv) {
                        sheetRef?.dismiss()
                        connectTo(tv)
                    }
                    row.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    lp.bottomMargin = ui.dp(8)
                    list.addView(row, lp)
                }
            }
        }

        val sheet = ui.sheet("Elige tu televisor", "El teléfono y el TV deben estar en el mismo Wi-Fi.") { box, dlg ->
            val list = ui.vertical()
            box.addView(list)
            val empty = ui.text("Buscando televisores…", 14f, Palette.MUTED)
            empty.setPadding(ui.dp(4), 0, 0, ui.dp(8))
            list.addView(empty)
            listRef = list
            emptyRef = empty
            main.postDelayed({
                if (dlg.isShowing && found.isEmpty()) {
                    empty.text = "No encontré televisores. Revisa que estén en el mismo Wi-Fi, o escribe la IP del TV (en el TV: Ajustes → Red)."
                }
            }, 8000)
            box.addView(ui.title("O conéctate por IP"))
            val ip = ui.input("Ej. 192.168.1.20")
            ip.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            box.addView(ip)
            box.addView(ui.space(10))
            box.addView(ui.matchWidth(ui.button("Conectar", Palette.ACCENT) {
                val host = ip.text.toString().trim()
                if (host.isNotEmpty()) {
                    dlg.dismiss()
                    connectTo(TvDevice(host, host))
                }
            }))
        }
        sheetRef = sheet
        sheet.setOnDismissListener { discovery.stop() }
        discovery.start()
    }

    private fun startPairing(d: TvDevice) {
        val pairing = Pairing(tls, d.host)
        setStatus("Emparejando… mira el TV", Palette.YELLOW)
        worker.execute {
            try {
                pairing.start("Control TV")
                main.post { askCode(d, pairing) }
            } catch (e: Exception) {
                pairing.close()
                main.post {
                    setStatus("No se pudo emparejar · toca para reintentar", Palette.RED)
                    toast("No se pudo emparejar: ${e.message}")
                }
            }
        }
    }

    private fun askCode(d: TvDevice, pairing: Pairing) {
        var finished = false
        var sheetRef: Dialog? = null
        val sheet = ui.sheet(
            "Emparejar con ${d.name}",
            "Escribe el código de 6 caracteres que aparece en tu televisor."
        ) { box, _ ->
            val field = ui.input("A1B2C3")
            field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            field.textSize = 26f
            field.gravity = Gravity.CENTER
            field.letterSpacing = 0.35f
            box.addView(field)
            box.addView(ui.space(12))
            val go = ui.button("Emparejar", Palette.ACCENT, heightDp = 54) {
                val code = field.text.toString()
                worker.execute {
                    try {
                        pairing.finish(code)
                        main.post {
                            finished = true
                            sheetRef?.dismiss()
                            toast("¡Emparejado!")
                            connectTo(d)
                        }
                    } catch (e: RetryableCodeException) {
                        main.post { field.error = e.message }
                    } catch (e: Exception) {
                        main.post {
                            finished = true
                            sheetRef?.dismiss()
                            toast(e.message ?: "Error al emparejar")
                            startPairing(d)
                        }
                    }
                }
            }
            box.addView(ui.matchWidth(go))
            field.requestFocus()
        }
        sheetRef = sheet
        sheet.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        sheet.setOnDismissListener {
            if (!finished) {
                worker.execute { pairing.close() }
                setStatus("Emparejamiento cancelado · toca para reintentar", Palette.RED)
            }
        }
    }

    // ---------------------------------------------------------------- app tiles

    private fun optionRow(title: String, sub: String, icon: Int, onTap: () -> Unit): View {
        val row = ui.horizontal()
        row.background = ui.ripple(ui.rounded(Palette.SURFACE2, 18))
        row.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
        val ic = ui.icon(icon, Palette.ACCENT, 24)
        ic.layoutParams = LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { rightMargin = ui.dp(14) }
        row.addView(ic)
        val col = ui.vertical()
        col.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        col.addView(ui.text(title, 15f, Palette.TEXT, true))
        val s = ui.text(sub, 12f, Palette.MUTED)
        s.setPadding(0, ui.dp(2), 0, 0)
        col.addView(s)
        row.addView(col)
        ui.press(row, false, null, onTap)
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = ui.dp(10) }
        return row
    }

    private fun showAddApp() {
        ui.sheet("Añadir app", "Crea un botón que abra una app en el TV.") { box, d ->
            box.addView(optionRow("Elegir de la lista", "Netflix, Spotify, Max, Twitch, Plex y más", R.drawable.ic_apps) {
                d.dismiss(); showCatalog()
            })
            val pkg = currentApp
            box.addView(optionRow(
                "La app abierta ahora en el TV",
                if (pkg == null) "Abre una app en el TV y vuelve aquí" else appLabel(pkg),
                R.drawable.ic_tv
            ) {
                if (currentApp == null) toast("Primero abre la app en el TV") else {
                    d.dismiss(); addCurrentApp()
                }
            })
            box.addView(optionRow("Escribir paquete o enlace", "Ej. com.spotify.tv.android", R.drawable.ic_keyboard) {
                d.dismiss(); editApp(-1, null)
            })
        }
    }

    private fun showCatalog() {
        ui.sheet("Elige una app", "Tócala para añadirla a tus apps.") { box, d ->
            val tiles = AppCatalog.apps.map { a ->
                ui.appTile(a.name, a.color, null) {
                    if (apps.none { it.link == a.link }) {
                        apps.add(a)
                        storage.saveApps(apps)
                        renderApps()
                        toast("${a.name} añadida")
                    } else {
                        toast("${a.name} ya está en tus apps")
                    }
                    d.dismiss()
                }
            }
            box.addView(ui.grid(tiles, 4, 14, center = true))
            val note = ui.text(
                "Si alguna no abre en tu TV, mantén presionado su botón y usa \"Grabar ruta\".",
                12f, Palette.MUTED
            )
            note.setPadding(ui.dp(2), ui.dp(16), 0, 0)
            box.addView(note)
        }
    }

    private fun addCurrentApp() {
        val pkg = currentApp
        if (pkg == null) {
            toast("Primero abre la app en el TV"); return
        }
        val known = AppCatalog.apps.firstOrNull { it.link.endsWith("id=$pkg") }
        val name = known?.name ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        val color = known?.color ?: Palette.CHIPS[apps.size % Palette.CHIPS.size]
        editApp(-1, AppShortcut(name, known?.link ?: AppCatalog.pkg(pkg), color))
    }

    private fun label(s: String, topDp: Int): TextView {
        val t = ui.text(s, 12f, Palette.MUTED)
        t.setPadding(ui.dp(2), ui.dp(topDp), 0, ui.dp(6))
        return t
    }

    private fun editApp(index: Int, preset: AppShortcut?) {
        val existing = apps.getOrNull(index) ?: preset
        ui.sheet(if (index >= 0) "Editar app" else "Nueva app") { box, d ->
            box.addView(label("Nombre", 0))
            val name = ui.input("Ej. Spotify")
            name.setText(existing?.name ?: "")
            box.addView(name)
            box.addView(label("Paquete o enlace", 14))
            val linkField = ui.input("com.ejemplo.app  o  https://…")
            linkField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            linkField.setText(existing?.link?.removePrefix("market://launch?id=") ?: "")
            box.addView(linkField)
            var color = existing?.color ?: Palette.CHIPS[apps.size % Palette.CHIPS.size]
            box.addView(label("Color", 14))
            box.addView(colorPicker(color) { color = it })

            if (index >= 0) {
                box.addView(ui.title("¿No abre en tu TV?"))
                val routeInfo = existing?.route?.size ?: 0
                val info = ui.text(
                    if (routeInfo > 0) "Ruta grabada: $routeInfo pasos. El botón usa la ruta en vez del enlace."
                    else "Graba el camino desde Inicio hasta la app y el botón lo repetirá por ti.",
                    12f, Palette.MUTED
                )
                info.setPadding(ui.dp(2), 0, 0, ui.dp(8))
                box.addView(info)
                val routeButtons = mutableListOf<View>(
                    ui.button("Grabar ruta", Palette.SURFACE2, iconRes = R.drawable.ic_record) {
                        d.dismiss(); startRoute(index)
                    }
                )
                if (routeInfo > 0) {
                    routeButtons.add(ui.button("Quitar ruta", Palette.SURFACE2) {
                        val a = apps[index]
                        apps[index] = a.copy(route = emptyList())
                        storage.saveApps(apps)
                        renderApps()
                        d.dismiss()
                    })
                }
                box.addView(ui.grid(routeButtons, routeButtons.size))
            }

            box.addView(ui.space(18))
            val actions = mutableListOf<View>()
            if (index >= 0) {
                actions.add(ui.button("Borrar", Palette.SURFACE2, Palette.RED) {
                    apps.removeAt(index)
                    storage.saveApps(apps)
                    renderApps()
                    d.dismiss()
                })
            }
            actions.add(ui.button("Guardar", Palette.ACCENT) {
                val raw = linkField.text.toString().trim()
                val nm = name.text.toString().trim()
                if (raw.isEmpty() || nm.isEmpty()) {
                    toast("Escribe el nombre y el paquete o enlace")
                } else {
                    val l = if (raw.contains(":")) raw else AppCatalog.pkg(raw)
                    val keepRoute = existing?.route ?: emptyList()
                    val a = AppShortcut(nm, l, color, if (index >= 0) keepRoute else emptyList())
                    if (index >= 0) apps[index] = a else apps.add(a)
                    storage.saveApps(apps)
                    renderApps()
                    d.dismiss()
                }
            })
            box.addView(ui.grid(actions, actions.size))
        }
    }

    private fun colorPicker(initial: Int, onPick: (Int) -> Unit): View {
        var color = initial
        val row = ui.horizontal()
        val swatches = mutableListOf<View>()
        fun refresh() {
            swatches.forEachIndexed { i, s ->
                val c = Palette.CHIPS[i]
                val o = ui.oval(c)
                if (c == color) o.setStroke(ui.dp(3), Palette.TEXT)
                s.background = o
            }
        }
        Palette.CHIPS.forEach { c ->
            val s = View(this)
            s.layoutParams = LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)).apply { rightMargin = ui.dp(10) }
            s.setOnClickListener {
                color = c
                onPick(c)
                refresh()
            }
            swatches.add(s)
            row.addView(s)
        }
        refresh()
        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.addView(row)
        return scroll
    }

    // ---------------------------------------------------------------- shortcuts

    private fun editShortcut(index: Int) {
        val existing = buttons.getOrNull(index)
        ui.sheet(if (existing == null) "Nuevo atajo" else "Editar atajo") { box, d ->
            box.addView(label("Nombre", 0))
            val name = ui.input("Ej. Música para estudiar")
            name.setText(existing?.label ?: "")
            box.addView(name)

            box.addView(label("Qué hace", 14))
            val types = ActionType.values()
            val typeSpinner = Spinner(this)
            typeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types.map { it.label })
            typeSpinner.setSelection(existing?.type?.ordinal ?: 0)
            typeSpinner.background = ui.rounded(Palette.SURFACE2, 16, Palette.STROKE)
            typeSpinner.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
            box.addView(typeSpinner)
            box.addView(ui.space(10))

            val value = ui.input("")
            value.setText(if (existing == null || existing.type == ActionType.KEY) "" else existing.value)
            val keySpinner = Spinner(this)
            keySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, KeyCodes.named.map { it.first })
            keySpinner.background = ui.rounded(Palette.SURFACE2, 16, Palette.STROKE)
            keySpinner.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
            if (existing != null && existing.type == ActionType.KEY) {
                val idx = KeyCodes.named.indexOfFirst { it.second.toString() == existing.value }
                if (idx >= 0) keySpinner.setSelection(idx)
            }
            box.addView(value)
            box.addView(keySpinner)

            fun updateFields() {
                val t = types[typeSpinner.selectedItemPosition]
                val isKey = t == ActionType.KEY
                keySpinner.visibility = if (isKey) View.VISIBLE else View.GONE
                value.visibility = if (isKey) View.GONE else View.VISIBLE
                value.hint = t.hint
            }
            typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    updateFields()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            updateFields()

            var color = existing?.color ?: Palette.CHIPS[buttons.size % Palette.CHIPS.size]
            box.addView(label("Color", 14))
            box.addView(colorPicker(color) { color = it })
            box.addView(ui.space(18))

            val actions = mutableListOf<View>()
            if (existing != null) {
                actions.add(ui.button("Borrar", Palette.SURFACE2, Palette.RED) {
                    buttons.removeAt(index)
                    storage.saveButtons(buttons)
                    renderShortcuts()
                    d.dismiss()
                })
            }
            actions.add(ui.button("Guardar", Palette.ACCENT) {
                val t = types[typeSpinner.selectedItemPosition]
                val v = if (t == ActionType.KEY) KeyCodes.named[keySpinner.selectedItemPosition].second.toString()
                else value.text.toString().trim()
                if (v.isEmpty()) {
                    toast("Falta el contenido del atajo")
                } else {
                    val lbl = name.text.toString().trim().ifEmpty {
                        if (t == ActionType.KEY) KeyCodes.named[keySpinner.selectedItemPosition].first else v.take(16)
                    }
                    val nb = CustomButton(lbl, t, v, color)
                    if (existing == null) buttons.add(nb) else buttons[index] = nb
                    storage.saveButtons(buttons)
                    renderShortcuts()
                    d.dismiss()
                }
            })
            box.addView(ui.grid(actions, actions.size))
        }
    }
}
