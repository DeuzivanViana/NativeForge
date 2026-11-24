package io.nativeforge.core.packages.source

import io.nativeforge.core.packages.model.PackageInfo
import io.nativeforge.core.packages.net.HttpClient
import io.nativeforge.core.toolchain.Abi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON index format published by NativeForge for Android-targeted libraries. */
@Serializable
data class PackageIndex(
    val schemaVersion: Int = 1,
    val packages: List<PackageInfo>,
)

/**
 * Source for libraries cross-compiled with the NDK against the Android
 * platform (SDL2, SDL3, SFML, raylib, native_app_glue...). These cannot come
 * from Termux because Termux builds them for X11.
 */
class JsonIndexSource(
    private val http: HttpClient,
    private val indexUrl: String,
    override val id: String = "nativeforge",
    override val displayName: String = "NativeForge Android libraries",
) : PackageSource {

    override suspend fun fetchIndex(abi: Abi): List<PackageInfo> {
        val index = json.decodeFromString(PackageIndex.serializer(), http.getBytes(indexUrl).decodeToString())
        return index.packages
            .filter { it.architecture == abi.debianArch || it.architecture == "all" }
            .map { it.copy(sourceId = id, downloadUrl = resolve(it.downloadUrl)) }
            .filter(::isVerifiable)
    }

    /**
     * Archives are trusted only through the index's SHA-256, so an entry without one is
     * dropped, and so is one served over a scheme other than HTTPS or the index's own
     * (`asset:` for the bundled index): plain HTTP could be altered on the network.
     */
    private fun isVerifiable(info: PackageInfo): Boolean {
        val scheme = schemeOf(info.downloadUrl) ?: return false
        return !info.sha256.isNullOrBlank() && (scheme == "https" || scheme == schemeOf(indexUrl))
    }

    private fun schemeOf(url: String): String? =
        Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):").find(url)?.groupValues?.get(1)?.lowercase()

    /** Archives may be named relative to the index ("aarch64/sdl2-2.32.tar.xz"). */
    private fun resolve(url: String): String {
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(url)) return url
        return runCatching { java.net.URI(indexUrl).resolve(url).toString() }.getOrDefault(url)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
