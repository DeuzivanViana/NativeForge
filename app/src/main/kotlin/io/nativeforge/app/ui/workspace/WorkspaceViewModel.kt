package io.nativeforge.app.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.nativeforge.app.di.AppContainer
import io.nativeforge.app.intel.CodeIntelService
import io.nativeforge.app.intel.IntelStatus
import io.nativeforge.core.codeintel.ClangFormatter
import io.nativeforge.core.codeintel.ClangdSession
import io.nativeforge.core.codeintel.CodeIntelOptions
import io.nativeforge.core.lsp.DiagnosticSeverity
import io.nativeforge.core.lsp.LspDiagnostic
import io.nativeforge.core.assets.AssetKinds
import io.nativeforge.core.build.diagnostics.Diagnostic
import io.nativeforge.core.build.model.BuildArtifact
import io.nativeforge.core.build.model.BuildEvent
import io.nativeforge.core.build.model.BuildRequest
import io.nativeforge.core.common.Outcome
import io.nativeforge.core.common.SafeFiles
import io.nativeforge.core.common.process.OutputStream
import io.nativeforge.core.packages.model.InstallEvent
import io.nativeforge.core.project.model.Project
import io.nativeforge.core.project.model.ProjectConfig
import io.nativeforge.core.project.search.ProjectSearch
import io.nativeforge.core.project.tree.ProjectTree
import io.nativeforge.core.project.tree.ProjectWatcher
import io.nativeforge.core.project.search.SearchMatch
import io.nativeforge.core.project.search.SearchQuery
import io.nativeforge.core.project.search.SearchResult
import io.nativeforge.core.vcs.FileChange
import io.nativeforge.core.vcs.GitCredentials
import io.nativeforge.core.vcs.GitException
import io.nativeforge.core.vcs.GitIdentity
import io.nativeforge.core.vcs.GitStatus
import io.nativeforge.feature.editor.CodeEditorController
import io.nativeforge.feature.editor.EditorDocument
import io.nativeforge.feature.editor.highlight.HighlightMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A row of the project file tree. [relativePath] uses '/' separators, as git does. */
typealias FileNode = io.nativeforge.core.project.tree.FileNode

/** A file's changes shown in the diff viewer. */
data class DiffView(val path: String, val change: FileChange?, val text: String)

/** "Search in project": the last query, its results and the replacement text. */
data class ProjectSearchState(
    val query: SearchQuery = SearchQuery(""),
    val replacement: String = "",
    val running: Boolean = false,
    val result: SearchResult = SearchResult.EMPTY,
    /** Invalid regular expression, or null. */
    val error: String? = null,
)

data class LogLine(val text: String, val isError: Boolean)

enum class BuildStatus { IDLE, RUNNING, SUCCEEDED, FAILED }

data class WorkspaceUiState(
    val project: Project? = null,
    val files: List<FileNode> = emptyList(),
    val openFiles: List<String> = emptyList(),
    val activeFile: String? = null,
    val dirtyFiles: Set<String> = emptySet(),
    val buildStatus: BuildStatus = BuildStatus.IDLE,
    val diagnostics: List<Diagnostic> = emptyList(),
    val panelVisible: Boolean = false,
    val message: String? = null,
    /** Folders open in the explorer, as relative paths. */
    val expandedDirs: Set<String> = setOf("src", "include", "assets"),
    /** Null when the project is not a git repository. */
    val git: GitStatus? = null,
    /** A git operation in progress ("Pulling…"), or null. */
    val gitBusy: String? = null,
    val diff: DiffView? = null,
    /** File shown in the asset viewer (image, sound, 3D model, font), or null. */
    val assetFile: File? = null,
    /** File open in the right-hand split editor, or null when the editor is not split. */
    val splitFile: String? = null,
    val splitOpen: Boolean = false,
    /** True when the split editor has the focus (commands and opened files go there). */
    val splitFocused: Boolean = false,
    /** Live clangd error/warning counts for the active file. */
    val liveErrors: Int = 0,
    val liveWarnings: Int = 0,
    val intelStatus: IntelStatus = IntelStatus.Off,
    /** Text shown by "Show type and documentation", or null. */
    val hoverText: String? = null,
)

class WorkspaceViewModel(
    private val container: AppContainer,
    private val projectName: String,
) : ViewModel() {

    val editor = CodeEditorController()

    /** Right-hand editor of the split view. */
    val splitEditor = CodeEditorController()
    private val documents = LinkedHashMap<String, EditorDocument>()
    val intel = CodeIntelService(viewModelScope)
    private val formatter get() = ClangFormatter(container.toolchain.clangFormat, container.toolchain.environment())

    /** The editor that has the focus: commands such as format or rename act on it. */
    val focusedEditor: CodeEditorController
        get() = if (_state.value.splitOpen && _state.value.splitFocused) splitEditor else editor
    private val _state = MutableStateFlow(WorkspaceUiState())
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()
    private var buildJob: Job? = null

    /**
     * Build output, apart from [state] so that a fast build only recomposes the output panel,
     * not the whole workspace. Lines are batched (see [appendLog]).
     */
    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    val log: StateFlow<List<LogLine>> = _log.asStateFlow()
    private val pendingLog = ArrayList<LogLine>()
    private var logFlush: Job? = null
    private val _search = MutableStateFlow(ProjectSearchState())
    val search: StateFlow<ProjectSearchState> = _search.asStateFlow()
    private var searchJob: Job? = null
    private var lastArtifact: BuildArtifact? = null

    init {
        val onChanged: (EditorDocument) -> Unit = { document ->
            _state.update { it.copy(dirtyFiles = it.dirtyFiles + document.path) }
            intel.textChanged(document.path) { document.text() }
            scheduleAutoSave(document.path)
        }
        editor.onContentChanged = onChanged
        splitEditor.onContentChanged = onChanged
        editor.onFocused = { _state.update { it.copy(splitFocused = false) }; refreshLiveCounts() }
        splitEditor.onFocused = { _state.update { it.copy(splitFocused = true) }; refreshLiveCounts() }
        viewModelScope.launch {
            intel.diagnostics.collect { all ->
                editor.document?.path?.let { path -> editor.setDiagnostics(path, all[path].orEmpty()) }
                splitEditor.document?.path?.let { path -> splitEditor.setDiagnostics(path, all[path].orEmpty()) }
                refreshLiveCounts()
            }
        }
        viewModelScope.launch {
            intel.status.collect { status ->
                val ready = status == IntelStatus.Ready
                editor.intelligence = if (ready) intel else null
                splitEditor.intelligence = if (ready) intel else null
                _state.update { it.copy(intelStatus = status) }
            }
        }
        viewModelScope.launch {
            when (val result = container.projectRepository.open(projectName)) {
                is Outcome.Success -> {
                    _state.update { it.copy(project = result.value) }
                    refreshFiles()
                    startCodeIntelligence()
                    defaultFile(result.value)?.let(::openFile)
                    watchFiles(result.value)
                }
                is Outcome.Failure -> showMessage(result.error.message)
            }
        }
    }

    /** Files made outside the explorer (terminal, git, a Makefile) show up on their own. */
    private fun watchFiles(project: Project) {
        viewModelScope.launch {
            runCatching {
                ProjectWatcher(project.root).changes().flowOn(Dispatchers.IO).collect { refreshFiles() }
            }
        }
    }

    fun refreshFiles() {
        val project = _state.value.project ?: return
        viewModelScope.launch {
            val expanded = _state.value.expandedDirs
            val nodes = withContext(Dispatchers.IO) { ProjectTree.list(project.root, expanded) }
            _state.update { it.copy(files = nodes) }
            refreshGit()
        }
    }

    fun toggleDirectory(relativePath: String) {
        val opening = relativePath !in _state.value.expandedDirs
        _state.update { it.copy(expandedDirs = if (opening) it.expandedDirs + relativePath else it.expandedDirs - relativePath) }
        // The explorer reads folders when they open (see ProjectTree).
        if (opening) refreshFiles()
    }

    /** Every file of the project, for "Go to file" (the explorer only lists open folders). */
    suspend fun allFiles(): List<FileNode> {
        val project = _state.value.project ?: return emptyList()
        return withContext(Dispatchers.IO) { ProjectTree.listAll(project.root).filterNot { it.isDirectory } }
    }

    fun collapseAll() = _state.update { it.copy(expandedDirs = emptySet()) }

    /** Starts clangd for the project when enabled in Settings and installed. */
    private fun startCodeIntelligence() {
        val project = _state.value.project ?: return
        val settings = container.settings.settings.value
        intel.stop()
        if (!settings.codeIntelligence) return
        val request = BuildRequest(project, container.toolchain, dependencies = container.packageManager.nativeDependencies(project.config.packages))
        // Threads and the background index follow the cores and the free memory.
        intel.start(request, CodeIntelOptions.forDevice(clangTidy = settings.clangTidy))
    }

    private var intelSettings: Pair<Boolean, Boolean>? = null

    /** Settings changed (code intelligence or clang-tidy toggled): restart clangd if needed. */
    fun applyIntelSettings(enabled: Boolean, clangTidy: Boolean) {
        val key = enabled to clangTidy
        if (intelSettings == null) { intelSettings = key; return }
        if (intelSettings == key) return
        intelSettings = key
        startCodeIntelligence()
    }

    /** New include folders (libraries changed): restart clangd. */
    fun restartCodeIntelligence() = startCodeIntelligence()

    fun openFile(file: File, inSplit: Boolean = _state.value.splitOpen && _state.value.splitFocused) {
        if (file.isDirectory) return
        if (AssetKinds.of(file) != null && !inSplit) {
            _state.update { it.copy(assetFile = file) }
            return
        }
        if (inSplit) return openInSplit(file)
        if (file.path == _state.value.splitFile) {
            // One document cannot be shown by both editors (they would share the caret).
            splitEditor.focus()
            return
        }
        viewModelScope.launch {
            val document = documents[file.path] ?: withContext(Dispatchers.IO) {
                when {
                    !file.isFile -> null
                    escapesProject(file) -> {
                        showMessage("${file.name} is a link to a file outside the project and was not opened"); null
                    }
                    file.length() > MAX_EDITABLE_BYTES -> { showMessage("${file.name} is too large to edit (over 16 MB)"); null }
                    isBinary(file) -> { showMessage("${file.name} is a binary file"); null }
                    else -> EditorDocument(file.path, file.readText())
                }
            }?.also { documents[file.path] = it } ?: return@launch
            editor.open(document)
            when (document.highlightMode) {
                HighlightMode.FULL -> Unit
                HighlightMode.VIEWPORT -> showMessage("${file.name} is large: colors only around the screen, no word completion")
                HighlightMode.OFF -> showMessage("${file.name} is very large: shown without colors")
            }
            intel.opened(document.path) { document.text() }
            editor.setDiagnostics(document.path, intel.diagnostics.value[document.path].orEmpty())
            _state.update {
                it.copy(
                    openFiles = if (file.path in it.openFiles) it.openFiles else it.openFiles + file.path,
                    activeFile = file.path,
                )
            }
            refreshLiveCounts()
        }
    }

    private suspend fun loadDocument(file: File): EditorDocument? = documents[file.path] ?: withContext(Dispatchers.IO) {
        when {
            !file.isFile || escapesProject(file) || file.length() > MAX_EDITABLE_BYTES || isBinary(file) -> null
            else -> EditorDocument(file.path, file.readText())
        }
    }?.also { documents[file.path] = it }

    fun closeAssetViewer() = _state.update { it.copy(assetFile = null) }

    // ---- Split editor ----

    /** Shows or hides the right-hand editor; it starts with the next open tab. */
    fun toggleSplit() {
        val state = _state.value
        if (state.splitOpen) {
            state.splitFile?.let { path -> viewModelScope.launch { save(path) } }
            splitEditor.close()
            _state.update { it.copy(splitOpen = false, splitFile = null, splitFocused = false) }
            editor.focus()
            return
        }
        val candidate = state.openFiles.firstOrNull { it != state.activeFile }
        _state.update { it.copy(splitOpen = true, splitFocused = true) }
        if (candidate != null) openInSplit(File(candidate)) else splitEditor.close()
    }

    fun focusOtherEditor() {
        if (!_state.value.splitOpen) return
        if (_state.value.splitFocused) editor.focus() else splitEditor.focus()
        _state.update { it.copy(splitFocused = !it.splitFocused) }
    }

    private fun openInSplit(file: File) {
        if (file.path == _state.value.activeFile) {
            // Move the file to the split pane and show another tab on the left.
            val other = _state.value.openFiles.firstOrNull { it != file.path && it != _state.value.splitFile }
            if (other == null) return showMessage("Open another file to show two files side by side")
            openFile(File(other), inSplit = false)
        }
        viewModelScope.launch {
            val document = loadDocument(file) ?: return@launch showMessage("${file.name} cannot be opened in the editor")
            _state.value.splitFile?.takeIf { it != file.path }?.let { save(it) }
            splitEditor.open(document)
            intel.opened(document.path) { document.text() }
            splitEditor.setDiagnostics(document.path, intel.diagnostics.value[document.path].orEmpty())
            _state.update {
                it.copy(
                    splitOpen = true,
                    splitFile = file.path,
                    openFiles = if (file.path in it.openFiles) it.openFiles else it.openFiles + file.path,
                )
            }
        }
    }

    // ---- Tabs ----

    fun selectTab(offset: Int) {
        val files = _state.value.openFiles
        if (files.size < 2) return
        val current = files.indexOf(_state.value.activeFile).coerceAtLeast(0)
        var index = Math.floorMod(current + offset, files.size)
        if (files[index] == _state.value.splitFile) index = Math.floorMod(index + offset.coerceIn(-1, 1), files.size)
        openFile(File(files[index]), inSplit = false)
    }

    fun closeActiveTab() {
        val state = _state.value
        if (state.splitOpen && state.splitFocused) return toggleSplit()
        state.activeFile?.let(::closeFile)
    }

    // ---- Code intelligence ----

    private fun refreshLiveCounts() {
        val path = focusedEditor.document?.path
        val list: List<LspDiagnostic> = path?.let { intel.diagnostics.value[it] }.orEmpty()
        _state.update {
            it.copy(
                liveErrors = list.count { d -> d.severity == DiagnosticSeverity.ERROR },
                liveWarnings = list.count { d -> d.severity == DiagnosticSeverity.WARNING },
            )
        }
    }

    /** F12: opens the definition of the symbol under the caret. */
    fun goToDefinition() {
        val controller = focusedEditor
        val document = controller.document ?: return
        val position = controller.caretPosition() ?: return
        if (!ClangdSession.supports(File(document.path))) return showMessage("Go to definition works in C and C++ files")
        if (intel.status.value != IntelStatus.Ready) return showMessage(intelUnavailableMessage())
        viewModelScope.launch {
            val target = intel.definition(document.path, document.text(), position).firstOrNull()
            val file = target?.file ?: return@launch showMessage("No definition found for '${controller.wordAtCaret()}'")
            val inProject = _state.value.project?.root?.let { file.absolutePath.startsWith(it.absolutePath + File.separator) } == true
            if (!inProject) {
                // Library or system header: open read-only would need more UI; show where it is.
                showMessage("Defined in ${file.name}:${target.range.start.line + 1} (outside the project)")
                return@launch
            }
            if (file.path == controller.document?.path) {
                controller.goTo(target.range.start.line + 1, target.range.start.character + 1)
            } else {
                openFile(file, inSplit = controller === splitEditor)
                kotlinx.coroutines.delay(120)
                focusedEditor.goTo(target.range.start.line + 1, target.range.start.character + 1)
            }
        }
    }

    /** Ctrl+Shift+I: shows the type and documentation of the symbol under the caret. */
    fun showHover() {
        val controller = focusedEditor
        val document = controller.document ?: return
        val position = controller.caretPosition() ?: return
        if (intel.status.value != IntelStatus.Ready) return showMessage(intelUnavailableMessage())
        viewModelScope.launch {
            val text = intel.hover(document.path, document.text(), position)
            if (text.isNullOrBlank()) showMessage("Nothing to show here") else _state.update { it.copy(hoverText = text.trim()) }
        }
    }

    fun dismissHover() = _state.update { it.copy(hoverText = null) }

    /** F2: renames the symbol under the caret in every project file that uses it. */
    fun rename(newName: String) {
        val controller = focusedEditor
        val document = controller.document ?: return
        val position = controller.caretPosition() ?: return
        val name = newName.trim()
        if (!Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name)) return showMessage("'$name' is not a valid C/C++ name")
        if (intel.status.value != IntelStatus.Ready) return showMessage(intelUnavailableMessage())
        val root = _state.value.project?.root ?: return
        viewModelScope.launch {
            val edit = intel.rename(document.path, document.text(), position, name)
            if (edit == null || edit.changes.isEmpty()) return@launch showMessage("This symbol cannot be renamed")
            var files = 0
            edit.changes.forEach { (uri, edits) ->
                val file = io.nativeforge.core.lsp.DocumentUri.toFile(uri) ?: return@forEach
                if (!file.absolutePath.startsWith(root.absolutePath + File.separator)) return@forEach
                files++
                when (file.path) {
                    editor.document?.path -> editor.applyEdits(edits)
                    splitEditor.document?.path -> splitEditor.applyEdits(edits)
                    else -> {
                        documents.remove(file.path)
                        withContext(Dispatchers.IO) {
                            val text = file.readText()
                            file.writeText(io.nativeforge.core.codeintel.TextEdits.apply(text, edits))
                        }
                    }
                }
            }
            showMessage("Renamed to '$name' in $files file" + if (files == 1) "" else "s")
        }
    }

    /** Shift+Alt+F: formats the focused file with clang-format. */
    fun formatDocument(quiet: Boolean = false) {
        val controller = focusedEditor
        val document = controller.document ?: return
        viewModelScope.launch { formatInPlace(controller, document, quiet) }
    }

    private suspend fun formatInPlace(controller: CodeEditorController, document: EditorDocument, quiet: Boolean): Boolean {
        val file = File(document.path)
        if (!ClangdSession.supports(file)) {
            if (!quiet) showMessage("Format document works in C and C++ files")
            return false
        }
        val root = _state.value.project?.root ?: return false
        val settings = container.settings.settings.value
        val result = withContext(Dispatchers.IO) {
            runCatching { formatter.format(document.text(), file, root, settings.formatStyle, settings.editor.tabWidth) }
        }
        result.onSuccess { replacements ->
            if (replacements.isNotEmpty()) controller.applyReplacements(replacements.map { Triple(it.offset, it.length, it.text) })
            if (!quiet) showMessage(if (replacements.isEmpty()) "Already formatted" else "Formatted")
        }.onFailure { if (!quiet) showMessage(it.message ?: "clang-format failed") }
        return result.isSuccess
    }

    private fun intelUnavailableMessage(): String = when (val status = intel.status.value) {
        IntelStatus.Off -> "Code intelligence is off (Settings › Code intelligence)"
        IntelStatus.Starting -> "clangd is still starting…"
        is IntelStatus.Unavailable -> "clangd is not available: ${status.reason}. Reinstall the toolchain to get it."
        IntelStatus.Ready -> ""
    }

    fun closeFile(path: String) {
        viewModelScope.launch {
            save(path)
            documents.remove(path)
            intel.closed(path)
            if (path == _state.value.splitFile) {
                splitEditor.close()
                _state.update { it.copy(splitFile = null) }
            }
            val remaining = _state.value.openFiles - path
            _state.update {
                it.copy(
                    openFiles = remaining,
                    activeFile = if (it.activeFile == path) remaining.lastOrNull { other -> other != it.splitFile } else it.activeFile,
                )
            }
            if (_state.value.activeFile == null) editor.close()
            _state.value.activeFile?.let { openFile(File(it)) }
        }
    }

    fun createFile(relativePath: String) {
        val project = _state.value.project ?: return
        val file = insideProject(project, relativePath) ?: return showMessage("Invalid file name")
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                if (!file.exists()) file.writeText("")
            }
            refreshFiles()
            openFile(file)
        }
    }

    fun createFolder(relativePath: String) {
        val project = _state.value.project ?: return
        val dir = insideProject(project, relativePath) ?: return showMessage("Invalid folder name")
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dir.mkdirs() }
            val rel = dir.relativeTo(project.root).invariantSeparatorsPath
            _state.update { state -> state.copy(expandedDirs = state.expandedDirs + parents(rel) + rel) }
            refreshFiles()
        }
    }

    /** Renames a file or folder in place; open editors follow the new path. */
    fun rename(file: File, newName: String) {
        val project = _state.value.project ?: return
        val name = newName.trim()
        if (name.isEmpty() || '/' in name || name == "." || name == "..") return showMessage("Invalid name")
        val target = File(file.parentFile, name)
        if (insideProject(project, target.relativeTo(project.root).path) == null) return showMessage("Invalid name")
        viewModelScope.launch {
            saveAll()
            val moved = withContext(Dispatchers.IO) { !target.exists() && file.renameTo(target) }
            if (!moved) return@launch showMessage("Could not rename ${file.name}")
            remapPaths(file.path, target.path)
            refreshFiles()
        }
    }

    /** Deletes a file or folder (with its contents) and closes its editors. */
    fun delete(file: File) {
        val project = _state.value.project ?: return
        if (file.canonicalFile == project.root.canonicalFile || file.name == ProjectConfig.FILE_NAME) {
            return showMessage("${file.name} cannot be deleted here")
        }
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                // Links are removed, never followed out of the project.
                io.nativeforge.core.common.SafeFiles.deleteTree(file)
            }
            if (!deleted) showMessage("Could not delete ${file.name}")
            val prefix = file.path
            _state.value.openFiles.filter { it == prefix || it.startsWith("$prefix/") }.forEach { path ->
                documents.remove(path)
                _state.update { it.copy(openFiles = it.openFiles - path, dirtyFiles = it.dirtyFiles - path) }
            }
            _state.update { it.copy(activeFile = it.activeFile?.takeIf { active -> active in it.openFiles } ?: it.openFiles.lastOrNull()) }
            _state.value.activeFile?.let { openFile(File(it)) } ?: editor.close()
            refreshFiles()
        }
    }

    private fun remapPaths(from: String, to: String) {
        fun map(path: String) = if (path == from) to else if (path.startsWith("$from/")) to + path.removePrefix(from) else path
        val remapped = documents.entries.associate { (path, doc) -> map(path) to if (map(path) != path) doc.copyTo(map(path)) else doc }
        documents.clear()
        documents.putAll(remapped)
        _state.update { state ->
            state.copy(
                openFiles = state.openFiles.map(::map),
                activeFile = state.activeFile?.let(::map),
                dirtyFiles = state.dirtyFiles.map(::map).toSet(),
            )
        }
        _state.value.activeFile?.let { openFile(File(it)) }
    }

    /** True for a path inside the project that really points elsewhere (through a link). */
    private fun escapesProject(file: File): Boolean {
        val root = _state.value.project?.root ?: return false
        if (!file.absolutePath.startsWith(root.absolutePath + File.separator)) return false
        return io.nativeforge.core.common.SafeFiles.resolveInside(root, file.relativeTo(root).path) == null
    }

    private fun insideProject(project: Project, relativePath: String): File? {
        val cleaned = relativePath.trim().trimStart('/')
        if (cleaned.isEmpty()) return null
        val file = File(project.root, cleaned)
        val root = project.root.canonicalPath
        val canonical = file.canonicalPath
        return file.takeIf { canonical.startsWith(root + File.separator) && "/.git/" !in "$canonical/".removePrefix(root) }
    }

    // ---- Git ----

    /** Reads the repository status in the background; cheap enough to run after each save. */
    fun refreshGit() {
        val project = _state.value.project ?: return
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) {
                if (container.git.isRepository(project.root)) runCatching { container.git.status(project.root) }.getOrNull() else null
            }
            _state.update { it.copy(git = status) }
        }
    }

    fun initGit() = gitTask("Creating repository…") { root ->
        container.git.init(root)
        "Repository created"
    }

    fun commit(message: String) = gitTask("Committing…") { root ->
        val settings = container.settings.settings.value
        val identity = GitIdentity(
            settings.gitName.ifBlank { "NativeForge user" },
            settings.gitEmail.ifBlank { "user@nativeforge.invalid" },
        )
        val commit = container.git.commitAll(root, message, identity)
        "Committed ${commit.shortId}"
    }

    fun pull(credentials: GitCredentials?) = gitTask("Pulling…") { root ->
        val result = container.git.pull(root, credentials)
        withContext(Dispatchers.Main) { reloadOpenDocuments() }
        "Pull: $result"
    }

    fun push(credentials: GitCredentials?) = gitTask("Pushing…") { root -> "Push: " + container.git.push(root, credentials) }

    fun showDiff(relativePath: String) {
        val project = _state.value.project ?: return
        viewModelScope.launch {
            saveAll()
            val text = withContext(Dispatchers.IO) {
                runCatching { container.git.diff(project.root, relativePath) }.getOrElse { it.message.orEmpty() }
            }
            _state.update { it.copy(diff = DiffView(relativePath, it.git?.changes?.get(relativePath), text)) }
        }
    }

    fun closeDiff() = _state.update { it.copy(diff = null) }

    fun discard(relativePath: String) = gitTask("Discarding…") { root ->
        container.git.discard(root, relativePath)
        withContext(Dispatchers.Main) {
            _state.update { it.copy(diff = null) }
            reloadOpenDocuments()
        }
        "Changes in $relativePath discarded"
    }

    private fun gitTask(label: String, block: suspend (File) -> String) {
        val project = _state.value.project ?: return
        if (_state.value.gitBusy != null) return
        viewModelScope.launch {
            documents.keys.toList().forEach { save(it) }
            _state.update { it.copy(gitBusy = label) }
            val message = withContext(Dispatchers.IO) {
                try {
                    block(project.root)
                } catch (e: GitException) {
                    e.message.orEmpty()
                } catch (e: IllegalArgumentException) {
                    e.message.orEmpty()
                }
            }
            _state.update { it.copy(gitBusy = null) }
            showMessage(message)
            refreshFiles()
        }
    }

    /** Re-reads open files from disk after git changed them; unsaved edits were saved first. */
    private fun reloadOpenDocuments() {
        val active = _state.value.activeFile
        documents.clear()
        val existing = _state.value.openFiles.filter { File(it).isFile }
        _state.update { it.copy(openFiles = existing, activeFile = active?.takeIf { path -> path in existing } ?: existing.lastOrNull()) }
        _state.value.activeFile?.let { openFile(File(it)) } ?: editor.close()
    }

    /** Applies changes made in the Build settings dialog. */
    fun updateConfig(transform: (ProjectConfig) -> ProjectConfig) {
        val project = _state.value.project ?: return
        viewModelScope.launch {
            when (val saved = container.projectRepository.saveConfig(project, transform(project.config))) {
                is Outcome.Success -> {
                    _state.update { it.copy(project = saved.value) }
                    showMessage("Build settings saved")
                }
                is Outcome.Failure -> showMessage(saved.error.message)
            }
        }
    }

    fun saveAll() {
        viewModelScope.launch { documents.keys.toList().forEach { save(it) } }
    }

    private suspend fun save(path: String, format: Boolean = true) {
        val document = documents[path] ?: return
        if (path !in _state.value.dirtyFiles) return
        if (format && container.settings.settings.value.formatOnSave) {
            val controller = when (path) {
                editor.document?.path -> editor
                splitEditor.document?.path -> splitEditor
                else -> null
            }
            if (controller != null) formatInPlace(controller, document, quiet = true)
        }
        val text = document.text()
        withContext(Dispatchers.IO) { SafeFiles.writeTextAtomically(File(path), text) }
        intel.saved(path)
        // Typed again while the file was being written: it stays unsaved.
        if (document.text() == text) _state.update { it.copy(dirtyFiles = it.dirtyFiles - path) }
        // Auto saves happen at every pause in typing; the Changes list follows explicit saves.
        if (format && _state.value.git != null) refreshGit()
    }

    private val autoSaves = HashMap<String, Job>()

    /**
     * With auto save on, a file is written [AUTO_SAVE_DELAY_MS] after the last key, without
     * formatting it: no work while typing, and little to lose if the app is killed.
     */
    private fun scheduleAutoSave(path: String) {
        if (!container.settings.settings.value.autoSave) return
        autoSaves.remove(path)?.cancel()
        autoSaves[path] = viewModelScope.launch {
            delay(AUTO_SAVE_DELAY_MS)
            autoSaves.remove(path)
            save(path, format = false)
        }
    }

    /** Saves, installs missing packages, builds, and optionally runs the program. */
    fun build(runAfterBuild: Boolean, onRun: (Project, BuildArtifact) -> Unit) {
        val project = _state.value.project ?: return
        if (buildJob?.isActive == true) return
        buildJob = viewModelScope.launch {
            documents.keys.toList().forEach { save(it) }
            clearLog()
            _state.update { it.copy(buildStatus = BuildStatus.RUNNING, diagnostics = emptyList(), panelVisible = true) }

            if (!installMissingPackages(project)) {
                _state.update { it.copy(buildStatus = BuildStatus.FAILED) }
                return@launch
            }

            val request = BuildRequest(
                project = project,
                toolchain = container.toolchain,
                dependencies = container.packageManager.nativeDependencies(project.config.packages),
            )
            container.buildService.build(request).collect { event -> onBuildEvent(event) }

            val artifact = lastArtifact
            if (runAfterBuild && artifact != null && _state.value.buildStatus == BuildStatus.SUCCEEDED) {
                onRun(project, artifact)
            }
        }
    }

    private suspend fun installMissingPackages(project: Project): Boolean {
        container.updateBundledPackages().takeIf { it.isNotEmpty() }?.let { appendLog("Updated libraries: ${it.joinToString()}", false) }
        val missing = project.config.packages.filterNot(container.packageManager::isInstalled)
        if (missing.isEmpty()) return true
        appendLog("Installing packages: ${missing.joinToString()}", isError = false)
        var success = true
        container.packageManager.install(missing).collect { event ->
            when (event) {
                is InstallEvent.Preparing -> appendLog("  ${event.message}", false)
                is InstallEvent.Downloading -> if (event.bytesRead == 0L) appendLog("  downloading ${event.pkg.name} ${event.pkg.version}", false)
                is InstallEvent.Failed -> { appendLog(event.error.message, true); success = false }
                else -> Unit
            }
        }
        return success
    }

    private fun onBuildEvent(event: BuildEvent) {
        when (event) {
            is BuildEvent.StepStarted -> appendLog("[${event.index + 1}/${event.total}] ${event.step.description}", false)
            is BuildEvent.Output -> appendLog(event.line, event.stream == OutputStream.STDERR)
            is BuildEvent.DiagnosticReported -> _state.update {
                if (it.diagnostics.size >= MAX_DIAGNOSTICS) it else it.copy(diagnostics = it.diagnostics + event.diagnostic)
            }
            is BuildEvent.StepFinished -> if (event.exitCode != 0) appendLog("Step failed with exit code ${event.exitCode}", true)
            is BuildEvent.Finished -> {
                lastArtifact = event.result.artifact
                val seconds = event.result.durationMillis / 1000.0
                appendLog(if (event.result.success) "Build succeeded in %.1fs".format(seconds) else "Build failed", !event.result.success)
                flushLog()
                _state.update { it.copy(buildStatus = if (event.result.success) BuildStatus.SUCCEEDED else BuildStatus.FAILED) }
                // CMake writes a more exact compilation database while configuring.
                intel.refreshDatabase()
            }
        }
    }

    fun cancelBuild() {
        buildJob?.cancel()
        _state.update { it.copy(buildStatus = BuildStatus.IDLE) }
        appendLog("Build cancelled", true)
    }

    fun openDiagnostic(diagnostic: Diagnostic) {
        val project = _state.value.project ?: return
        val path = diagnostic.file ?: return
        val file = File(path).let { if (it.isAbsolute) it else File(project.root, path) }
        if (!file.isFile) return
        openFile(file)
        viewModelScope.launch { editor.goTo(diagnostic.line ?: 1, diagnostic.column ?: 1) }
    }

    /** Shows why a graphical program closed right after starting, when the runner reported it. */
    fun reportStartError(error: String?) {
        if (error == null) return
        appendLog("The program stopped while starting:", true)
        error.lines().forEach { appendLog("  $it", true) }
        appendLog("Use More › Program log for the full log.", false)
        _state.update { it.copy(panelVisible = true) }
    }

    /** Appends the app's recent log (including the program's process) to the output panel. */
    fun showProgramLog(read: () -> List<String>) {
        viewModelScope.launch {
            val lines = withContext(Dispatchers.IO) { read() }
            appendLog("── Program log (last ${lines.size} lines) ──", false)
            lines.forEach { line ->
                // logcat "time" format: "MM-DD hh:mm:ss.mmm L/Tag(pid): message"
                val level = line.substringAfter(' ').substringAfter(' ').firstOrNull()
                appendLog(line, level == 'E' || level == 'F')
            }
            _state.update { it.copy(panelVisible = true) }
        }
    }

    /** Every package the project could use: bundled Android libraries first, then Termux. */
    fun loadCatalog() {
        viewModelScope.launch {
            container.packageManager.refreshSource(io.nativeforge.app.di.AppContainer.BUNDLED_SOURCE_ID)
            if (container.packageManager.catalog.value.none { !it.sourceId.startsWith("nativeforge") }) {
                container.packageManager.refresh()
            }
        }
    }

    val catalog get() = container.packageManager.catalog

    /** Replaces the project's libraries and extra flags; missing packages are installed on the next build. */
    fun updateLibraries(packages: List<String>, compilerFlags: List<String>, linkerFlags: List<String>) {
        val project = _state.value.project ?: return
        viewModelScope.launch {
            val config = project.config.copy(packages = packages.distinct(), compilerFlags = compilerFlags, linkerFlags = linkerFlags)
            when (val saved = container.projectRepository.saveConfig(project, config)) {
                is Outcome.Success -> {
                    _state.update { it.copy(project = saved.value) }
                    restartCodeIntelligence()
                    val missing = packages.filterNot(container.packageManager::isInstalled)
                    if (missing.isNotEmpty()) {
                        appendLog("Installing ${missing.joinToString()}…", false)
                        _state.update { it.copy(panelVisible = true) }
                        installMissingPackages(saved.value)
                        appendLog("Libraries ready", false)
                    }
                }
                is Outcome.Failure -> showMessage(saved.error.message)
            }
        }
    }

    fun togglePanel() = _state.update { it.copy(panelVisible = !it.panelVisible) }

    fun hidePanel() = _state.update { it.copy(panelVisible = false) }

    // ---- Search in project ----

    /** Searches every text file of the project (unsaved editor text included), after a short pause for typing. */
    fun searchInProject(query: SearchQuery) {
        searchJob?.cancel()
        _search.update { it.copy(query = query, error = null) }
        val root = _state.value.project?.root ?: return
        if (query.text.isEmpty()) {
            _search.update { it.copy(running = false, result = SearchResult.EMPTY) }
            return
        }
        ProjectSearch.compile(query).exceptionOrNull()?.let { error ->
            val reason = error.message?.lineSequence()?.firstOrNull().orEmpty()
            _search.update { it.copy(running = false, result = SearchResult.EMPTY, error = "Invalid regular expression: $reason") }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _search.update { it.copy(running = true) }
            val unsaved = documents.filterKeys { it in _state.value.dirtyFiles }.mapValues { it.value.text() }
            val result = withContext(Dispatchers.IO) {
                val context = coroutineContext
                ProjectSearch.search(root, query, textOf = { unsaved[it.path] }, checkCancelled = { context.ensureActive() })
            }
            _search.update { it.copy(running = false, result = result) }
        }
    }

    fun setSearchReplacement(text: String) = _search.update { it.copy(replacement = text) }

    /** Opens a search result in the focused editor and selects the match. */
    fun openSearchMatch(file: File, match: SearchMatch) {
        val controller = focusedEditor
        val length = match.end - match.start
        if (controller.document?.path == file.path) {
            controller.select(match.line, match.column, length)
            controller.focus()
            return
        }
        openFile(file, inSplit = controller === splitEditor)
        viewModelScope.launch {
            delay(150)
            focusedEditor.select(match.line, match.column, length)
            focusedEditor.focus()
        }
    }

    /**
     * Replaces every match of the current search in the files of the result. Files shown in
     * an editor are edited there (one undo step, left unsaved); other files with unsaved
     * changes are edited in memory; the rest are rewritten on disk.
     */
    fun replaceInProject() {
        val search = _search.value
        val files = search.result.files.map { it.file }
        if (files.isEmpty() || search.running) return
        searchJob?.cancel()
        viewModelScope.launch {
            var total = 0
            var changed = 0
            var failed = 0
            for (file in files) {
                val path = file.path
                val controller = when (path) {
                    editor.document?.path -> editor
                    splitEditor.document?.path -> splitEditor
                    else -> null
                }
                val document = documents[path]
                val count = when {
                    controller != null && document != null -> {
                        val replacements = ProjectSearch.replacements(document.text(), search.query, search.replacement)
                        if (replacements.isNotEmpty()) controller.applyReplacements(replacements)
                        replacements.size
                    }
                    document != null && path in _state.value.dirtyFiles -> {
                        val (text, count) = ProjectSearch.replace(document.text(), search.query, search.replacement)
                        if (count > 0) {
                            documents[path] = EditorDocument(path, text)
                            intel.textChanged(path, text)
                        }
                        count
                    }
                    else -> {
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching {
                                val (text, count) = ProjectSearch.replace(file.readText(), search.query, search.replacement)
                                if (count > 0) file.writeText(text)
                                text to count
                            }
                        }
                        val written = outcome.getOrNull()
                        if (written == null) {
                            failed++
                            0
                        } else {
                            val (text, count) = written
                            if (count > 0 && documents.remove(path) != null) intel.textChanged(path, text)
                            count
                        }
                    }
                }
                if (count > 0) { total += count; changed++ }
            }
            val summary = "Replaced $total occurrence" + (if (total == 1) "" else "s") + " in $changed file" + (if (changed == 1) "" else "s")
            showMessage(if (failed > 0) "$summary ($failed could not be written)" else summary)
            if (_state.value.git != null) refreshGit()
            searchInProject(search.query)
        }
    }

    fun showMessage(message: String) = _state.update { it.copy(message = message) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    /** The app left the screen (home button, a running program): clangd stops if it stays away. */
    fun onAppHidden() = intel.appHidden()

    /** Back on screen: clangd starts again if it was stopped while away. */
    fun onAppShown() = intel.appShown()

    override fun onCleared() {
        intel.stop()
        // Persist unsaved edits when the screen goes away.
        documents.forEach { (path, document) ->
            if (path in _state.value.dirtyFiles) runCatching { SafeFiles.writeTextAtomically(File(path), document.text()) }
        }
    }

    /**
     * Queues a line for the output panel. Lines are published at most every [LOG_FLUSH_MS]: a
     * build printing thousands of lines then costs a few list copies and redraws instead of one
     * per line. Only called on the main thread (view model scope).
     */
    private fun appendLog(text: String, isError: Boolean) {
        pendingLog += logLine(text, isError)
        if (logFlush?.isActive == true) return
        logFlush = viewModelScope.launch {
            delay(LOG_FLUSH_MS)
            flushLog()
        }
    }

    private fun flushLog() {
        logFlush?.cancel()
        logFlush = null
        if (pendingLog.isEmpty()) return
        val batch = pendingLog.toList()
        pendingLog.clear()
        _log.update { appendCapped(it, batch, MAX_LOG_LINES) }
    }

    private fun clearLog() {
        logFlush?.cancel()
        logFlush = null
        pendingLog.clear()
        _log.value = emptyList()
    }

    private fun defaultFile(project: Project): File? =
        project.sourceDirectory.walkTopDown().firstOrNull { it.isFile && it.nameWithoutExtension == "main" }
            ?: project.sourceDirectory.walkTopDown().firstOrNull { it.isFile }

    private fun isBinary(file: File): Boolean = file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        val read = input.read(buffer)
        (0 until maxOf(read, 0)).any { buffer[it] == 0.toByte() }
    }

    private fun parents(relativePath: String): List<String> {
        val parts = relativePath.split('/')
        return (1 until parts.size).map { parts.take(it).joinToString("/") }
    }

    private companion object {
        const val MAX_LOG_LINES = 2000
        const val LOG_FLUSH_MS = 50L
        const val MAX_DIAGNOSTICS = 1000
        /** Larger files stay closed; from 1 MB the editor highlights less (see HighlightMode). */
        const val MAX_EDITABLE_BYTES = 16L * 1024 * 1024
        const val SEARCH_DEBOUNCE_MS = 250L
        const val AUTO_SAVE_DELAY_MS = 1_500L
    }
}
