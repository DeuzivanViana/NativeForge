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
    DARK_PLUS(
        "Dark+", "dark-plus",
        AppPalette(
            isDark = true,
            background = Color(0xFF1E1E1E), panel = Color(0xFF181818), raised = Color(0xFF252526), overlay = Color(0xFF2D2D30),
            border = Color(0xFF2B2B2B), text = Color(0xFFD4D4D4), textMuted = Color(0xFF9D9D9D),
            accent = Color(0xFF3794FF), onAccent = Color(0xFF000000), accentContainer = Color(0xFF094771),
            secondary = Color(0xFFC586C0), tertiary = Color(0xFF4EC9B0),
            error = Color(0xFFF14C4C), warning = Color(0xFFCCA700), success = Color(0xFF89D185),
        ),
    ),
    GITHUB_DARK(
        "GitHub Dark", "github-dark-default",
        AppPalette(
            isDark = true,
            background = Color(0xFF0D1117), panel = Color(0xFF010409), raised = Color(0xFF161B22), overlay = Color(0xFF21262D),
            border = Color(0xFF30363D), text = Color(0xFFE6EDF3), textMuted = Color(0xFF8B949E),
            accent = Color(0xFF58A6FF), onAccent = Color(0xFF0D1117), accentContainer = Color(0xFF16304F),
            secondary = Color(0xFFD2A8FF), tertiary = Color(0xFF7EE787),
            error = Color(0xFFFF7B72), warning = Color(0xFFD29922), success = Color(0xFF3FB950),
        ),
    ),
    GITHUB_DIMMED(
        "GitHub Dimmed", "github-dark-dimmed",
        AppPalette(
            isDark = true,
            background = Color(0xFF22272E), panel = Color(0xFF1C2128), raised = Color(0xFF2D333B), overlay = Color(0xFF373E47),
            border = Color(0xFF444C56), text = Color(0xFFADBAC7), textMuted = Color(0xFF8D96A0),
            accent = Color(0xFF539BF5), onAccent = Color(0xFF1C2128), accentContainer = Color(0xFF263C5A),
            secondary = Color(0xFFDCBDFB), tertiary = Color(0xFF8DDB8C),
            error = Color(0xFFF47067), warning = Color(0xFFC69026), success = Color(0xFF57AB5A),
        ),
    ),
    GRUVBOX_HARD(
        "Gruvbox Hard", "gruvbox-dark-hard",
        AppPalette(
            isDark = true,
            background = Color(0xFF1D2021), panel = Color(0xFF141617), raised = Color(0xFF282828), overlay = Color(0xFF3C3836),
            border = Color(0xFF32302F), text = Color(0xFFEBDBB2), textMuted = Color(0xFFA89984),
            accent = Color(0xFF83A598), onAccent = Color(0xFF1D2021), accentContainer = Color(0xFF2E4246),
            secondary = Color(0xFFD3869B), tertiary = Color(0xFFB8BB26),
            error = Color(0xFFFB4934), warning = Color(0xFFFABD2F), success = Color(0xFFB8BB26),
        ),
    ),
    CATPPUCCIN_MACCHIATO(
        "Catppuccin Macchiato", "catppuccin-macchiato",
        AppPalette(
            isDark = true,
            background = Color(0xFF24273A), panel = Color(0xFF1E2030), raised = Color(0xFF363A4F), overlay = Color(0xFF494D64),
            border = Color(0xFF181926), text = Color(0xFFCAD3F5), textMuted = Color(0xFF939AB7),
            accent = Color(0xFFC6A0F6), onAccent = Color(0xFF24273A), accentContainer = Color(0xFF4A3F66),
            secondary = Color(0xFFF5BDE6), tertiary = Color(0xFFA6DA95),
            error = Color(0xFFED8796), warning = Color(0xFFEED49F), success = Color(0xFFA6DA95),
        ),
    ),
    CATPPUCCIN_FRAPPE(
        "Catppuccin Frappé", "catppuccin-frappe",
        AppPalette(
            isDark = true,
            background = Color(0xFF303446), panel = Color(0xFF292C3C), raised = Color(0xFF414559), overlay = Color(0xFF51576D),
            border = Color(0xFF232634), text = Color(0xFFC6D0F5), textMuted = Color(0xFF949CBB),
            accent = Color(0xFFCA9EE6), onAccent = Color(0xFF303446), accentContainer = Color(0xFF55466B),
            secondary = Color(0xFFF4B8E4), tertiary = Color(0xFFA6D189),
            error = Color(0xFFE78284), warning = Color(0xFFE5C890), success = Color(0xFFA6D189),
        ),
    ),
    ROSE_PINE(
        "Rosé Pine", "rose-pine",
        AppPalette(
            isDark = true,
            background = Color(0xFF191724), panel = Color(0xFF1F1D2E), raised = Color(0xFF26233A), overlay = Color(0xFF403D52),
            border = Color(0xFF2A273F), text = Color(0xFFE0DEF4), textMuted = Color(0xFF908CAA),
            accent = Color(0xFFC4A7E7), onAccent = Color(0xFF191724), accentContainer = Color(0xFF403D52),
            secondary = Color(0xFFEBBCBA), tertiary = Color(0xFF9CCFD8),
            error = Color(0xFFEB6F92), warning = Color(0xFFF6C177), success = Color(0xFF9CCFD8),
        ),
    ),
    ROSE_PINE_MOON(
        "Rosé Pine Moon", "rose-pine-moon",
        AppPalette(
            isDark = true,
            background = Color(0xFF232136), panel = Color(0xFF2A273F), raised = Color(0xFF393552), overlay = Color(0xFF44415A),
            border = Color(0xFF1E1C2E), text = Color(0xFFE0DEF4), textMuted = Color(0xFF908CAA),
            accent = Color(0xFFC4A7E7), onAccent = Color(0xFF232136), accentContainer = Color(0xFF4A4565),
            secondary = Color(0xFFEA9A97), tertiary = Color(0xFF9CCFD8),
            error = Color(0xFFEB6F92), warning = Color(0xFFF6C177), success = Color(0xFF9CCFD8),
        ),
    ),
    KANAGAWA(
        "Kanagawa Wave", "kanagawa-wave",
        AppPalette(
            isDark = true,
            background = Color(0xFF1F1F28), panel = Color(0xFF16161D), raised = Color(0xFF2A2A37), overlay = Color(0xFF363646),
            border = Color(0xFF2A2A37), text = Color(0xFFDCD7BA), textMuted = Color(0xFF938AA9),
            accent = Color(0xFF7E9CD8), onAccent = Color(0xFF16161D), accentContainer = Color(0xFF2D4F67),
            secondary = Color(0xFF957FB8), tertiary = Color(0xFF98BB6C),
            error = Color(0xFFFF5D62), warning = Color(0xFFFF9E3B), success = Color(0xFF98BB6C),
        ),
    ),
    KANAGAWA_DRAGON(
        "Kanagawa Dragon", "kanagawa-dragon",
        AppPalette(
            isDark = true,
            background = Color(0xFF181616), panel = Color(0xFF0D0C0C), raised = Color(0xFF282727), overlay = Color(0xFF393836),
            border = Color(0xFF1D1C19), text = Color(0xFFC5C9C5), textMuted = Color(0xFFA6A69C),
            accent = Color(0xFF8BA4B0), onAccent = Color(0xFF0D0C0C), accentContainer = Color(0xFF2D4F67),
            secondary = Color(0xFF8992A7), tertiary = Color(0xFF87A987),
            error = Color(0xFFC4746E), warning = Color(0xFFC4B28A), success = Color(0xFF87A987),
        ),
    ),
    EVERFOREST(
        "Everforest Dark", "everforest-dark",
        AppPalette(
            isDark = true,
            background = Color(0xFF2D353B), panel = Color(0xFF232A2E), raised = Color(0xFF343F44), overlay = Color(0xFF3D484D),
            border = Color(0xFF1E2326), text = Color(0xFFD3C6AA), textMuted = Color(0xFF9DA9A0),
            accent = Color(0xFFA7C080), onAccent = Color(0xFF2D353B), accentContainer = Color(0xFF425047),
            secondary = Color(0xFFD699B6), tertiary = Color(0xFF83C092),
            error = Color(0xFFE67E80), warning = Color(0xFFDBBC7F), success = Color(0xFFA7C080),
        ),
    ),
    PALENIGHT(
        "Material Palenight", "material-theme-palenight",
        AppPalette(
            isDark = true,
            background = Color(0xFF292D3E), panel = Color(0xFF232635), raised = Color(0xFF32374D), overlay = Color(0xFF3A3F58),
            border = Color(0xFF1F2233), text = Color(0xFFBABED8), textMuted = Color(0xFF8A90B5),
            accent = Color(0xFF82AAFF), onAccent = Color(0xFF292D3E), accentContainer = Color(0xFF3C4366),
            secondary = Color(0xFFC792EA), tertiary = Color(0xFFC3E88D),
            error = Color(0xFFF07178), warning = Color(0xFFFFCB6B), success = Color(0xFFC3E88D),
        ),
    ),
    AYU_DARK(
        "Ayu Dark", "ayu-dark",
        AppPalette(
            isDark = true,
            background = Color(0xFF10141C), panel = Color(0xFF0D1017), raised = Color(0xFF141821), overlay = Color(0xFF1C212B),
            border = Color(0xFF1B1F29), text = Color(0xFFBFBDB6), textMuted = Color(0xFF7A8293),
            accent = Color(0xFFE6B450), onAccent = Color(0xFF0D1017), accentContainer = Color(0xFF3D3420),
            secondary = Color(0xFFFF8F40), tertiary = Color(0xFFAAD94C),
            error = Color(0xFFD95757), warning = Color(0xFFFFB454), success = Color(0xFF70BF56),
        ),
    ),
    AYU_MIRAGE(
        "Ayu Mirage", "ayu-mirage",
        AppPalette(
            isDark = true,
            background = Color(0xFF242936), panel = Color(0xFF1F2430), raised = Color(0xFF282E3B), overlay = Color(0xFF323A4C),
            border = Color(0xFF171B24), text = Color(0xFFCCCAC2), textMuted = Color(0xFF8A9199),
            accent = Color(0xFFFFCC66), onAccent = Color(0xFF1F2430), accentContainer = Color(0xFF4A4330),
            secondary = Color(0xFFFFA659), tertiary = Color(0xFFD5FF80),
            error = Color(0xFFFF6666), warning = Color(0xFFFFD173), success = Color(0xFF87D96C),
        ),
    ),
    NIGHT_OWL(
        "Night Owl", "night-owl",
        AppPalette(
            isDark = true,
            background = Color(0xFF011627), panel = Color(0xFF01111D), raised = Color(0xFF0B2942), overlay = Color(0xFF13344F),
            border = Color(0xFF122D42), text = Color(0xFFD6DEEB), textMuted = Color(0xFF809AB0),
            accent = Color(0xFF82AAFF), onAccent = Color(0xFF011627), accentContainer = Color(0xFF1D3B53),
            secondary = Color(0xFFC792EA), tertiary = Color(0xFFADDB67),
            error = Color(0xFFEF5350), warning = Color(0xFFFFCB6B), success = Color(0xFFC5E478),
        ),
    ),
    HORIZON(
        "Horizon", "horizon",
        AppPalette(
            isDark = true,
            background = Color(0xFF1C1E26), panel = Color(0xFF16161C), raised = Color(0xFF232530), overlay = Color(0xFF2E303E),
            border = Color(0xFF2E303E), text = Color(0xFFD5D8DA), textMuted = Color(0xFF8C8FA8),
            accent = Color(0xFFE95378), onAccent = Color(0xFF1C1E26), accentContainer = Color(0xFF4A2433),
            secondary = Color(0xFFB877DB), tertiary = Color(0xFFFAB795),
            error = Color(0xFFF43E5C), warning = Color(0xFFFAC29A), success = Color(0xFF27D797),
        ),
    ),
    POIMANDRES(
        "Poimandres", "poimandres",
        AppPalette(
            isDark = true,
            background = Color(0xFF1B1E28), panel = Color(0xFF171922), raised = Color(0xFF252B37), overlay = Color(0xFF303340),
            border = Color(0xFF12141C), text = Color(0xFFA6ACCD), textMuted = Color(0xFF7F86A8),
            accent = Color(0xFF5DE4C7), onAccent = Color(0xFF1B1E28), accentContainer = Color(0xFF25403F),
            secondary = Color(0xFFADD7FF), tertiary = Color(0xFFFFFAC2),
            error = Color(0xFFD0679D), warning = Color(0xFFFFFAC2), success = Color(0xFF5DE4C7),
        ),
    ),
    SYNTHWAVE_84(
        "Synthwave '84", "synthwave-84",
        AppPalette(
            isDark = true,
            background = Color(0xFF262335), panel = Color(0xFF241B2F), raised = Color(0xFF2A2139), overlay = Color(0xFF34294F),
            border = Color(0xFF171520), text = Color(0xFFF0EFF5), textMuted = Color(0xFF9097C6),
            accent = Color(0xFFFF7EDB), onAccent = Color(0xFF262335), accentContainer = Color(0xFF463465),
            secondary = Color(0xFF36F9F6), tertiary = Color(0xFF72F1B8),
            error = Color(0xFFFE4450), warning = Color(0xFFFEDE5D), success = Color(0xFF72F1B8),
        ),
    ),
    VITESSE_DARK(
        "Vitesse Dark", "vitesse-dark",
        AppPalette(
            isDark = true,
            background = Color(0xFF121212), panel = Color(0xFF0E0E0E), raised = Color(0xFF1A1A1A), overlay = Color(0xFF242424),
            border = Color(0xFF222222), text = Color(0xFFDBD7CA), textMuted = Color(0xFF959490),
            accent = Color(0xFF4D9375), onAccent = Color(0xFF121212), accentContainer = Color(0xFF1F3329),
            secondary = Color(0xFFC98A7D), tertiary = Color(0xFF80A665),
            error = Color(0xFFCB7676), warning = Color(0xFFD4976C), success = Color(0xFF4D9375),
        ),
    ),
    VITESSE_BLACK(
        "Vitesse Black", "vitesse-black",
        AppPalette(
            isDark = true,
            background = Color(0xFF000000), panel = Color(0xFF080808), raised = Color(0xFF121212), overlay = Color(0xFF1E1E1E),
            border = Color(0xFF191919), text = Color(0xFFDBD7CA), textMuted = Color(0xFF959490),
            accent = Color(0xFF4D9375), onAccent = Color(0xFF000000), accentContainer = Color(0xFF1A2E24),
            secondary = Color(0xFFC98A7D), tertiary = Color(0xFF80A665),
            error = Color(0xFFCB7676), warning = Color(0xFFD4976C), success = Color(0xFF4D9375),
        ),
    ),
    VESPER(
        "Vesper", "vesper",
        AppPalette(
            isDark = true,
            background = Color(0xFF101010), panel = Color(0xFF0A0A0A), raised = Color(0xFF1C1C1C), overlay = Color(0xFF282828),
            border = Color(0xFF232323), text = Color(0xFFF0F0F0), textMuted = Color(0xFFA0A0A0),
            accent = Color(0xFFFFC799), onAccent = Color(0xFF101010), accentContainer = Color(0xFF3D3024),
            secondary = Color(0xFF99FFE4), tertiary = Color(0xFFFFCFA8),
            error = Color(0xFFFF8080), warning = Color(0xFFFFC799), success = Color(0xFF99FFE4),
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
