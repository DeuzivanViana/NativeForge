package io.nativeforge.core.remote

import io.nativeforge.core.remote.http.HttpRequest
import io.nativeforge.core.remote.http.HttpResponse
import io.nativeforge.core.remote.http.HttpServer
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The web IDE's files, read from this module's resources (`web/`), kept in memory with a
 * gzip copy, and served under a content-addressed prefix (`/a/<hash>/`) so browsers cache
 * them for a year and fetch them again only after an app update changes them.
 * `index.html` itself is never cached and gets the prefix and CSP hash filled in.
 */
class StaticAssets(private val loader: (String) -> ByteArray? = ::loadResource) {

    private class Asset(val bytes: ByteArray, val gzip: ByteArray?, val etag: String, val contentType: String)

    private val cache = ConcurrentHashMap<String, Asset>()

    /** Changes whenever any asset changes: hash of every listed file. */
    val version: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        val manifest = loader("MANIFEST")?.toString(Charsets.UTF_8).orEmpty()
        manifest.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { name ->
            digest.update(name.toByteArray())
            loader(name)?.let(digest::update)
        }
        digest.digest().take(6).joinToString("") { "%02x".format(it) }
    }

    val prefix: String get() = "/a/$version/"

    /** The page, with the asset prefix substituted. */
    val indexHtml: String by lazy {
        loader("index.html")!!.toString(Charsets.UTF_8).replace("{{ASSETS}}", prefix.removeSuffix("/"))
    }

    /** CSP source for the inline import map of [indexHtml] (no other inline script is allowed). */
    val importMapHash: String by lazy {
        val match = Regex("<script type=\"importmap\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(indexHtml)
        val body = match?.groupValues?.get(1).orEmpty()
        "'sha256-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body.toByteArray())) + "'"
    }

    fun index(request: HttpRequest): HttpResponse =
        respond(request, asset("index.html", indexHtml.toByteArray()), cacheControl = "no-cache")

    /** Serves `/a/<version>/<name>`; null when the path is not an asset. */
    fun serve(request: HttpRequest): HttpResponse? {
        if (!request.path.startsWith("/a/")) return null
        val rest = request.path.removePrefix("/a/")
        val version = rest.substringBefore('/')
        val name = rest.substringAfter('/', "")
        if (name.isEmpty() || name.split('/').any { it == ".." || it.isEmpty() || it.startsWith(".") } || name == "index.html") return null
        val asset = cache[name] ?: (loader(name)?.let { asset(name, it) }?.also { cache[name] = it } ?: return null)
        // An old page asking for old files after an update: serve the current file, but do not let it be cached forever.
        val cacheControl = if (version == this.version) "public, max-age=31536000, immutable" else "no-cache"
        return respond(request, asset, cacheControl)
    }

    private fun asset(name: String, bytes: ByteArray): Asset {
        val type = contentType(name)
        val gzip = if (HttpServer.isCompressible(type) && bytes.size > 1024) HttpServer.gzip(bytes) else null
        val etag = "\"" + MessageDigest.getInstance("SHA-256").digest(bytes).take(12).joinToString("") { "%02x".format(it) } + "\""
        return Asset(bytes, gzip, etag, type)
    }

    private fun respond(request: HttpRequest, asset: Asset, cacheControl: String): HttpResponse {
        if (request.header("if-none-match") == asset.etag) {
            return HttpResponse(304).header("ETag", asset.etag).header("Cache-Control", cacheControl)
        }
        val useGzip = asset.gzip != null && request.header("accept-encoding")?.contains("gzip") == true
        return HttpResponse(200, asset.contentType, if (useGzip) asset.gzip!! else asset.bytes)
            .header("ETag", asset.etag)
            .header("Cache-Control", cacheControl)
            .apply {
                if (useGzip) header("Content-Encoding", "gzip")
                if (asset.gzip != null) header("Vary", "Accept-Encoding")
            }
    }

    companion object {
        private const val ROOT = "/io/nativeforge/core/remote/web/"

        fun loadResource(name: String): ByteArray? =
            StaticAssets::class.java.getResourceAsStream(ROOT + name)?.use { it.readBytes() }

        fun contentType(name: String): String = when (name.substringAfterLast('.').lowercase()) {
            "html" -> "text/html; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "ico" -> "image/x-icon"
            "woff2" -> "font/woff2"
            "txt", "license" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }
    }
}
