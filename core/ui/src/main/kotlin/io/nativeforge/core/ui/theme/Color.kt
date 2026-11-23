package io.nativeforge.core.ui.theme

import androidx.compose.ui.graphics.Color

/** Semantic colors for diagnostics, used by build output and the problems list. */
data class DiagnosticColors(val error: Color, val warning: Color, val note: Color, val success: Color)

/**
 * Colors of one app theme. Surfaces go from [background] (darkest in dark themes)
 * through [panel] and [raised] to [overlay], like VS Code's editor, side bar,
 * widget and menu colors.
 */
data class AppPalette(
    val isDark: Boolean,
    val background: Color,
    val panel: Color,
    val raised: Color,
    val overlay: Color,
    val border: Color,
    val text: Color,
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val secondary: Color,
    val tertiary: Color,
    val error: Color,
    val warning: Color,
    val success: Color,
) {
    val diagnostics: DiagnosticColors get() = DiagnosticColors(error, warning, accent, success)
}

/**
 * App themes selectable in Settings. [editorThemeId] is the editor theme that
 * matches it, applied when the user picks this theme (and changeable afterwards).
 */
enum class AppTheme(val displayName: String, val editorThemeId: String, val palette: AppPalette) {
    BLACK(
        "Black", "nativeforge-black",
        AppPalette(
            isDark = true,
            background = Color(0xFF000000), panel = Color(0xFF0A0A0A), raised = Color(0xFF141414), overlay = Color(0xFF1E1E1E),
            border = Color(0xFF262626), text = Color(0xFFE6E6E6), textMuted = Color(0xFF8C8C8C),
            accent = Color(0xFF61AFEF), onAccent = Color(0xFF000000), accentContainer = Color(0xFF0F2A40),
            secondary = Color(0xFFC678DD), tertiary = Color(0xFF98C379),
            error = Color(0xFFE06C75), warning = Color(0xFFE5C07B), success = Color(0xFF98C379),
        ),
    ),
    ONE_DARK(
        "One Dark", "one-dark-pro",
        AppPalette(
            isDark = true,
            background = Color(0xFF282C34), panel = Color(0xFF21252B), raised = Color(0xFF2C313A), overlay = Color(0xFF353B45),
            border = Color(0xFF181A1F), text = Color(0xFFD7DAE0), textMuted = Color(0xFF8B929E),
            accent = Color(0xFF61AFEF), onAccent = Color(0xFF1B1F27), accentContainer = Color(0xFF2B4A66),
            secondary = Color(0xFFC678DD), tertiary = Color(0xFF98C379),
            error = Color(0xFFE06C75), warning = Color(0xFFE5C07B), success = Color(0xFF98C379),
        ),
    ),
    DRACULA(
        "Dracula", "dracula",
        AppPalette(
            isDark = true,
            background = Color(0xFF282A36), panel = Color(0xFF21222C), raised = Color(0xFF343746), overlay = Color(0xFF424450),
            border = Color(0xFF191A21), text = Color(0xFFF8F8F2), textMuted = Color(0xFF9EA3BF),
            accent = Color(0xFFBD93F9), onAccent = Color(0xFF21222C), accentContainer = Color(0xFF44385E),
            secondary = Color(0xFFFF79C6), tertiary = Color(0xFF50FA7B),
            error = Color(0xFFFF5555), warning = Color(0xFFF1FA8C), success = Color(0xFF50FA7B),
        ),
    ),
    MONOKAI(
        "Monokai", "monokai",
        AppPalette(
            isDark = true,
            background = Color(0xFF272822), panel = Color(0xFF1E1F1C), raised = Color(0xFF34352F), overlay = Color(0xFF414339),
            border = Color(0xFF171814), text = Color(0xFFF8F8F2), textMuted = Color(0xFF90908A),
            accent = Color(0xFFA6E22E), onAccent = Color(0xFF1E1F1C), accentContainer = Color(0xFF3B4A1A),
            secondary = Color(0xFFF92672), tertiary = Color(0xFF66D9EF),
            error = Color(0xFFF92672), warning = Color(0xFFE6DB74), success = Color(0xFFA6E22E),
        ),
    ),
    NORD(
        "Nord", "nord",
        AppPalette(
            isDark = true,
            background = Color(0xFF2E3440), panel = Color(0xFF272C36), raised = Color(0xFF3B4252), overlay = Color(0xFF434C5E),
            border = Color(0xFF222730), text = Color(0xFFECEFF4), textMuted = Color(0xFF9BA3B4),
            accent = Color(0xFF88C0D0), onAccent = Color(0xFF2E3440), accentContainer = Color(0xFF3B5563),
            secondary = Color(0xFFB48EAD), tertiary = Color(0xFFA3BE8C),
            error = Color(0xFFBF616A), warning = Color(0xFFEBCB8B), success = Color(0xFFA3BE8C),
        ),
    ),
    TOKYO_NIGHT(
        "Tokyo Night", "tokyo-night",
        AppPalette(
            isDark = true,
            background = Color(0xFF1A1B26), panel = Color(0xFF16161E), raised = Color(0xFF232433), overlay = Color(0xFF2F3149),
            border = Color(0xFF101014), text = Color(0xFFC0CAF5), textMuted = Color(0xFF787C99),
            accent = Color(0xFF7AA2F7), onAccent = Color(0xFF16161E), accentContainer = Color(0xFF2A3A63),
            secondary = Color(0xFFBB9AF7), tertiary = Color(0xFF9ECE6A),
            error = Color(0xFFF7768E), warning = Color(0xFFE0AF68), success = Color(0xFF9ECE6A),
        ),
    ),
    CATPPUCCIN(
        "Catppuccin Mocha", "catppuccin-mocha",
        AppPalette(
            isDark = true,
            background = Color(0xFF1E1E2E), panel = Color(0xFF181825), raised = Color(0xFF313244), overlay = Color(0xFF45475A),
            border = Color(0xFF11111B), text = Color(0xFFCDD6F4), textMuted = Color(0xFF9399B2),
            accent = Color(0xFFCBA6F7), onAccent = Color(0xFF1E1E2E), accentContainer = Color(0xFF45385F),
            secondary = Color(0xFFF5C2E7), tertiary = Color(0xFFA6E3A1),
            error = Color(0xFFF38BA8), warning = Color(0xFFF9E2AF), success = Color(0xFFA6E3A1),
        ),
    ),
    GRUVBOX(
        "Gruvbox Dark", "gruvbox-dark-medium",
        AppPalette(
            isDark = true,
            background = Color(0xFF282828), panel = Color(0xFF1D2021), raised = Color(0xFF32302F), overlay = Color(0xFF3C3836),
            border = Color(0xFF141617), text = Color(0xFFEBDBB2), textMuted = Color(0xFFA89984),
            accent = Color(0xFFFABD2F), onAccent = Color(0xFF1D2021), accentContainer = Color(0xFF5A4A1C),
            secondary = Color(0xFFD3869B), tertiary = Color(0xFFB8BB26),
            error = Color(0xFFFB4934), warning = Color(0xFFFABD2F), success = Color(0xFFB8BB26),
        ),
    ),
    SOLARIZED_DARK(
        "Solarized Dark", "solarized-dark",
        AppPalette(
            isDark = true,
            background = Color(0xFF002B36), panel = Color(0xFF00212B), raised = Color(0xFF073642), overlay = Color(0xFF0B4452),
            border = Color(0xFF001A21), text = Color(0xFFEEE8D5), textMuted = Color(0xFF93A1A1),
            accent = Color(0xFF268BD2), onAccent = Color(0xFFFDF6E3), accentContainer = Color(0xFF0E4A6E),
            secondary = Color(0xFFD33682), tertiary = Color(0xFF859900),
            error = Color(0xFFDC322F), warning = Color(0xFFB58900), success = Color(0xFF859900),
        ),
    ),
    LIGHT(
        "Light", "github-light",
        AppPalette(
            isDark = false,
            background = Color(0xFFFFFFFF), panel = Color(0xFFF6F8FA), raised = Color(0xFFEFF2F5), overlay = Color(0xFFFFFFFF),
            border = Color(0xFFD0D7DE), text = Color(0xFF1F2328), textMuted = Color(0xFF59636E),
            accent = Color(0xFF0969DA), onAccent = Color(0xFFFFFFFF), accentContainer = Color(0xFFDDF4FF),
            secondary = Color(0xFF8250DF), tertiary = Color(0xFF1A7F37),
            error = Color(0xFFCF222E), warning = Color(0xFF9A6700), success = Color(0xFF1A7F37),
        ),
    ),
    SOLARIZED_LIGHT(
        "Solarized Light", "solarized-light",
        AppPalette(
            isDark = false,
            background = Color(0xFFFDF6E3), panel = Color(0xFFEEE8D5), raised = Color(0xFFE6DFC8), overlay = Color(0xFFFDF6E3),
            border = Color(0xFFD9D2BC), text = Color(0xFF073642), textMuted = Color(0xFF657B83),
            accent = Color(0xFF268BD2), onAccent = Color(0xFFFDF6E3), accentContainer = Color(0xFFCFE3F2),
            secondary = Color(0xFFD33682), tertiary = Color(0xFF859900),
            error = Color(0xFFDC322F), warning = Color(0xFFB58900), success = Color(0xFF859900),
        ),
    );

    companion object {
        val DEFAULT = BLACK

        fun fromName(name: String?): AppTheme = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
