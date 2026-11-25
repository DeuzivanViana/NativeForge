package io.nativeforge.core.codeintel

import io.nativeforge.core.build.CompileDatabase
import io.nativeforge.core.build.model.BuildRequest
import io.nativeforge.core.lsp.CompletionResult
import io.nativeforge.core.lsp.DocumentUri
import io.nativeforge.core.lsp.JsonRpcConnection
import io.nativeforge.core.lsp.Location
import io.nativeforge.core.lsp.LspClient
import io.nativeforge.core.lsp.LspDiagnostic
import io.nativeforge.core.lsp.Position
import io.nativeforge.core.lsp.TextEdit
import io.nativeforge.core.lsp.WorkspaceEdit
import io.nativeforge.core.common.process.BackgroundThreads
import io.nativeforge.core.project.model.BuildSystemKind
import io.nativeforge.core.toolchain.BuildParallelism
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** User options for the language server (Settings screen). */
data class CodeIntelOptions(
    /** Run clang-tidy checks and show them as warnings. */
    val clangTidy: Boolean = false,
    /** Index the whole project in the background (go to definition across files). */
    val backgroundIndex: Boolean = true,
    /** Worker threads clangd may use. */
    val threads: Int = 2,
) {
    companion object {
        /** Below this much free memory clangd skips the project-wide index (each file still works). */
        const val LOW_MEMORY_MB = 1_024L

        /** Memory budget of one clangd worker (an AST of a file with big headers). */
        const val MEMORY_PER_THREAD_MB = 768L

        /**
         * Options sized for the phone: at most half the cores and one worker per
         * [MEMORY_PER_THREAD_MB] of free memory (1 to 4), and no background index when memory
         * is short. [availableMemoryMb] null (unknown) leaves the index on.
         */
        fun forDevice(
            clangTidy: Boolean,
            cores: Int = Runtime.getRuntime().availableProcessors(),
            availableMemoryMb: Long? = BuildParallelism.availableMemoryMb(),
        ): CodeIntelOptions {
            val byCpu = (cores / 2).coerceIn(1, 4)
            val byMemory = availableMemoryMb?.let { (it / MEMORY_PER_THREAD_MB).toInt().coerceIn(1, 4) } ?: byCpu
            return CodeIntelOptions(
                clangTidy = clangTidy,
                backgroundIndex = availableMemoryMb == null || availableMemoryMb >= LOW_MEMORY_MB,
                threads = minOf(byCpu, byMemory),
            )
        }
    }
}

/**
 * One clangd process serving one project.
 *
 * The session writes the compilation database clangd reads (see [CompileDatabase]) and a
 * user config that adds the Android target and sysroot to every file, including headers
 * and files the database does not list. Documents are kept in sync with full-text updates.
 */
class ClangdSession private constructor(
    private val process: Process,
    private val client: LspClient,
    val projectRoot: File,
) {
    private val versions = ConcurrentHashMap<String, Int>()
    private val sentText = ConcurrentHashMap<String, String>()
    private val stderrTail = ArrayDeque<String>()

    /** Latest diagnostics per file path. Called on the reader thread. */
    @Volatile var onDiagnostics: (file: File, diagnostics: List<LspDiagnostic>) -> Unit = { _, _ -> }

    val isAlive: Boolean get() = process.isAlive && !client.isClosed

    init {
        client.onDiagnostics = { uri, list -> DocumentUri.toFile(uri)?.let { onDiagnostics(it, list) } }
        // Must never throw: stop() destroys clangd, which closes this pipe under the reader
        // ("Stream closed"), and an exception escaping any thread ends the Android app.
        BackgroundThreads.drainLines(process.errorStream, "clangd-stderr") { line ->
            synchronized(stderrTail) {
                stderrTail.addLast(line)
                while (stderrTail.size > 50) stderrTail.removeFirst()
            }
        }
    }

    /** Last lines clangd wrote to stderr, for error reports. */
    fun recentLog(): List<String> = synchronized(stderrTail) { stderrTail.toList() }

    /** Opens [file] or, when already open, sends its new [text] if it changed. */
    fun sync(file: File, text: String) {
        val uri = DocumentUri.of(file)
        val previous = sentText[uri]
        if (previous == text) return
        val version = (versions[uri] ?: 0) + 1
        versions[uri] = version
        sentText[uri] = text
        if (previous == null) client.didOpen(uri, languageId(file), version, text) else client.didChange(uri, version, text)
    }

    fun saved(file: File) {
        val uri = DocumentUri.of(file)
        if (sentText.containsKey(uri)) client.didSave(uri)
    }

    fun close(file: File) {
        val uri = DocumentUri.of(file)
        if (sentText.remove(uri) != null) {
            versions.remove(uri)
            client.didClose(uri)
        }
    }

    fun completion(file: File, text: String, position: Position): CompletableFuture<CompletionResult> {
        sync(file, text)
        return client.completion(DocumentUri.of(file), position)
    }

    fun definition(file: File, text: String, position: Position): CompletableFuture<List<Location>> {
        sync(file, text)
        val uri = DocumentUri.of(file)
        // Prefer the definition; fall back to the declaration (functions from library headers).
        return client.definition(uri, position).thenCompose { found ->
            if (found.isNotEmpty()) CompletableFuture.completedFuture(found) else client.declaration(uri, position)
        }
    }

    fun hover(file: File, text: String, position: Position): CompletableFuture<String?> {
        sync(file, text)
        return client.hover(DocumentUri.of(file), position)
    }

    fun rename(file: File, text: String, position: Position, newName: String): CompletableFuture<WorkspaceEdit> {
        sync(file, text)
        return client.rename(DocumentUri.of(file), position, newName)
    }

    fun formatting(file: File, text: String, tabSize: Int): CompletableFuture<List<TextEdit>> {
        sync(file, text)
        return client.formatting(DocumentUri.of(file), tabSize)
    }

    fun stop() {
        runCatching { client.shutdown() }
        if (!runCatching { process.waitFor(1, TimeUnit.SECONDS) }.getOrDefault(false)) process.destroy()
    }

    companion object {
        /** File extensions clangd understands. */
        val SUPPORTED_EXTENSIONS = setOf("c", "h", "cpp", "cc", "cxx", "c++", "hpp", "hh", "hxx", "inl", "ipp", "m", "mm")

        fun supports(file: File): Boolean = file.extension.lowercase() in SUPPORTED_EXTENSIONS

        fun languageId(file: File): String = when (file.extension.lowercase()) {
            "c" -> "c"
            "m" -> "objective-c"
            "mm" -> "objective-cpp"
            "h" -> "cpp" // headers are parsed as C++ unless the database says otherwise
            else -> "cpp"
        }

        /**
         * Writes the compilation database and clangd config, starts clangd and initializes it.
         * Blocks up to [timeoutMillis] for the handshake.
         */
        fun start(request: BuildRequest, options: CodeIntelOptions, timeoutMillis: Long = 20_000): ClangdSession {
            val toolchain = request.toolchain
            val clangd = toolchain.clangd
            require(clangd.isFile) { "clangd is not installed (it comes with the clang package)" }
            val environment = toolchain.environment()
            val databaseDir = ClangdFiles.writeDatabase(request)
            environment["HOME"]?.let { home -> ClangdFiles.writeUserConfig(File(home), toolchain.baseCompileFlags(), options) }

            val process = ProcessBuilder(command(clangd, databaseDir, options))
                .directory(request.project.root)
                .apply { environment().putAll(environment) }
                .start()
            val connection = JsonRpcConnection(process.inputStream, process.outputStream, "clangd").start()
            val client = LspClient(connection)
            val session = ClangdSession(process, client, request.project.root)
            try {
                client.initialize(request.project.root).get(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                val log = session.recentLog().takeLast(5).joinToString("\n")
                session.stop()
                throw IllegalStateException("clangd did not start: ${e.cause?.message ?: e.message}\n$log".trim(), e)
            }
            return session
        }

        /**
         * clangd's command line. Light on a phone: a bounded worker pool, preambles on disk
         * instead of in memory, and the background index on the lowest priority so typing,
         * completion and builds come first.
         */
        fun command(clangd: File, databaseDir: File, options: CodeIntelOptions): List<String> = buildList {
            add(clangd.path)
            add("--compile-commands-dir=${databaseDir.path}")
            add("--background-index=${options.backgroundIndex}")
            if (options.backgroundIndex) add("--background-index-priority=low")
            add("-j=${options.threads.coerceIn(1, 8)}")
            add("--pch-storage=disk")
            add("--header-insertion=iwyu")
            add("--completion-style=detailed")
            add("--function-arg-placeholders=false")
            add("--limit-results=100")
            add("--log=" + (System.getenv("NF_CLANGD_LOG") ?: "error"))
            if (options.clangTidy) add("--clang-tidy")
        }
    }
}

/** Files clangd reads: the compilation database and the user config. */
object ClangdFiles {

    /** Folder holding the project's compile_commands.json for clangd. */
    fun databaseDirectory(request: BuildRequest): File = File(request.project.buildDirectory, ".nativeforge")

    /**
     * Writes compile_commands.json: CMake's own database when CMake already configured the
     * project, otherwise one with the flags NativeForge would build with.
     */
    fun writeDatabase(request: BuildRequest): File {
        val dir = databaseDirectory(request).apply { mkdirs() }
        val target = File(dir, "compile_commands.json")
        val cmake = if (request.project.config.buildSystem == BuildSystemKind.CMAKE) CompileDatabase.cmakeDatabase(request) else null
        val json = cmake?.readText() ?: CompileDatabase.toJson(CompileDatabase.commands(request))
        if (!target.isFile || target.readText() != json) target.writeText(json)
        return dir
    }

    /**
     * `~/.config/clangd/config.yaml`: the target and sysroot for every file, and the
     * clang-tidy checks when enabled. A project's own `.clangd` file still applies on top.
     */
    fun writeUserConfig(home: File, baseFlags: List<String>, options: CodeIntelOptions) {
        val file = File(home, ".config/clangd/config.yaml")
        val flags = baseFlags.filterNot { it.startsWith("-fdiagnostics-color") }
        val yaml = buildString {
            appendLine("# Written by NativeForge on every start; edit the project's .clangd file instead.")
            appendLine("CompileFlags:")
            appendLine("  Add: [${flags.joinToString(", ") { quoteYaml(it) }}]")
            appendLine("Diagnostics:")
            // Include-cleaner hints are noise for small projects and quick experiments.
            appendLine("  UnusedIncludes: None")
            appendLine("  MissingIncludes: None")
            appendLine("  ClangTidy:")
            if (options.clangTidy) {
                appendLine("    Add: [${TIDY_CHECKS.joinToString(", ") { quoteYaml(it) }}]")
                appendLine("    Remove: [bugprone-easily-swappable-parameters, readability-magic-numbers]")
            } else {
                appendLine("    Remove: ['*']")
            }
        }
        file.parentFile?.mkdirs()
        if (!file.isFile || file.readText() != yaml) file.writeText(yaml)
    }

    /** A useful, low-noise default set of clang-tidy checks. */
    val TIDY_CHECKS = listOf(
        "bugprone-*", "performance-*", "clang-analyzer-core.*", "modernize-use-nullptr",
        "modernize-use-override", "readability-misleading-indentation", "readability-redundant-*",
    )

    private fun quoteYaml(value: String) = "'" + value.replace("'", "''") + "'"
}
