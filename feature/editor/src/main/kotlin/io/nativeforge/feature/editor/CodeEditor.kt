package io.nativeforge.feature.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.automirrored.filled.LastPage
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.collectAsState
import io.nativeforge.core.ui.component.ToolbarIconButton
import io.nativeforge.core.ui.theme.CodeTextStyle
import io.github.rosemoe.sora.widget.CodeEditor as SoraCodeEditor

/** Compose wrapper around Sora's code editor with TextMate highlighting. */
@Composable
fun CodeEditor(
    controller: CodeEditorController,
    modifier: Modifier = Modifier,
    preferences: EditorPreferences = EditorPreferences(),
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextMateBootstrap.ensureInitialized(context)
            SoraCodeEditor(context).also { controller.attach(it, preferences) }
        },
        update = { controller.applyPreferences(preferences) },
        onRelease = { it.release() },
    )
    DisposableEffect(controller) {
        onDispose { controller.detach() }
    }
}

/** Symbols that are awkward to type on a phone keyboard. `|` marks where the caret lands. */
private val SYMBOLS = listOf(
    "{" to "{|}", "}" to "}|", "(" to "(|)", ")" to ")|", ";" to ";|", "[" to "[|]", "]" to "]|",
    "<" to "<|>", ">" to ">|", "\"" to "\"|\"", "'" to "'|'", "#" to "#|", "&" to "&|", "*" to "*|",
    "=" to "=|", "+" to "+|", "-" to "-|", "/" to "/|", "\\" to "\\\\|", "|" to "\\||", ":" to ":|",
    "!" to "!|", "%" to "%|", "^" to "^|", "~" to "~|", "," to ",|", "." to ".|", "_" to "_|", "->" to "->|",
    "::" to "::|",
)

/** Common C/C++ snippets. */
private val SNIPPETS = listOf(
    "#include" to "#include <|>",
    "printf" to "printf(\"|\\n\");",
    "cout" to "std::cout << | << std::endl;",
    "if" to "if (|) {\n}",
    "for" to "for (int i = 0; i < |; i++) {\n}",
    "while" to "while (|) {\n}",
    "main" to "int main(void) {\n    |\n    return 0;\n}",
    "struct" to "struct | {\n};",
)

private fun insertTemplate(controller: CodeEditorController, template: String) {
    // "\|" is a literal pipe; a single "|" is the caret marker.
    val literal = template.replace("\\|", "\u0000")
    val caret = literal.indexOf('|').coerceAtLeast(0)
    val text = literal.replace("|", "").replace('\u0000', '|')
    controller.insert(text, caret)
}

/**
 * Two-row keyboard accessory: editing actions (with prominent undo/redo) on
 * top, symbols and snippets below. Both rows scroll horizontally.
 */
@Composable
fun EditorAccessoryBar(
    controller: CodeEditorController,
    onFind: () -> Unit,
    modifier: Modifier = Modifier,
    showActions: Boolean = true,
    showSymbols: Boolean = true,
    codeActions: CodeActions? = null,
) {
    val state by controller.state.collectAsState()
    var showSnippets by rememberSaveable { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (showActions) Row(
                Modifier.fillMaxWidth().height(40.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolbarIconButton(Icons.AutoMirrored.Filled.Undo, "Undo", controller::undo, enabled = state.canUndo, tint = accent, highlighted = state.canUndo)
                ToolbarIconButton(Icons.AutoMirrored.Filled.Redo, "Redo", controller::redo, enabled = state.canRedo, tint = accent, highlighted = state.canRedo)
                BarDivider()
                ToolbarIconButton(Icons.AutoMirrored.Filled.KeyboardTab, "Tab", controller::insertTab)
                ToolbarIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left", { controller.move(CaretMove.LEFT) })
                ToolbarIconButton(Icons.Filled.KeyboardArrowDown, "Down", { controller.move(CaretMove.DOWN) })
                ToolbarIconButton(Icons.Filled.KeyboardArrowUp, "Up", { controller.move(CaretMove.UP) })
                ToolbarIconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right", { controller.move(CaretMove.RIGHT) })
                ToolbarIconButton(Icons.Filled.FirstPage, "Line start", { controller.move(CaretMove.LINE_START) })
                ToolbarIconButton(Icons.AutoMirrored.Filled.LastPage, "Line end", { controller.move(CaretMove.LINE_END) })
                BarDivider()
                TextAction("//", "Toggle comment", controller::toggleComment)
                TextAction("⧉", "Duplicate line", controller::duplicateLine)
                ToolbarIconButton(Icons.Filled.SelectAll, "Select all", controller::selectAll)
                ToolbarIconButton(Icons.Filled.ContentCopy, "Copy", controller::copy)
                ToolbarIconButton(Icons.Filled.ContentCut, "Cut", controller::cut)
                ToolbarIconButton(Icons.Filled.ContentPaste, "Paste", controller::paste)
                BarDivider()
                ToolbarIconButton(Icons.Filled.Search, "Find and replace", onFind)
                if (codeActions != null) {
                    BarDivider()
                    ToolbarIconButton(Icons.Filled.AutoAwesome, "Suggestions (Ctrl+Space)", controller::requestCompletion)
                    ToolbarIconButton(Icons.Filled.MyLocation, "Go to definition (F12)", codeActions.onDefinition)
                    ToolbarIconButton(Icons.Filled.DriveFileRenameOutline, "Rename symbol (F2)", codeActions.onRename)
                    ToolbarIconButton(Icons.AutoMirrored.Filled.FormatIndentIncrease, "Format document (Shift+Alt+F)", codeActions.onFormat)
                }
                ToolbarIconButton(Icons.Filled.LibraryAdd, "Snippets", { showSnippets = !showSnippets }, highlighted = showSnippets)
                ToolbarIconButton(Icons.Filled.KeyboardHide, "Hide keyboard", controller::hideKeyboard)
            }
            if (showSymbols) {
                Row(
                    Modifier.fillMaxWidth().height(36.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val items = if (showSnippets) SNIPPETS else SYMBOLS
                    items.forEach { (label, template) ->
                        KeyCap(label) { insertTemplate(controller, template) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BarDivider() {
    VerticalDivider(Modifier.height(20.dp).padding(horizontal = 2.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun TextAction(label: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 36.dp).widthIn(min = 36.dp).clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
    }
}

@Composable
private fun KeyCap(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxHeight().widthIn(min = 34.dp).clickable(onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Code intelligence buttons for the accessory bar (touch equivalents of F12, F2 and Shift+Alt+F). */
class CodeActions(val onDefinition: () -> Unit, val onRename: () -> Unit, val onFormat: () -> Unit)

/** Compact find/replace bar shown above the editor. */
@Composable
fun FindReplaceBar(
    controller: CodeEditorController,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    startWithReplace: Boolean = false,
) {
    val state by controller.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var showReplace by rememberSaveable(startWithReplace) { mutableStateOf(startWithReplace) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextAction(if (showReplace) "▾" else "▸", "Toggle replace", { showReplace = !showReplace })
                InlineField(
                    value = query,
                    onValueChange = { query = it; controller.search(it) },
                    placeholder = "Find",
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    onDone = controller::findNext,
                )
                val counter = when {
                    query.isEmpty() -> ""
                    state.searchMatches == 0 -> "0"
                    else -> "${state.searchIndex + 1}/${state.searchMatches}"
                }
                Text(counter, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp))
                ToolbarIconButton(Icons.Filled.KeyboardArrowUp, "Previous match", controller::findPrevious, enabled = state.searchMatches > 0)
                ToolbarIconButton(Icons.Filled.KeyboardArrowDown, "Next match", controller::findNext, enabled = state.searchMatches > 0)
                ToolbarIconButton(Icons.Filled.Close, "Close", { controller.stopSearch(); onClose() })
            }
            if (showReplace) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(36.dp))
                    InlineField(replacement, { replacement = it }, "Replace", Modifier.weight(1f), onDone = { controller.replaceCurrent(replacement) })
                    TextAction("1", "Replace", { controller.replaceCurrent(replacement) })
                    TextAction("All", "Replace all", { controller.replaceAll(replacement) })
                }
            }
        }
    }
}

@Composable
private fun InlineField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onDone: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = CodeTextStyle.copy(color = colors.onSurface, fontSize = 13.sp),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onDone() }),
        modifier = modifier.height(30.dp),
        decorationBox = { inner ->
            Surface(color = colors.surfaceContainerLowest, shape = MaterialTheme.shapes.extraSmall) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = CodeTextStyle, color = LocalContentColor.current.copy(alpha = 0.5f))
                    }
                    inner()
                }
            }
        },
    )
}
