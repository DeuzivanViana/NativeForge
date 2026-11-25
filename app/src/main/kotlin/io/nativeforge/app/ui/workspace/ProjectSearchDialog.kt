package io.nativeforge.app.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.nativeforge.core.project.search.FileMatches
import io.nativeforge.core.project.search.SearchMatch
import io.nativeforge.core.project.search.SearchQuery
import io.nativeforge.core.ui.component.DialogPrimaryButton
import io.nativeforge.core.ui.component.NfConfirmDialog
import io.nativeforge.core.ui.component.NfDialog
import io.nativeforge.core.ui.component.NfTextField
import java.io.File

/** One row of the result list: a file header or a match inside it. */
private sealed interface ResultRow {
    data class Header(val file: FileMatches) : ResultRow
    data class Hit(val file: File, val match: SearchMatch) : ResultRow
}

/**
 * Ctrl+Shift+F: searches every file of the project, VS Code style. Tapping a result opens
 * the file at the match; "Replace all" changes every match in the listed files.
 */
@Composable
fun ProjectSearchDialog(
    state: ProjectSearchState,
    onSearch: (SearchQuery) -> Unit,
    onReplacementChange: (String) -> Unit,
    onReplaceAll: () -> Unit,
    onOpen: (File, SearchMatch) -> Unit,
    onDismiss: () -> Unit,
) {
    val query = state.query
    var showReplace by rememberSaveable { mutableStateOf(state.replacement.isNotEmpty()) }
    var confirmReplace by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val result = state.result
    val rows = remember(result) {
        result.files.flatMap { file -> listOf<ResultRow>(ResultRow.Header(file)) + file.matches.map { ResultRow.Hit(file.file, it) } }
    }
    val summary = when {
        query.text.isEmpty() -> null
        state.running -> "Searching…"
        result.totalMatches == 0 -> "No results"
        else -> buildString {
            append("${result.totalMatches} result")
            if (result.totalMatches != 1) append("s")
            append(" in ${result.files.size} file")
            if (result.files.size != 1) append("s")
            if (result.truncated) append(" (stopped at the first ${result.totalMatches})")
        }
    }
    val escToClose = Modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) { onDismiss(); true } else false
    }

    NfDialog(
        title = "Search in project",
        subtitle = summary,
        onDismiss = onDismiss,
        maxWidth = 720.dp,
        actions = if (showReplace) {
            {
                DialogPrimaryButton(
                    "Replace all",
                    { confirmReplace = true },
                    enabled = result.totalMatches > 0 && !state.running && state.error == null,
                )
            }
        } else null,
    ) {
        NfTextField(
            value = query.text,
            onValueChange = { onSearch(query.copy(text = it)) },
            placeholder = "Search",
            monospace = true,
            error = state.error,
            imeAction = ImeAction.Search,
            keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
            focusRequester = focus,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp).then(escToClose),
        )
        if (showReplace) {
            NfTextField(
                value = state.replacement,
                onValueChange = onReplacementChange,
                placeholder = if (query.regex) "Replace (\$1 for groups)" else "Replace",
                monospace = true,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp).then(escToClose),
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Option("Match case", query.matchCase) { onSearch(query.copy(matchCase = !query.matchCase)) }
            Option("Whole word", query.wholeWord) { onSearch(query.copy(wholeWord = !query.wholeWord)) }
            Option("Regex", query.regex) { onSearch(query.copy(regex = !query.regex)) }
            Option("Replace", showReplace) { showReplace = !showReplace }
        }
        if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            items(rows, contentType = { if (it is ResultRow.Header) "header" else "hit" }) { row ->
                when (row) {
                    is ResultRow.Header -> FileHeader(row.file)
                    is ResultRow.Hit -> MatchRow(row.match) { onOpen(row.file, row.match) }
                }
            }
        }
    }

    if (confirmReplace) {
        val files = result.files.size
        NfConfirmDialog(
            title = "Replace all",
            message = "Replace ${result.totalMatches} match" + (if (result.totalMatches == 1) "" else "es") +
                " in $files file" + (if (files == 1) "" else "s") + " with \"${state.replacement}\"? " +
                "Files open in the editor can be undone there; other files are saved right away.",
            confirmLabel = "Replace",
            destructive = true,
            onConfirm = { confirmReplace = false; onReplaceAll() },
            onDismiss = { confirmReplace = false },
        )
    }
}

@Composable
private fun Option(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        modifier = Modifier.height(28.dp),
    )
}

@Composable
private fun FileHeader(file: FileMatches) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            file.relativePath,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            file.matches.size.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun MatchRow(match: SearchMatch, onClick: () -> Unit) {
    val highlight = MaterialTheme.colorScheme.primaryContainer
    val onHighlight = MaterialTheme.colorScheme.onPrimaryContainer
    val text = remember(match, highlight) {
        buildAnnotatedString {
            val preview = match.preview
            val start = match.previewStart.coerceIn(0, preview.length)
            val end = match.previewEnd.coerceIn(start, preview.length)
            append(preview.substring(0, start))
            withStyle(SpanStyle(background = highlight, color = onHighlight, fontWeight = FontWeight.SemiBold)) {
                append(preview.substring(start, end))
            }
            append(preview.substring(end))
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 34.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            match.line.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
