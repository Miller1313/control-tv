package com.miller.controltv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
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
import java.util.concurrent.Executors
import kotlin.math.abs

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

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var tvName: TextView
    private lateinit var volumeLabel: TextView
    private lateinit var navHolder: FrameLayout
    private lateinit var navToggle: FrameLayout
    private lateinit var appsHolder: LinearLayout
    private lateinit var nowPlayingCard: LinearLayout
    private lateinit var nowPlayingText: TextView
    private lateinit var nowPlayingButton: View
    private lateinit var historyHolder: LinearLayout
    private lateinit var shortcutsHolder: LinearLayout
    private lateinit var searchField: EditText

    private val pages = mutableListOf<View>()
    private val tabs = mutableListOf<LinearLayout>()

    private lateinit var buttons: MutableList<CustomButton>
    private lateinit var apps: MutableList<AppShortcut>

    // ================================================================ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        storage = Storage(this)
        tls = Tls(this)
        buttons = storage.loadButtons()
        apps = storage.loadApps()
        setContentView(buildLayout())
        selectTab(0)

        worker.execute { tls.keyStore() }

        val last = storage.lastDevice
        if (last != null) connectTo(last) else main.postDelayed({ showDevicePicker() }, 300)
    }

    override fun onDestroy() {
        super.onDestroy()
        client?.close()
        worker.shutdown()
    }

    override fun onResume() {
        super.onResume()
        val d = device
        if (d != null && client == null) connectTo(d)
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

    // ================================================================ layout

    private fun buildLayout(): View {
        val root = ui.vertical()
        root.setBackgroundColor(Palette.BG)
        root.addView(header())

        val content = FrameLayout(this)
        content.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        listOf(controlPage(), appsPage(), searchPage()).forEach { page ->
            val scroll = ScrollView(this)
            scroll.isVerticalScrollBarEnabled = false
            scroll.addView(page)
            pages.add(scroll)
            content.addView(scroll)
        }
        root.addView(content)
        root.addView(bottomNav())
        return root
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
        statusRow.setOnClickListener { device?.let { connectTo(it) } ?: showDevicePicker() }
        texts.addView(nameRow)
        texts.addView(statusRow)
        row.addView(texts)

        row.addView(ui.iconButton(R.drawable.ic_input, 46, Palette.SURFACE2, iconDp = 22) { showInputs() })
        val gap = View(this)
        gap.layoutParams = LinearLayout.LayoutParams(ui.dp(10), 1)
        row.addView(gap)
        val powerBg = (Palette.RED and 0x00FFFFFF) or 0x2E000000
        row.addView(ui.iconButton(R.drawable.ic_power, 46, powerBg, Palette.RED, 22) { key(KeyCodes.POWER) })
        return row
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
            val color = if (on) Color.WHITE else Palette.MUTED
            (t.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(color)
            (t.getChildAt(1) as TextView).setTextColor(color)
        }
        if (index == 1) renderNowPlaying()
    }

    // ---------------------------------------------------------------- control page

    private fun controlPage(): View {
        val page = ui.vertical()
        page.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(16))

        navToggle = ui.iconButton(if (storage.touchpad) R.drawable.ic_dpad else R.drawable.ic_touch, 54) {
            toggleTouchpad()
        }
        page.addView(
            ui.grid(
                listOf(
                    ui.iconButton(R.drawable.ic_back, 54) { key(KeyCodes.BACK) },
                    ui.iconButton(R.drawable.ic_home, 54) { key(KeyCodes.HOME) },
                    navToggle,
                    ui.iconButton(R.drawable.ic_more, 54) { showMore() },
                ), 4, 10, center = true
            )
        )

        page.addView(ui.space(18))
        navHolder = FrameLayout(this)
        navHolder.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(270))
        page.addView(navHolder)
        renderNav()

        page.addView(ui.space(18))
        volumeLabel = ui.text("VOL", 12f, Palette.MUTED, true)
        page.addView(
            ui.grid(
                listOf(
                    pill(KeyCodes.VOLUME_DOWN, KeyCodes.VOLUME_UP, volumeLabel, R.drawable.ic_mute) { key(KeyCodes.MUTE) },
                    pill(KeyCodes.CHANNEL_DOWN, KeyCodes.CHANNEL_UP, ui.text("CH", 12f, Palette.MUTED, true), null, null),
                ), 2, 12
            )
        )

        page.addView(ui.space(16))
        val media = ui.horizontal()
        media.gravity = Gravity.CENTER
        media.background = ui.rounded(Palette.SURFACE, 30)
        media.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
        media.addView(ui.iconButton(R.drawable.ic_prev, 46, Palette.SURFACE) { key(KeyCodes.PREVIOUS) })
        media.addView(flexGap())
        media.addView(ui.iconButton(R.drawable.ic_rew, 46, Palette.SURFACE, repeat = true) { key(KeyCodes.REWIND) })
        media.addView(flexGap())
        media.addView(ui.iconButton(R.drawable.ic_play_pause, 58, Palette.TEXT, Palette.BG, 26) { key(KeyCodes.PLAY_PAUSE) })
        media.addView(flexGap())
        media.addView(ui.iconButton(R.drawable.ic_ff, 46, Palette.SURFACE, repeat = true) { key(KeyCodes.FAST_FORWARD) })
        media.addView(flexGap())
        media.addView(ui.iconButton(R.drawable.ic_next, 46, Palette.SURFACE) { key(KeyCodes.NEXT) })
        page.addView(media)

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

    private fun flexGap(): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        return v
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

    private fun toggleTouchpad() {
        storage.touchpad = !storage.touchpad
        (navToggle.getChildAt(0) as ImageView).setImageResource(
            if (storage.touchpad) R.drawable.ic_dpad else R.drawable.ic_touch
        )
        renderNav()
        toast(if (storage.touchpad) "Modo touchpad: desliza para moverte, toca para OK" else "Modo flechas")
    }

    private fun renderNav() {
        navHolder.removeAllViews()
        navHolder.addView(if (storage.touchpad) touchpad() else dpad())
    }

    private fun dpad(): View {
        val size = ui.dp(262)
        val ring = FrameLayout(this)
        ring.layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        ring.background = ui.oval(Palette.SURFACE, Palette.STROKE)

        fun arrow(rot: Float, gravity: Int, code: Int): View {
            val b = ui.iconButton(
                R.drawable.ic_chevron, 76, Palette.SURFACE, Palette.TEXT, 30,
                repeat = true, rotation = rot
            ) { key(code) }
            val m = ui.dp(6)
            b.layoutParams = FrameLayout.LayoutParams(ui.dp(76), ui.dp(76), gravity).apply { setMargins(m, m, m, m) }
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
        ok.layoutParams = FrameLayout.LayoutParams(ui.dp(104), ui.dp(104), Gravity.CENTER)
        val okText = ui.text("OK", 20f, Color.WHITE, true)
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

    // ---------------------------------------------------------------- apps page

    private fun appsPage(): View {
        val page = ui.vertical()
        page.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(16))
        page.addView(ui.text("Tus apps", 22f, Palette.TEXT, true))
        val sub = ui.text("Toca para abrirla en el TV. Mantén presionada para editar.", 13f, Palette.MUTED)
        sub.setPadding(0, ui.dp(4), 0, ui.dp(18))
        page.addView(sub)
        appsHolder = ui.vertical()
        page.addView(appsHolder)
        renderApps()

        page.addView(ui.space(22))
        nowPlayingCard = ui.vertical(16)
        nowPlayingCard.background = ui.rounded(Palette.SURFACE, 22, Palette.STROKE)
        val cap = ui.text("ABIERTA AHORA EN EL TV", 11f, Palette.MUTED, true)
        cap.letterSpacing = 0.08f
        nowPlayingCard.addView(cap)
        nowPlayingText = ui.text("—", 16f, Palette.TEXT, true)
        nowPlayingText.setPadding(0, ui.dp(6), 0, ui.dp(12))
        nowPlayingCard.addView(nowPlayingText)
        nowPlayingButton = ui.button("Guardar como botón", Palette.SURFACE2, iconRes = R.drawable.ic_add) {
            addCurrentApp()
        }
        nowPlayingCard.addView(nowPlayingButton)
        page.addView(nowPlayingCard)
        renderNowPlaying()
        return page
    }

    private fun renderApps() {
        appsHolder.removeAllViews()
        val views = mutableListOf<View>()
        apps.forEachIndexed { i, a ->
            views.add(ui.appTile(a.name, a.color, { editApp(i, null) }) { link(a.link) })
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
        AppCatalog.apps.firstOrNull { it.link.endsWith("id=$pkg") }?.name ?: pkg

    // ---------------------------------------------------------------- search page

    private fun searchPage(): View {
        val page = ui.vertical()
        page.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(16))
        page.addView(ui.text("Buscar", 22f, Palette.TEXT, true))
        val sub = ui.text("Canciones, videos, series o películas.", 13f, Palette.MUTED)
        sub.setPadding(0, ui.dp(4), 0, ui.dp(16))
        page.addView(sub)
        searchField = ui.input("¿Qué quieres ver o escuchar?")
        searchField.imeOptions = EditorInfo.IME_ACTION_SEARCH
        searchField.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                youtubeSearch(searchField.text.toString()); true
            } else false
        }
        page.addView(searchField)
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
        c.key(code)
    }

    private fun link(url: String) {
        val c = client
        if (c == null || !c.ready) {
            notConnected(); return
        }
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
            if (needsPairing) {
                setStatus("Necesita emparejar", Palette.YELLOW)
                device?.let { startPairing(it) }
            } else {
                setStatus("Desconectado · toca para reconectar", Palette.RED)
                if (autoRetry) {
                    autoRetry = false
                    main.postDelayed({ device?.let { if (client == null) connectTo(it) } }, 2000)
                }
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

    // ================================================================ sheets

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
            box.addView(ui.title("Configuración"))
            fun volLabel() = if (storage.phoneVolumeKeys) "Volumen con botones del teléfono: Sí"
            else "Volumen con botones del teléfono: No"
            val volBtn = ui.button(volLabel(), Palette.SURFACE2, iconRes = R.drawable.ic_volume) {}
            ui.press(volBtn, false, null) {
                storage.phoneVolumeKeys = !storage.phoneVolumeKeys
                (volBtn.getChildAt(1) as TextView).text = volLabel()
            }
            box.addView(ui.matchWidth(volBtn))
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
        val dlg = ui.sheet(
            "Teclado",
            "Lo que escribas aparece en el TV al instante. Primero selecciona un campo de texto en el TV."
        ) { box, _ ->
            val field = ui.input("Escribe aquí…")
            field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            field.imeOptions = EditorInfo.IME_ACTION_DONE
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val c = client
                    if (c == null || !c.ready || s == null) return
                    repeat(before) { c.key(KeyCodes.DEL) }
                    if (count > 0) {
                        val added = s.subSequence(start, start + count).toString()
                        for (ch in added) {
                            val k = KeyCodes.forChar(ch) ?: continue
                            if (k.second) {
                                c.key(KeyCodes.SHIFT_LEFT, RemoteClient.START_LONG)
                                c.key(k.first)
                                c.key(KeyCodes.SHIFT_LEFT, RemoteClient.END_LONG)
                            } else {
                                c.key(k.first)
                            }
                        }
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            field.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) {
                    key(KeyCodes.ENTER); true
                } else false
            }
            box.addView(field)
            box.addView(ui.space(12))
            box.addView(
                ui.grid(
                    listOf(
                        ui.button("Borrar", iconRes = R.drawable.ic_backspace, repeat = true) { key(KeyCodes.DEL) },
                        ui.button("Enter", Palette.ACCENT) { key(KeyCodes.ENTER) },
                    ), 2
                )
            )
            field.requestFocus()
        }
        dlg.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
    }

    private fun showDevicePicker() {
        val found = mutableListOf<TvDevice>()
        var listRef: LinearLayout? = null
        var emptyRef: TextView? = null
        var sheetRef: android.app.Dialog? = null

        val realDiscovery = Discovery(this) { tv ->
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
        sheet.setOnDismissListener { realDiscovery.stop() }
        realDiscovery.start()
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
        var sheetRef: android.app.Dialog? = null
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
                "Si alguna no abre en tu TV, abre la app en el TV y usa \"La app abierta ahora en el TV\".",
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
        editApp(-1, AppShortcut(name, AppCatalog.pkg(pkg), color))
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
                    val a = AppShortcut(nm, l, color)
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
