package com.miller.controltv

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object Palette {
    val BG = Color.parseColor("#0F1115")
    val CARD = Color.parseColor("#181C24")
    val KEY = Color.parseColor("#232935")
    val KEY_STRONG = Color.parseColor("#2E3646")
    val TEXT = Color.parseColor("#EEF1F6")
    val MUTED = Color.parseColor("#8A93A6")
    val ACCENT = Color.parseColor("#4F8CFF")
    val RED = Color.parseColor("#E5484D")
    val GREEN = Color.parseColor("#3DD68C")
    val YELLOW = Color.parseColor("#F5C451")
    val YOUTUBE = Color.parseColor("#FF0033")
    val NETFLIX = Color.parseColor("#B20710")
    val SPOTIFY = Color.parseColor("#1DB954")
    val PRIME = Color.parseColor("#00A8E1")
    val DISNEY = Color.parseColor("#1A3D8F")
    val CHIPS = intArrayOf(
        Color.parseColor("#4F8CFF"), Color.parseColor("#8E4EC6"), Color.parseColor("#12A594"),
        Color.parseColor("#E5484D"), Color.parseColor("#F76B15"), Color.parseColor("#3E63DD")
    )
}

class Ui(private val ctx: Context) {
    private val handler = Handler(Looper.getMainLooper())

    fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
    ).toInt()

    fun rounded(color: Int, radiusDp: Int = 16): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    fun ripple(base: GradientDrawable) =
        RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), base, null)

    fun vertical(paddingDp: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
    }

    fun horizontal(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun text(s: String, sizeSp: Float = 14f, color: Int = Palette.TEXT, bold: Boolean = false) =
        TextView(ctx).apply {
            text = s
            setTextColor(color)
            textSize = sizeSp
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

    fun section(title: String): TextView = text(title.uppercase(), 12f, Palette.MUTED, true).apply {
        letterSpacing = 0.08f
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(4), dp(18), 0, dp(8)) }
    }

    fun card(): LinearLayout = vertical(12).apply {
        background = rounded(Palette.CARD, 22)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    fun input(hint: String): EditText = EditText(ctx).apply {
        this.hint = hint
        setHintTextColor(Palette.MUTED)
        setTextColor(Palette.TEXT)
        textSize = 15f
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT
        background = rounded(Palette.KEY, 14)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    /**
     * A remote key. When [repeat] is true, holding it down keeps sending.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun key(
        label: String,
        color: Int = Palette.KEY,
        textColor: Int = Palette.TEXT,
        sizeSp: Float = 15f,
        heightDp: Int = 52,
        circle: Boolean = false,
        repeat: Boolean = false,
        onLongPress: (() -> Unit)? = null,
        onTap: () -> Unit
    ): TextView {
        val v = TextView(ctx).apply {
            text = label
            setTextColor(textColor)
            textSize = sizeSp
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 2
            background = ripple(if (circle) oval(color) else rounded(color, 16))
            isClickable = true
            isFocusable = true
            minHeight = dp(heightDp)
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        if (repeat) {
            var repeating: Runnable? = null
            v.setOnTouchListener { view, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        view.isPressed = true
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onTap()
                        val r = object : Runnable {
                            override fun run() {
                                onTap()
                                handler.postDelayed(this, 130)
                            }
                        }
                        repeating = r
                        handler.postDelayed(r, 450)
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
                v.setOnLongClickListener { onLongPress(); true }
            }
        }
        return v
    }

    /** Lays out views in rows of [columns] equal-width cells. */
    fun grid(views: List<View>, columns: Int, gapDp: Int = 8): LinearLayout {
        val root = vertical()
        views.chunked(columns).forEachIndexed { rowIndex, rowViews ->
            val row = horizontal()
            row.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { if (rowIndex > 0) topMargin = dp(gapDp) }
            for (i in 0 until columns) {
                val cell: View = rowViews.getOrNull(i) ?: View(ctx)
                cell.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (i > 0) leftMargin = dp(gapDp) }
                row.addView(cell)
            }
            root.addView(row)
        }
        return root
    }

    fun space(heightDp: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }
}
