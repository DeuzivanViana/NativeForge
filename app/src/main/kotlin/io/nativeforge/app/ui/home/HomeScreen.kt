package io.nativeforge.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import io.nativeforge.core.project.model.Project
import io.nativeforge.core.ui.component.CompactTopBar
import io.nativeforge.core.ui.component.DialogPrimaryButton
import io.nativeforge.core.ui.component.NfConfirmDialog
import io.nativeforge.core.ui.component.EmptyState
import io.nativeforge.core.ui.component.SectionHeader
import io.nativeforge.core.ui.component.ToolbarIconButton

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
                EmptyState(
                    icon = Icons.Filled.Code,
                    title = "No projects yet",
                    message = "Create a console, raylib, SDL3, SFML or OpenGL ES project, or clone one from a git repository.",
                    action = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { showNewProject = true }, shape = MaterialTheme.shapes.small) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("New project")
                            }
                            OutlinedButton(onClick = { showClone = true }, shape = MaterialTheme.shapes.small) {
                                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Clone")
                            }
                        }
                    },
                )
            } else {
                SectionHeader("Recent projects · ${state.projects.size}") {
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
                            onOpen = { onOpenProject(summary.project.name) },
                            onDelete = { pendingDelete = summary.project },
                        )
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }

    if (showNewProject) {
        NewProjectDialog(
            existingNames = state.projects.map { it.project.name }.toSet(),
            onDismiss = { showNewProject = false },
            onCreate = { name, template, options ->
                showNewProject = false
                viewModel.createProject(name, template, options, onOpenProject)
            },
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
            title = "Delete ${project.name}?",
            message = "The project folder and all its files are removed. This cannot be undone.",
            confirmLabel = "Delete",
            destructive = true,
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
            "NativeForge will download clang, clangd, clang-format, CMake, Ninja, Make and their libraries " +
                "(about 150 MB) from the Termux package repository (packages-cf.termux.dev) and run them on this device.\n\n" +
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
                    "Clang, CMake, Ninja and Make are downloaded once (about 150 MB) and run fully on your device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is ToolchainStatus.Installing -> {
                    Text(status.message, style = MaterialTheme.typography.bodySmall)
                    if (status.progress != null) {
                        LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                is ToolchainStatus.Failed -> Text(status.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                ToolchainStatus.Ready -> Unit
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
