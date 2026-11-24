package io.nativeforge.app.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.nativeforge.app.di.appContainer
import io.nativeforge.app.run.ProgramRunner
import io.nativeforge.core.build.diagnostics.Severity
import io.nativeforge.core.ui.component.CompactTopBar
import io.nativeforge.core.ui.component.DialogPrimaryButton
import io.nativeforge.core.ui.component.DialogSecondaryButton
import io.nativeforge.core.ui.component.NfDialog
import io.nativeforge.core.ui.component.NfTextField
import io.nativeforge.core.ui.component.ToolbarIconButton
import io.nativeforge.core.ui.theme.LocalDiagnosticColors
import io.nativeforge.app.intel.IntelStatus
import io.nativeforge.app.ui.workspace.commands.GoToLineDialog
import io.nativeforge.app.ui.workspace.commands.HoverDialog
import io.nativeforge.app.ui.workspace.commands.KeyboardShortcuts
import io.nativeforge.app.ui.workspace.commands.PickerDialog
import io.nativeforge.app.ui.workspace.commands.PickerItem
import io.nativeforge.app.ui.workspace.commands.RenameDialog
import io.nativeforge.app.ui.workspace.commands.WorkspaceCommand
import io.nativeforge.feature.assets.AssetViewerDialog
import io.nativeforge.feature.editor.CodeActions
import io.nativeforge.feature.editor.CodeEditor
import io.nativeforge.feature.editor.EditorAccessoryBar
import io.nativeforge.feature.editor.FindReplaceBar
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun WorkspaceScreen(
    projectName: String,
    onBack: () -> Unit,
    onOpenPackages: () -> Unit,
    onExport: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val container = appContainer()
    val viewModel: WorkspaceViewModel = viewModel(
        key = "workspace-$projectName",
        factory = viewModelFactory { initializer { WorkspaceViewModel(container, projectName) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val editorState by viewModel.editor.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var newFileDialog by rememberSaveable { mutableStateOf(false) }
    var findOpen by rememberSaveable { mutableStateOf(false) }
    var librariesOpen by rememberSaveable { mutableStateOf(false) }
    var buildSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var dockedExplorer by rememberSaveable { mutableStateOf(true) }
    var replaceMode by rememberSaveable { mutableStateOf(false) }
    var paletteOpen by remember { mutableStateOf(false) }
    var quickOpen by remember { mutableStateOf(false) }
    var goToLineOpen by remember { mutableStateOf(false) }
    var projectSearchOpen by rememberSaveable { mutableStateOf(false) }
    var renameFrom by remember { mutableStateOf<String?>(null) }
    var toggleExplorerRequest by remember { mutableStateOf(0) }
    val focusedEditor = if (state.splitOpen && state.splitFocused) viewModel.splitEditor else viewModel.editor
    val focusedState by focusedEditor.state.collectAsStateWithLifecycle()

    LaunchedEffect(settings.codeIntelligence, settings.clangTidy) {
        viewModel.applyIntelSettings(settings.codeIntelligence, settings.clangTidy)
    }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (settings.autoSave) viewModel.saveAll()
    }
    // Back from a graphical program: show why it closed, if it reported a start-up error.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.reportStartError(ProgramRunner.consumeStartError(context))
    }

    val build: (Boolean) -> Unit = { run ->
        viewModel.build(runAfterBuild = run) { project, artifact ->
            ProgramRunner.run(context, container, project, artifact)?.let(viewModel::showMessage)
        }
    }
    val leave = {
        if (settings.autoSave) viewModel.saveAll()
        onBack()
    }

    /** Runs a command from a keyboard shortcut, the command palette or a menu. */
    val runCommand: (WorkspaceCommand) -> Unit = { command ->
        val editor = viewModel.focusedEditor
        when (command) {
            WorkspaceCommand.COMMAND_PALETTE -> paletteOpen = true
            WorkspaceCommand.QUICK_OPEN -> quickOpen = true
            WorkspaceCommand.SAVE -> viewModel.saveAll()
            WorkspaceCommand.BUILD -> if (state.buildStatus != BuildStatus.RUNNING) build(false)
            WorkspaceCommand.RUN -> if (state.buildStatus != BuildStatus.RUNNING) build(true)
            WorkspaceCommand.CANCEL_BUILD -> if (state.buildStatus == BuildStatus.RUNNING) viewModel.cancelBuild()
            WorkspaceCommand.FIND -> { replaceMode = false; findOpen = true }
            WorkspaceCommand.REPLACE -> { replaceMode = true; findOpen = true }
            WorkspaceCommand.SEARCH_IN_PROJECT -> projectSearchOpen = true
            WorkspaceCommand.FIND_NEXT -> editor.findNext()
            WorkspaceCommand.FIND_PREVIOUS -> editor.findPrevious()
            WorkspaceCommand.GO_TO_LINE -> goToLineOpen = true
            WorkspaceCommand.COMPLETE -> editor.requestCompletion()
            WorkspaceCommand.GO_TO_DEFINITION -> viewModel.goToDefinition()
            WorkspaceCommand.RENAME -> renameFrom = editor.wordAtCaret()
            WorkspaceCommand.SHOW_HOVER -> viewModel.showHover()
            WorkspaceCommand.FORMAT -> viewModel.formatDocument()
            WorkspaceCommand.TOGGLE_COMMENT -> editor.toggleComment()
            WorkspaceCommand.DUPLICATE_LINE -> editor.duplicateLine()
            WorkspaceCommand.MOVE_LINE_UP -> editor.moveLines(down = false)
            WorkspaceCommand.MOVE_LINE_DOWN -> editor.moveLines(down = true)
            WorkspaceCommand.DELETE_LINE -> editor.deleteLine()
            WorkspaceCommand.NEXT_TAB -> viewModel.selectTab(1)
            WorkspaceCommand.PREVIOUS_TAB -> viewModel.selectTab(-1)
            WorkspaceCommand.CLOSE_TAB -> viewModel.closeActiveTab()
            WorkspaceCommand.NEW_FILE -> newFileDialog = true
            WorkspaceCommand.SPLIT_EDITOR -> viewModel.toggleSplit()
            WorkspaceCommand.FOCUS_OTHER_EDITOR -> viewModel.focusOtherEditor()
            WorkspaceCommand.TOGGLE_EXPLORER -> toggleExplorerRequest++
            WorkspaceCommand.TOGGLE_PANEL -> viewModel.togglePanel()
            WorkspaceCommand.TERMINAL -> ProgramRunner.openShell(context, container)
            WorkspaceCommand.ZOOM_IN, WorkspaceCommand.ZOOM_OUT -> container.settings.update {
                val delta = if (command == WorkspaceCommand.ZOOM_IN) 1f else -1f
                val range = io.nativeforge.app.settings.AppSettings.FONT_SIZES
                it.copy(editor = it.editor.copy(fontSizeSp = (it.editor.fontSizeSp + delta).coerceIn(range.first.toFloat(), range.last.toFloat())))
            }
            WorkspaceCommand.LIBRARIES -> librariesOpen = true
            WorkspaceCommand.BUILD_SETTINGS -> buildSettingsOpen = true
            WorkspaceCommand.PROGRAM_LOG -> viewModel.showProgramLog { ProgramRunner.readProgramLog() }
            WorkspaceCommand.EXPORT -> { viewModel.saveAll(); onExport() }
            WorkspaceCommand.PACKAGES -> onOpenPackages()
            WorkspaceCommand.SETTINGS -> onOpenSettings()
        }
    }
    val dialogOpen = paletteOpen || quickOpen || goToLineOpen || projectSearchOpen || renameFrom != null || newFileDialog ||
        librariesOpen || buildSettingsOpen || state.assetFile != null || state.diff != null || state.hoverText != null
    DisposableEffect(Unit) { onDispose { KeyboardShortcuts.handler = null } }
    KeyboardShortcuts.handler = handler@{ event ->
        // Dialogs handle their own keys (Enter, Esc, arrows).
        if (dialogOpen) return@handler false
        if (event.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE && findOpen && event.action == android.view.KeyEvent.ACTION_DOWN) {
            viewModel.focusedEditor.stopSearch(); findOpen = false
            return@handler true
        }
        val command = WorkspaceCommand.forKey(event) ?: return@handler false
        runCommand(command)
        true
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Tablets and landscape phones keep the explorer docked beside the editor.
    val wide = maxWidth >= 720.dp
    val toggleExplorer: () -> Unit = {
        if (wide) dockedExplorer = !dockedExplorer else scope.launch { if (drawerState.isOpen) drawerState.close() else drawerState.open() }
    }
    LaunchedEffect(toggleExplorerRequest) { if (toggleExplorerRequest > 0) toggleExplorer() }
    ModalNavigationDrawer(
        drawerState = drawerState,
        // Swiping right while scrolling code must not pull the explorer out: by default it
        // opens only from the toolbar button, and a swipe can still close it.
        gesturesEnabled = !wide && (drawerState.isOpen || settings.explorerSwipe),
        drawerContent = {
            if (!wide) {
                ModalDrawerSheet(
                    modifier = Modifier.widthIn(max = 300.dp),
                    drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    drawerShape = MaterialTheme.shapes.extraSmall,
                ) {
                    ExplorerPanel(
                        projectName = projectName,
                        state = state,
                        viewModel = viewModel,
                        onFileOpened = { scope.launch { drawerState.close() } },
                        modifier = Modifier.statusBarsPadding(),
                        onSearch = { projectSearchOpen = true; scope.launch { drawerState.close() } },
                    )
                }
            }
        },
    ) {
        Row(Modifier.fillMaxSize()) {
        if (wide && dockedExplorer) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(260.dp).fillMaxHeight()) {
                ExplorerPanel(
                    projectName = projectName,
                    state = state,
                    viewModel = viewModel,
                    onFileOpened = {},
                    modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                    onSearch = { projectSearchOpen = true },
                )
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                CompactTopBar(
                    title = projectName,
                    subtitle = state.activeFile?.let { File(it).name + if (it in state.dirtyFiles) " •" else "" },
                    navigation = {
                        ToolbarIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", leave)
                        ToolbarIconButton(Icons.Filled.FolderOpen, "Explorer", toggleExplorer, highlighted = wide && dockedExplorer)
                    },
                    actions = {
                        val accent = MaterialTheme.colorScheme.primary
                        ToolbarIconButton(Icons.AutoMirrored.Filled.Undo, "Undo", focusedEditor::undo, enabled = focusedState.canUndo, tint = accent)
                        ToolbarIconButton(Icons.AutoMirrored.Filled.Redo, "Redo", focusedEditor::redo, enabled = focusedState.canRedo, tint = accent)
                        ToolbarIconButton(Icons.Filled.Save, "Save all", viewModel::saveAll, enabled = state.dirtyFiles.isNotEmpty())
                        if (state.buildStatus == BuildStatus.RUNNING) {
                            ToolbarIconButton(Icons.Filled.Stop, "Cancel build", viewModel::cancelBuild, tint = LocalDiagnosticColors.current.error)
                        } else {
                            ToolbarIconButton(Icons.Filled.Build, "Build", { build(false) })
                            ToolbarIconButton(Icons.Filled.PlayArrow, "Build and run", { build(true) }, tint = LocalDiagnosticColors.current.success)
                        }
                        Box {
                            ToolbarIconButton(Icons.Filled.MoreVert, "More", { menuOpen = true })
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                val item: @Composable (String, () -> Unit) -> Unit = { text, action ->
                                    MenuItem(text) { menuOpen = false; action() }
                                }
                                item("Command palette (Ctrl+Shift+P)") { paletteOpen = true }
                                item("Go to file (Ctrl+P)") { quickOpen = true }
                                item("Find and replace") { replaceMode = true; findOpen = true }
                                item("Search in project (Ctrl+Shift+F)") { projectSearchOpen = true }
                                item("Format document") { viewModel.formatDocument() }
                                item("Go to definition") { viewModel.goToDefinition() }
                                item("Rename symbol") { renameFrom = viewModel.focusedEditor.wordAtCaret() }
                                item(if (state.splitOpen) "Close split editor" else "Split editor") { viewModel.toggleSplit() }
                                item("Libraries…") { librariesOpen = true }
                                item("Build settings…") { buildSettingsOpen = true }
                                item("New file") { newFileDialog = true }
                                item(if (state.panelVisible) "Hide output panel" else "Show output panel") { viewModel.togglePanel() }
                                item("Program log") { viewModel.showProgramLog { ProgramRunner.readProgramLog() } }
                                item("Terminal") { ProgramRunner.openShell(context, container) }
                                item("Packages") { onOpenPackages() }
                                item("Export APK") { viewModel.saveAll(); onExport() }
                                item("Settings") { onOpenSettings() }
                            }
                        }
                    },
                )
                if (state.openFiles.isNotEmpty()) {
                    EditorTabs(
                        openFiles = state.openFiles,
                        activeFile = state.activeFile,
                        dirtyFiles = state.dirtyFiles,
                        onSelect = { viewModel.openFile(File(it)) },
                        onClose = viewModel::closeFile,
                    )
                }
                if (findOpen) FindReplaceBar(focusedEditor, onClose = { findOpen = false }, startWithReplace = replaceMode)
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    CodeEditor(
                        controller = viewModel.editor,
                        preferences = settings.editor,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    if (state.splitOpen) {
                        VerticalDivider(color = if (state.splitFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            SplitHeader(
                                file = state.splitFile,
                                dirty = state.splitFile in state.dirtyFiles,
                                focused = state.splitFocused,
                                onClose = viewModel::toggleSplit,
                            )
                            CodeEditor(
                                controller = viewModel.splitEditor,
                                preferences = settings.editor,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                        }
                    }
                }
                if (settings.showEditorToolbar || settings.showSymbolRow) {
                    EditorAccessoryBar(
                        focusedEditor,
                        onFind = { replaceMode = false; findOpen = true },
                        showActions = settings.showEditorToolbar,
                        showSymbols = settings.showSymbolRow,
                        codeActions = CodeActions(
                            onDefinition = viewModel::goToDefinition,
                            onRename = { renameFrom = viewModel.focusedEditor.wordAtCaret() },
                            onFormat = { viewModel.formatDocument() },
                        ),
                    )
                }
                if (state.panelVisible) {
                    BuildPanel(
                        log = state.log,
                        diagnostics = state.diagnostics,
                        onDiagnosticClick = viewModel::openDiagnostic,
                        onClose = viewModel::togglePanel,
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                    )
                }
                StatusBar(
                    state = state,
                    line = focusedState.line,
                    column = focusedState.column,
                    tabWidth = settings.editor.tabWidth,
                    onTogglePanel = viewModel::togglePanel,
                )
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp))
        }
        }
    }
    }

    state.assetFile?.let { file -> AssetViewerDialog(file, onDismiss = viewModel::closeAssetViewer) }

    state.hoverText?.let { text -> HoverDialog(text, onDismiss = viewModel::dismissHover) }

    renameFrom?.let { current ->
        RenameDialog(current, onRename = { name -> renameFrom = null; viewModel.rename(name) }, onDismiss = { renameFrom = null })
    }

    if (projectSearchOpen) {
        val searchState by viewModel.search.collectAsStateWithLifecycle()
        ProjectSearchDialog(
            state = searchState,
            onSearch = viewModel::searchInProject,
            onReplacementChange = viewModel::setSearchReplacement,
            onReplaceAll = viewModel::replaceInProject,
            onOpen = { file, match -> projectSearchOpen = false; viewModel.openSearchMatch(file, match) },
            onDismiss = { projectSearchOpen = false },
        )
    }

    if (goToLineOpen) {
        val lineCount = remember { viewModel.focusedEditor.document?.text()?.count { it == '\n' }?.plus(1) ?: 1 }
        GoToLineDialog(
            lineCount = lineCount,
            onGo = { line, column -> goToLineOpen = false; viewModel.focusedEditor.goTo(line, column); viewModel.focusedEditor.focus() },
            onDismiss = { goToLineOpen = false },
        )
    }

    if (paletteOpen) {
        val items = remember { WorkspaceCommand.entries.filter { it != WorkspaceCommand.COMMAND_PALETTE }.map { PickerItem(it, it.title, it.shortcutLabel) } }
        PickerDialog(
            title = "Commands",
            placeholder = "Type a command",
            items = items,
            onPick = { command -> paletteOpen = false; runCommand(command) },
            onDismiss = { paletteOpen = false },
        )
    }

    if (quickOpen) {
        val items = remember(state.files) {
            state.files.filterNot { it.isDirectory }.map { PickerItem(it.file, it.relativePath, "") }
        }
        PickerDialog(
            title = "Go to file",
            placeholder = "File name",
            items = items,
            onPick = { file -> quickOpen = false; viewModel.openFile(file) },
            onDismiss = { quickOpen = false },
        )
    }

    if (buildSettingsOpen) {
        state.project?.let { project ->
            BuildSettingsDialog(project, onSave = viewModel::updateConfig, onDismiss = { buildSettingsOpen = false })
        }
    }

    state.diff?.let { diff ->
        DiffDialog(
            diff = diff,
            onOpen = {
                viewModel.closeDiff()
                state.project?.let { viewModel.openFile(File(it.root, diff.path)) }
            },
            onDiscard = { viewModel.discard(diff.path) },
            onDismiss = viewModel::closeDiff,
        )
    }

    if (librariesOpen) {
        state.project?.let { project ->
            LibrariesDialog(project.config, viewModel, onDismiss = { librariesOpen = false })
        }
    }

    if (newFileDialog) {
        var name by rememberSaveable { mutableStateOf("src/") }
        NfDialog(
            title = "New file",
            onDismiss = { newFileDialog = false },
            maxWidth = 440.dp,
            actions = {
                DialogSecondaryButton("Cancel") { newFileDialog = false }
                DialogPrimaryButton("Create", onClick = { newFileDialog = false; viewModel.createFile(name) }, enabled = name.isNotBlank())
            },
        ) {
            NfTextField(
                value = name,
                onValueChange = { name = it },
                label = "Path in project",
                placeholder = "src/player.cpp",
                supportingText = "Folders are created as needed. Sources go in src/, images and sounds in assets/.",
                monospace = true,
                modifier = Modifier.padding(14.dp),
            )
        }
    }
}

/** Title row of the split editor. */
@Composable
private fun SplitHeader(file: String?, dirty: Boolean, focused: Boolean, onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().height(28.dp).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (file?.let { File(it).name } ?: "Open a file here (tap this side, then pick a file)") + if (dirty) " ●" else "",
                style = MaterialTheme.typography.labelMedium,
                color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.size(28.dp).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, contentDescription = "Close split editor", modifier = Modifier.size(14.dp))
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        onClick = onClick,
        modifier = Modifier.height(36.dp),
    )
}

/** Slim editor tabs (32dp) with a close button and an accent line under the active tab. */
@Composable
private fun EditorTabs(
    openFiles: List<String>,
    activeFile: String?,
    dirtyFiles: Set<String>,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().height(32.dp).horizontalScroll(rememberScrollState())) {
            openFiles.forEach { path ->
                val active = path == activeFile
                Column(
                    Modifier
                        .fillMaxHeight()
                        .background(if (active) MaterialTheme.colorScheme.background else Color.Transparent)
                        .clickable { onSelect(path) },
                ) {
                    Row(Modifier.weight(1f).padding(start = 10.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            File(path).name,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (path in dirtyFiles) {
                            Text(" ●", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Box(Modifier.size(26.dp).clickable { onClose(path) }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Close, contentDescription = "Close ${File(path).name}", modifier = Modifier.size(14.dp))
                        }
                    }
                    Box(
                        Modifier.fillMaxWidth().height(2.dp)
                            .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent),
                    )
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** VS Code-style status bar: build state and problem counts on the left, caret and file info on the right. */
@Composable
private fun StatusBar(state: WorkspaceUiState, line: Int, column: Int, tabWidth: Int, onTogglePanel: () -> Unit) {
    val diagnostics = LocalDiagnosticColors.current
    // Live clangd results for the open file when available, otherwise the last build's.
    val live = state.intelStatus == IntelStatus.Ready
    val errors = if (live) state.liveErrors else state.diagnostics.count { it.severity == Severity.ERROR }
    val warnings = if (live) state.liveWarnings else state.diagnostics.count { it.severity == Severity.WARNING }
    val background = when (state.buildStatus) {
        BuildStatus.FAILED -> diagnostics.error.copy(alpha = 0.25f)
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    Surface(color = background, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Row(
            Modifier.fillMaxWidth().height(24.dp).clickable(onClick = onTogglePanel).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.git?.let { git ->
                StatusText("⎇ ${git.branch ?: "main"}" + if (git.hasChanges) "*" else "")
                Spacer(Modifier.width(10.dp))
            }
            when (state.buildStatus) {
                BuildStatus.RUNNING -> {
                    CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp)
                    StatusText(" Building…")
                }
                BuildStatus.SUCCEEDED -> StatusText("✓ Build succeeded")
                BuildStatus.FAILED -> StatusText("✗ Build failed")
                BuildStatus.IDLE -> StatusText("Ready")
            }
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Filled.Error, contentDescription = "Errors", modifier = Modifier.size(12.dp))
            StatusText(" $errors  ")
            Icon(Icons.Filled.Warning, contentDescription = "Warnings", modifier = Modifier.size(12.dp))
            StatusText(" $warnings")
            Spacer(Modifier.width(12.dp))
            StatusText(
                when (val intel = state.intelStatus) {
                    IntelStatus.Ready -> "clangd ✓"
                    IntelStatus.Starting -> "clangd…"
                    is IntelStatus.Unavailable -> "clangd ✗"
                    IntelStatus.Off -> ""
                },
            )
            Spacer(Modifier.weight(1f))
            StatusText("Ln $line, Col $column")
            Spacer(Modifier.width(12.dp))
            StatusText("Spaces: $tabWidth")
            Spacer(Modifier.width(12.dp))
            StatusText(languageOf(state.activeFile))
        }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1)
}

private fun languageOf(path: String?): String {
    val name = path?.substringAfterLast('/') ?: return ""
    if (name == "CMakeLists.txt") return "CMake"
    return when (name.substringAfterLast('.', "").lowercase()) {
        "c" -> "C"
        "h" -> "C/C++ Header"
        "cpp", "cc", "cxx", "c++" -> "C++"
        "hpp", "hh", "hxx", "inl" -> "C++ Header"
        "cmake" -> "CMake"
        "json" -> "JSON"
        "md" -> "Markdown"
        else -> "Plain Text"
    }
}
