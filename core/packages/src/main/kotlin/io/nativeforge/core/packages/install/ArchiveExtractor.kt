package io.nativeforge.core.packages.install

import io.nativeforge.core.packages.model.ArchiveFormat
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Extracts package archives. Every entry path is passed through a mapper (to strip
 * prefixes such as `data/data/com.termux/files/usr/`) and checked so that no
 * entry escapes the destination directory.
 */
class ArchiveExtractor {

    /**
     * @param pathMapper maps an archive path to a path relative to [destination],
     *   or returns null to skip the entry.
     * @return installed paths relative to [destination].
     */
    fun extract(
        archive: File,
        format: ArchiveFormat,
        destination: File,
        pathMapper: (String) -> String? = { it },
    ): List<String> {
        destination.mkdirs()
        return archive.inputStream().buffered().use { input ->
            when (format) {
                ArchiveFormat.DEB -> extractDeb(input, destination, pathMapper)
                ArchiveFormat.TAR_XZ -> extractTar(XZCompressorInputStream(input), destination, pathMapper)
                ArchiveFormat.TAR_GZ -> extractTar(GzipCompressorInputStream(input), destination, pathMapper)
                ArchiveFormat.ZIP -> extractEntries(ZipArchiveInputStream(input), destination, pathMapper)
            }
        }
    }

    private fun extractDeb(input: InputStream, destination: File, pathMapper: (String) -> String?): List<String> {
        val ar = ArArchiveInputStream(input)
        while (true) {
            val entry = ar.nextEntry ?: break
            val name = entry.name
            if (name.startsWith("data.tar")) {
                val payload: InputStream = when {
                    name.endsWith(".xz") -> XZCompressorInputStream(ar)
                    name.endsWith(".gz") -> GzipCompressorInputStream(ar)
                    name == "data.tar" -> ar
                    else -> throw IOException("Unsupported .deb payload compression: $name")
                }
                return extractTar(payload, destination, pathMapper)
            }
        }
        throw IOException("No data.tar member found in .deb archive")
    }

    private fun extractTar(input: InputStream, destination: File, pathMapper: (String) -> String?) =
        extractEntries(TarArchiveInputStream(input), destination, pathMapper)

    private fun <E : ArchiveEntry> extractEntries(
        archive: ArchiveInputStream<E>,
        destination: File,
        pathMapper: (String) -> String?,
    ): List<String> {
        val installed = mutableListOf<String>()
        val root = destination.canonicalFile
        while (true) {
            val entry = archive.nextEntry ?: break
            val relative = pathMapper(normalize(entry.name))?.takeIf { it.isNotEmpty() } ?: continue
            val target = File(root, relative)
            ensureInside(root, target)

            when {
                entry.isDirectory -> target.mkdirs()
                entry is TarArchiveEntry && entry.isSymbolicLink -> {
                    target.parentFile?.mkdirs()
                    if (target.exists() || Files.isSymbolicLink(target.toPath())) target.delete()
                    Files.createSymbolicLink(target.toPath(), Paths.get(entry.linkName))
                    installed += relative
                }
                entry is TarArchiveEntry && entry.isLink -> {
                    // Hard link: copy the already extracted file.
                    val linked = pathMapper(normalize(entry.linkName))?.let { File(root, it) }
                    if (linked != null && linked.isFile) {
                        ensureInside(root, linked)
                        if (!linked.canonicalPath.startsWith(root.path + File.separator)) {
                            throw IOException("Archive hard link points outside the destination: ${entry.linkName}")
                        }
                        removeLink(target)
                        linked.copyTo(target, overwrite = true)
                        installed += relative
                    }
                }
                else -> {
                    target.parentFile?.mkdirs()
                    // A previous entry may have planted a link with this name; writing
                    // through it would land outside the destination.
                    removeLink(target)
                    target.outputStream().use { archive.copyTo(it) }
                    val mode = (entry as? TarArchiveEntry)?.mode ?: 0
                    if (mode and EXECUTE_BITS != 0) target.setExecutable(true, false)
                    installed += relative
                }
            }
        }
        return installed
    }

    private fun removeLink(target: File) {
        if (Files.isSymbolicLink(target.toPath())) Files.delete(target.toPath())
    }

    private fun normalize(path: String): String = path.removePrefix("./").trimStart('/')

    private fun ensureInside(root: File, target: File) {
        val canonicalParent = (target.parentFile ?: root).canonicalPath
        if (canonicalParent != root.path && !canonicalParent.startsWith(root.path + File.separator)) {
            throw IOException("Archive entry escapes destination: $target")
        }
    }

    private companion object {
        const val EXECUTE_BITS = 0b001_001_001
    }
}
