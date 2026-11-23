package io.nativeforge.core.vcs

/** How a file differs from the last commit. */
enum class FileChange(val letter: Char) {
    ADDED('A'),
    MODIFIED('M'),
    DELETED('D'),
    UNTRACKED('U'),
    CONFLICTING('C'),
}

/** Working tree state of a repository. Paths are relative to the repository root, with '/' separators. */
data class GitStatus(
    /** Current branch, or a short commit id when HEAD is detached; null before the first commit. */
    val branch: String?,
    val changes: Map<String, FileChange>,
    /** True when the repository has no commit yet. */
    val isEmpty: Boolean = false,
) {
    val hasChanges: Boolean get() = changes.isNotEmpty()
}

data class CommitInfo(
    val id: String,
    val shortId: String,
    val message: String,
    val author: String,
    val timeMillis: Long,
)

/** Name and e-mail written into commits. */
data class GitIdentity(val name: String, val email: String)

/**
 * HTTPS credentials. For GitHub, GitLab and most hosts the password is a personal
 * access token. They are used for one operation and never written to disk.
 */
data class GitCredentials(val username: String, val password: String)

/** Progress of a clone, pull or push. [percent] is null while the total is unknown. */
data class GitProgress(val task: String, val percent: Int?)

class GitException(message: String, cause: Throwable? = null) : Exception(message, cause)
