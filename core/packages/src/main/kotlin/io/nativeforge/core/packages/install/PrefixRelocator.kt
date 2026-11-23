package io.nativeforge.core.packages.install

import java.io.File

/**
 * Rewrites a hard-coded install prefix inside text files (shell scripts,
 * pkg-config `.pc` files, CMake package configs...). Binary files are left
 * untouched because their string tables cannot change length; binaries are
 * handled through environment variables instead.
 */
class PrefixRelocator(
    private val oldPrefix: String,
    private val newPrefix: String,
    private val maxFileSize: Long = 4L * 1024 * 1024,
) {

    /** @return number of files rewritten. */
    fun relocate(root: File, relativePaths: List<String>): Int {
        if (oldPrefix == newPrefix) return 0
        val needle = oldPrefix.toByteArray()
        var rewritten = 0
        relativePaths.asSequence()
            .map { File(root, it) }
            .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.length() <= maxFileSize }
            .forEach { file ->
                val bytes = file.readBytes()
                if (isText(bytes) && bytes.containsSequence(needle)) {
                    file.writeText(bytes.decodeToString().replace(oldPrefix, newPrefix))
                    rewritten++
                }
            }
        return rewritten
    }

    private fun isText(bytes: ByteArray): Boolean {
        val sample = minOf(bytes.size, 8192)
        if (sample >= 4 && bytes[0] == 0x7F.toByte() && bytes[1] == 'E'.code.toByte()) return false
        for (i in 0 until sample) if (bytes[i] == 0.toByte()) return false
        return true
    }

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
