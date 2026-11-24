package io.nativeforge.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.nativeforge.app.di.AppContainer
import io.nativeforge.core.common.Outcome
import io.nativeforge.core.packages.model.InstallEvent
import io.nativeforge.core.project.model.Project
import io.nativeforge.core.project.template.ProjectOptions
import io.nativeforge.core.project.template.ProjectTemplate
import io.nativeforge.core.toolchain.ClangToolchain
import io.nativeforge.core.project.model.ProjectConfig
import io.nativeforge.core.project.model.SourceFiles
import io.nativeforge.core.vcs.GitCredentials
import io.nativeforge.core.vcs.GitException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Toolchain installation state shown on the home screen. */
sealed interface ToolchainStatus {
    data object Ready : ToolchainStatus
    data class Missing(val components: List<String>) : ToolchainStatus
    data class Installing(val message: String, val progress: Float?) : ToolchainStatus
    data class Failed(val message: String) : ToolchainStatus
}

/** What the project list shows about a project besides its configuration. */
data class ProjectSummary(
    val project: Project,
    val modifiedMillis: Long,
    val sourceFiles: Int,
    /** Current branch when the project is a git repository. */
    val branch: String? = null,
    val changes: Int = 0,
)

/** Progress of "Clone repository". */
data class CloneState(val message: String, val percent: Int? = null, val error: String? = null)

data class HomeUiState(
    val projects: List<ProjectSummary> = emptyList(),
    val toolchain: ToolchainStatus = ToolchainStatus.Ready,
    val message: String? = null,
    val clone: CloneState? = null,
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            container.packageManager.loadInstalled()
            val updated = container.updateBundledPackages()
            if (updated.isNotEmpty()) _state.update { it.copy(message = "Updated libraries: ${updated.joinToString()}") }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val missing = withContext(Dispatchers.IO) { container.toolchain.missingComponents() }
            val projects = container.projectRepository.listProjects()
            // Each summary walks the project's files and reads its Git status: do several at once.
            val summaries = coroutineScope {
                projects.map { project -> async(summaryDispatcher) { summarize(project) } }.awaitAll()
            }.sortedByDescending { it.modifiedMillis }
            _state.update {
                it.copy(
                    projects = summaries,
                    toolchain = if (missing.isEmpty()) ToolchainStatus.Ready else ToolchainStatus.Missing(missing),
                )
            }
        }
    }

    private val summaryDispatcher = Dispatchers.IO.limitedParallelism(4)

    private fun summarize(project: Project): ProjectSummary {
        var newest = File(project.root, ProjectConfig.FILE_NAME).lastModified()
        var sources = 0
        project.root.walkTopDown()
            .maxDepth(6)
            .onEnter { it == project.root || (!it.name.startsWith(".") && it.name !in SourceFiles.IGNORED_DIRS && !SourceFiles.isSymlink(it)) }
            .filter { it.isFile }
            .take(5000)
            .forEach { file ->
                if (SourceFiles.isCompilable(file) || SourceFiles.isHeader(file)) sources++
                newest = maxOf(newest, file.lastModified())
            }
        val git = if (container.git.isRepository(project.root)) runCatching { container.git.status(project.root) }.getOrNull() else null
        return ProjectSummary(project, newest, sources, git?.let { it.branch ?: "main" }, git?.changes?.size ?: 0)
    }

    private var cloneJob: Job? = null
    @Volatile private var cloneCancelled = false

    /**
     * Clones an HTTPS repository into the workspace and opens it as a project. The build
     * system, language and libraries are detected; nothing in the repository runs until
     * the user builds it.
     */
    fun cloneRepository(url: String, name: String, branch: String, credentials: GitCredentials?, onCloned: (String) -> Unit) {
        if (cloneJob?.isActive == true) return
        cloneCancelled = false
        cloneJob = viewModelScope.launch {
            _state.update { it.copy(clone = CloneState("Connecting…")) }
            val target = when (val dir = container.projectRepository.directoryFor(name.trim())) {
                is Outcome.Success -> dir.value
                is Outcome.Failure -> {
                    _state.update { it.copy(clone = CloneState("", error = dir.error.message)) }
                    return@launch
                }
            }
            val error = withContext(Dispatchers.IO) {
                try {
                    container.git.clone(
                        url = url,
                        target = target,
                        branch = branch.ifBlank { null },
                        credentials = credentials,
                        onProgress = { progress -> _state.update { it.copy(clone = CloneState(progress.task, progress.percent)) } },
                        isCancelled = { cloneCancelled },
                    )
                    null
                } catch (e: GitException) {
                    if (cloneCancelled) "Clone cancelled" else e.message
                }
            }
            if (error != null) {
                _state.update { it.copy(clone = CloneState("", error = error)) }
                return@launch
            }
            when (val imported = container.projectRepository.importDirectory(target)) {
                is Outcome.Success -> {
                    _state.update { it.copy(clone = null) }
                    refresh()
                    onCloned(imported.value.key)
                }
                is Outcome.Failure -> _state.update { it.copy(clone = CloneState("", error = imported.error.message)) }
            }
        }
    }

    fun cancelClone() {
        cloneCancelled = true
        if (cloneJob?.isActive != true) _state.update { it.copy(clone = null) }
    }

    fun dismissClone() {
        if (cloneJob?.isActive != true) _state.update { it.copy(clone = null) }
    }

    /**
     * Creates a project from [template] in the workspace, or in `<parent>/<name>` on the
     * device when [parent] is set. [onCreated] receives the project's key.
     */
    fun createProject(name: String, template: ProjectTemplate, options: ProjectOptions, parent: File?, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            when (val result = container.projectRepository.create(name.trim(), template, options, parent)) {
                is Outcome.Success -> {
                    refresh()
                    onCreated(result.value.key)
                }
                is Outcome.Failure -> _state.update { it.copy(message = result.error.message) }
            }
        }
    }

    /** Opens a folder of the device as a project, in place. [onOpened] receives the project's key. */
    fun openFolder(directory: File, onOpened: (String) -> Unit) {
        viewModelScope.launch {
            when (val result = container.projectRepository.openFolder(directory)) {
                is Outcome.Success -> {
                    refresh()
                    onOpened(result.value.key)
                }
                is Outcome.Failure -> _state.update { it.copy(message = result.error.message) }
            }
        }
    }

    /**
     * Copies a folder that cannot be opened in place (a cloud drive, or no file access) into
     * the workspace as a new project called [name].
     */
    fun importCopy(copyInto: (File) -> Int, name: String, onOpened: (String) -> Unit) {
        viewModelScope.launch {
            val target = when (val dir = container.projectRepository.directoryFor(name)) {
                is Outcome.Success -> dir.value
                is Outcome.Failure -> {
                    _state.update { it.copy(message = dir.error.message) }
                    return@launch
                }
            }
            _state.update { it.copy(message = "Copying $name…") }
            val copied = withContext(Dispatchers.IO) {
                runCatching { copyInto(target) }.onFailure { io.nativeforge.core.common.SafeFiles.deleteTree(target) }
            }
            copied.exceptionOrNull()?.let { error ->
                _state.update { it.copy(message = "Could not copy the folder: ${error.message}") }
                return@launch
            }
            when (val imported = container.projectRepository.importDirectory(target)) {
                is Outcome.Success -> {
                    refresh()
                    _state.update { it.copy(message = "Copied ${copied.getOrDefault(0)} files into NativeForge") }
                    onOpened(imported.value.key)
                }
                is Outcome.Failure -> _state.update { it.copy(message = imported.error.message) }
            }
        }
    }

    /** Deletes a workspace project, or forgets an opened folder (its files stay on the device). */
    fun deleteProject(project: Project) {
        viewModelScope.launch {
            val result = if (project.isExternal) container.projectRepository.forget(project) else container.projectRepository.delete(project)
            (result as? Outcome.Failure)?.let { failure -> _state.update { it.copy(message = failure.error.message) } }
            refresh()
        }
    }

    fun installToolchain() {
        viewModelScope.launch {
            var total = 0
            var installed = 0
            container.packageManager.install(ClangToolchain.REQUIRED_PACKAGES).collect { event ->
                // Several archives download at once, so progress counts installed packages.
                val progress = if (total > 0) installed.toFloat() / total else null
                val status = when (event) {
                    is InstallEvent.Resolved -> {
                        total = event.packages.size
                        ToolchainStatus.Installing("Installing $total packages…", 0f)
                    }
                    is InstallEvent.Downloading -> null
                    is InstallEvent.Extracting -> ToolchainStatus.Installing("Installing ${event.pkg.name} (${installed + 1} of $total)…", progress)
                    is InstallEvent.Installed -> {
                        installed++
                        null
                    }
                    is InstallEvent.Failed -> ToolchainStatus.Failed(event.error.message)
                    InstallEvent.Completed -> null
                }
                if (status != null) _state.update { it.copy(toolchain = status) }
                if (event == InstallEvent.Completed) refresh()
            }
        }
    }

    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}
