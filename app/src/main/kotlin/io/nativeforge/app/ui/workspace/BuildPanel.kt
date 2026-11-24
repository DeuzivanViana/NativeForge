package io.nativeforge.app.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import android.widget.Toast
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.nativeforge.core.build.diagnostics.Diagnostic
import io.nativeforge.core.build.diagnostics.Severity
import io.nativeforge.core.ui.component.ToolbarIconButton
import io.nativeforge.core.ui.theme.CodeTextStyle
import io.nativeforge.core.ui.theme.LocalDiagnosticColors
import kotlinx.coroutines.flow.StateFlow

/**
 * Bottom panel with the raw build output and the parsed problems list. Output text can be
 * selected (long press, then drag the handles, scrolling as needed) and the copy button copies
 * the whole tab.
 *
 * The header is a drag handle: dragging it resizes the panel through [onResize] (pixels, positive
 * when dragging down) and [onResizeEnd] runs when the finger lifts, so the caller can close the
 * panel when it was dragged almost shut.
 */
@Composable
fun BuildPanel(
    log: StateFlow<List<LogLine>>,
    diagnostics: List<Diagnostic>,
    onDiagnosticClick: (Diagnostic) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onResize: (Float) -> Unit = {},
    onResizeEnd: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val colors = LocalDiagnosticColors.current
    // Filtering runs only when the diagnostics change, not on every log line.
    val problems = remember(diagnostics) { diagnostics.filter { it.severity != Severity.NOTE } }
    val resize by rememberUpdatedState(onResize)
    val resizeEnd by rememberUpdatedState(onResizeEnd)
    val header = remember { HeaderPosition() }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copyTab = {
        val text = if (tab == 0) {
            log.value.joinToString("\n") { it.text }
        } else {
            problems.joinToString("\n") { d ->
                val where = d.file?.let { "$it:${d.line ?: "?"}:${d.column ?: "?"}: " }.orEmpty()
                "$where${d.severity.name.lowercase()}: ${d.message}"
            }
        }
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, if (text.isEmpty()) "Nothing to copy" else "Copied", Toast.LENGTH_SHORT).show()
    }

    val panelColor = MaterialTheme.colorScheme.surfaceContainerLow
    Surface(modifier = modifier.clipToBounds(), color = panelColor) {
        Column(Modifier.fillMaxSize()) {
            // The header is drawn above the output (zIndex) on an opaque background, so the
            // platform text view below can never show through the tab labels.
            Column(
                Modifier
                    .zIndex(1f)
                    .fillMaxWidth()
                    .background(panelColor)
                    .onPlaced { header.coordinates = it }
                    .pointerInput(Unit) {
                        // The header moves with the finger while the panel resizes, so local
                        // drag amounts would shrink by that movement (the handle would lag at
                        // half speed). Positions in root coordinates don't move with it.
                        var lastY = 0f
                        detectVerticalDragGestures(
                            onDragStart = { start -> lastY = header.rootY(start.y) },
                            onDragEnd = { resizeEnd() },
                            onDragCancel = { resizeEnd() },
                        ) { change, _ ->
                            change.consume()
                            val y = header.rootY(change.position.y)
                            resize(y - lastY)
                            lastY = y
                        }
                    },
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.fillMaxWidth().height(6.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(width = 28.dp, height = 3.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50)),
                    )
                }
                Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    PanelTab("OUTPUT", selected = tab == 0) { tab = 0 }
                    PanelTab("PROBLEMS ${problems.size}", selected = tab == 1) { tab = 1 }
                    Spacer(Modifier.weight(1f))
                    ToolbarIconButton(Icons.Filled.ContentCopy, if (tab == 0) "Copy output" else "Copy problems", copyTab)
                    ToolbarIconButton(Icons.Filled.Close, "Close panel", onClose)
                }
            }
            when (tab) {
                0 -> BuildOutputView(
                    log = log.collectAsStateWithLifecycle().value,
                    textColor = MaterialTheme.colorScheme.onSurface,
                    errorColor = colors.error,
                    selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxSize().clipToBounds().padding(horizontal = 8.dp),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(problems, contentType = { "problem" }) { diagnostic ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onDiagnosticClick(diagnostic) }.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            val (icon, tint) = when (diagnostic.severity) {
                                Severity.ERROR -> Icons.Filled.Error to colors.error
                                Severity.WARNING -> Icons.Filled.Warning to colors.warning
                                Severity.NOTE -> Icons.Filled.Info to colors.note
                            }
                            Icon(icon, contentDescription = diagnostic.severity.name, tint = tint, modifier = Modifier.size(14.dp).padding(top = 2.dp))
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text(diagnostic.message, style = MaterialTheme.typography.bodySmall)
                                diagnostic.file?.let { file ->
                                    Text(
                                        "${file.substringAfterLast('/')}:${diagnostic.line ?: "?"}:${diagnostic.column ?: "?"}",
                                        style = CodeTextStyle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelTab(text: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxHeight().clickable(onClick = onClick).padding(horizontal = 10.dp)) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent))
    }
}

/** Live layout position of the panel header, to read drag positions in root coordinates. */
private class HeaderPosition {
    var coordinates: LayoutCoordinates? = null

    fun rootY(localY: Float): Float {
        val coordinates = coordinates?.takeIf { it.isAttached } ?: return localY
        return coordinates.localToRoot(Offset(0f, localY)).y
    }
}
