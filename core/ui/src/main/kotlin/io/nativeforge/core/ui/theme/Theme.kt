package io.nativeforge.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val LocalDiagnosticColors = staticCompositionLocalOf { AppTheme.DEFAULT.palette.diagnostics }

/** The active palette, for components that need colors Material's scheme has no slot for. */
val LocalAppPalette = staticCompositionLocalOf { AppTheme.DEFAULT.palette }

/** Monospace style for code, build logs and terminal-like text. */
val CodeTextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)

private fun AppPalette.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentContainer,
        onPrimaryContainer = text,
        secondary = secondary,
        onSecondary = background,
        secondaryContainer = raised,
        onSecondaryContainer = text,
        tertiary = tertiary,
        onTertiary = background,
        background = background,
        onBackground = text,
        surface = background,
        onSurface = text,
        surfaceVariant = raised,
        onSurfaceVariant = textMuted,
        surfaceTint = background,
        surfaceDim = background,
        surfaceBright = overlay,
        surfaceContainerLowest = background,
        surfaceContainerLow = panel,
        surfaceContainer = panel,
        surfaceContainerHigh = raised,
        surfaceContainerHighest = overlay,
        inverseSurface = text,
        inverseOnSurface = background,
        outline = textMuted.copy(alpha = 0.6f),
        outlineVariant = border,
        error = error,
        onError = background,
        scrim = background.copy(alpha = 0.6f),
    )
}

/**
 * Compact type scale: denser than Material's defaults, closer to a desktop
 * editor such as VS Code.
 */
private val CompactTypography: Typography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontSize = 18.sp, lineHeight = 24.sp),
        titleMedium = t.titleMedium.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        titleSmall = t.titleSmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
        bodyLarge = t.bodyLarge.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
        bodySmall = t.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = t.labelLarge.copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelMedium = t.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelSmall = t.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
    )
}

/** Small corner radii, like a desktop editor, instead of Material's pill-shaped surfaces. */
private val CompactShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(5.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

@Composable
fun NativeForgeTheme(
    theme: AppTheme = AppTheme.DEFAULT,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalAppPalette provides theme.palette,
        LocalDiagnosticColors provides theme.palette.diagnostics,
    ) {
        MaterialTheme(colorScheme = theme.palette.toColorScheme(), typography = CompactTypography, shapes = CompactShapes) {
            // Smaller touch targets than Material's 48dp keep toolbars slim.
            // Text outside a Surface uses the theme's text color instead of Compose's default black.
            CompositionLocalProvider(
                LocalMinimumInteractiveComponentSize provides 36.dp,
                LocalContentColor provides theme.palette.text,
                content = content,
            )
        }
    }
}
