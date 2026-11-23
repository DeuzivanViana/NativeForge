package io.nativeforge.core.toolchain

import java.io.File
import java.nio.file.Files

/**
 * Termux builds ninja, make and cmake with the shell path `/data/data/com.termux/files/usr/bin/sh`
 * compiled in (ninja runs every build command through it, make runs recipes with it, and
 * cmake writes it as `SHELL =` into generated Makefiles). That file does not exist outside
 * Termux, so every command fails with exit code 127 ("not found"): CMake's compiler check
 * with Ninja is the first thing to hit it.
 *
 * Strings inside binaries cannot change length, so the path is replaced in place by
 * `/////////////////////////system/bin/sh`: the same length, and repeated slashes are a
 * valid spelling of `/system/bin/sh`. The tail is unchanged, so any other string the
 * linker merged into it (`/bin/sh`, `sh`) still reads the same.
 */
object TermuxShellPatch {

    private const val ORIGINAL = ToolchainLayout.TERMUX_PREFIX + "/bin/sh"
    private const val TARGET = "system/bin/sh"
    private val NEEDLE = ORIGINAL.toByteArray(Charsets.US_ASCII)
    private val REPLACEMENT = ("/".repeat(ORIGINAL.length - TARGET.length) + TARGET).toByteArray(Charsets.US_ASCII)

    /** Largest binary inspected; cmake is the biggest file that needs it (about 15 MB). */
    private const val MAX_SIZE = 96L * 1024 * 1024

    /** What [ORIGINAL] becomes. */
    val replacement: String get() = REPLACEMENT.decodeToString()

    /**
     * Replaces every occurrence of the Termux shell path that is a whole path (not followed
     * by more path characters, so `.../bin/sha256sum` is left alone). Returns the count.
     */
    fun patch(bytes: ByteArray): Int {
        var count = 0
        var i = 0
        val first = NEEDLE[0]
        while (i <= bytes.size - NEEDLE.size) {
            if (bytes[i] == first && matchesAt(bytes, i) && !isPathChar(bytes.getOrNull(i + NEEDLE.size))) {
                REPLACEMENT.copyInto(bytes, i)
                count++
                i += NEEDLE.size
            } else {
                i++
            }
        }
        return count
    }

    private fun matchesAt(bytes: ByteArray, offset: Int): Boolean {
        for (j in NEEDLE.indices) if (bytes[offset + j] != NEEDLE[j]) return false
        return true
    }

    private fun isPathChar(b: Byte?): Boolean {
        if (b == null) return false
        val c = b.toInt().toChar()
        return c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == '+' || c == '/'
    }

    /** Patches [file] if it is an ELF file that contains the path. Returns true when it changed. */
    fun patchFile(file: File): Boolean {
        if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() > MAX_SIZE || !isElf(file)) return false
        val bytes = file.readBytes()
        if (patch(bytes) == 0) return false
        // Write next to the original and rename, so a half-written binary is never left behind.
        val temp = File(file.parentFile, ".${file.name}.nfpatch")
        temp.writeBytes(bytes)
        temp.setExecutable(true, false)
        temp.setReadable(true, false)
        if (!temp.renameTo(file)) {
            temp.delete()
            return false
        }
        return true
    }

    /**
     * Patches every ELF file directly in [binDir]. A stamp file remembers each file's size
     * and modification time, so files are only read again after a package update.
     */
    fun patchDirectory(binDir: File, stamp: File): List<String> {
        val known = runCatching { stamp.readLines() }.getOrDefault(emptyList())
            .mapNotNull { line -> line.split('\t').takeIf { it.size == 3 }?.let { it[0] to "${it[1]}\t${it[2]}" } }
            .toMap()
        val patched = mutableListOf<String>()
        val updated = LinkedHashMap<String, String>()
        binDir.listFiles().orEmpty()
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && !it.name.startsWith(".") }
            .sortedBy { it.name }
            .forEach { file ->
                val signature = "${file.length()}\t${file.lastModified()}"
                if (known[file.name] != signature && runCatching { patchFile(file) }.getOrDefault(false)) {
                    patched += file.name
                }
                updated[file.name] = "${file.length()}\t${file.lastModified()}"
            }
        if (updated != known) {
            runCatching {
                stamp.parentFile?.mkdirs()
                stamp.writeText(updated.entries.joinToString("") { (name, sig) -> "$name\t$sig\n" })
            }
        }
        return patched
    }

    private fun isElf(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 && magic[0] == 0x7F.toByte() && magic[1] == 'E'.code.toByte() &&
                magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()
        }
    }.getOrDefault(false)
}
