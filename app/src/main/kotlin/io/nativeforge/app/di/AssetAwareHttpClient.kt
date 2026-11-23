package io.nativeforge.app.di

import android.content.res.AssetManager
import io.nativeforge.core.packages.net.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Serves `asset:<path>` URLs from the APK's assets and delegates everything else.
 * The bundled package index (`asset:packages/index.json`) and its archives use it,
 * so SDL3, raylib and SFML install without a network connection.
 */
class AssetAwareHttpClient(
    private val assets: AssetManager,
    private val delegate: HttpClient,
) : HttpClient {

    override suspend fun getBytes(url: String): ByteArray {
        val path = assetPath(url) ?: return delegate.getBytes(url)
        return withContext(Dispatchers.IO) { assets.open(path).use { it.readBytes() } }
    }

    override suspend fun download(url: String, destination: File, onProgress: (Long, Long?) -> Unit) {
        val path = assetPath(url) ?: return delegate.download(url, destination, onProgress)
        withContext(Dispatchers.IO) {
            destination.parentFile?.mkdirs()
            val copied = assets.open(path).use { input -> destination.outputStream().use { input.copyTo(it) } }
            onProgress(copied, copied)
        }
    }

    private fun assetPath(url: String): String? = url.takeIf { it.startsWith(SCHEME) }?.removePrefix(SCHEME)

    companion object {
        const val SCHEME = "asset:"
        const val BUNDLED_INDEX = "asset:packages/index.json"
    }
}
