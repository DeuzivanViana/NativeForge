package io.nativeforge.core.remote

import io.nativeforge.core.common.SafeFiles
import java.io.File
import java.io.IOException

/** One entry of a project's file tree; [path] is relative to the project root, with '/'. */
data class FileEntry(val path: String, val isDirectory: Boolean, val size: Long, val modified: Long)

/** Thrown for file requests that are refused (outside the project, too large, missing). */
class FileAccessException(val status: Int, message: String) : IOException(message)

/**
 * File operations of the web IDE, confined to one project folder.
 *
 * Every path the browser sends is relative to the project root and goes through [resolve]:
 * absolute paths, `..` segments, NUL bytes and anything whose canonical path (symbolic links
 * followed) lands outside the root are refused, so a request can never read or write other
 * files of the app, such as signing keys or the trusted-browser list.
 */
class ProjectFiles(root: File, private val hiddenDirectories: Set<File> = emptySet()) {
    val root: File = root.canonicalFile

    fun resolve(relative: String?, allowRoot: Boolean = false): File {
        val normalized = relative?.replace('\\', '/') ?: throw FileAccessException(400, "Missing path")
        if (normalized.startsWith("/")) throw FileAccessException(400, "Paths are relative to the project")
        val path = normalized.trimEnd('/')
        if (path.isEmpty()) {
            if (allowRoot) return root
            throw FileAccessException(400, "Missing path")
        }
        if (path.contains('\u0000') || path.split('/').any { it == ".." || it == "." || it.isEmpty() }) {
            throw FileAccessException(400, "Invalid path")
        }
        return SafeFiles.resolveInside(root, path) ?: throw FileAccessException(403, "Path is outside the project")
    }

    fun relativePath(file: File): String = file.canonicalFile.relativeTo(root).invariantSeparatorsPath

    /** Files and folders below the root, folders first, without VCS and build folders. */
    fun tree(maxEntries: Int = MAX_TREE_ENTRIES): List<FileEntry> {
        val result = ArrayList<FileEntry>()
        val hidden = hiddenDirectories.mapNotNull { runCatching { it.canonicalPath }.getOrNull() }.toSet()
        fun visit(dir: File, depth: Int) {
            val children = dir.listFiles().orEmpty().sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            for (child in children) {
                if (result.size >= maxEntries) return
                if (SafeFiles.isSymlink(child) && child.isDirectory) continue
                if (child.isDirectory && (child.name in SKIPPED_DIRS || child.canonicalPath in hidden)) continue
                result += FileEntry(relativePath(child), child.isDirectory, if (child.isFile) child.length() else 0, child.lastModified())
                if (child.isDirectory && depth < MAX_DEPTH) visit(child, depth + 1)
            }
        }
        visit(root, 0)
        return result
    }

    /** Text of a file, or null when it looks binary. */
    fun readText(relative: String): ReadResult {
        val file = resolve(relative)
        if (!file.isFile) throw FileAccessException(404, "No such file")
        if (file.length() > MAX_TEXT_BYTES) throw FileAccessException(413, "File is larger than ${MAX_TEXT_BYTES / (1024 * 1024)} MB")
        val bytes = file.readBytes()
        val binary = bytes.take(8000).any { it == 0.toByte() }
        return ReadResult(if (binary) null else String(bytes, Charsets.UTF_8), file.length(), file.lastModified())
    }

    data class ReadResult(val text: String?, val size: Long, val modified: Long)

    /**
     * Writes [bytes] atomically (temporary file, then rename), creating parent folders.
     * When [expectedModified] is given and the file changed on disk since, nothing is written.
     */
    fun write(relative: String, bytes: ByteArray, expectedModified: Long? = null): Long = synchronized(WRITE_LOCK) {
        val file = resolve(relative)
        if (file.isDirectory) throw FileAccessException(409, "A folder has that name")
        if (expectedModified != null && file.isFile && file.lastModified() != expectedModified) {
            throw FileAccessException(409, "The file changed on the device since it was opened")
        }
        val parent = file.parentFile
        if (!parent.isDirectory && !parent.mkdirs()) throw FileAccessException(500, "Could not create the folder")
        val tmp = File(parent, ".${file.name}.${System.nanoTime()}.nf-tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        file.lastModified()
    }

    fun createDirectory(relative: String) {
        val dir = resolve(relative)
        if (dir.exists()) throw FileAccessException(409, "Already exists")
        if (!dir.mkdirs()) throw FileAccessException(500, "Could not create the folder")
    }

    fun createFile(relative: String) {
        val file = resolve(relative)
        if (file.exists()) throw FileAccessException(409, "Already exists")
        write(relative, ByteArray(0))
    }

    fun rename(from: String, to: String) {
        val source = resolve(from)
        val target = resolve(to)
        if (!source.exists()) throw FileAccessException(404, "No such file")
        if (target.exists()) throw FileAccessException(409, "Already exists")
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) throw FileAccessException(500, "Could not rename")
    }

    fun delete(relative: String) {
        val file = resolve(relative)
        if (!file.exists() && !SafeFiles.isSymlink(file)) throw FileAccessException(404, "No such file")
        val ok = if (file.isDirectory && !SafeFiles.isSymlink(file)) SafeFiles.deleteTree(file) else file.delete()
        if (!ok) throw FileAccessException(500, "Could not delete")
    }

    companion object {
        const val MAX_TEXT_BYTES = 8L * 1024 * 1024
        const val MAX_TREE_ENTRIES = 5000
        private const val MAX_DEPTH = 16
        val SKIPPED_DIRS = setOf(".git", ".cache", "node_modules")

        /** Writes are short; one lock keeps two saves of the same file from interleaving. */
        private val WRITE_LOCK = Any()
    }
}
