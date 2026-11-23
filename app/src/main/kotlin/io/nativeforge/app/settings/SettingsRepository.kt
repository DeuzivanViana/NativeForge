package io.nativeforge.app.settings

import android.content.Context
import io.nativeforge.core.codeintel.FormatStyle
import io.nativeforge.core.ui.theme.AppTheme
import io.nativeforge.feature.editor.EditorPreferences
import io.nativeforge.feature.editor.EditorTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything adjustable on the Settings screen. */
data class AppSettings(
    val appTheme: AppTheme = AppTheme.DEFAULT,
    val editor: EditorPreferences = EditorPreferences(),
    /** Editing actions row above the keyboard (undo/redo, arrows, comment...). */
    val showEditorToolbar: Boolean = true,
    /** Symbols and snippets row above the keyboard. */
    val showSymbolRow: Boolean = true,
    val terminalFontSizeSp: Int = 12,
    /** Save open files automatically when leaving the workspace or the app. */
    val autoSave: Boolean = true,
    /** Open the explorer by swiping from the left edge (it always opens with the toolbar button). */
    val explorerSwipe: Boolean = false,
    /** Author written into git commits. */
    val gitName: String = "",
    val gitEmail: String = "",
    /** Completion, live errors, go to definition and rename through clangd. */
    val codeIntelligence: Boolean = true,
    /** Extra clang-tidy checks in the live diagnostics. */
    val clangTidy: Boolean = false,
    /** Style for "Format document" when the project has no .clang-format file. */
    val formatStyle: FormatStyle = FormatStyle.LLVM,
    val formatOnSave: Boolean = false,
    /** Package index URLs added by the user (see packages/README.md for the format). */
    val packageRepositories: List<String> = emptyList(),
) {
    companion object {
        val FONT_SIZES = 10..24
        val TERMINAL_FONT_SIZES = 8..24
        val TAB_WIDTHS = listOf(2, 4, 8)
    }
}

/** Persists [AppSettings] in SharedPreferences and exposes them as a flow. */
class SettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        save(updated)
    }

    /** Picks an app theme and the editor theme that matches it. */
    fun setAppTheme(theme: AppTheme) = update {
        it.copy(appTheme = theme, editor = it.editor.copy(theme = EditorTheme.fromId(theme.editorThemeId)))
    }

    private fun load(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            appTheme = AppTheme.fromName(prefs.getString(KEY_APP_THEME, null)),
            editor = EditorPreferences(
                theme = EditorTheme.fromId(prefs.getString(KEY_EDITOR_THEME, null)),
                fontSizeSp = prefs.getInt(KEY_FONT_SIZE, defaults.editor.fontSizeSp.toInt()).toFloat(),
                wordWrap = prefs.getBoolean(KEY_WORD_WRAP, defaults.editor.wordWrap),
                lineNumbers = prefs.getBoolean(KEY_LINE_NUMBERS, defaults.editor.lineNumbers),
                tabWidth = prefs.getInt(KEY_TAB_WIDTH, defaults.editor.tabWidth),
                highlightCurrentLine = prefs.getBoolean(KEY_HIGHLIGHT_LINE, defaults.editor.highlightCurrentLine),
            ),
            showEditorToolbar = prefs.getBoolean(KEY_EDITOR_TOOLBAR, defaults.showEditorToolbar),
            showSymbolRow = prefs.getBoolean(KEY_SYMBOL_ROW, defaults.showSymbolRow),
            terminalFontSizeSp = prefs.getInt(KEY_TERMINAL_FONT, defaults.terminalFontSizeSp),
            autoSave = prefs.getBoolean(KEY_AUTO_SAVE, defaults.autoSave),
            explorerSwipe = prefs.getBoolean(KEY_EXPLORER_SWIPE, defaults.explorerSwipe),
            gitName = prefs.getString(KEY_GIT_NAME, null).orEmpty(),
            gitEmail = prefs.getString(KEY_GIT_EMAIL, null).orEmpty(),
            codeIntelligence = prefs.getBoolean(KEY_CODE_INTEL, defaults.codeIntelligence),
            clangTidy = prefs.getBoolean(KEY_CLANG_TIDY, defaults.clangTidy),
            formatStyle = FormatStyle.entries.firstOrNull { it.name == prefs.getString(KEY_FORMAT_STYLE, null) } ?: defaults.formatStyle,
            formatOnSave = prefs.getBoolean(KEY_FORMAT_ON_SAVE, defaults.formatOnSave),
            packageRepositories = prefs.getString(KEY_PACKAGE_REPOS, null).orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    private fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_APP_THEME, settings.appTheme.name)
            .putString(KEY_EDITOR_THEME, settings.editor.theme.id)
            .putInt(KEY_FONT_SIZE, settings.editor.fontSizeSp.toInt())
            .putBoolean(KEY_WORD_WRAP, settings.editor.wordWrap)
            .putBoolean(KEY_LINE_NUMBERS, settings.editor.lineNumbers)
            .putInt(KEY_TAB_WIDTH, settings.editor.tabWidth)
            .putBoolean(KEY_HIGHLIGHT_LINE, settings.editor.highlightCurrentLine)
            .putBoolean(KEY_EDITOR_TOOLBAR, settings.showEditorToolbar)
            .putBoolean(KEY_SYMBOL_ROW, settings.showSymbolRow)
            .putInt(KEY_TERMINAL_FONT, settings.terminalFontSizeSp)
            .putBoolean(KEY_AUTO_SAVE, settings.autoSave)
            .putBoolean(KEY_EXPLORER_SWIPE, settings.explorerSwipe)
            .putString(KEY_GIT_NAME, settings.gitName)
            .putString(KEY_GIT_EMAIL, settings.gitEmail)
            .putBoolean(KEY_CODE_INTEL, settings.codeIntelligence)
            .putBoolean(KEY_CLANG_TIDY, settings.clangTidy)
            .putString(KEY_FORMAT_STYLE, settings.formatStyle.name)
            .putBoolean(KEY_FORMAT_ON_SAVE, settings.formatOnSave)
            .putString(KEY_PACKAGE_REPOS, settings.packageRepositories.joinToString("\n"))
            .apply()
    }

    private companion object {
        const val KEY_APP_THEME = "app_theme"
        const val KEY_EDITOR_THEME = "editor_theme"
        const val KEY_FONT_SIZE = "editor_font_size"
        const val KEY_WORD_WRAP = "editor_word_wrap"
        const val KEY_LINE_NUMBERS = "editor_line_numbers"
        const val KEY_TAB_WIDTH = "editor_tab_width"
        const val KEY_HIGHLIGHT_LINE = "editor_highlight_line"
        const val KEY_EDITOR_TOOLBAR = "editor_toolbar"
        const val KEY_SYMBOL_ROW = "editor_symbol_row"
        const val KEY_TERMINAL_FONT = "terminal_font_size"
        const val KEY_AUTO_SAVE = "auto_save"
        const val KEY_EXPLORER_SWIPE = "explorer_swipe"
        const val KEY_GIT_NAME = "git_name"
        const val KEY_GIT_EMAIL = "git_email"
        const val KEY_CODE_INTEL = "code_intelligence"
        const val KEY_CLANG_TIDY = "clang_tidy"
        const val KEY_FORMAT_STYLE = "format_style"
        const val KEY_FORMAT_ON_SAVE = "format_on_save"
        const val KEY_PACKAGE_REPOS = "package_repositories"
    }
}
