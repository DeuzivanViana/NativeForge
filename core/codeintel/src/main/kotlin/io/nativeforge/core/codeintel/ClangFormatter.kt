package io.nativeforge.core.codeintel

import io.nativeforge.core.common.process.BackgroundThreads
import java.io.File
import java.util.concurrent.TimeUnit

/** A text replacement in character (UTF-16) offsets of the original text. */
data class Replacement(val offset: Int, val length: Int, val text: String)

/** Built-in clang-format styles, used when the project has no .clang-format file. */
enum class FormatStyle(val clangName: String) {
    LLVM("LLVM"), GOOGLE("Google"), CHROMIUM("Chromium"), MOZILLA("Mozilla"), WEBKIT("WebKit"), MICROSOFT("Microsoft"), GNU("GNU"),
}

/**
 * Formats C/C++ sources with clang-format.
 *
 * clang-format reports replacements (`--output-replacements-xml`) instead of the whole
 * formatted file, so the editor applies only what changed: the caret stays in place and
 * the change is one undo step.
 */
class ClangFormatter(
    private val clangFormat: File,
    private val environment: Map<String, String> = emptyMap(),
) {
    val isAvailable: Boolean get() = clangFormat.isFile

    /**
     * Returns the replacements that format [text] (the contents of [file]), using the
     * project's .clang-format when one exists between [file] and [projectRoot].
     */
    fun format(text: String, file: File, projectRoot: File, fallback: FormatStyle, indentWidth: Int): List<Replacement> {
        require(isAvailable) { "clang-format is not installed (it comes with the clang package)" }
        val style = if (hasStyleFile(file, projectRoot)) "file" else
            "{BasedOnStyle: ${fallback.clangName}, IndentWidth: $indentWidth, TabWidth: $indentWidth, UseTab: Never, ColumnLimit: 100}"
        val command = listOf(clangFormat.path, "--assume-filename=${file.path}", "--style=$style", "--output-replacements-xml")
        val process = ProcessBuilder(command)
            .directory(file.parentFile ?: projectRoot)
            .redirectErrorStream(false)
            .apply { environment().putAll(environment) }
            .start()
        val input = text.toByteArray(Charsets.UTF_8)
        // Not plain threads: clang-format exiting before it read everything (a bad style) makes
        // the write fail with "Broken pipe", and an uncaught exception would end the app.
        val writer = BackgroundThreads.writeAndClose(process.outputStream, input, "clang-format-input")
        val errors = StringBuffer()
        val errorReader = BackgroundThreads.drainLines(process.errorStream, "clang-format-errors") { errors.appendLine(it) }
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroy()
            error("clang-format timed out")
        }
        writer.join(1_000)
        errorReader.join(1_000)
        if (process.exitValue() != 0) error("clang-format failed: ${errors.toString().trim().ifEmpty { "exit code ${process.exitValue()}" }}")
        return parseReplacements(output, input)
    }

    companion object {
        val STYLE_FILES = listOf(".clang-format", "_clang-format")

        fun hasStyleFile(file: File, projectRoot: File): Boolean {
            val root = projectRoot.absoluteFile
            var dir = file.absoluteFile.parentFile
            while (dir != null) {
                if (STYLE_FILES.any { File(dir, it).isFile }) return true
                if (dir == root) return false
                dir = dir.parentFile
            }
            return false
        }

        private val REPLACEMENT = Regex("<replacement offset='(\\d+)' length='(\\d+)'>(.*?)</replacement>|<replacement offset='(\\d+)' length='(\\d+)'/>", RegexOption.DOT_MATCHES_ALL)

        /** Parses clang-format's XML; its offsets count UTF-8 bytes of [utf8], converted here to chars. */
        fun parseReplacements(xml: String, utf8: ByteArray): List<Replacement> {
            val text = String(utf8, Charsets.UTF_8)
            val byteToChar = byteToCharOffsets(text, utf8.size)
            return REPLACEMENT.findAll(xml).map { match ->
                val offset = (match.groupValues[1].ifEmpty { match.groupValues[4] }).toInt()
                val length = (match.groupValues[2].ifEmpty { match.groupValues[5] }).toInt()
                val start = byteToChar[offset.coerceIn(0, utf8.size)]
                val end = byteToChar[(offset + length).coerceIn(0, utf8.size)]
                Replacement(start, end - start, unescape(match.groupValues[3]))
            }.toList()
        }

        /** Maps each UTF-8 byte offset (0..size) to the UTF-16 offset of the char it belongs to. */
        private fun byteToCharOffsets(text: String, size: Int): IntArray {
            val map = IntArray(size + 1)
            var byte = 0
            var index = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                val bytes = when {
                    codePoint < 0x80 -> 1
                    codePoint < 0x800 -> 2
                    codePoint < 0x10000 -> 3
                    else -> 4
                }
                repeat(bytes) { if (byte + it <= size) map[byte + it] = index }
                byte += bytes
                index += Character.charCount(codePoint)
            }
            if (byte <= size) map[byte.coerceAtMost(size)] = text.length
            for (i in byte + 1..size) map[i] = text.length
            return map
        }

        private fun unescape(value: String): String {
            val numeric = Regex("&#(x?)([0-9a-fA-F]+);")
            return numeric.replace(value) { m ->
                val code = if (m.groupValues[1].isNotEmpty()) m.groupValues[2].toInt(16) else m.groupValues[2].toInt()
                String(Character.toChars(code))
            }.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        }

        /** Applies [replacements] to [text] (used by tests and as a fallback). */
        fun apply(text: String, replacements: List<Replacement>): String {
            val builder = StringBuilder(text)
            replacements.sortedByDescending { it.offset }.forEach { builder.replace(it.offset, it.offset + it.length, it.text) }
            return builder.toString()
        }
    }
}
