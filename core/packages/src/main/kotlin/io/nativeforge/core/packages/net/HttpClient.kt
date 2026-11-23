package io.nativeforge.core.packages.net

import java.io.File

/** Network abstraction so the package manager can be tested without a network. */
interface HttpClient {
    suspend fun getBytes(url: String): ByteArray

    /** Downloads [url] to [destination], reporting `(bytesRead, totalBytes)`. */
    suspend fun download(url: String, destination: File, onProgress: (Long, Long?) -> Unit = { _, _ -> })
}

class HttpException(val statusCode: Int, url: String) : Exception("HTTP $statusCode for $url")
