package io.nativeforge.app.intel

import io.nativeforge.core.build.model.BuildRequest
import io.nativeforge.core.codeintel.ClangdFiles
import io.nativeforge.core.codeintel.ClangdSession
import io.nativeforge.core.codeintel.CodeIntelOptions
import io.nativeforge.core.lsp.CompletionResult
import io.nativeforge.core.lsp.Location
import io.nativeforge.core.lsp.LspDiagnostic
import io.nativeforge.core.lsp.Position
import io.nativeforge.core.lsp.WorkspaceEdit
import io.nativeforge.feature.editor.EditorIntelligence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** What the status bar shows about clangd. */
sealed interface IntelStatus {
    data object Off : IntelStatus
    data object Starting : IntelStatus
    data object Ready : IntelStatus
    data class Unavailable(val reason: String) : IntelStatus
}

/**
 * Runs clangd for one open project and exposes it to the editor. All calls are safe
 * when clangd is missing or crashed: the editor then falls back to word completion.
 */
class CodeIntelService(private val scope: CoroutineScope) : EditorIntelligence {

    @Volatile private var session: ClangdSession? = null
    private var request: BuildRequest? = null
    private var options = CodeIntelOptions()
    private var restarts = 0
    private val pendingSync = ConcurrentHashMap<String, Job>()
    private val texts = ConcurrentHashMap<String, String>()

    private val _status = MutableStateFlow<IntelStatus>(IntelStatus.Off)
    val status: StateFlow<IntelStatus> = _status.asStateFlow()

    /** Live diagnostics per absolute file path. */
    private val _diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
    val diagnostics: StateFlow<Map<String, List<LspDiagnostic>>> = _diagnostics.asStateFlow()

    fun start(request: BuildRequest, options: CodeIntelOptions) {
        this.request = request
        this.options = options
        if (!request.toolchain.clangd.isFile) {
            _status.value = IntelStatus.Unavailable("clangd not installed")
            return
        }
        _status.value = IntelStatus.Starting
        scope.launch(Dispatchers.IO) {
            val started = runCatching { ClangdSession.start(request, options) }
            started.onSuccess { s ->
                s.onDiagnostics = { file, list -> _diagnostics.update { it + (file.path to list) } }
                session = s
                _status.value = IntelStatus.Ready
                // Re-open documents the editor already showed.
                texts.forEach { (path, text) -> runCatching { s.sync(File(path), text) } }
            }.onFailure { _status.value = IntelStatus.Unavailable(it.message?.lineSequence()?.firstOrNull() ?: "clangd failed") }
        }
    }

    /** Rewrites the compilation database, e.g. after CMake configured the project. */
    fun refreshDatabase() {
        val request = request ?: return
        scope.launch(Dispatchers.IO) { runCatching { ClangdFiles.writeDatabase(request) } }
    }

    fun stop() {
        val current = session
        session = null
        _status.value = IntelStatus.Off
        if (current != null) scope.launch(Dispatchers.IO) { current.stop() }
    }

    /** The editor text of [path] changed: tell clangd after a short pause (diagnostics follow). */
    fun textChanged(path: String, text: String) {
        if (!ClangdSession.supports(File(path))) return
        texts[path] = text
        pendingSync.remove(path)?.cancel()
        pendingSync[path] = scope.launch(Dispatchers.IO) {
            delay(SYNC_DELAY_MS)
            withSession { it.sync(File(path), text) }
        }
    }

    fun opened(path: String, text: String) {
        if (!ClangdSession.supports(File(path))) return
        texts[path] = text
        scope.launch(Dispatchers.IO) { withSession { it.sync(File(path), text) } }
    }

    fun saved(path: String) {
        scope.launch(Dispatchers.IO) { withSession { it.saved(File(path)) } }
    }

    fun closed(path: String) {
        texts.remove(path)
        pendingSync.remove(path)?.cancel()
        _diagnostics.update { it - path }
        scope.launch(Dispatchers.IO) { withSession { it.close(File(path)) } }
    }

    override fun completion(path: String, text: String, position: Position): CompletionResult? {
        val file = File(path)
        if (!ClangdSession.supports(file)) return null
        val s = liveSession() ?: return null
        texts[path] = text
        pendingSync.remove(path)?.cancel()
        return runCatching { s.completion(file, text, position).get(COMPLETION_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    suspend fun definition(path: String, text: String, position: Position): List<Location> = call { s ->
        s.definition(File(path), text, position).await()
    }.orEmpty()

    suspend fun rename(path: String, text: String, position: Position, newName: String): WorkspaceEdit? = call { s ->
        s.rename(File(path), text, position, newName).await()
    }

    suspend fun hover(path: String, text: String, position: Position): String? = call { s ->
        s.hover(File(path), text, position).await()
    }

    private suspend fun <T> call(block: suspend (ClangdSession) -> T): T? = withContext(Dispatchers.IO) {
        val s = liveSession() ?: return@withContext null
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) { runCatching { block(s) }.getOrNull() }
    }

    private fun withSession(block: (ClangdSession) -> Unit) {
        liveSession()?.let { runCatching { block(it) } }
    }

    /** The session, restarting clangd (a few times) when it crashed. */
    private fun liveSession(): ClangdSession? {
        val current = session ?: return null
        if (current.isAlive) return current
        session = null
        val request = request
        if (request != null && restarts < MAX_RESTARTS) {
            restarts++
            start(request, options)
        } else {
            _status.value = IntelStatus.Unavailable("clangd stopped")
        }
        return null
    }

    private companion object {
        const val SYNC_DELAY_MS = 350L
        const val COMPLETION_TIMEOUT_MS = 4_000L
        const val REQUEST_TIMEOUT_MS = 10_000L
        const val MAX_RESTARTS = 3
    }
}
