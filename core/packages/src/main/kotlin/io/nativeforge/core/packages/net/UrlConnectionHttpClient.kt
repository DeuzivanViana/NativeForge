package io.nativeforge.core.packages.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** [HttpClient] built on [HttpURLConnection], available on the JVM and Android. */
class UrlConnectionHttpClient(
    private val userAgent: String = "NativeForge-IDE",
    private val timeoutMillis: Int = 30_000,
) : HttpClient {

    override suspend fun getBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        open(url).let { connection ->
            try {
                connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }
    }

    override suspend fun download(url: String, destination: File, onProgress: (Long, Long?) -> Unit) =
        withContext(Dispatchers.IO) {
            val connection = open(url)
            try {
                val total = connection.contentLengthLong.takeIf { it > 0 }
                destination.parentFile?.mkdirs()
                val partial = File(destination.path + ".part")
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                        var read = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            read += count
                            onProgress(read, total)
                        }
                    }
                }
                if (destination.exists()) destination.delete()
                check(partial.renameTo(destination)) { "Could not move download to $destination" }
            } finally {
                connection.disconnect()
            }
        }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", userAgent)
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw HttpException(code, url)
        }
        return connection
    }
}
