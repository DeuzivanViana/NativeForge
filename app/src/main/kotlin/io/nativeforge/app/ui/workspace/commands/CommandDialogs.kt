package io.nativeforge.app.ui.workspace.commands

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nativeforge.core.ui.component.DialogPrimaryButton
import io.nativeforge.core.ui.component.DialogSecondaryButton
import io.nativeforge.core.ui.component.NfDialog
import io.nativeforge.core.ui.component.NfTextField

/** One row of a filterable picker. */
data class PickerItem<T>(val value: T, val title: String, val detail: String = "")

/**
 * VS Code-style picker: a filter field and a list. Up/Down move the selection, Enter picks.
 * Matching is fuzzy (the letters of the query, in order).
 */
@Composable
fun <T> PickerDialog(
    title: String,
    placeholder: String,
    items: List<PickerItem<T>>,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableIntStateOf(0) }
    val filtered = remember(query, items) {
        if (query.isBlank()) items else items.mapNotNull { item -> fuzzyScore(query, item.title)?.let { it to item } }
            .sortedByDescending { it.first }.map { it.second }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    NfDialog(title = title, onDismiss = onDismiss, maxWidth = 620.dp) {
        NfTextField(
            value = query,
            onValueChange = { query = it; selected = 0 },
            label = null,
            placeholder = placeholder,
            monospace = true,
            imeAction = ImeAction.Go,
            keyboardActions = KeyboardActions(onGo = { filtered.getOrNull(selected)?.let { onPick(it.value) } }),
            focusRequester = focus,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> { selected = (selected + 1).coerceAtMost(filtered.lastIndex.coerceAtLeast(0)); true }
                    Key.DirectionUp -> { selected = (selected - 1).coerceAtLeast(0); true }
                    Key.Enter, Key.NumPadEnter -> { filtered.getOrNull(selected)?.let { onPick(it.value) }; true }
                    Key.Escape -> { onDismiss(); true }
                    else -> false
                }
            },
        )
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            itemsIndexed(filtered.take(MAX_ROWS)) { index, item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (index == selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { onPick(item.value) }
                        .heightIn(min = 36.dp)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(item.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (item.detail.isNotEmpty()) {
                        Text(item.detail, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Fuzzy match score, or null when [query]'s letters do not appear in order in [text]. */
fun fuzzyScore(query: String, text: String): Int? {
    val q = query.lowercase().filterNot { it.isWhitespace() }
    val t = text.lowercase()
    var score = 0
    var position = 0
    var previous = -2
    for (c in q) {
        val found = t.indexOf(c, position)
        if (found < 0) return null
        score += if (found == previous + 1) 5 else 1
        if (found == 0 || t[found - 1] in "/_-. ") score += 3
        previous = found
        position = found + 1
    }
    return score - t.length / 10
}

/** Ctrl+G: asks for a line number (optionally "line:column"). */
@Composable
fun GoToLineDialog(lineCount: Int, onGo: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val parts = text.split(':').map { it.trim().toIntOrNull() }
    val line = parts.getOrNull(0)
    val go = { if (line != null) onGo(line.coerceIn(1, lineCount.coerceAtLeast(1)), parts.getOrNull(1) ?: 1) }
    NfDialog(
        title = "Go to line",
        onDismiss = onDismiss,
        maxWidth = 380.dp,
        actions = {
            DialogSecondaryButton("Cancel", onDismiss)
            DialogPrimaryButton("Go", onClick = go, enabled = line != null)
        },
    ) {
        NfTextField(
            value = text,
            onValueChange = { text = it },
            label = "Line (1–$lineCount), or line:column",
            placeholder = "42",
            monospace = true,
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Go,
            keyboardActions = KeyboardActions(onGo = { go() }),
            focusRequester = focus,
            modifier = Modifier.padding(14.dp),
        )
    }
}

/** F2: asks for the new name of the symbol under the caret. */
@Composable
fun RenameDialog(current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val valid = Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name) && name != current
    NfDialog(
        title = "Rename symbol",
        subtitle = current.takeIf { it.isNotEmpty() }?.let { "Every use of '$it' in the project" },
        onDismiss = onDismiss,
        maxWidth = 420.dp,
        actions = {
            DialogSecondaryButton("Cancel", onDismiss)
            DialogPrimaryButton("Rename", onClick = { onRename(name) }, enabled = valid)
        },
    ) {
        NfTextField(
            value = name,
            onValueChange = { name = it },
            label = "New name",
            monospace = true,
            imeAction = ImeAction.Done,
            keyboardActions = KeyboardActions(onDone = { if (valid) onRename(name) }),
            focusRequester = focus,
            modifier = Modifier.padding(14.dp),
        )
    }
}

/** Type and documentation of a symbol (clangd hover). */
@Composable
fun HoverDialog(text: String, onDismiss: () -> Unit) {
    NfDialog(title = "Symbol information", onDismiss = onDismiss, maxWidth = 640.dp) {
        SelectionContainer {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(14.dp)) {
                Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

private const val MAX_ROWS = 200
