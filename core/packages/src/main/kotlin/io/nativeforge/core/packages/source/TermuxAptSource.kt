package io.nativeforge.core.packages.source

import io.nativeforge.core.packages.model.ArchiveFormat
import io.nativeforge.core.packages.model.InstallTarget
import io.nativeforge.core.packages.model.PackageInfo
import io.nativeforge.core.packages.net.HttpClient
import io.nativeforge.core.packages.net.HttpException
import io.nativeforge.core.toolchain.Abi
import io.nativeforge.core.toolchain.ToolchainLayout
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.ByteArrayInputStream

/**
 * Reads the Termux APT repository. It provides the toolchain (clang, lld,
 * cmake, ninja...) and console libraries (ncurses, zlib, openssl...).
 */
class TermuxAptSource(
    private val http: HttpClient,
    private val baseUrl: String = DEFAULT_MIRROR,
    private val distribution: String = "stable",
    private val component: String = "main",
) : PackageSource {

    override val id: String = "termux-main"
    override val displayName: String = "Termux main repository"

    override suspend fun fetchIndex(abi: Abi): List<PackageInfo> {
        val text = downloadIndex(abi)
        return DebControlParser.parse(text).mapNotNull { toPackageInfo(it) }
    }

    private suspend fun downloadIndex(abi: Abi): String {
        val base = "$baseUrl/dists/$distribution/$component/binary-${abi.debianArch}/Packages"
        // Prefer compressed indexes; fall back to the plain file.
        val candidates: List<Pair<String, (ByteArray) -> ByteArray>> =
            listOf(".xz" to ::unxz, ".gz" to ::gunzip, "" to { bytes -> bytes })
        var lastError: Exception? = null
        for ((suffix, decode) in candidates) {
            try {
                return decode(http.getBytes(base + suffix)).decodeToString()
            } catch (e: HttpException) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("No package index found at $base")
    }

    private fun toPackageInfo(fields: Map<String, String>): PackageInfo? {
        val name = fields["Package"] ?: return null
        val filename = fields["Filename"] ?: return null
        return PackageInfo(
            name = name,
            version = fields["Version"].orEmpty(),
            description = fields["Description"].orEmpty().lineSequence().firstOrNull().orEmpty(),
            architecture = fields["Architecture"].orEmpty(),
            depends = DebControlParser.splitList(fields["Depends"]) + DebControlParser.splitList(fields["Pre-Depends"]),
            provides = DebControlParser.splitList(fields["Provides"]).map { it.substringBefore('(').trim() },
            downloadUrl = "$baseUrl/$filename",
            sha256 = fields["SHA256"],
            sizeBytes = fields["Size"]?.toLongOrNull(),
            format = ArchiveFormat.DEB,
            target = InstallTarget.PREFIX,
            sourceId = id,
            stripPrefix = ToolchainLayout.TERMUX_PREFIX.trimStart('/'),
        )
    }

    private fun unxz(bytes: ByteArray) = XZCompressorInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
    private fun gunzip(bytes: ByteArray) = GzipCompressorInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    companion object {
        const val DEFAULT_MIRROR = "https://packages-cf.termux.dev/apt/termux-main"
    }
}
