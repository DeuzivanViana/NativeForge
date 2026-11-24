package io.nativeforge.feature.terminal

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/** A key on the extra keys bar: either literal text or an Android key code translated by Termux. */
sealed interface ExtraKey {
    val label: String

    data class Text(override val label: String, val text: String) : ExtraKey
    data class Code(override val label: String, val keyCode: Int) : ExtraKey
}

internal val DEFAULT_EXTRA_KEYS = listOf(
    ExtraKey.Text("ESC", "\u001b"),
    ExtraKey.Text("TAB", "\t"),
    ExtraKey.Code("←", KeyEvent.KEYCODE_DPAD_LEFT),
    ExtraKey.Code("↓", KeyEvent.KEYCODE_DPAD_DOWN),
    ExtraKey.Code("↑", KeyEvent.KEYCODE_DPAD_UP),
    ExtraKey.Code("→", KeyEvent.KEYCODE_DPAD_RIGHT),
    ExtraKey.Code("HOME", KeyEvent.KEYCODE_MOVE_HOME),
    ExtraKey.Code("END", KeyEvent.KEYCODE_MOVE_END),
    ExtraKey.Code("PGUP", KeyEvent.KEYCODE_PAGE_UP),
    ExtraKey.Code("PGDN", KeyEvent.KEYCODE_PAGE_DOWN),
    ExtraKey.Text("-", "-"),
    ExtraKey.Text("/", "/"),
    ExtraKey.Text("|", "|"),
)

/**
 * Row of extra keys under the terminal (CTRL and ALT are sticky toggles). Plain views, not
 * Compose: the console also ships inside every exported app, which should stay small.
 */
internal class ExtraKeysBar(
    context: Context,
    private val onToggleCtrl: () -> Unit,
    private val onToggleAlt: () -> Unit,
    private val onKey: (ExtraKey) -> Unit,
) : HorizontalScrollView(context) {

    private val ctrl = key("CTRL") { onToggleCtrl() }
    private val alt = key("ALT") { onToggleAlt() }

    init {
        setBackgroundColor(Color.BLACK)
        isHorizontalScrollBarEnabled = false
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ctrl)
            addView(alt)
            DEFAULT_EXTRA_KEYS.forEach { extra -> addView(key(extra.label) { onKey(extra) }) }
        }
        addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun setModifiers(ctrlActive: Boolean, altActive: Boolean) {
        ctrl.isSelected = ctrlActive
        alt.isSelected = altActive
        listOf(ctrl, alt).forEach { it.background = if (it.isSelected) selectedBackground() else ripple() }
    }

    private fun key(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        typeface = Typeface.MONOSPACE
        setTextColor(TEXT_COLOR)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        minWidth = dp(44)
        minHeight = dp(40)
        setPadding(dp(10), 0, dp(10), 0)
        background = ripple()
        isClickable = true
        isFocusable = false
        setOnClickListener { onClick() }
    }

    private fun ripple() = TypedValue().let { value ->
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        context.getDrawable(value.resourceId)
    }

    private fun selectedBackground() = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(SELECTED_COLOR)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val TEXT_COLOR = Color.rgb(0xE6, 0xE6, 0xE6)
        val SELECTED_COLOR = Color.rgb(0x3A, 0x3F, 0x4A)
    }
}
