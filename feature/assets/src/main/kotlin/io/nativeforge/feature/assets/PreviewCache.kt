package io.nativeforge.feature.assets

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.util.LruCache
import java.io.File

/**
 * Decoded image previews, so going back to an image (or switching tabs) does not decode it
 * again. Bounded to an eighth of the app's heap, least recently used first out, and emptied
 * when Android asks the app to give memory back ([trim], from `onTrimMemory`).
 * Entries are keyed by path, size and modification time, so an edited file is decoded again.
 */
object PreviewCache {

    private val cache = object : LruCache<String, Bitmap>(maxSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    private fun maxSizeKb(): Int = (Runtime.getRuntime().maxMemory() / 8 / 1024).toInt().coerceAtLeast(4 * 1024)

    private fun key(file: File) = "${file.path}:${file.length()}:${file.lastModified()}"

    fun get(file: File): Bitmap? = cache.get(key(file))

    fun put(file: File, bitmap: Bitmap) {
        cache.put(key(file), bitmap)
    }

    /** Frees memory: half the cache when the UI is hidden, all of it when memory is low. */
    @Suppress("DEPRECATION") // the levels still arrive on every supported Android version
    fun trim(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> cache.evictAll()
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> cache.trimToSize(cache.maxSize() / 2)
        }
    }
}
