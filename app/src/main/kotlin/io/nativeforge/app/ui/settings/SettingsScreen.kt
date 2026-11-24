package io.nativeforge.app.ui.settings

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Surface
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import io.nativeforge.core.ui.component.FieldLabel
import io.nativeforge.core.ui.component.FormSection
import io.nativeforge.core.ui.component.NfTextField
import io.nativeforge.core.ui.component.SegmentedChoice
import io.nativeforge.core.ui.component.SwitchRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.nativeforge.app.di.AppContainer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.nativeforge.app.BuildConfig
import io.nativeforge.app.di.appContainer
import io.nativeforge.app.settings.AppSettings
import io.nativeforge.core.ui.component.CompactTopBar
import io.nativeforge.core.ui.component.ToolbarIconButton
import io.nativeforge.core.ui.theme.AppTheme
import io.nativeforge.feature.editor.EditorTheme
import io.nativeforge.feature.editor.EditorThemeColors
import io.nativeforge.feature.editor.EditorThemePreviews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val container = appContainer()
    val repository = container.settings
    val settings by repository.settings.collectAsStateWithLifecycle()
    val update: ((AppSettings) -> AppSettings) -> Unit = repository::update

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CompactTopBar(
            title = "Settings",
            subtitle = "Appearance, editor, terminal, git and packages",
            navigation = { ToolbarIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) },
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FormSection("Appearance") {
                FieldLabel("App theme")
                AppThemeGrid(selected = settings.appTheme, onSelect = repository::setAppTheme)
                Hint("Choosing an app theme also picks the matching editor theme.")
                FieldLabel("Editor theme")
                EditorThemeList(selected = settings.editor.theme) { theme ->
                    update { it.copy(editor = it.editor.copy(theme = theme)) }
                }
            }

            FormSection("Editor") {
                StepperRow("Font size", settings.editor.fontSizeSp.toInt(), AppSettings.FONT_SIZES) { size ->
                    update { it.copy(editor = it.editor.copy(fontSizeSp = size.toFloat())) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tab width", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    SegmentedChoice(
                        options = AppSettings.TAB_WIDTHS,
                        selected = settings.editor.tabWidth,
                        onSelect = { width -> update { it.copy(editor = it.editor.copy(tabWidth = width)) } },
                        label = { "$it spaces" },
                        modifier = Modifier.width(240.dp),
                    )
                }
                SwitchRow("Word wrap", settings.editor.wordWrap, { on -> update { it.copy(editor = it.editor.copy(wordWrap = on)) } },
                    subtitle = "Wrap long lines instead of scrolling sideways")
                SwitchRow("Line numbers", settings.editor.lineNumbers, { on -> update { it.copy(editor = it.editor.copy(lineNumbers = on)) } })
                SwitchRow("Highlight current line", settings.editor.highlightCurrentLine,
                    { on -> update { it.copy(editor = it.editor.copy(highlightCurrentLine = on)) } })
                SwitchRow("Editing toolbar", settings.showEditorToolbar, { on -> update { it.copy(showEditorToolbar = on) } },
                    subtitle = "Undo, redo, arrows, comment, copy/paste, find")
                SwitchRow("Symbol row", settings.showSymbolRow, { on -> update { it.copy(showSymbolRow = on) } },
                    subtitle = "{ } ( ) ; and snippets above the keyboard")
            }

            FormSection("Code intelligence") {
                SwitchRow("Completion and live errors", settings.codeIntelligence, { on -> update { it.copy(codeIntelligence = on) } },
                    subtitle = "clangd: suggestions while typing, red/yellow underlines, go to definition (F12) and rename (F2)")
                SwitchRow("clang-tidy checks", settings.clangTidy, { on -> update { it.copy(clangTidy = on) } },
                    subtitle = "Extra warnings about likely bugs and slow code (uses more CPU)")
                FieldLabel("Format style (when the project has no .clang-format)")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    io.nativeforge.core.codeintel.FormatStyle.entries.forEach { style ->
                        androidx.compose.material3.FilterChip(
                            selected = style == settings.formatStyle,
                            onClick = { update { it.copy(formatStyle = style) } },
                            label = { Text(style.clangName, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
                SwitchRow("Format on save", settings.formatOnSave, { on -> update { it.copy(formatOnSave = on) } },
                    subtitle = "Run clang-format on C/C++ files when saving (Shift+Alt+F formats any time)")
            }

            FormSection("Workspace") {
                SwitchRow("Auto save", settings.autoSave, { on -> update { it.copy(autoSave = on) } },
                    subtitle = "Save files when leaving a project or the app")
                SwitchRow("Swipe to open the explorer", settings.explorerSwipe, { on -> update { it.copy(explorerSwipe = on) } },
                    subtitle = "Off: only the folder button opens it, so scrolling code never pulls it out")
            }

            FormSection("Terminal") {
                StepperRow("Font size", settings.terminalFontSizeSp, AppSettings.TERMINAL_FONT_SIZES) { size ->
                    update { it.copy(terminalFontSizeSp = size) }
                }
                Hint("The terminal always uses a black background. Pinch to zoom while it is open.")
            }

            FormSection("Git") {
                NfTextField(settings.gitName, { value -> update { it.copy(gitName = value) } }, label = "Author name", placeholder = "Your Name")
                NfTextField(
                    settings.gitEmail,
                    { value -> update { it.copy(gitEmail = value) } },
                    label = "Author e-mail",
                    placeholder = "you@example.com",
                    supportingText = "Written into commits you make in the Changes tab of the explorer.",
                    keyboardType = KeyboardType.Email,
                )
            }

            FormSection("Package repositories") {
                PackageRepositories(settings.packageRepositories) { urls ->
                    update { it.copy(packageRepositories = urls) }
                    container.applyPackageRepositories(urls)
                }
            }

            FormSection("About") {
                InfoRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                InfoRow("Device ABI", container.abi.androidName)
                InfoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                InfoRow("Toolchain", if (container.toolchain.missingComponents().isEmpty()) "Installed" else "Not installed")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** App themes as a wrapping grid, dark ones first, so every theme is visible without sideways scrolling. */
@Composable
private fun AppThemeGrid(selected: AppTheme, onSelect: (AppTheme) -> Unit) {
    val (dark, light) = remember { AppTheme.entries.partition { it.palette.isDark } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Dark" to dark, "Light" to light).forEach { (group, themes) ->
            Hint(group)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                themes.forEach { theme -> AppThemeCard(theme, selected = theme == selected, onClick = { onSelect(theme) }) }
            }
        }
    }
}

@Composable
private fun AppThemeCard(theme: AppTheme, selected: Boolean, onClick: () -> Unit) {
    val palette = theme.palette
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        Modifier
            .width(96.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(if (selected) 2.dp else 1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        // Miniature window: title bar, side bar and editor area.
        Column(Modifier.fillMaxWidth().height(56.dp).background(palette.background)) {
            Box(Modifier.fillMaxWidth().height(10.dp).background(palette.panel))
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(18.dp).fillMaxSize().background(palette.panel))
                Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.width(40.dp).height(4.dp).background(palette.accent, CircleShape))
                    Box(Modifier.width(28.dp).height(4.dp).background(palette.secondary, CircleShape))
                    Box(Modifier.width(48.dp).height(4.dp).background(palette.textMuted, CircleShape))
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(theme.displayName, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun EditorThemeList(selected: EditorTheme, onSelect: (EditorTheme) -> Unit) {
    // Collapsed to the current theme by default: the full list is long.
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        if (expanded) {
            val (dark, light) = remember { EditorTheme.entries.partition { it.isDark } }
            listOf("Dark" to dark, "Light" to light).forEach { (group, themes) ->
                Hint(group)
                themes.forEach { theme -> EditorThemeRow(theme, theme == selected, onClick = { onSelect(theme) }) }
            }
        } else {
            EditorThemeRow(selected, selected = true, onClick = { expanded = true })
        }
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Show fewer" else "Show all ${EditorTheme.entries.size} editor themes")
        }
    }
}

@Composable
private fun EditorThemeRow(theme: EditorTheme, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    // Theme files are 10-70 KB of JSON each: parse them off the main thread so expanding the list doesn't stutter.
    val colors by produceState(EditorThemePreviews.cached(theme), theme) {
        if (value == null) value = withContext(Dispatchers.IO) { EditorThemePreviews.colors(context, theme) }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .heightIn(min = 36.dp)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Code sample rendered with the theme's own colors.
        val preview = colors
        Row(
            Modifier
                .width(132.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(preview?.let { Color(it.background) } ?: MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            val style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            // Transparent text until the colors load keeps the row's height stable.
            fun color(argb: (EditorThemeColors) -> Int) = preview?.let { Color(argb(it)) } ?: Color.Transparent
            Text("int ", style = style, color = color { it.keyword })
            Text("x = ", style = style, color = color { it.foreground })
            Text("\"hi\"", style = style, color = color { it.string })
            Text(" //", style = style, color = color { it.comment })
        }
        Spacer(Modifier.width(12.dp))
        Text(theme.displayName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

/** Label with − value + buttons; denser than a slider and exact. */
@Composable
private fun StepperRow(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolbarIconButton(Icons.Filled.Remove, "Smaller", { onChange((value - 1).coerceIn(range)) }, enabled = value > range.first)
                Text("$value sp", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(52.dp), textAlign = TextAlign.Center)
                ToolbarIconButton(Icons.Filled.Add, "Larger", { onChange((value + 1).coerceIn(range)) }, enabled = value < range.last)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Extra package indexes (index.json over HTTPS), searched after the bundled libraries. */
@Composable
private fun PackageRepositories(urls: List<String>, onChange: (List<String>) -> Unit) {
    var draft by remember { mutableStateOf("") }
    val trimmed = draft.trim()
    val error = when {
        trimmed.isEmpty() -> null
        !AppContainer.isRepositoryUrl(trimmed) -> "Use an https:// address of an index.json"
        trimmed in urls -> "Already added"
        else -> null
    }
    Hint("Packages from these indexes are prebuilt binaries that neither NativeForge nor F-Droid has checked. Add only indexes you trust.")
    urls.forEach { url ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                url,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ToolbarIconButton(Icons.Filled.Remove, "Remove $url", onClick = { onChange(urls - url) })
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NfTextField(
            draft,
            { draft = it },
            modifier = Modifier.weight(1f),
            placeholder = "https://example.org/packages/index.json",
            error = error,
            monospace = true,
            keyboardType = KeyboardType.Uri,
        )
        ToolbarIconButton(Icons.Filled.Add, "Add repository", onClick = {
            if (trimmed.isNotEmpty() && error == null) { onChange(urls + trimmed); draft = "" }
        })
    }
}
