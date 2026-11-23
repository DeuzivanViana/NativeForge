package io.nativeforge.core.common

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * File helpers that never follow symbolic links. Kotlin's `deleteRecursively` and
 * `walkTopDown` enter linked folders, so deleting a project that contains a link to
 * another folder would delete that folder's contents too.
 */
object SafeFiles {

    fun isSymlink(file: File): Boolean = Files.isSymbolicLink(file.toPath())

    /** Deletes [root] and everything below it; links are removed, never followed. */
    fun deleteTree(root: File): Boolean {
        val path = root.toPath()
        if (!Files.exists(path) && !Files.isSymbolicLink(path)) return true
        return try {
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path)) {
                Files.delete(path)
            } else {
                Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        Files.delete(dir)
                        return FileVisitResult.CONTINUE
                    }
                })
            }
            true
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Regular files below [root] (sorted), skipping links and anything whose real
     * location is outside [root].
     */
    fun regularFiles(root: File): List<File> {
        if (!root.isDirectory) return emptyList()
        val base = root.canonicalPath + File.separator
        return root.walkTopDown()
            .onEnter { it == root || !isSymlink(it) }
            .filter { it.isFile && !isSymlink(it) && it.canonicalPath.startsWith(base) }
            .sortedBy { it.path }
            .toList()
    }

    /** [relative] resolved under [root], or null when it would point outside it. */
    fun resolveInside(root: File, relative: String): File? {
        if (relative.isBlank()) return null
        val file = File(root, relative.trim())
        val base = root.canonicalPath
        val canonical = file.canonicalPath
        return file.takeIf { canonical == base || canonical.startsWith(base + File.separator) }
    }
}
