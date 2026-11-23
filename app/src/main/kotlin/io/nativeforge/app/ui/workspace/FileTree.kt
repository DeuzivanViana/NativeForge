package io.nativeforge.app.ui.workspace

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nativeforge.core.ui.theme.AppPalette
import io.nativeforge.core.ui.theme.LocalAppPalette
import io.nativeforge.core.vcs.FileChange
import java.io.File

/** What the rows of [FileTree] can do. */
data class FileTreeActions(
    val onOpen: (File) -> Unit,
    val onToggle: (String) -> Unit,
    val onNewFile: (parent: String) -> Unit,
    val onNewFolder: (parent: String) -> Unit,
    val onRename: (FileNode) -> Unit,
    val onDelete: (FileNode) -> Unit,
    val onShowChanges: (String) -> Unit,
)

/**
 * Collapsible project tree (28dp rows) in the style of VS Code's explorer: indent guides,
 * file-type icons, unsaved and git markers. Tap opens a file or folds a folder; a long
 * press offers New file, New folder, Rename and Delete.
 */
@Composable
fun FileTree(
    nodes: List<FileNode>,
    expanded: Set<String>,
    activeFile: String?,
    dirtyFiles: Set<String>,
    changes: Map<String, FileChange>,
    actions: FileTreeActions,
    modifier: Modifier = Modifier,
) {
    val visible = remember(nodes, expanded) { visibleNodes(nodes, expanded) }
    // Folders that contain changes get a dot, like VS Code.
    val changedFolders = remember(changes) {
        changes.keys.flatMap { path ->
            val parts = path.split('/').dropLast(1)
            parts.indices.map { parts.take(it + 1).joinToString("/") }
        }.toSet()
    }
    LazyColumn(modifier) {
        items(visible, key = { it.file.path }) { node ->
            FileRow(
                node = node,
                isExpanded = node.relativePath in expanded,
                selected = node.file.path == activeFile,
                dirty = node.file.path in dirtyFiles,
                change = changes[node.relativePath],
                folderChanged = node.isDirectory && node.relativePath in changedFolders,
                actions = actions,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    node: FileNode,
    isExpanded: Boolean,
    selected: Boolean,
    dirty: Boolean,
    change: FileChange?,
    folderChanged: Boolean,
    actions: FileTreeActions,
) {
    val palette = LocalAppPalette.current
    var menuOpen by remember { mutableStateOf(false) }
    val changeColor = change?.let { changeColor(it, palette) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                .combinedClickable(
                    onClick = { if (node.isDirectory) actions.onToggle(node.relativePath) else actions.onOpen(node.file) },
                    onLongClick = { menuOpen = true },
                )
                .padding(start = 4.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Indent guides: one thin line per level.
            repeat(node.depth) {
                Box(Modifier.width(14.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))
                }
            }
            if (node.isDirectory) {
                Icon(
                    if (isExpanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Spacer(Modifier.width(2.dp))
            val (icon, tint) = fileIcon(node, isExpanded, palette)
            Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp), tint = tint)
            Spacer(Modifier.width(6.dp))
            Text(
                node.file.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = changeColor ?: MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (dirty) Text("●", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            if (change != null) {
                Text(
                    change.letter.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = changeColor ?: Color.Unspecified,
                    modifier = Modifier.padding(start = 6.dp),
                )
            } else if (folderChanged) {
                Box(Modifier.size(6.dp).background(palette.warning, CircleShape))
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            val parent = if (node.isDirectory) node.relativePath else node.relativePath.substringBeforeLast('/', "")
            val item: @Composable (String, () -> Unit) -> Unit = { text, action ->
                DropdownMenuItem(
                    text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
                    onClick = { menuOpen = false; action() },
                    modifier = Modifier.height(36.dp),
                )
            }
            item("New file here") { actions.onNewFile(parent) }
            item("New folder here") { actions.onNewFolder(parent) }
            item("Rename") { actions.onRename(node) }
            if (change != null) item("Show changes") { actions.onShowChanges(node.relativePath) }
            item("Delete") { actions.onDelete(node) }
        }
    }
}

/** Rows whose parent folders are all expanded. */
internal fun visibleNodes(nodes: List<FileNode>, expanded: Set<String>): List<FileNode> {
    val result = ArrayList<FileNode>(nodes.size)
    var hiddenBelowDepth = Int.MAX_VALUE
    for (node in nodes) {
        if (node.depth > hiddenBelowDepth) continue
        hiddenBelowDepth = Int.MAX_VALUE
        result += node
        if (node.isDirectory && node.relativePath !in expanded) hiddenBelowDepth = node.depth
    }
    return result
}

fun changeColor(change: FileChange, palette: AppPalette): Color = when (change) {
    FileChange.ADDED, FileChange.UNTRACKED -> palette.success
    FileChange.MODIFIED -> palette.warning
    FileChange.DELETED, FileChange.CONFLICTING -> palette.error
}

private fun fileIcon(node: FileNode, expanded: Boolean, palette: AppPalette): Pair<ImageVector, Color> {
    if (node.isDirectory) return (if (expanded) Icons.Filled.FolderOpen else Icons.Filled.Folder) to palette.warning.copy(alpha = 0.9f)
    val name = node.file.name
    if (name == "CMakeLists.txt" || name.endsWith(".cmake")) return Icons.Outlined.Settings to palette.success
    if (name in setOf("Makefile", "makefile", "GNUmakefile", "build.ninja") || name.endsWith(".mk")) {
        return Icons.Outlined.Terminal to palette.success
    }
    return when (name.substringAfterLast('.', "").lowercase()) {
        "c" -> Icons.Outlined.Code to palette.accent
        "cpp", "cc", "cxx", "c++" -> Icons.Outlined.Code to palette.secondary
        "h", "hpp", "hh", "hxx", "inl" -> Icons.Outlined.Code to palette.tertiary
        "json", "toml", "yaml", "yml", "xml", "lua" -> Icons.Outlined.DataObject to palette.warning
        "png", "jpg", "jpeg", "gif", "bmp", "qoi", "svg", "webp" -> Icons.Outlined.Image to palette.tertiary
        "wav", "ogg", "mp3", "flac" -> Icons.Outlined.AudioFile to palette.tertiary
        "md", "txt" -> Icons.AutoMirrored.Outlined.Article to palette.textMuted
        "sh" -> Icons.Outlined.Terminal to palette.textMuted
        else -> Icons.AutoMirrored.Outlined.InsertDriveFile to palette.textMuted
    }
}

/** Project name and, for git repositories, the branch and number of changed files. */
@Composable
fun ExplorerTitle(projectName: String, branch: String?, changeCount: Int, modifier: Modifier = Modifier) {
    Column(modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 6.dp)) {
        Text(projectName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (branch != null) {
            Text(
                "⎇ $branch  ·  " + if (changeCount > 0) "$changeCount changed" else "no changes",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
