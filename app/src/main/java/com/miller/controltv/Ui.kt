package com.miller.controltv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

data class Theme(
    val name: String,
    val bg: String, val surface: String, val surface2: String, val surface3: String, val stroke: String,
    val text: String, val muted: String, val accent: String, val accent2: String,
    val light: Boolean = false
)

object Themes {
    val all = listOf(
        Theme("Medianoche", "#0B0D12", "#14171F", "#1C2029", "#262B36", "#2A2F3A", "#F2F4F8", "#8B93A5", "#7C8CFF", "#B07CFF"),
        Theme("AMOLED", "#000000", "#0C0C0C", "#161616", "#222222", "#1E1E1E", "#FFFFFF", "#8A8A8A", "#00E5FF", "#2979FF"),
        Theme("Océano", "#06141B", "#0C2230", "#123042", "#1A4057", "#1A3A4E", "#E6F6FF", "#7FA7BD", "#00B4F0", "#00D6A8"),
        Theme("Atardecer", "#1A0E14", "#26141D", "#321A26", "#422233", "#3A2030", "#FFF1EC", "#C79AA6", "#FF7A59", "#FF3D7F"),
        Theme("Bosque", "#0C1410", "#13201A", "#1A2B22", "#24392D", "#22352A", "#EAF5EE", "#8FB09C", "#2FBF71", "#8BC34A"),
        Theme("Neón", "#0A0618", "#140D2B", "#1D1340", "#2A1B59", "#2A1F4F", "#F5F0FF", "#9A8CC4", "#FF2BD6", "#00D9FF"),
        Theme("Cine Rojo", "#0F0F0F", "#1A1A1A", "#242424", "#2F2F2F", "#2C2C2C", "#F5F5F5", "#9A9A9A", "#E50914", "#FF6A3D"),
        Theme("Mocha", "#17120F", "#221B16", "#2D241D", "#3A2F26", "#352B23", "#F6EDE4", "#B3A193", "#C8894F", "#E0B354"),
        Theme("Claro", "#F3F4F8", "#FFFFFF", "#ECEEF4", "#E0E3EC", "#DADEE8", "#12141A", "#6B7280", "#4F6BFF", "#8B5CF6", true),
        Theme("Sakura", "#FFF4F6", "#FFFFFF", "#FCE7ED", "#F7D3DE", "#F1CFD9", "#2A1A20", "#8A6A75", "#F0507F", "#FF8A5B", true),
    )
}

object Palette {
    var BG = Color.parseColor("#0B0D12")
    var SURFACE = Color.parseColor("#14171F")
    var SURFACE2 = Color.parseColor("#1C2029")
    var SURFACE3 = Color.parseColor("#262B36")
    var STROKE = Color.parseColor("#2A2F3A")
    var TEXT = Color.parseColor("#F2F4F8")
    var MUTED = Color.parseColor("#8B93A5")
    var ACCENT = Color.parseColor("#7C8CFF")
    var ACCENT2 = Color.parseColor("#B07CFF")
    var LIGHT = false
    val RED = Color.parseColor("#FF5C67")
    val GREEN = Color.parseColor("#2FBF71")
    val YELLOW = Color.parseColor("#E9A800")
    val BLUE = Color.parseColor("#3E8BFF")
    val CHIPS = intArrayOf(
        Color.parseColor("#7C8CFF"), Color.parseColor("#B07CFF"), Color.parseColor("#12A594"),
        Color.parseColor("#FF5C67"), Color.parseColor("#F76B15"), Color.parseColor("#3E63DD"),
        Color.parseColor("#E5A00D"), Color.parseColor("#1DB954")
    )

    fun apply(t: Theme) {
        BG = Color.parseColor(t.bg)
        SURFACE = Color.parseColor(t.surface)
        SURFACE2 = Color.parseColor(t.surface2)
        SURFACE3 = Color.parseColor(t.surface3)
        STROKE = Color.parseColor(t.stroke)
        TEXT = Color.parseColor(t.text)
        MUTED = Color.parseColor(t.muted)
        ACCENT = Color.parseColor(t.accent)
        ACCENT2 = Color.parseColor(t.accent2)
        LIGHT = t.light
    }

    /** Readable text colour on top of [bg]. */
    fun on(bg: Int): Int {
        val l = (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)) / 255.0
        return if (l > 0.62) Color.parseColor("#14161C") else Color.parseColor("#F7F8FB")
    }
}

class Ui(private val act: Activity) {
    private val handler = Handler(Looper.getMainLooper())
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), act.resources.displayMetrics
    ).toInt()

    fun rounded(color: Int, radiusDp: Int = 16, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = r(radiusDp)
            if (stroke != null) setStroke(dp(1), stroke)
        }

    fun gradient(a: Int, b: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(a, b)).apply {
            cornerRadius = r(radiusDp)
        }

    fun oval(color: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    fun ripple(base: GradientDrawable) =
        RippleDrawable(ColorStateList.valueOf(if (Palette.LIGHT) Color.argb(40, 0, 0, 0) else Color.argb(50, 255, 255, 255)), base, null)

    fun vertical(paddingDp: Int = 0): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
    }

    fun horizontal(): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** Font family of the current design (e.g. monospace for Retro). */
    var family: String = "sans-serif"

    fun text(s: String, sizeSp: Float = 14f, color: Int = Palette.TEXT, bold: Boolean = false) =
        TextView(act).apply {
            text = s
            setTextColor(color)
            textSize = sizeSp
            typeface = Typeface.create(if (bold && family == "sans-serif") "sans-serif-medium" else family,
                if (bold && family != "sans-serif") Typeface.BOLD else Typeface.NORMAL)
        }

    /** Corner radius multiplier of the current design (0 = square keys, 1 = normal). */
    var roundness: Float = 1f

    private fun r(radiusDp: Int): Float = dp(radiusDp) * roundness

    fun title(s: String): TextView = text(s, 13f, Palette.MUTED, true).apply {
        letterSpacing = 0.04f
        setPadding(dp(4), dp(18), dp(4), dp(10))
    }

    fun icon(res: Int, tint: Int = Palette.TEXT, sizeDp: Int = 24): ImageView = ImageView(act).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), Gravity.CENTER)
    }

    fun input(hint: String): EditText = EditText(act).apply {
        this.hint = hint
        setHintTextColor(Palette.MUTED)
        setTextColor(Palette.TEXT)
        textSize = 15f
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT
        background = rounded(Palette.SURFACE2, 16, Palette.STROKE)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    /** Attaches tap / hold-to-repeat / long-press behaviour with haptics. */
    @SuppressLint("ClickableViewAccessibility")
    fun press(v: View, repeat: Boolean, onLongPress: (() -> Unit)?, onTap: () -> Unit) {
        v.isClickable = true
        v.isFocusable = true
        if (repeat) {
            var repeating: Runnable? = null
            v.setOnTouchListener { view, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.isPressed = true
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onTap()
                        val r = object : Runnable {
                            override fun run() {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                onTap()
                                handler.postDelayed(this, 120)
                            }
                        }
                        repeating = r
                        handler.postDelayed(r, 420)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.isPressed = false
                        repeating?.let { handler.removeCallbacks(it) }
                        repeating = null
                    }
                }
                true
            }
        } else {
            v.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onTap()
            }
            if (onLongPress != null) {
                v.setOnLongClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onLongPress(); true
                }
            }
        }
    }

    /** Round (or rounded-square) button with an icon. */
    fun iconButton(
        res: Int,
        sizeDp: Int = 56,
        bg: Int = Palette.SURFACE2,
        tint: Int = Palette.on(bg),
        iconDp: Int = 24,
        circle: Boolean = true,
        repeat: Boolean = false,
        rotation: Float = 0f,
        onLongPress: (() -> Unit)? = null,
        onTap: () -> Unit
    ): FrameLayout {
        val f = FrameLayout(act)
        f.background = ripple(if (circle) oval(bg) else rounded(bg, 18))
        f.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        val i = icon(res, tint, iconDp)
        i.rotation = rotation
        f.addView(i)
        press(f, repeat, onLongPress, onTap)
        return f
    }

    /** Text button (pill or rounded rect). */
    fun button(
        label: String,
        bg: Int = Palette.SURFACE2,
        textColor: Int = Palette.on(bg),
        heightDp: Int = 50,
        sizeSp: Float = 14f,
        iconRes: Int? = null,
        repeat: Boolean = false,
        onLongPress: (() -> Unit)? = null,
        onTap: () -> Unit
    ): LinearLayout {
        val b = horizontal()
        b.gravity = Gravity.CENTER
        b.minimumHeight = dp(heightDp)
        b.setPadding(dp(12), dp(6), dp(12), dp(6))
        b.background = ripple(rounded(bg, 16))
        if (iconRes != null) {
            val i = icon(iconRes, textColor, 18)
            i.layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(8) }
            b.addView(i)
        }
        b.addView(text(label, sizeSp, textColor, true).apply { maxLines = 2; gravity = Gravity.CENTER })
        press(b, repeat, onLongPress, onTap)
        return b
    }

    /** App tile: coloured square with the initial and the name under it. */
    fun appTile(name: String, color: Int, onLongPress: (() -> Unit)?, onTap: () -> Unit): LinearLayout {
        val col = vertical()
        col.gravity = Gravity.CENTER_HORIZONTAL
        val sq = FrameLayout(act)
        sq.background = ripple(gradient(color, darken(color), 22))
        sq.layoutParams = LinearLayout.LayoutParams(dp(68), dp(68))
        sq.addView(text(initials(name), 22f, Color.WHITE, true).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            )
        })
        press(sq, false, onLongPress, onTap)
        col.addView(sq)
        col.addView(text(name, 12f, Palette.TEXT).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(0, dp(6), 0, 0)
        })
        return col
    }

    fun addTile(label: String, onTap: () -> Unit): LinearLayout {
        val col = vertical()
        col.gravity = Gravity.CENTER_HORIZONTAL
        val sq = FrameLayout(act)
        val base = rounded(Palette.SURFACE, 22)
        base.setStroke(dp(1), Palette.STROKE)
        sq.background = ripple(base)
        sq.layoutParams = LinearLayout.LayoutParams(dp(68), dp(68))
        sq.addView(icon(R.drawable.ic_add, Palette.MUTED, 28))
        press(sq, false, null, onTap)
        col.addView(sq)
        col.addView(text(label, 12f, Palette.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        return col
    }

    fun chip(label: String, color: Int = Palette.SURFACE2, textColor: Int = Palette.on(color),
             onLongPress: (() -> Unit)? = null, onTap: () -> Unit): TextView =
        text(label, 13f, textColor, true).apply {
            background = ripple(rounded(color, 40))
            setPadding(dp(14), dp(9), dp(14), dp(9))
            press(this, false, onLongPress, onTap)
        }

    fun darken(c: Int, f: Float = 0.72f): Int =
        Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())

    private fun initials(name: String): String {
        val parts = name.trim().split(" ", "-", ".").filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts[0].take(2).replaceFirstChar { it.uppercase() }
            else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
        }
    }

    /** Equal-width rows of [columns] cells. */
    fun grid(views: List<View>, columns: Int, gapDp: Int = 10, center: Boolean = false): LinearLayout {
        val root = vertical()
        views.chunked(columns).forEachIndexed { rowIndex, rowViews ->
            val row = horizontal()
            row.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { if (rowIndex > 0) topMargin = dp(gapDp) }
            for (i in 0 until columns) {
                val cell: View = rowViews.getOrNull(i) ?: View(act)
                if (center) {
                    val holder = LinearLayout(act).apply { gravity = Gravity.CENTER_HORIZONTAL }
                    holder.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { if (i > 0) leftMargin = dp(gapDp) }
                    holder.addView(cell)
                    row.addView(holder)
                } else {
                    cell.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { if (i > 0) leftMargin = dp(gapDp) }
                    row.addView(cell)
                }
            }
            root.addView(row)
        }
        return root
    }

    /** Wrapping row of chips. */
    fun flow(views: List<View>): LinearLayout {
        // Simple flow: rows of up to 3 chips, left aligned.
        val root = vertical()
        views.chunked(3).forEachIndexed { idx, rowViews ->
            val row = horizontal()
            row.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { if (idx > 0) topMargin = dp(8) }
            rowViews.forEachIndexed { i, v ->
                v.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (i > 0) leftMargin = dp(8) }
                row.addView(v)
            }
            root.addView(row)
        }
        return root
    }

    fun space(heightDp: Int): View = View(act).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    fun matchWidth(v: View): View {
        v.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return v
    }

    /** Bottom sheet that slides up from the bottom of the screen. */
    fun sheet(titleText: String, subtitle: String? = null, build: (LinearLayout, Dialog) -> Unit): Dialog {
        val d = Dialog(act)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = vertical()
        box.setPadding(dp(20), dp(10), dp(20), dp(24))
        box.background = GradientDrawable().apply {
            setColor(Palette.SURFACE)
            val r = dp(28).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        // grabber
        val grab = View(act).apply {
            background = rounded(Palette.SURFACE3, 4)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            }
        }
        box.addView(grab)
        box.addView(text(titleText, 20f, Palette.TEXT, true))
        if (subtitle != null) {
            box.addView(text(subtitle, 13f, Palette.MUTED).apply { setPadding(0, dp(4), 0, 0) })
        }
        box.addView(space(16))
        build(box, d)
        val scroll = ScrollView(act).apply {
            isFillViewport = false
            addView(box)
        }
        d.setContentView(scroll)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setGravity(Gravity.BOTTOM)
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setWindowAnimations(android.R.style.Animation_InputMethod)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.55f)
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        d.show()
        return d
    }
}
