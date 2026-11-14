package com.miller.controltv

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.net.URLEncoder
import java.util.concurrent.Executors

class MainActivity : Activity(), RemoteClient.Listener {

    private lateinit var ui: Ui
    private lateinit var storage: Storage
    private lateinit var tls: Tls
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var client: RemoteClient? = null
    private var device: TvDevice? = null
    private var autoRetry = true

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var tvName: TextView
    private lateinit var volumeText: TextView
    private lateinit var customHolder: LinearLayout
    private lateinit var buttons: MutableList<CustomButton>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        storage = Storage(this)
        tls = Tls(this)
        buttons = storage.loadButtons()
        setContentView(buildLayout())

        // Generate the client certificate early (takes a moment the first time).
        worker.execute { tls.keyStore() }

        val last = storage.lastDevice
        if (last != null) connectTo(last) else showDevicePicker()
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

    // ---------------------------------------------------------------- layout

    private fun buildLayout(): View {
        val root = ui.vertical(16)
        root.setBackgroundColor(Palette.BG)

        root.addView(header())

        // Top row: power, home, back, settings
        root.addView(ui.space(14))
        root.addView(
            ui.grid(
                listOf(
                    ui.key("Apagar", Palette.RED, sizeSp = 14f) { key(KeyCodes.POWER) },
                    ui.key("⌂ Inicio") { key(KeyCodes.HOME) },
                    ui.key("← Atrás") { key(KeyCodes.BACK) },
                    ui.key("⚙") { key(KeyCodes.SETTINGS) },
                ), 4
            )
        )

        // D-pad
        root.addView(ui.space(16))
        root.addView(dpad())

        // Volume / channel
        root.addView(ui.space(16))
        root.addView(volumeAndChannel())

        // Media keys
        root.addView(ui.space(12))
        root.addView(
            ui.grid(
                listOf(
                    ui.key("⏮") { key(KeyCodes.PREVIOUS) },
                    ui.key("⏪", repeat = true) { key(KeyCodes.REWIND) },
                    ui.key("⏯", Palette.KEY_STRONG) { key(KeyCodes.PLAY_PAUSE) },
                    ui.key("⏩", repeat = true) { key(KeyCodes.FAST_FORWARD) },
                    ui.key("⏭") { key(KeyCodes.NEXT) },
                ), 5, 6
            )
        )

        // Apps
        root.addView(ui.section("Apps"))
        root.addView(
            ui.grid(
                listOf(
                    ui.key("YouTube", Palette.YOUTUBE) { link("https://www.youtube.com") },
                    ui.key("Netflix", Palette.NETFLIX) { link("https://www.netflix.com/title") },
                    ui.key("Spotify", Palette.SPOTIFY) { link("https://open.spotify.com") },
                    ui.key("Prime Video", Palette.PRIME) { link("https://app.primevideo.com") },
                    ui.key("Disney+", Palette.DISNEY) { link("https://www.disneyplus.com") },
                    ui.key("Guía", Palette.KEY) { key(KeyCodes.GUIDE) },
                ), 3
            )
        )

        // Search
        root.addView(ui.section("Búsqueda rápida"))
        root.addView(searchCard())

        // Keyboard
        root.addView(ui.section("Teclado"))
        root.addView(keyboardCard())

        // Inputs
        root.addView(ui.section("Fuente / entrada"))
        root.addView(
            ui.grid(
                listOf(
                    ui.key("HDMI 1") { key(KeyCodes.HDMI_1) },
                    ui.key("HDMI 2") { key(KeyCodes.HDMI_2) },
                    ui.key("HDMI 3") { key(KeyCodes.HDMI_3) },
                    ui.key("AV") { key(KeyCodes.COMPOSITE_1) },
                    ui.key("TV / Antena") { key(KeyCodes.ANTENNA_CABLE) },
                    ui.key("Menú fuentes", Palette.KEY_STRONG) { key(KeyCodes.TV_INPUT) },
                ), 3
            )
        )
        root.addView(
            ui.text(
                "Si un botón HDMI no responde, usa \"Menú fuentes\": algunos televisores solo aceptan ese.",
                12f, Palette.MUTED
            ).apply { setPadding(ui.dp(4), ui.dp(8), ui.dp(4), 0) }
        )

        // Custom buttons
        root.addView(ui.section("Mis botones"))
        customHolder = ui.vertical()
        root.addView(customHolder)
        renderCustomButtons()
        root.addView(
            ui.text("Mantén presionado un botón para editarlo o borrarlo.", 12f, Palette.MUTED)
                .apply { setPadding(ui.dp(4), ui.dp(8), ui.dp(4), 0) }
        )

        // Extras
        root.addView(ui.section("Más"))
        root.addView(
            ui.grid(
                listOf(
                    ui.key("Subtítulos") { key(KeyCodes.CAPTIONS) },
                    ui.key("Asistente") { key(KeyCodes.ASSIST) },
                    ui.key("Menú") { key(KeyCodes.MENU) },
                ), 3
            )
        )
        root.addView(ui.space(30))

        return ScrollView(this).apply {
            setBackgroundColor(Palette.BG)
            isFillViewport = true
            addView(root)
        }
    }

    private fun header(): View {
        val row = ui.horizontal()
        val texts = ui.vertical()
        texts.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tvName = ui.text("Control TV", 22f, Palette.TEXT, true)
        val statusRow = ui.horizontal()
        statusDot = View(this).apply {
            background = ui.oval(Palette.MUTED)
            layoutParams = LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)).apply { rightMargin = ui.dp(6) }
        }
        statusText = ui.text("Sin conectar", 13f, Palette.MUTED)
        statusRow.addView(statusDot)
        statusRow.addView(statusText)
        statusRow.setOnClickListener { device?.let { connectTo(it) } }
        texts.addView(tvName)
        texts.addView(statusRow)
        row.addView(texts)
        row.addView(ui.key("📺 Cambiar TV", Palette.KEY, sizeSp = 13f, heightDp = 40) { showDevicePicker() })
        return row
    }

    private fun dpad(): View {
        val size = ui.dp(68)
        fun cell(v: View?): View {
            val holder = LinearLayout(this).apply { gravity = Gravity.CENTER }
            holder.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
            }
            if (v != null) holder.addView(v, LinearLayout.LayoutParams(size, size))
            return holder
        }
        fun row(vararg views: View?): LinearLayout = ui.horizontal().apply {
            gravity = Gravity.CENTER
            views.forEach { addView(cell(it)) }
        }
        val pad = ui.vertical(10).apply {
            gravity = Gravity.CENTER
            background = ui.rounded(Palette.CARD, 140)
        }
        pad.addView(row(null, ui.key("▲", circle = true, repeat = true) { key(KeyCodes.DPAD_UP) }, null))
        pad.addView(
            row(
                ui.key("◀", circle = true, repeat = true) { key(KeyCodes.DPAD_LEFT) },
                ui.key("OK", Palette.ACCENT, circle = true, sizeSp = 18f) { key(KeyCodes.DPAD_CENTER) },
                ui.key("▶", circle = true, repeat = true) { key(KeyCodes.DPAD_RIGHT) },
            )
        )
        pad.addView(row(null, ui.key("▼", circle = true, repeat = true) { key(KeyCodes.DPAD_DOWN) }, null))
        val wrap = LinearLayout(this).apply { gravity = Gravity.CENTER }
        wrap.addView(pad)
        return wrap
    }

    private fun volumeAndChannel(): View {
        fun column(title: String, up: () -> Unit, down: () -> Unit, extra: TextView? = null): LinearLayout {
            val c = ui.vertical(8).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                background = ui.rounded(Palette.CARD, 22)
            }
            c.addView(ui.key("+", sizeSp = 22f, repeat = true, onTap = up))
            c.addView(ui.text(title, 12f, Palette.MUTED, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, ui.dp(8), 0, if (extra == null) ui.dp(8) else 0)
            })
            extra?.let {
                it.gravity = Gravity.CENTER
                it.setPadding(0, 0, 0, ui.dp(8))
                c.addView(it)
            }
            c.addView(ui.key("−", sizeSp = 22f, repeat = true, onTap = down))
            return c
        }
        volumeText = ui.text("", 11f, Palette.MUTED)
        val middle = ui.vertical().apply { gravity = Gravity.CENTER }
        middle.addView(ui.key("🔇 Silencio", Palette.KEY, sizeSp = 13f) { key(KeyCodes.MUTE) })
        middle.addView(ui.space(8))
        middle.addView(ui.key("⌨ Escribir", Palette.KEY, sizeSp = 13f) { quickTypeDialog() })
        middle.addView(ui.space(8))
        middle.addView(ui.key("🔎 Buscar", Palette.KEY, sizeSp = 13f) { key(KeyCodes.SEARCH) })

        return ui.grid(
            listOf(
                column("VOL", { key(KeyCodes.VOLUME_UP) }, { key(KeyCodes.VOLUME_DOWN) }, volumeText),
                middle,
                column("CH", { key(KeyCodes.CHANNEL_UP) }, { key(KeyCodes.CHANNEL_DOWN) }),
            ), 3, 10
        )
    }

    private fun searchCard(): View {
        val card = ui.card()
        val field = ui.input("Canción, artista o película…")
        field.imeOptions = EditorInfo.IME_ACTION_SEARCH
        field.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { youtubeSearch(field.text.toString()); true } else false
        }
        card.addView(field)
        card.addView(ui.space(10))
        card.addView(
            ui.grid(
                listOf(
                    ui.key("▶ YouTube", Palette.YOUTUBE, sizeSp = 14f) { youtubeSearch(field.text.toString()) },
                    ui.key("Google TV", Palette.ACCENT, sizeSp = 14f) { googleTvSearch(field.text.toString()) },
                ), 2
            )
        )
        card.addView(
            ui.text(
                "YouTube abre los resultados directo. \"Google TV\" abre el buscador del TV y escribe tu búsqueda (sirve para películas de cualquier app).",
                12f, Palette.MUTED
            ).apply { setPadding(ui.dp(2), ui.dp(10), ui.dp(2), 0) }
        )
        return card
    }

    private fun keyboardCard(): View {
        val card = ui.card()
        val field = ui.input("Escribe aquí y pulsa Enviar")
        field.imeOptions = EditorInfo.IME_ACTION_SEND
        field.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) {
                sendText(field.text.toString()); field.setText(""); true
            } else false
        }
        card.addView(field)
        card.addView(ui.space(10))
        card.addView(
            ui.grid(
                listOf(
                    ui.key("Enviar", Palette.ACCENT, sizeSp = 14f) {
                        sendText(field.text.toString()); field.setText("")
                    },
                    ui.key("⌫ Borrar", sizeSp = 14f, repeat = true) { key(KeyCodes.DEL) },
                    ui.key("↵ Enter", sizeSp = 14f) { key(KeyCodes.ENTER) },
                ), 3
            )
        )
        card.addView(
            ui.text("Primero selecciona un campo de texto en el TV (por ejemplo, el buscador).", 12f, Palette.MUTED)
                .apply { setPadding(ui.dp(2), ui.dp(10), ui.dp(2), 0) }
        )
        return card
    }

    private fun renderCustomButtons() {
        customHolder.removeAllViews()
        val views = mutableListOf<View>()
        buttons.forEachIndexed { i, b ->
            views.add(ui.key(b.label, b.color, sizeSp = 14f, onLongPress = { editButton(i) }) { runCustom(b) })
        }
        views.add(ui.key("＋ Añadir", Palette.KEY, Palette.MUTED, sizeSp = 14f) { editButton(-1) })
        customHolder.addView(ui.grid(views, 3))
    }

    // --------------------------------------------------------------- actions

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
        link("https://www.youtube.com/results?search_query=" + URLEncoder.encode(q.trim(), "UTF-8"))
    }

    private fun googleTvSearch(q: String) {
        if (q.isBlank()) {
            toast("Escribe qué quieres buscar"); return
        }
        key(KeyCodes.SEARCH)
        main.postDelayed({ sendText(q.trim()) }, 1800)
        main.postDelayed({ key(KeyCodes.ENTER) }, 2600)
    }

    private fun runCustom(b: CustomButton) {
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

    // ------------------------------------------------------------ connection

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
        worker.execute { tls.keyStore(); main.post { if (client === c) c.connect() } }
    }

    override fun onReady() {
        main.post {
            autoRetry = true
            device?.let { storage.lastDevice = it }
            setStatus("Conectado", Palette.GREEN)
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
        main.post { volumeText.text = if (muted) "silencio" else if (max > 0) "$level/$max" else "" }
    }

    // --------------------------------------------------------------- dialogs

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun showDevicePicker() {
        val found = mutableListOf<TvDevice>()
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        val box = ui.vertical(20)
        val hint = ui.text("Buscando televisores en tu Wi-Fi…", 14f, Palette.MUTED)
        box.addView(hint)
        val list = android.widget.ListView(this).apply {
            this.adapter = adapter
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(220))
        }
        box.addView(list)
        val ip = ui.input("O escribe la IP del TV (ej. 192.168.1.20)")
        ip.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        box.addView(ip)

        lateinit var discovery: Discovery
        val dlg = dialog()
            .setTitle("Elige tu televisor")
            .setView(box)
            .setPositiveButton("Usar IP") { _, _ ->
                val host = ip.text.toString().trim()
                if (host.isNotEmpty()) connectTo(TvDevice(host, host))
            }
            .setNegativeButton("Cancelar", null)
            .create()

        discovery = Discovery(this) { d ->
            main.post {
                if (found.none { it.host == d.host }) {
                    found.add(d)
                    adapter.add("${d.name}\n${d.host}")
                    hint.text = "Toca tu televisor:"
                }
            }
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            dlg.dismiss()
            connectTo(found[pos])
        }
        dlg.setOnDismissListener { discovery.stop() }
        dlg.show()
        discovery.start()
        main.postDelayed({
            if (dlg.isShowing && found.isEmpty()) {
                hint.text = "No encontré televisores. Revisa que el teléfono y el TV estén en el mismo Wi-Fi, o escribe la IP del TV (Ajustes → Red en el TV)."
            }
        }, 8000)
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
                    setStatus("No se pudo emparejar", Palette.RED)
                    dialog().setTitle("No se pudo emparejar")
                        .setMessage("Error: ${e.message}\n\nComprueba que el TV esté encendido y en el mismo Wi-Fi.")
                        .setPositiveButton("Reintentar") { _, _ -> startPairing(d) }
                        .setNegativeButton("Cerrar", null)
                        .show()
                }
            }
        }
    }

    private fun askCode(d: TvDevice, pairing: Pairing) {
        val box = ui.vertical(20)
        box.addView(ui.text("Escribe el código de 6 caracteres que aparece en tu televisor.", 14f, Palette.TEXT))
        box.addView(ui.space(12))
        val field = ui.input("Ej. 4F2A9C").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            textSize = 22f
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
        }
        box.addView(field)
        val dlg = dialog().setTitle("Emparejar con ${d.name}")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Emparejar", null)
            .setNegativeButton("Cancelar") { _, _ ->
                worker.execute { pairing.close() }
                setStatus("Emparejamiento cancelado", Palette.RED)
            }
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = field.text.toString()
                worker.execute {
                    try {
                        pairing.finish(code)
                        main.post {
                            dlg.dismiss()
                            toast("¡Emparejado!")
                            connectTo(d)
                        }
                    } catch (e: RetryableCodeException) {
                        main.post { field.error = e.message }
                    } catch (e: Exception) {
                        main.post {
                            dlg.dismiss()
                            toast(e.message ?: "Error al emparejar")
                            // The TV closed the session: start again so it shows a new code.
                            startPairing(d)
                        }
                    }
                }
            }
        }
        dlg.show()
    }

    private fun quickTypeDialog() {
        val field = ui.input("Texto para el TV")
        val box = ui.vertical(20).apply { addView(field) }
        dialog().setTitle("Escribir en el TV")
            .setView(box)
            .setPositiveButton("Enviar") { _, _ -> sendText(field.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .show()
        field.requestFocus()
    }

    private fun editButton(index: Int) {
        val existing = buttons.getOrNull(index)
        val box = ui.vertical(20)
        val name = ui.input("Nombre del botón").apply { setText(existing?.label ?: "") }
        box.addView(ui.text("Nombre", 12f, Palette.MUTED))
        box.addView(name)
        box.addView(ui.space(12))
        box.addView(ui.text("Qué hace", 12f, Palette.MUTED))

        val types = ActionType.values()
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, types.map { it.label }
            )
            setSelection(existing?.type?.ordinal ?: 0)
        }
        box.addView(typeSpinner)
        box.addView(ui.space(12))

        val value = ui.input("").apply { setText(if (existing?.type == ActionType.KEY) "" else existing?.value ?: "") }
        val keySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, KeyCodes.named.map { it.first }
            )
            if (existing?.type == ActionType.KEY) {
                val idx = KeyCodes.named.indexOfFirst { it.second.toString() == existing.value }
                if (idx >= 0) setSelection(idx)
            }
        }
        box.addView(value)
        box.addView(keySpinner)

        box.addView(ui.space(12))
        box.addView(ui.text("Color", 12f, Palette.MUTED))
        var color = existing?.color ?: Palette.CHIPS[buttons.size % Palette.CHIPS.size]
        val colorRow = ui.horizontal()
        val swatches = mutableListOf<View>()
        fun refreshSwatches() {
            swatches.forEachIndexed { i, s ->
                val c = Palette.CHIPS[i]
                s.background = ui.oval(c).apply {
                    if (c == color) setStroke(ui.dp(3), Palette.TEXT)
                }
            }
        }
        Palette.CHIPS.forEach { c ->
            val s = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)).apply { rightMargin = ui.dp(10) }
                setOnClickListener { color = c; refreshSwatches() }
            }
            swatches.add(s)
            colorRow.addView(s)
        }
        refreshSwatches()
        box.addView(colorRow)

        fun updateFields() {
            val t = types[typeSpinner.selectedItemPosition]
            val isKey = t == ActionType.KEY
            keySpinner.visibility = if (isKey) View.VISIBLE else View.GONE
            value.visibility = if (isKey) View.GONE else View.VISIBLE
            value.hint = t.hint
        }
        typeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = updateFields()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        updateFields()

        val scroll = ScrollView(this).apply { addView(box) }
        val b = dialog().setTitle(if (existing == null) "Nuevo botón" else "Editar botón")
            .setView(scroll)
            .setPositiveButton("Guardar") { _, _ ->
                val t = types[typeSpinner.selectedItemPosition]
                val v = if (t == ActionType.KEY) KeyCodes.named[keySpinner.selectedItemPosition].second.toString()
                else value.text.toString().trim()
                val label = name.text.toString().trim().ifEmpty {
                    if (t == ActionType.KEY) KeyCodes.named[keySpinner.selectedItemPosition].first else v.take(14)
                }
                if (v.isEmpty()) {
                    toast("Falta el contenido del botón"); return@setPositiveButton
                }
                val nb = CustomButton(label, t, v, color)
                if (existing == null) buttons.add(nb) else buttons[index] = nb
                storage.saveButtons(buttons)
                renderCustomButtons()
            }
            .setNegativeButton("Cancelar", null)
        if (existing != null) {
            b.setNeutralButton("Borrar") { _, _ ->
                buttons.removeAt(index)
                storage.saveButtons(buttons)
                renderCustomButtons()
            }
        }
        b.show()
    }
}
