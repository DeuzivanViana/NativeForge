package io.nativeforge.core.vcs

import java.net.URI

/** Validation for repository addresses typed by the user. */
object GitUrls {

    /**
     * Returns an error message, or null when [url] can be cloned. Only HTTPS is accepted:
     * plain http and git:// can be altered on the network, file:// and local paths would
     * let a link read other folders of the app, and SSH needs keys the app does not manage.
     */
    fun validate(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return "Enter the repository address"
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return "This is not a valid address"
        return when {
            uri.scheme.equals("https", ignoreCase = true).not() ->
                "Use an https:// address (http, git://, ssh and local paths are not supported)"
            uri.host.isNullOrBlank() -> "The address has no host name"
            uri.rawUserInfo != null -> "Remove the user name or token from the address; enter it under Sign in instead"
            uri.path.isNullOrBlank() || uri.path == "/" -> "The address has no repository path"
            else -> null
        }
    }

    /** A project name derived from the repository address: `https://host/user/my-game.git` → `my-game`. */
    fun projectName(url: String): String {
        val last = url.trim().trimEnd('/').substringAfterLast('/').removeSuffix(".git")
        val cleaned = last.replace(Regex("[^A-Za-z0-9 _.-]"), "-").trim('-', '.', ' ')
        return cleaned.take(64).ifEmpty { "repository" }.let { if (it.first().isLetterOrDigit()) it else "repo-$it" }
    }
}
