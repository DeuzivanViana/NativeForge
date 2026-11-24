package io.nativeforge.core.export.web

import io.nativeforge.core.common.NfError
import io.nativeforge.core.common.Outcome
import io.nativeforge.core.common.SafeFiles
import io.nativeforge.core.common.outcomeOf
import io.nativeforge.core.project.runtime.LaunchSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A console program built for `wasm32-wasip1`, to package as a web page. */
data class WebExportRequest(
    /** The WebAssembly module produced by the build (a WASI command with `_start`). */
    val wasm: File,
    /** Page title (the app name from the export settings). */
    val title: String,
    /** Program name passed as `argv[0]`; also names the files in the zip. */
    val programName: String,
    val arguments: List<String> = emptyList(),
    /** Project `assets/` folder, readable by the program as `assets/<file>`, or null. */
    val assetsDirectory: File? = null,
    /** Folder that receives `index.html`, the module and a README (replaced on each export). */
    val outputDirectory: File,
    /** Zip of [outputDirectory]'s files, to save or share. */
    val outputZip: File,
)

data class WebExport(val page: File, val zip: File)

/**
 * Packages a WASI program as a page that runs in any modern browser, opened from a file
 * or served by any static host:
 *
 * * `index.html` is self-contained: the module (base64), the project's assets, the WASI
 *   runtime and a small terminal. A Content-Security-Policy allows only its own inline
 *   script and style (by hash) and WebAssembly compilation; no network access at all.
 * * `<name>.wasm` is the same module, for wasmtime, wasmer or Node.js.
 *
 * Programs run on the page's thread. Where the browser supports JavaScript Promise
 * Integration (Chromium 137+, Android WebView), reads from stdin, sleeps and output yield
 * to the page, so input is typed in the terminal and Stop works; elsewhere input is asked
 * in a dialog.
 */
class WebExporter(private val limits: Limits = Limits()) {

    data class Limits(val maxModuleBytes: Long = 64L shl 20, val maxAssetBytes: Long = 32L shl 20)

    fun export(request: WebExportRequest): Outcome<WebExport> {
        val wasm = request.wasm
        if (!wasm.isFile) return Outcome.Failure(NfError.NotFound("The build produced no WebAssembly module"))
        if (wasm.length() > limits.maxModuleBytes) {
            return Outcome.Failure(NfError.Validation("The module is larger than ${limits.maxModuleBytes shr 20} MB"))
        }
        val module = wasm.readBytes()
        if (!isWasiModule(module)) {
            return Outcome.Failure(NfError.Validation("${wasm.name} is not a WebAssembly module"))
        }
        val assets = request.assetsDirectory?.let(SafeFiles::regularFiles).orEmpty()
        val assetBytes = assets.sumOf { it.length() }
        if (assetBytes > limits.maxAssetBytes) {
            return Outcome.Failure(
                NfError.Validation(
                    "The assets folder holds ${assetBytes shr 20} MB; a web page can embed at most ${limits.maxAssetBytes shr 20} MB. " +
                        "Turn off \"Include assets\" or remove large files.",
                ),
            )
        }
        return outcomeOf(errorFactory = { NfError.Io("Could not write the web export: ${it.message}", it) }) {
            val name = fileName(request.programName)
            val files = assets.map { file ->
                val relative = file.relativeTo(request.assetsDirectory!!).invariantSeparatorsPath
                PageFile("${LaunchSpec.BUNDLED_ASSETS_DIR}/$relative", base64(file.readBytes()))
            }
            val page = page(request.title, PageConfig(request.title, name, request.arguments, ENVIRONMENT, files), module)
            val readme = readme(request.title, name)

            val dir = request.outputDirectory
            SafeFiles.deleteTree(dir)
            dir.mkdirs()
            val pageFile = File(dir, "index.html").apply { writeText(page) }
            File(dir, "$name.wasm").writeBytes(module)
            File(dir, "README.txt").writeText(readme)

            request.outputZip.parentFile?.mkdirs()
            ZipOutputStream(request.outputZip.outputStream().buffered()).use { zip ->
                listOf("index.html" to page.toByteArray(), "$name.wasm" to module, "README.txt" to readme.toByteArray())
                    .forEach { (entry, bytes) ->
                        // Fixed times make the zip depend only on its contents.
                        zip.putNextEntry(ZipEntry("$name/$entry").apply { time = ZIP_TIME })
                        zip.write(bytes)
                        zip.closeEntry()
                    }
            }
            WebExport(pageFile, request.outputZip)
        }
    }

    @Serializable
    internal data class PageFile(val path: String, val data: String)

    @Serializable
    internal data class PageConfig(
        val title: String,
        val program: String,
        val args: List<String>,
        val env: List<String>,
        val files: List<PageFile>,
    )

    internal fun page(title: String, config: PageConfig, module: ByteArray): String {
        val script = resource("wasi.js") + "\n" + resource("page.js")
        val style = resource("page.css")
        // Neither may end the element it is inlined in.
        check(!script.contains("</script", ignoreCase = true) && !style.contains("</style", ignoreCase = true))
        val csp = listOf(
            "default-src 'none'",
            "script-src 'sha256-${sha256(script)}' 'wasm-unsafe-eval'",
            "style-src 'sha256-${sha256(style)}'",
            "img-src data:",
            "base-uri 'none'",
            "form-action 'none'",
        ).joinToString("; ")
        val values = mapOf(
            "CSP" to csp,
            "TITLE" to escapeHtml(title.ifBlank { config.program }),
            "STYLE" to style,
            "CONFIG" to scriptJson(json.encodeToString(PageConfig.serializer(), config)),
            "WASM" to base64(module),
            "SCRIPT" to script,
        )
        // One pass over the template, so text inside a value is never taken for a placeholder.
        return PLACEHOLDER.replace(resource("page.html")) { values.getValue(it.groupValues[1]) }
    }

    private fun readme(title: String, name: String) = """
        |$title, exported by NativeForge as WebAssembly (WASI preview 1).
        |
        |index.html   Open it in a browser (Chrome, Edge, Firefox, Safari), from a file or any
        |             static web host. It contains the program and its assets and needs no network.
        |$name.wasm
        |             The same program for WASI runtimes, e.g. wasmtime $name.wasm
        |
        |In the page, the program has a terminal with stdin, the files of the project's
        |assets/ folder (copies: changes and new files last until the page is closed),
        |a clock and random numbers. There are no threads, sockets or graphics.
        |
        |The page's scripts are under the zlib license (NativeForge runtime exception). The
        |module statically links wasi-libc (MIT, BSD-2-Clause, Apache-2.0) and, for C++,
        |libc++ (Apache-2.0 WITH LLVM-exception).
        |""".trimMargin()

    companion object {
        /** Folder under the exports directory that holds unpacked web exports. */
        const val OUTPUT_FOLDER = "web"

        private val ENVIRONMENT = listOf("TERM=xterm-256color", "HOME=/", "PWD=/", "LANG=en_US.UTF-8")
        private const val ZIP_TIME = 1_704_067_200_000L // 2024-01-01

        private val json = Json { encodeDefaults = true }
        private val PLACEHOLDER = Regex("""\{\{(CSP|TITLE|STYLE|CONFIG|WASM|SCRIPT)\}\}""")

        /** True for a binary WebAssembly module (`\0asm`, version 1). */
        fun isWasiModule(bytes: ByteArray): Boolean =
            bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0, 0x61, 0x73, 0x6d, 1, 0, 0, 0))

        /** A file name safe on every system, from the program name. */
        fun fileName(programName: String): String =
            programName.replace(Regex("[^A-Za-z0-9_.-]"), "_").trim('.', '_').ifEmpty { "program" }.take(64)

        internal fun escapeHtml(text: String): String = buildString {
            text.forEach { c ->
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(c)
                }
            }
        }

        /** JSON that cannot close or confuse the script element it sits in. */
        internal fun scriptJson(text: String): String =
            text.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")

        private fun base64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

        private fun sha256(text: String) =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

        private fun resource(name: String): String =
            requireNotNull(WebExporter::class.java.getResourceAsStream("/io/nativeforge/core/export/web/$name")) {
                "Missing web runtime resource $name"
            }.use { it.readBytes().decodeToString() }
    }
}
