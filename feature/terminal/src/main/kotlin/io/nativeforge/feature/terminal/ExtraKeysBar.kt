package io.nativeforge.feature.terminal

import android.view.KeyEvent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

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

@Composable
internal fun ExtraKeysBar(
    ctrlActive: Boolean,
    altActive: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onKey: (ExtraKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = Color.Black, contentColor = Color(0xFFE6E6E6)) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            FilterChip(selected = ctrlActive, onClick = onToggleCtrl, label = { Text("CTRL", fontFamily = FontFamily.Monospace) })
            FilterChip(selected = altActive, onClick = onToggleAlt, label = { Text("ALT", fontFamily = FontFamily.Monospace) })
            DEFAULT_EXTRA_KEYS.forEach { key ->
                TextButton(onClick = { onKey(key) }) { Text(key.label, fontFamily = FontFamily.Monospace, color = Color(0xFFE6E6E6)) }
            }
        }
    }
}
