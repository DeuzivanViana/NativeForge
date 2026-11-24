package io.nativeforge.app.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.nativeforge.app.di.appContainer
import io.nativeforge.app.run.ProgramRunner
import io.nativeforge.app.storage.DeviceFolders
import io.nativeforge.core.project.model.Project
import io.nativeforge.core.ui.component.CompactTopBar
import io.nativeforge.core.ui.component.DialogPrimaryButton
import io.nativeforge.core.ui.component.ProgressBar
import io.nativeforge.core.ui.component.NfConfirmDialog
import io.nativeforge.core.ui.component.EmptyState
import io.nativeforge.core.ui.component.SectionHeader
import io.nativeforge.core.ui.component.ToolbarIconButton
import java.io.File

@Composable
fun HomeScreen(
    onOpenProject: (String) -> Unit,
    onOpenPackages: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val container = appContainer()
    val viewModel: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(container) } })
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showNewProject by rememberSaveable { mutableStateOf(false) }
    var showClone by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Project?>(null) }

    // Folders of the device: the system picker, the "Files and media" permission and the
    // copy fallback for folders that cannot be built in place.
    var pickPurpose by rememberSaveable { mutableStateOf(FolderPurpose.OPEN_PROJECT) }
    var accessPurpose by remember { mutableStateOf<FolderPurpose?>(null) }
    var pendingCopy by remember { mutableStateOf<Uri?>(null) }
    var newProjectParent by rememberSaveable { mutableStateOf<String?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = DeviceFolders.pathOf(context, uri)?.takeIf { DeviceFolders.hasStorageAccess(context) }
        when (pickPurpose) {
            FolderPurpose.OPEN_PROJECT -> if (path != null) viewModel.openFolder(path, onOpenProject) else pendingCopy = uri
            FolderPurpose.NEW_PROJECT_LOCATION -> if (path != null) {
                newProjectParent = path.path
            } else {
                viewModel.showMessage("Choose a folder on the phone's storage or SD card, and allow access to files")
            }
        }
    }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        folderPicker.launch(null)
    }
    val pickFolder: (FolderPurpose) -> Unit = { purpose ->
        pickPurpose = purpose
        if (DeviceFolders.hasStorageAccess(context)) folderPicker.launch(null) else accessPurpose = purpose
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            CompactTopBar(
                title = "NativeForge",
                subtitle = "C/C++ on Android",
                actions = {
                    ToolbarIconButton(Icons.Filled.Add, "New project", { showNewProject = true }, tint = MaterialTheme.colorScheme.primary)
                    ToolbarIconButton(Icons.Filled.FolderOpen, "Open folder", { pickFolder(FolderPurpose.OPEN_PROJECT) })
                    ToolbarIconButton(Icons.Filled.Download, "Clone repository", { showClone = true })
                    ToolbarIconButton(Icons.Filled.Terminal, "Terminal", { ProgramRunner.openShell(context, container) })
                    ToolbarIconButton(Icons.Filled.Inventory2, "Packages", onOpenPackages)
                    ToolbarIconButton(Icons.Filled.Settings, "Settings", onOpenSettings)
                },
            )
            var consentOpen by remember { mutableStateOf(false) }
            ToolchainBanner(state.toolchain, onInstall = { consentOpen = true })
            if (consentOpen) ToolchainConsentDialog(onAccept = { consentOpen = false; viewModel.installToolchain() }, onDecline = { consentOpen = false })
            if (state.projects.isEmpty()) {
                NoProjects(
                    onNewProject = { showNewProject = true },
                    onOpenFolder = { pickFolder(FolderPurpose.OPEN_PROJECT) },
                    onClone = { showClone = true },
                )
            } else {
                SectionHeader("Recent projects · ${state.projects.size}") {
                    ToolbarIconButton(Icons.Filled.FolderOpen, "Open folder", { pickFolder(FolderPurpose.OPEN_PROJECT) })
                    ToolbarIconButton(Icons.Filled.Download, "Clone repository", { showClone = true })
                    ToolbarIconButton(Icons.Filled.Add, "New project", { showNewProject = true })
                }
                LazyColumn(
                    Modifier.fillMaxSize().navigationBarsPadding(),
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.projects, key = { it.project.root.path }) { summary ->
                        ProjectCard(
                            summary = summary,
                            onOpen = { onOpenProject(summary.project.key) },
                            onDelete = { pendingDelete = summary.project },
                        )
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }

    val workspaceNames = state.projects.filterNot { it.project.isExternal }.map { it.project.name }.toSet()
    if (showNewProject) {
        NewProjectDialog(
            existingNames = workspaceNames,
            location = newProjectParent?.let(::File),
            onChooseLocation = { pickFolder(FolderPurpose.NEW_PROJECT_LOCATION) },
            onResetLocation = { newProjectParent = null },
            onDismiss = { showNewProject = false },
            onCreate = { name, template, options, parent ->
                showNewProject = false
                viewModel.createProject(name, template, options, parent, onOpenProject)
            },
        )
    }

    accessPurpose?.let { purpose ->
        StorageAccessDialog(
            purpose = purpose,
            onAllow = { accessPurpose = null; permissionRequest.launch(DeviceFolders.PERMISSIONS) },
            onCopyInstead = { accessPurpose = null; folderPicker.launch(null) },
            onDismiss = { accessPurpose = null },
        )
    }

    pendingCopy?.let { uri ->
        val folderName = remember(uri) { DeviceFolders.nameOf(context, uri) ?: "Imported" }
        ImportCopyDialog(
            folderName = folderName,
            onPhoneStorage = DeviceFolders.pathOf(context, uri) != null,
            onImport = {
                pendingCopy = null
                val name = projectNameForCopy(folderName, workspaceNames)
                viewModel.importCopy({ target -> DeviceFolders.copyTree(context, uri, target) }, name, onOpenProject)
            },
            onOpenSettings = { pendingCopy = null; context.startActivity(DeviceFolders.appSettingsIntent(context)) },
            onDismiss = { pendingCopy = null },
        )
    }

    if (showClone || state.clone != null) {
        CloneDialog(
            existingNames = state.projects.map { it.project.name }.toSet(),
            state = state.clone,
            onClone = { url, name, branch, credentials ->
                viewModel.cloneRepository(url, name, branch, credentials) { cloned ->
                    showClone = false
                    onOpenProject(cloned)
                }
            },
            onCancel = viewModel::cancelClone,
            onDismiss = { showClone = false; viewModel.dismissClone() },
        )
    }

    pendingDelete?.let { project ->
        NfConfirmDialog(
            title = if (project.isExternal) "Remove ${project.name} from the list?" else "Delete ${project.name}?",
            message = if (project.isExternal) {
                "The folder ${project.root.path} and its files stay on your phone; only NativeForge's build files are removed. Open it again at any time."
            } else {
                "The project folder and all its files are removed. This cannot be undone."
            },
            confirmLabel = if (project.isExternal) "Remove" else "Delete",
            destructive = !project.isExternal,
            onConfirm = { viewModel.deleteProject(project); pendingDelete = null },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * Explicit, opt-in consent before downloading executables (F-Droid inclusion policy):
 * the programs come from the Termux repository, not from the app store.
 */
@Composable
private fun ToolchainConsentDialog(onAccept: () -> Unit, onDecline: () -> Unit) {
    io.nativeforge.core.ui.component.NfDialog(
        title = "Download the compiler toolchain?",
        onDismiss = onDecline,
        maxWidth = 480.dp,
        actions = {
            io.nativeforge.core.ui.component.DialogSecondaryButton("Not now", onDecline)
            DialogPrimaryButton("Download", onAccept)
        },
    ) {
        Text(
            "NativeForge will download clang, clangd, clang-format, CMake, Ninja, Make, their libraries and a terminal " +
                "with bash, coreutils, nano and curl (about 170 MB) from the Termux package repository (packages-cf.termux.dev) and run them on this device.\n\n" +
                "These programs are free software, but they are not built or checked by F-Droid or by the store you " +
                "installed NativeForge from. Downloading them bypasses those checks. Libraries you add to a project later " +
                "are downloaded the same way.\n\nYou can keep using the editor without them; building and running needs them.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
private fun ToolchainBanner(status: ToolchainStatus, onInstall: () -> Unit) {
    if (status == ToolchainStatus.Ready) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Build, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Compiler toolchain", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                when (status) {
                    is ToolchainStatus.Missing -> DialogPrimaryButton("Install", onInstall)
                    is ToolchainStatus.Failed -> DialogPrimaryButton("Retry", onInstall)
                    else -> Unit
                }
            }
            when (status) {
                is ToolchainStatus.Missing -> Text(
                    "Clang, CMake, Ninja, Make and bash are downloaded once (about 170 MB) and run fully on your device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is ToolchainStatus.Installing -> {
                    Text(status.message, style = MaterialTheme.typography.bodySmall)
                    ProgressBar(status.progress, Modifier.fillMaxWidth())
                }
                is ToolchainStatus.Failed -> Text(status.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                ToolchainStatus.Ready -> Unit
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Shown while there are no projects: what NativeForge does and the three ways to start. */
@Composable
internal fun NoProjects(onNewProject: () -> Unit, onOpenFolder: () -> Unit, onClone: () -> Unit) {
    EmptyState(
        icon = Icons.Filled.Code,
        title = "No projects yet",
        message = "Create a console, LVGL, raylib, SDL or OpenGL ES project, open a folder from your phone, " +
            "or clone a git repository.",
        action = {
            // Wraps to a second line on narrow screens instead of squeezing the labels.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = onNewProject, shape = MaterialTheme.shapes.small) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New project", maxLines = 1, softWrap = false)
                }
                OutlinedButton(onClick = onOpenFolder, shape = MaterialTheme.shapes.small) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open folder", maxLines = 1, softWrap = false)
                }
                OutlinedButton(onClick = onClone, shape = MaterialTheme.shapes.small) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clone", maxLines = 1, softWrap = false)
                }
            }
        },
    )
}
