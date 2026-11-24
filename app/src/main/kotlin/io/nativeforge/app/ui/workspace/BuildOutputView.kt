package io.nativeforge.app.ui.workspace

import android.graphics.Typeface
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import io.nativeforge.core.ui.theme.CodeTextStyle

/**
 * Selectable build output. Uses a platform [TextView] in a [ScrollView] instead of a Compose
 * `SelectionContainer` around a `LazyColumn`: lazy items scrolled off screen are disposed while
 * they are still part of the selection, and dragging a multi-line selection while scrolling
 * crashed the app. One TextView keeps the whole text, so the selection can span any number of
 * lines, survives scrolling and keeps working while the build appends output.
 */
@Composable
fun BuildOutputView(
    log: List<LogLine>,
    textColor: Color,
    errorColor: Color,
    selectionColor: Color,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val text = TextView(context).apply {
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, CodeTextStyle.fontSize.value)
                setTextIsSelectable(true)
                setText("", TextView.BufferType.EDITABLE)
            }
            ScrollView(context).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = true
                addView(text, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                val state = OutputState(text)
                tag = state
                // The new height is only known after layout, so scroll to the end from there.
                text.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> state.scrollIfPending(this) }
            }
        },
        update = { scroll ->
            (scroll.tag as OutputState).render(scroll, log, textColor.toArgb(), errorColor.toArgb(), selectionColor.toArgb())
        },
    )
}

private class OutputState(val text: TextView) {
    private var shown: List<LogLine> = emptyList()
    private var textColor = 0
    private var errorColor = 0
    private var scrollPending = false

    fun scrollIfPending(scroll: ScrollView) {
        if (!scrollPending) return
        scrollPending = false
        scroll.scrollTo(0, text.height)
    }

    fun render(scroll: ScrollView, log: List<LogLine>, textColor: Int, errorColor: Int, selectionColor: Int) {
        text.setTextColor(textColor)
        text.highlightColor = selectionColor
        val colorsChanged = errorColor != this.errorColor || textColor != this.textColor
        this.textColor = textColor
        this.errorColor = errorColor

        val atBottom = !scroll.canScrollVertically(1)
        val update = if (colorsChanged) LogUpdate.Replace else planLogUpdate(shown, log)
        val editable = text.editableText
        when {
            update == LogUpdate.None -> return
            update is LogUpdate.Incremental && editable != null -> {
                // Editing in place keeps the selection spans, so a selection being dragged survives.
                if (update.dropLines > 0) editable.delete(0, charsOfFirstLines(shown, update.dropLines))
                appendLines(editable, update.appended, prependNewline = true)
            }
            else -> {
                val builder = SpannableStringBuilder()
                appendLines(builder, log, prependNewline = false)
                text.setText(builder, TextView.BufferType.EDITABLE)
            }
        }
        shown = log
        // Follow the output like a terminal, unless the user scrolled up or is selecting text.
        if ((atBottom || update == LogUpdate.Replace) && !text.hasSelection()) {
            scrollPending = true
            scroll.post { scrollIfPending(scroll) }
        }
    }

    private fun appendLines(out: Editable, lines: List<LogLine>, prependNewline: Boolean) {
        lines.forEachIndexed { index, line ->
            if (index > 0 || prependNewline) out.append('\n')
            val start = out.length
            out.append(line.text)
            if (line.isError && line.text.isNotEmpty()) {
                out.setSpan(ForegroundColorSpan(errorColor), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
