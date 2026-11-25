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
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayInputStream

/**
 * Reads the Termux APT repository. It provides the toolchain (clang, lld,
 * cmake, ninja...) and console libraries (ncurses, zlib, openssl...).
 */
class TermuxAptSource(
    private val http: HttpClient,
    /**
     * Servers tried in order until one gives an index; archives then come from the same one.
     * Only Termux's own servers: the index carries every archive's SHA-256, so it must come
     * from a host Termux runs, over HTTPS.
     */
    private val mirrors: List<String> = DEFAULT_MIRRORS,
    private val distribution: String = "stable",
    private val component: String = "main",
) : PackageSource {

    constructor(http: HttpClient, baseUrl: String) : this(http, listOf(baseUrl))

    init {
        require(mirrors.isNotEmpty()) { "no Termux mirror given" }
    }

    override val id: String = "termux-main"
    override val displayName: String = "Termux main repository"

    override suspend fun fetchIndex(abi: Abi): List<PackageInfo> {
        var lastError: Exception? = null
        for (mirror in mirrors) {
            val text = try {
                downloadIndex(mirror, abi)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Unreachable, refused, timed out or a broken file: try the next server.
                lastError = e
                continue
            }
            return DebControlParser.parse(text).mapNotNull { toPackageInfo(mirror, it) }
        }
        throw lastError ?: IllegalStateException("No Termux mirror configured")
    }

    private suspend fun downloadIndex(mirror: String, abi: Abi): String {
        val base = "$mirror/dists/$distribution/$component/binary-${abi.debianArch}/Packages"
        // Prefer compressed indexes; fall back to the plain file.
        val candidates: List<Pair<String, (ByteArray) -> ByteArray>> =
            listOf(".xz" to ::unxz, ".gz" to ::gunzip, "" to { bytes -> bytes })
        var lastError: Exception? = null
        for ((suffix, decode) in candidates) {
            try {
                return decode(http.getBytes(base + suffix)).decodeToString()
            } catch (e: HttpException) {
                // Not published in this form: try the next one. Network errors go to the next mirror.
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("No package index found at $base")
    }

    private fun toPackageInfo(mirror: String, fields: Map<String, String>): PackageInfo? {
        val name = fields["Package"] ?: return null
        val filename = fields["Filename"] ?: return null
        return PackageInfo(
            name = name,
            version = fields["Version"].orEmpty(),
            description = fields["Description"].orEmpty().lineSequence().firstOrNull().orEmpty(),
            architecture = fields["Architecture"].orEmpty(),
            depends = DebControlParser.splitList(fields["Depends"]) + DebControlParser.splitList(fields["Pre-Depends"]),
            provides = DebControlParser.splitList(fields["Provides"]).map { it.substringBefore('(').trim() },
            downloadUrl = "$mirror/$filename",
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
        /** Termux's Cloudflare front, the address Termux itself uses by default. */
        const val DEFAULT_MIRROR = "https://packages-cf.termux.dev/apt/termux-main"

        /** Termux's primary server, behind the Cloudflare front: used when that one fails. */
        const val PRIMARY_SERVER = "https://packages.termux.dev/apt/termux-main"

        val DEFAULT_MIRRORS = listOf(DEFAULT_MIRROR, PRIMARY_SERVER)
    }
}
