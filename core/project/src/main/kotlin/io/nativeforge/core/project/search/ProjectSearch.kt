package io.nativeforge.core.project.search

import io.nativeforge.core.project.model.Project
import java.io.File
import java.nio.file.Files

/** What to look for in "Search in project". */
data class SearchQuery(
    val text: String,
    val matchCase: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false,
)

/** One hit. [line] and [column] are 1-based; [start]/[end] are character offsets in the file. */
data class SearchMatch(
    val line: Int,
    val column: Int,
    val start: Int,
    val end: Int,
    /** The line around the hit (shortened when long), with the hit at [previewStart] until [previewEnd]. */
    val preview: String,
    val previewStart: Int,
    val previewEnd: Int,
)

data class FileMatches(val file: File, val relativePath: String, val matches: List<SearchMatch>)

data class SearchResult(
    val files: List<FileMatches>,
    val totalMatches: Int,
    /** True when the search stopped at the match limit. */
    val truncated: Boolean,
) {
    companion object {
        val EMPTY = SearchResult(emptyList(), 0, false)
    }
}

/** Finds and replaces text in every text file of a project. Pure JVM so it can be unit tested. */
object ProjectSearch {
    const val MAX_MATCHES = 5000
    const val MAX_FILE_BYTES = 4L * 1024 * 1024
    private const val PREVIEW_CONTEXT = 60

    /** Folders that are never searched: version control, IDE metadata and the build output. */
    private val SKIPPED_DIRS = setOf(".git", ".cache", ".gradle", ".idea", ".vscode")

    /** The pattern for [query], or a failure with the regex error. Blank queries match nothing. */
    fun compile(query: SearchQuery): Result<Regex?> = runCatching {
        if (query.text.isEmpty()) return@runCatching null
        var pattern = if (query.regex) query.text else Regex.escape(query.text)
        if (query.wholeWord) pattern = "(?<![A-Za-z0-9_])(?:$pattern)(?![A-Za-z0-9_])"
        val options = buildSet {
            if (!query.matchCase) add(RegexOption.IGNORE_CASE)
            if (query.regex) add(RegexOption.MULTILINE)
        }
        Regex(pattern, options)
    }

    /** Text files below [root], in path order, skipping links, binaries, big files and [SKIPPED_DIRS]. */
    fun searchableFiles(root: File): List<File> {
        if (!root.isDirectory) return emptyList()
        val base = root.canonicalPath + File.separator
        return root.walkTopDown()
            .onEnter { dir ->
                dir == root || (!Files.isSymbolicLink(dir.toPath()) && dir.name !in SKIPPED_DIRS &&
                    !(dir.parentFile == root && dir.name == Project.BUILD_DIR))
            }
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && it.length() <= MAX_FILE_BYTES }
            .filter { runCatching { it.canonicalPath.startsWith(base) }.getOrDefault(false) }
            .filterNot(::isBinary)
            .sortedBy { it.relativeTo(root).path.lowercase() }
            .toList()
    }

    /**
     * Searches every file of [root]. [textOf] supplies the text of files open in the editor
     * (with unsaved changes); other files are read from disk. [checkCancelled] is called
     * between files and may throw to stop.
     */
    fun search(
        root: File,
        query: SearchQuery,
        textOf: (File) -> String? = { null },
        maxMatches: Int = MAX_MATCHES,
        checkCancelled: () -> Unit = {},
    ): SearchResult {
        val regex = compile(query).getOrNull() ?: return SearchResult.EMPTY
        val files = mutableListOf<FileMatches>()
        var total = 0
        for (file in searchableFiles(root)) {
            checkCancelled()
            val text = textOf(file) ?: runCatching { file.readText() }.getOrNull() ?: continue
            val matches = find(text, regex, maxMatches - total)
            if (matches.isEmpty()) continue
            files += FileMatches(file, file.relativeTo(root).path, matches)
            total += matches.size
            if (total >= maxMatches) return SearchResult(files, total, truncated = true)
        }
        return SearchResult(files, total, truncated = false)
    }

    /** Matches of [regex] in [text], at most [limit]. Empty matches are skipped. */
    fun find(text: String, regex: Regex, limit: Int = MAX_MATCHES): List<SearchMatch> {
        if (limit <= 0) return emptyList()
        val lineStarts = lineStarts(text)
        val result = mutableListOf<SearchMatch>()
        for (match in regex.findAll(text)) {
            if (match.range.isEmpty()) continue
            val start = match.range.first
            val end = match.range.last + 1
            val lineIndex = lineIndexOf(lineStarts, start)
            val lineStart = lineStarts[lineIndex]
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }.let {
                if (it > lineStart && text[it - 1] == '\r') it - 1 else it
            }
            result += preview(text, lineStart, lineEnd, start, minOf(end, lineEnd), lineIndex + 1)
            if (result.size >= limit) break
        }
        return result
    }

    /**
     * [text] with every match of [query] replaced by [replacement], and the number of
     * replacements. In regex mode `$1`, `${name}` refer to groups; otherwise the
     * replacement is literal.
     */
    fun replace(text: String, query: SearchQuery, replacement: String): Pair<String, Int> {
        val regex = compile(query).getOrNull() ?: return text to 0
        var count = 0
        // The transform's result is inserted literally.
        val replaced = regex.replace(text) { match ->
            if (match.range.isEmpty()) return@replace match.value
            count++
            if (query.regex) expand(match, replacement) else replacement
        }
        return replaced to count
    }

    /** Replacements as (offset, length, text) for an editor that keeps undo history. */
    fun replacements(text: String, query: SearchQuery, replacement: String): List<Triple<Int, Int, String>> {
        val regex = compile(query).getOrNull() ?: return emptyList()
        return regex.findAll(text).filterNot { it.range.isEmpty() }.map { match ->
            val value = if (query.regex) expand(match, replacement) else replacement
            Triple(match.range.first, match.range.last + 1 - match.range.first, value)
        }.toList()
    }

    /** Expands `$n`, `${name}` and `\x` escapes in [replacement] against [match], like java.util.regex. */
    private fun expand(match: MatchResult, replacement: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < replacement.length) {
            val c = replacement[i]
            when {
                c == '\\' && i + 1 < replacement.length -> { out.append(replacement[i + 1]); i += 2 }
                c == '$' && i + 1 < replacement.length && replacement[i + 1] == '{' -> {
                    val close = replacement.indexOf('}', i + 2)
                    if (close < 0) { out.append(c); i++; continue }
                    val name = replacement.substring(i + 2, close)
                    val value = runCatching { (match.groups as? MatchNamedGroupCollection)?.get(name)?.value }.getOrNull()
                    out.append(value.orEmpty())
                    i = close + 1
                }
                c == '$' && i + 1 < replacement.length && replacement[i + 1].isDigit() -> {
                    var j = i + 1
                    var group = 0
                    // Take as many digits as still name an existing group, like java.util.regex.
                    while (j < replacement.length && replacement[j].isDigit()) {
                        val next = group * 10 + (replacement[j] - '0')
                        if (next > match.groupValues.size - 1 && j > i + 1) break
                        group = next
                        j++
                    }
                    out.append(if (group < match.groups.size) match.groups[group]?.value.orEmpty() else "")
                    i = j
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun preview(text: String, lineStart: Int, lineEnd: Int, start: Int, end: Int, line: Int): SearchMatch {
        var from = lineStart
        var to = lineEnd
        if (start - from > PREVIEW_CONTEXT) from = start - PREVIEW_CONTEXT
        if (to - end > PREVIEW_CONTEXT * 2) to = end + PREVIEW_CONTEXT * 2
        val raw = text.substring(from, to)
        val leading = raw.length - raw.trimStart().length
        val shown = raw.substring(minOf(leading, start - from)).trimEnd()
        val cut = from + minOf(leading, start - from)
        val prefix = if (from > lineStart) "…" else ""
        val suffix = if (to < lineEnd) "…" else ""
        val hitStart = prefix.length + (start - cut)
        val hitEnd = (hitStart + (end - start)).coerceAtMost(prefix.length + shown.length)
        return SearchMatch(
            line = line,
            column = start - lineStart + 1,
            start = start,
            end = end,
            preview = prefix + shown + suffix,
            previewStart = hitStart,
            previewEnd = maxOf(hitStart, hitEnd),
        )
    }

    private fun lineStarts(text: String): IntArray {
        val starts = ArrayList<Int>()
        starts += 0
        text.forEachIndexed { index, c -> if (c == '\n') starts += index + 1 }
        return starts.toIntArray()
    }

    private fun lineIndexOf(lineStarts: IntArray, offset: Int): Int {
        val found = lineStarts.binarySearch(offset)
        return if (found >= 0) found else -found - 2
    }

    private fun isBinary(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            val read = input.read(buffer)
            (0 until maxOf(read, 0)).any { buffer[it] == 0.toByte() }
        }
    }.getOrDefault(true)
}
