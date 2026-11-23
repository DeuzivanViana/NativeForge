package io.nativeforge.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nativeforge.core.project.model.BuildSystemKind
import io.nativeforge.core.project.model.CMakeGenerator
import io.nativeforge.core.project.model.Language
import io.nativeforge.core.project.model.RuntimeKind
import io.nativeforge.core.ui.component.Tag
import io.nativeforge.core.ui.component.ToolbarIconButton
import io.nativeforge.core.ui.theme.LocalAppPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * One project in the home list: kind icon, name, a few tags (language, build system,
 * libraries) and a muted line with files, last edit and git branch.
 */
@Composable
fun ProjectCard(summary: ProjectSummary, onOpen: () -> Unit, onDelete: () -> Unit) {
    val config = summary.project.config
    val colors = MaterialTheme.colorScheme
    val palette = LocalAppPalette.current
    var menuOpen by remember { mutableStateOf(false) }
    val (icon, tint) = runtimeIcon(config.runtime, palette.accent, palette.success, palette.secondary)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.small, color = tint.copy(alpha = 0.14f), modifier = Modifier.size(36.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        summary.project.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Tag(if (config.language == Language.C) "C" else "C++", color = palette.secondary)
                    Spacer(Modifier.width(4.dp))
                    Tag(buildLabel(config.buildSystem, config.cmakeGenerator), color = palette.tertiary)
                }
                if (config.packages.isNotEmpty()) {
                    Text(
                        config.packages.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    detailLine(summary),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                ToolbarIconButton(Icons.Filled.MoreVert, "Project actions", { menuOpen = true })
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Open", style = MaterialTheme.typography.bodyMedium) },
                        onClick = { menuOpen = false; onOpen() },
                        modifier = Modifier.height(36.dp),
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", style = MaterialTheme.typography.bodyMedium, color = colors.error) },
                        onClick = { menuOpen = false; onDelete() },
                        modifier = Modifier.height(36.dp),
                    )
                }
            }
        }
    }
}

private fun runtimeIcon(runtime: RuntimeKind, accent: Color, success: Color, secondary: Color): Pair<ImageVector, Color> =
    when (runtime) {
        RuntimeKind.CONSOLE -> Icons.Filled.Terminal to success
        RuntimeKind.SDL3, RuntimeKind.SDL2 -> Icons.Filled.Layers to secondary
        RuntimeKind.NATIVE_ACTIVITY -> Icons.Filled.SportsEsports to accent
    }

private fun buildLabel(kind: BuildSystemKind, generator: CMakeGenerator): String = when (kind) {
    BuildSystemKind.SINGLE -> "Built-in"
    BuildSystemKind.CMAKE -> if (generator == CMakeGenerator.NINJA) "CMake" else "CMake·Make"
    BuildSystemKind.MAKE -> "Make"
    BuildSystemKind.NINJA -> "Ninja"
}

private fun detailLine(summary: ProjectSummary): String = buildList {
    add(if (summary.sourceFiles == 1) "1 source" else "${summary.sourceFiles} sources")
    add("edited " + relativeTime(summary.modifiedMillis))
    summary.branch?.let { add("⎇ $it" + if (summary.changes > 0) " +${summary.changes}" else "") }
}.joinToString("  ·  ")

/** "just now", "5 min ago", "3 h ago", "yesterday", "4 days ago" or a date. */
internal fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    val elapsed = (now - millis).coerceAtLeast(0)
    return when {
        elapsed < TimeUnit.MINUTES.toMillis(1) -> "just now"
        elapsed < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(elapsed)} min ago"
        elapsed < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(elapsed)} h ago"
        elapsed < TimeUnit.DAYS.toMillis(2) -> "yesterday"
        elapsed < TimeUnit.DAYS.toMillis(7) -> "${TimeUnit.MILLISECONDS.toDays(elapsed)} days ago"
        else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(millis))
    }
}
