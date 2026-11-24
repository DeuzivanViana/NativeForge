package io.nativeforge.app.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nativeforge.core.ui.component.DialogPrimaryButton
import io.nativeforge.core.ui.component.DialogSecondaryButton
import io.nativeforge.core.ui.component.NfConfirmDialog
import io.nativeforge.core.ui.component.NfDialog
import io.nativeforge.core.ui.component.NfTextField
import io.nativeforge.core.ui.component.ToolbarIconButton
import io.nativeforge.core.ui.theme.LocalAppPalette
import io.nativeforge.core.vcs.GitCredentials
import java.io.File

private enum class ExplorerTab { FILES, CHANGES }

/** Pending name prompt of the explorer. */
private sealed interface NamePrompt {
    data class NewFile(val parent: String) : NamePrompt
    data class NewFolder(val parent: String) : NamePrompt
    data class Rename(val node: FileNode) : NamePrompt
}

/**
 * Side panel of the workspace: the file tree and, in a second tab, the git changes with
 * commit, pull and push. The Changes tab stays out of the way until it is opened.
 */
@Composable
fun ExplorerPanel(
    projectName: String,
    state: WorkspaceUiState,
    viewModel: WorkspaceViewModel,
    onFileOpened: () -> Unit,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(ExplorerTab.FILES) }
    var prompt by remember { mutableStateOf<NamePrompt?>(null) }
    var pendingDelete by remember { mutableStateOf<FileNode?>(null) }
    val git = state.git

    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ExplorerTitle(projectName, git?.branch ?: git?.let { "main" }, git?.changes?.size ?: 0, Modifier.weight(1f))
            ToolbarIconButton(Icons.AutoMirrored.Filled.NoteAdd, "New file", { prompt = NamePrompt.NewFile("") })
            ToolbarIconButton(Icons.Filled.CreateNewFolder, "New folder", { prompt = NamePrompt.NewFolder("") })
            ToolbarIconButton(Icons.Filled.UnfoldLess, "Collapse all", viewModel::collapseAll)
            ToolbarIconButton(Icons.Filled.Refresh, "Refresh", viewModel::refreshFiles)
        }
        Row(Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 8.dp)) {
            TabLabel("Files", tab == ExplorerTab.FILES) { tab = ExplorerTab.FILES }
            val count = git?.changes?.size ?: 0
            TabLabel(if (count > 0) "Changes ($count)" else "Changes", tab == ExplorerTab.CHANGES) { tab = ExplorerTab.CHANGES }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SearchEntry(onSearch)
        when (tab) {
            ExplorerTab.FILES -> FileTree(
                nodes = state.files,
                expanded = state.expandedDirs,
                activeFile = state.activeFile,
                dirtyFiles = state.dirtyFiles,
                changes = git?.changes.orEmpty(),
                actions = FileTreeActions(
                    onOpen = { viewModel.openFile(it); onFileOpened() },
                    onToggle = viewModel::toggleDirectory,
                    onNewFile = { prompt = NamePrompt.NewFile(it) },
                    onNewFolder = { prompt = NamePrompt.NewFolder(it) },
                    onRename = { prompt = NamePrompt.Rename(it) },
                    onDelete = { pendingDelete = it },
                    onShowChanges = viewModel::showDiff,
                ),
                modifier = Modifier.weight(1f),
            )
            ExplorerTab.CHANGES -> ChangesPanel(state, viewModel, Modifier.weight(1f))
        }
    }

    prompt?.let { current ->
        val (title, initial, confirm) = when (current) {
            is NamePrompt.NewFile -> Triple("New file", current.parent.withSlash(), "Create")
            is NamePrompt.NewFolder -> Triple("New folder", current.parent.withSlash(), "Create")
            is NamePrompt.Rename -> Triple("Rename ${current.node.file.name}", current.node.file.name, "Rename")
        }
        var value by remember(current) { mutableStateOf(initial) }
        NfDialog(
            title = title,
            onDismiss = { prompt = null },
            maxWidth = 440.dp,
            actions = {
                DialogSecondaryButton("Cancel") { prompt = null }
                DialogPrimaryButton(confirm, onClick = {
                    prompt = null
                    when (current) {
                        is NamePrompt.NewFile -> viewModel.createFile(value)
                        is NamePrompt.NewFolder -> viewModel.createFolder(value)
                        is NamePrompt.Rename -> viewModel.rename(current.node.file, value)
                    }
                    if (current is NamePrompt.NewFile) onFileOpened()
                }, enabled = value.isNotBlank() && !value.endsWith("/"))
            },
        ) {
            NfTextField(
                value = value,
                onValueChange = { value = it },
                label = if (current is NamePrompt.Rename) "New name" else "Path in project",
                placeholder = if (current is NamePrompt.NewFolder) "assets/sprites" else "src/player.cpp",
                supportingText = when (current) {
                    is NamePrompt.Rename -> null
                    else -> "Folders are created as needed. Sources go in src/, images and sounds in assets/."
                },
                monospace = true,
                modifier = Modifier.padding(14.dp),
            )
        }
    }

    pendingDelete?.let { node ->
        NfConfirmDialog(
            title = "Delete ${node.file.name}?",
            message = if (node.isDirectory) "The folder and everything in it is deleted." else "The file is deleted from the project.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { viewModel.delete(node.file); pendingDelete = null },
            onDismiss = { pendingDelete = null },
        )
    }
}

private fun String.withSlash() = if (isEmpty()) "" else "$this/"

@Composable
private fun TabLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp), verticalArrangement = Arrangement.Bottom) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.padding(top = 6.dp, bottom = 5.dp),
        )
        Box(
            Modifier.width(if (selected) 24.dp else 0.dp).height(2.dp)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
    }
}

/** Changed files, commit box, pull/push. Shows an "Initialize" button when the project has no repository. */
@Composable
private fun ChangesPanel(state: WorkspaceUiState, viewModel: WorkspaceViewModel, modifier: Modifier) {
    val git = state.git
    val palette = LocalAppPalette.current
    var message by rememberSaveable { mutableStateOf("") }
    var signIn by remember { mutableStateOf<((GitCredentials) -> Unit)?>(null) }
    var credentials by remember { mutableStateOf<GitCredentials?>(null) }

    Column(modifier.fillMaxWidth()) {
        if (state.gitBusy != null) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(state.gitBusy, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (git == null) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "This project is not a git repository. Create one to see what changed since your last commit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DialogPrimaryButton("Initialize repository", viewModel::initGit, enabled = state.gitBusy == null)
            }
            return@Column
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NfTextField(value = message, onValueChange = { message = it }, placeholder = "Commit message")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DialogPrimaryButton(
                    "Commit",
                    onClick = { viewModel.commit(message); message = "" },
                    enabled = git.hasChanges && message.isNotBlank() && state.gitBusy == null,
                )
                DialogSecondaryButton("Pull") { viewModel.pull(credentials) }
                DialogSecondaryButton("Push") {
                    val creds = credentials
                    if (creds != null) viewModel.push(creds) else signIn = { viewModel.push(it) }
                }
                DialogSecondaryButton(if (credentials == null) "Sign in" else "Signed in") { signIn = { } }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (!git.hasChanges) {
            Text(
                if (git.isEmpty) "No commits yet. Write a message and commit to save a first version." else "No changes since the last commit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            items(git.changes.entries.toList(), key = { it.key }) { (path, change) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 32.dp).clickable { viewModel.showDiff(path) }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(File(path).name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val parent = path.substringBeforeLast('/', "")
                        if (parent.isNotEmpty()) {
                            Text(parent, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                    Text(change.letter.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = changeColor(change, palette))
                }
            }
        }
    }

    signIn?.let { then ->
        GitSignInDialog(
            initial = credentials,
            onDismiss = { signIn = null },
            onSignIn = { creds ->
                credentials = creds
                signIn = null
                then(creds)
            },
        )
    }
}

/** User name and access token for HTTPS remotes. Kept in memory only while the project is open. */
@Composable
private fun GitSignInDialog(initial: GitCredentials?, onDismiss: () -> Unit, onSignIn: (GitCredentials) -> Unit) {
    var user by remember { mutableStateOf(initial?.username.orEmpty()) }
    var token by remember { mutableStateOf(initial?.password.orEmpty()) }
    NfDialog(
        title = "Sign in to the remote",
        subtitle = "Used for push and private repositories",
        onDismiss = onDismiss,
        maxWidth = 440.dp,
        actions = {
            DialogSecondaryButton("Cancel", onDismiss)
            DialogPrimaryButton("Continue", { onSignIn(GitCredentials(user.trim(), token.trim())) }, enabled = user.isNotBlank() && token.isNotBlank())
        },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NfTextField(user, { user = it }, label = "User name")
            NfTextField(
                token,
                { token = it },
                label = "Access token",
                supportingText = "A personal access token from GitHub, GitLab... It is never saved to disk.",
                password = true,
            )
        }
    }
}

/** Unified diff of one file, coloured like a code review. */
@Composable
fun DiffDialog(diff: DiffView, onOpen: () -> Unit, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    val palette = LocalAppPalette.current
    val lines = remember(diff.text) { diff.text.lines().filterNot { it.startsWith("diff --git") || it.startsWith("index ") } }
    var confirmDiscard by remember { mutableStateOf(false) }
    NfDialog(
        title = File(diff.path).name,
        subtitle = diff.path,
        onDismiss = onDismiss,
        actions = {
            if (diff.change != null) DialogSecondaryButton("Discard changes") { confirmDiscard = true }
            DialogSecondaryButton("Close", onDismiss)
            DialogPrimaryButton("Open file", onOpen)
        },
    ) {
        if (lines.all { it.isBlank() }) {
            Text("No differences to show.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(14.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp).background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
            items(lines) { line ->
                val (background, color) = when {
                    line.startsWith("+++") || line.startsWith("---") -> Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
                    line.startsWith("+") -> palette.success.copy(alpha = 0.14f) to palette.success
                    line.startsWith("-") -> palette.error.copy(alpha = 0.14f) to palette.error
                    line.startsWith("@@") -> palette.accent.copy(alpha = 0.10f) to palette.accent
                    else -> Color.Transparent to MaterialTheme.colorScheme.onSurface
                }
                Text(
                    line.ifEmpty { " " },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    color = color,
                    softWrap = false,
                    modifier = Modifier.fillMaxWidth().background(background).padding(horizontal = 10.dp, vertical = 1.dp),
                )
            }
        }
    }
    if (confirmDiscard) {
        NfConfirmDialog(
            title = "Discard changes?",
            message = "${diff.path} goes back to its last committed version. New files are deleted.",
            confirmLabel = "Discard",
            destructive = true,
            onConfirm = { confirmDiscard = false; onDiscard() },
            onDismiss = { confirmDiscard = false },
        )
    }
}

/** A search-field look-alike that opens "Search in project" (no room for another icon in the header). */
@Composable
private fun SearchEntry(onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).height(30.dp).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                "Search in project",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}
