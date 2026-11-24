package io.nativeforge.feature.editor

import android.content.Context
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import org.eclipse.tm4e.core.registry.IThemeSource

/** Editor color themes bundled in `assets/textmate/themes` (VS Code theme format). */
enum class EditorTheme(val id: String, val displayName: String, val isDark: Boolean) {
    BLACK("nativeforge-black", "NativeForge Black", true),
    ONE_DARK("one-dark-pro", "One Dark Pro", true),
    DRACULA("dracula", "Dracula", true),
    MONOKAI("monokai", "Monokai", true),
    NORD("nord", "Nord", true),
    TOKYO_NIGHT("tokyo-night", "Tokyo Night", true),
    CATPPUCCIN_MOCHA("catppuccin-mocha", "Catppuccin Mocha", true),
    GRUVBOX_DARK("gruvbox-dark-medium", "Gruvbox Dark", true),
    GITHUB_DARK("github-dark-default", "GitHub Dark", true),
    SOLARIZED_DARK("solarized-dark", "Solarized Dark", true),
    AYU_DARK("ayu-dark", "Ayu Dark", true),
    NIGHT_OWL("night-owl", "Night Owl", true),
    DARK_PLUS("dark-plus", "Dark+ (VS Code)", true),
    GITHUB_DIMMED("github-dark-dimmed", "GitHub Dark Dimmed", true),
    GRUVBOX_DARK_HARD("gruvbox-dark-hard", "Gruvbox Dark Hard", true),
    CATPPUCCIN_MACCHIATO("catppuccin-macchiato", "Catppuccin Macchiato", true),
    CATPPUCCIN_FRAPPE("catppuccin-frappe", "Catppuccin Frappé", true),
    ROSE_PINE("rose-pine", "Rosé Pine", true),
    ROSE_PINE_MOON("rose-pine-moon", "Rosé Pine Moon", true),
    KANAGAWA_WAVE("kanagawa-wave", "Kanagawa Wave", true),
    KANAGAWA_DRAGON("kanagawa-dragon", "Kanagawa Dragon", true),
    EVERFOREST_DARK("everforest-dark", "Everforest Dark", true),
    PALENIGHT("material-theme-palenight", "Material Palenight", true),
    AYU_MIRAGE("ayu-mirage", "Ayu Mirage", true),
    HORIZON("horizon", "Horizon", true),
    POIMANDRES("poimandres", "Poimandres", true),
    SYNTHWAVE_84("synthwave-84", "Synthwave '84", true),
    VITESSE_DARK("vitesse-dark", "Vitesse Dark", true),
    VITESSE_BLACK("vitesse-black", "Vitesse Black", true),
    VESPER("vesper", "Vesper", true),
    GITHUB_LIGHT("github-light", "GitHub Light", false),
    ONE_LIGHT("one-light", "One Light", false),
    SOLARIZED_LIGHT("solarized-light", "Solarized Light", false);

    val assetPath: String get() = "textmate/themes/$id.json"

    companion object {
        val DEFAULT = BLACK

        fun fromId(id: String?): EditorTheme = entries.firstOrNull { it.id == id || it.name == id } ?: DEFAULT
    }
}

/** One-time registration of grammars with Sora's TextMate registries; themes load on first use. */
object TextMateBootstrap {
    @Volatile private var initialized = false
    private val loadedThemes = mutableSetOf<EditorTheme>()

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(context.applicationContext.assets))
            GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
            initialized = true
        }
    }

    fun applyTheme(theme: EditorTheme) {
        synchronized(this) {
            if (theme !in loadedThemes) {
                val stream = FileProviderRegistry.getInstance().tryGetInputStream(theme.assetPath)
                    ?: error("Missing editor theme ${theme.assetPath}")
                ThemeRegistry.getInstance().loadTheme(
                    ThemeModel(IThemeSource.fromInputStream(stream, theme.assetPath, null), theme.id).apply {
                        isDark = theme.isDark
                    },
                )
                loadedThemes += theme
            }
        }
        ThemeRegistry.getInstance().setTheme(theme.id)
    }
}
