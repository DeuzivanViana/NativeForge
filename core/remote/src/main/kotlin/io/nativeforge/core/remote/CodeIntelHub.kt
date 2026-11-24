package io.nativeforge.core.remote

import io.nativeforge.core.codeintel.ClangdFiles
import io.nativeforge.core.codeintel.ClangdSession
import io.nativeforge.core.common.Logger
import io.nativeforge.core.lsp.CompletionItem
import io.nativeforge.core.lsp.DiagnosticSeverity
import io.nativeforge.core.lsp.LspDiagnostic
import io.nativeforge.core.lsp.Position
import io.nativeforge.core.lsp.Range
import io.nativeforge.core.project.model.Project
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * clangd for the browser: one session for the project the browser works on, started on
 * first use. Diagnostics are pushed to every connected browser through [publish]; other
 * requests answer with positions in LSP coordinates (0-based line and UTF-16 column).
 */
class CodeIntelHub(
    private val backend: RemoteBackend,
    private val logger: Logger,
    private val publish: (JsonObject) -> Unit,
) {
    private val starter = Executors.newSingleThreadExecutor { Thread(it, "nf-remote-clangd").apply { isDaemon = true } }
    private val lock = Any()
    @Volatile private var session: ClangdSession? = null
    @Volatile private var project: Project? = null
    @Volatile private var starting = false
    private val pending = LinkedHashMap<String, String>() // path -> text, synced once clangd is up

    val status: String get() = when {
        !backend.codeIntelEnabled() -> "off"
        starting -> "starting"
        session?.isAlive == true -> "ready"
        else -> "idle"
    }

    /** Opens or updates [file] in clangd, starting it for [target] if needed. */
    fun sync(target: Project, file: File, text: String) {
        if (!backend.codeIntelEnabled() || !ClangdSession.supports(file)) return
        val live = sessionFor(target)
        if (live == null) {
            synchronized(lock) { pending[file.path] = text }
            return
        }
        runCatching { live.sync(file, text) }
    }

    fun close(file: File) {
        synchronized(lock) { pending.remove(file.path) }
        runCatching { session?.close(file) }
    }

    fun saved(file: File) {
        runCatching { session?.saved(file) }
    }

    fun completion(target: Project, file: File, text: String, line: Int, character: Int): JsonObject? {
        val live = sessionFor(target) ?: return null
        val result = runCatching { live.completion(file, text, Position(line, character)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }.getOrNull() ?: return null
        return Json.obj(
            "incomplete" to result.isIncomplete,
            "items" to result.items.take(MAX_ITEMS).map(::completionJson),
        )
    }

    fun hover(target: Project, file: File, text: String, line: Int, character: Int): String? {
        val live = sessionFor(target) ?: return null
        return runCatching { live.hover(file, text, Position(line, character)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }.getOrNull()
    }

    /** Definition locations; paths are relative to the project when inside it, else absolute. */
    fun definition(target: Project, file: File, text: String, line: Int, character: Int): List<JsonObject> {
        val live = sessionFor(target) ?: return emptyList()
        val found = runCatching { live.definition(file, text, Position(line, character)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }.getOrNull().orEmpty()
        val files = ProjectFiles(target.root)
        return found.mapNotNull { location ->
            val path = location.file ?: return@mapNotNull null
            val inside = runCatching { path.canonicalPath.startsWith(files.root.path + File.separator) }.getOrDefault(false)
            Json.obj(
                "path" to if (inside) files.relativePath(path) else path.path,
                "inside" to inside,
                "range" to rangeJson(location.range),
            )
        }
    }

    fun format(target: Project, file: File, text: String, tabSize: Int): List<JsonObject>? {
        val live = sessionFor(target) ?: return null
        val edits = runCatching { live.formatting(file, text, tabSize).get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }.getOrNull() ?: return null
        return edits.map { Json.obj("range" to rangeJson(it.range), "text" to it.newText) }
    }

    /** Rewrites the compilation database after a build (CMake writes a more exact one). */
    fun refreshDatabase(target: Project) {
        if (project?.root != target.root) return
        starter.execute { runCatching { ClangdFiles.writeDatabase(backend.buildRequest(target)) } }
    }

    fun stop() {
        synchronized(lock) {
            session?.let { s -> Thread { s.stop() }.apply { isDaemon = true }.start() }
            session = null
            project = null
            pending.clear()
        }
        starter.shutdownNow()
    }

    private fun sessionFor(target: Project): ClangdSession? {
        synchronized(lock) {
            val current = session
            if (current != null && current.isAlive && project?.root == target.root) return current
            if (starting) return null
            current?.let { old -> Thread { old.stop() }.apply { isDaemon = true }.start() }
            session = null
            project = target
            starting = true
        }
        publish(Json.obj("t" to "intel", "status" to "starting"))
        starter.execute {
            val started = runCatching { ClangdSession.start(backend.buildRequest(target), backend.codeIntelOptions()) }
            val toSync: Map<String, String>
            synchronized(lock) {
                starting = false
                started.getOrNull()?.let { live ->
                    live.onDiagnostics = { file, list -> publishDiagnostics(target, file, list) }
                    session = live
                }
                toSync = LinkedHashMap(pending)
                pending.clear()
            }
            started.exceptionOrNull()?.let { logger.warn(TAG, "clangd did not start", it) }
            val live = started.getOrNull()
            toSync.forEach { (path, text) -> runCatching { live?.sync(File(path), text) } }
            publish(
                Json.obj(
                    "t" to "intel",
                    "status" to if (live != null) "ready" else "failed",
                    "message" to started.exceptionOrNull()?.message,
                ),
            )
        }
        return null
    }

    private fun publishDiagnostics(target: Project, file: File, list: List<LspDiagnostic>) {
        val files = ProjectFiles(target.root)
        val path = runCatching { files.relativePath(file) }.getOrNull()?.takeUnless { it.startsWith("..") } ?: return
        publish(
            Json.obj(
                "t" to "diag",
                "project" to target.key,
                "path" to path,
                "items" to list.take(MAX_DIAGNOSTICS).map { d ->
                    Json.obj(
                        "range" to rangeJson(d.range),
                        "severity" to when (d.severity) {
                            DiagnosticSeverity.ERROR -> "error"
                            DiagnosticSeverity.WARNING -> "warning"
                            DiagnosticSeverity.INFORMATION -> "info"
                            DiagnosticSeverity.HINT -> "hint"
                        },
                        "message" to d.message,
                        "source" to d.source,
                    )
                },
            ),
        )
    }

    companion object {
        private const val TAG = "CodeIntelHub"
        private const val TIMEOUT_SECONDS = 8L
        private const val MAX_ITEMS = 100
        private const val MAX_DIAGNOSTICS = 300

        fun rangeJson(range: Range) = Json.obj(
            "start" to Json.obj("line" to range.start.line, "ch" to range.start.character),
            "end" to Json.obj("line" to range.end.line, "ch" to range.end.character),
        )

        fun completionJson(item: CompletionItem) = Json.obj(
            "label" to item.label.trim(),
            "kind" to item.kind.name.lowercase(),
            "detail" to item.detail,
            "doc" to item.documentation,
            "sort" to item.sortText,
            "filter" to item.filterText,
            "insert" to (item.textEdit?.newText ?: item.insertText ?: item.label.trim()),
            "range" to item.textEdit?.range?.let(::rangeJson),
            "extra" to item.additionalTextEdits.map { Json.obj("range" to rangeJson(it.range), "text" to it.newText) },
        )
    }
}
