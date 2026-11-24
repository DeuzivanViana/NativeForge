package io.nativeforge.core.vcs

import io.nativeforge.core.common.SafeFiles
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.BatchingProgressMonitor
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RemoteConfig
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.filter.PathFilter
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * Git operations NativeForge offers, implemented with JGit (pure Java, no git binary).
 * Every function blocks; call them from a background dispatcher.
 */
class GitClient(private val homeDir: File) {

    init {
        JGitSetup.install(homeDir)
    }

    fun isRepository(dir: File): Boolean = File(dir, Constants.DOT_GIT).isDirectory

    /**
     * Clones [url] into [target], which must not exist yet. Submodules are not cloned,
     * symbolic links are checked out as plain files (see [NoHooksFs]) and hooks never run, so a repository
     * cannot point files outside [target] or execute code just by being cloned.
     */
    fun clone(
        url: String,
        target: File,
        branch: String? = null,
        credentials: GitCredentials? = null,
        onProgress: (GitProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ) {
        GitUrls.validate(url)?.let { throw GitException(it) }
        cloneFrom(url.trim(), target, branch, credentials, onProgress, isCancelled)
    }

    /** [clone] without the address check; tests clone local repositories through it. */
    internal fun cloneFrom(
        uri: String,
        target: File,
        branch: String?,
        credentials: GitCredentials?,
        onProgress: (GitProgress) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        if (target.exists()) throw GitException("${target.name} already exists")
        try {
            val git = Git.cloneRepository()
                .setURI(uri)
                .setDirectory(target)
                .setFs(JGitSetup.fs(homeDir))
                .setCloneSubmodules(false)
                .setTimeout(TIMEOUT_SECONDS)
                .setProgressMonitor(Monitor(onProgress, isCancelled))
                .setCredentialsProvider(credentials.provider())
                .apply { if (!branch.isNullOrBlank()) setBranch(branch.trim()) }
                .call()
            git.use { excludeLocally(it.repository, listOf("build/", "nativeforge.json")) }
            removeSymlinks(target)
        } catch (e: Exception) {
            SafeFiles.deleteTree(target)
            throw e.toGitException("Clone failed")
        }
    }

    /** Creates a repository in [dir] with a .gitignore for NativeForge's build folder. */
    fun init(dir: File) {
        wrap("Could not create the repository") {
            Git.init().setDirectory(dir).setFs(JGitSetup.fs(homeDir)).setInitialBranch("main").call().close()
            val ignore = File(dir, ".gitignore")
            if (!ignore.exists()) ignore.writeText("build/\n")
        }
    }

    fun status(dir: File): GitStatus = withGit(dir, "Could not read the repository status") { git ->
        val status = git.status().call()
        val changes = LinkedHashMap<String, FileChange>()
        status.untracked.forEach { changes[it] = FileChange.UNTRACKED }
        status.added.forEach { changes[it] = FileChange.ADDED }
        (status.changed + status.modified).forEach { changes[it] = FileChange.MODIFIED }
        (status.removed + status.missing).forEach { changes[it] = FileChange.DELETED }
        status.conflicting.forEach { changes[it] = FileChange.CONFLICTING }
        val repo = git.repository
        val head = repo.resolve(Constants.HEAD)
        GitStatus(
            branch = repo.branch?.let { if (head != null && it == head.name) it.take(7) else it },
            changes = changes.toSortedMap(),
            isEmpty = head == null,
        )
    }

    /** Unified diff of [path] (relative to the repository root) between the last commit and the working tree. */
    fun diff(dir: File, path: String): String = withGit(dir, "Could not compute the diff") { git ->
        val out = ByteArrayOutputStream()
        DiffFormatter(out).use { formatter ->
            formatter.setRepository(git.repository)
            formatter.pathFilter = PathFilter.create(path)
            formatter.format(headTree(git.repository), FileTreeIterator(git.repository))
        }
        out.toString(Charsets.UTF_8.name())
    }

    /** Stages every change (new, modified and deleted files) and commits it. */
    fun commitAll(dir: File, message: String, identity: GitIdentity): CommitInfo =
        withGit(dir, "Commit failed") { git ->
            require(message.isNotBlank()) { "Write a commit message" }
            git.add().addFilepattern(".").call()
            git.add().addFilepattern(".").setUpdate(true).call()
            val commit = git.commit()
                .setMessage(message.trim())
                .setAuthor(identity.name, identity.email)
                .setCommitter(identity.name, identity.email)
                .setNoVerify(true)
                .setSign(false)
                .call()
            CommitInfo(commit.name, commit.name.take(7), commit.shortMessage, commit.authorIdent.name, commit.commitTime * 1000L)
        }

    fun log(dir: File, max: Int = 30): List<CommitInfo> = withGit(dir, "Could not read the history") { git ->
        if (git.repository.resolve(Constants.HEAD) == null) return@withGit emptyList()
        git.log().setMaxCount(max).call().map {
            CommitInfo(it.name, it.name.take(7), it.shortMessage, it.authorIdent.name, it.commitTime * 1000L)
        }
    }

    /** Restores [path] to its last committed content; an untracked file is deleted. */
    fun discard(dir: File, path: String) = withGit(dir, "Could not discard the changes") { git ->
        val tracked = git.repository.resolve(Constants.HEAD) != null &&
            git.status().addPath(path).call().untracked.isEmpty()
        if (tracked) {
            git.checkout().setStartPoint(Constants.HEAD).addPath(path).call()
        } else {
            val file = File(dir, path)
            if (file.canonicalPath.startsWith(dir.canonicalPath + File.separator)) file.delete()
        }
        Unit
    }

    fun pull(dir: File, credentials: GitCredentials? = null, onProgress: (GitProgress) -> Unit = {}): String =
        withGit(dir, "Pull failed") { git ->
            requireHttpsRemote(git.repository, pullRemote(git.repository))
            val result = git.pull()
                .setTimeout(TIMEOUT_SECONDS)
                .setCredentialsProvider(credentials.provider())
                .setProgressMonitor(Monitor(onProgress) { false })
                .call()
            val merge = result.mergeResult
            when {
                result.isSuccessful -> merge?.mergeStatus?.toString()?.lowercase()?.replace('_', ' ') ?: "Up to date"
                merge != null && merge.conflicts != null -> "Merge conflicts in ${merge.conflicts.keys.joinToString()}"
                else -> throw GitException("Pull did not complete: ${merge?.mergeStatus ?: result.rebaseResult?.status}")
            }
        }

    fun push(dir: File, credentials: GitCredentials?, onProgress: (GitProgress) -> Unit = {}): String =
        withGit(dir, "Push failed") { git ->
            requireHttpsRemote(git.repository, Constants.DEFAULT_REMOTE_NAME)
            val results = git.push()
                .setTimeout(TIMEOUT_SECONDS)
                .setCredentialsProvider(credentials.provider())
                .setProgressMonitor(Monitor(onProgress) { false })
                .call()
            val updates = results.flatMap { it.remoteUpdates }
            val rejected = updates.filter { it.status.name.startsWith("REJECTED") }
            if (rejected.isNotEmpty()) {
                throw GitException("The remote rejected the push (${rejected.first().status.name.lowercase()}). Pull first.")
            }
            updates.joinToString { "${it.remoteName.removePrefix("refs/heads/")}: ${it.status.name.lowercase().replace('_', ' ')}" }
                .ifEmpty { "Nothing to push" }
        }

    /** URL of the `origin` remote, if any. */
    fun remoteUrl(dir: File): String? = withGit(dir, "Could not read the remote") { git ->
        git.repository.config.getString(ConfigConstants.CONFIG_REMOTE_SECTION, Constants.DEFAULT_REMOTE_NAME, "url")
    }

    /** The remote `git pull` fetches from: the current branch's, or origin. */
    private fun pullRemote(repository: Repository): String =
        repository.branch?.let { BranchConfig(repository.config, it).remote } ?: Constants.DEFAULT_REMOTE_NAME

    /**
     * Pull and push only talk to HTTPS servers. Every address of the remote is checked as JGit
     * will use it (after `url.*.insteadOf` rewriting, push URLs included), because a project
     * opened from shared storage brings its own `.git/config`.
     */
    private fun requireHttpsRemote(repository: Repository, remoteName: String) {
        val remote = RemoteConfig(repository.config, remoteName)
        val addresses = remote.urIs + remote.pushURIs
        if (addresses.isEmpty()) throw GitException("This repository has no remote named $remoteName")
        addresses.forEach { uri ->
            GitUrls.validate(uri.toString())?.let { throw GitException("The $remoteName remote is not supported: $it") }
        }
    }

    private fun headTree(repository: Repository): AbstractTreeIterator {
        val tree = repository.resolve("HEAD^{tree}") ?: return EmptyTreeIterator()
        return repository.newObjectReader().use { reader -> CanonicalTreeParser(null, reader, tree) }
    }

    private fun <T> withGit(dir: File, failure: String, block: (Git) -> T): T = wrap(failure) {
        val repository = FileRepositoryBuilder()
            .setFS(JGitSetup.fs(homeDir))
            .setGitDir(File(dir, Constants.DOT_GIT))
            .setWorkTree(dir)
            .setMustExist(true)
            .build()
        Git(repository).use(block)
    }

    private inline fun <T> wrap(failure: String, block: () -> T): T =
        try {
            block()
        } catch (e: GitException) {
            throw e
        } catch (e: Exception) {
            throw e.toGitException(failure)
        }

    private fun Exception.toGitException(failure: String): GitException = when (this) {
        is GitException -> this
        is TransportException -> GitException(
            if (message.orEmpty().contains("not authorized", ignoreCase = true) ||
                message.orEmpty().contains("Authentication", ignoreCase = true)
            ) {
                "$failure: the server asked for a sign-in. Enter your user name and an access token."
            } else {
                "$failure: ${message ?: "network error"}"
            },
            this,
        )
        is GitAPIException, is IllegalArgumentException, is IllegalStateException ->
            GitException("$failure: ${message ?: javaClass.simpleName}", this)
        else -> GitException("$failure: ${message ?: javaClass.simpleName}", this)
    }

    private fun GitCredentials?.provider(): CredentialsProvider? =
        this?.let { UsernamePasswordCredentialsProvider(it.username, it.password) }

    /** Adds patterns to .git/info/exclude: ignored locally without touching the project's .gitignore. */
    private fun excludeLocally(repository: Repository, patterns: List<String>) {
        val exclude = File(repository.directory, "info/exclude")
        exclude.parentFile.mkdirs()
        val existing = if (exclude.isFile) exclude.readLines() else emptyList()
        val missing = patterns.filterNot { it in existing }
        if (missing.isNotEmpty()) exclude.appendText(missing.joinToString("\n", prefix = if (existing.isEmpty()) "" else "\n", postfix = "\n"))
    }

    /** Defence in depth: no symbolic link may survive in a cloned tree. */
    private fun removeSymlinks(root: File) {
        root.walkTopDown()
            .onEnter { !Files.isSymbolicLink(it.toPath()) }
            .filter { Files.isSymbolicLink(it.toPath()) }
            .toList()
            .forEach { Files.deleteIfExists(it.toPath()) }
    }

    private class Monitor(
        private val onProgress: (GitProgress) -> Unit,
        private val cancelled: () -> Boolean,
    ) : BatchingProgressMonitor() {
        override fun onUpdate(taskName: String, workCurr: Int) = onProgress(GitProgress(taskName, null))
        override fun onEndTask(taskName: String, workCurr: Int) = onProgress(GitProgress(taskName, null))
        override fun onUpdate(taskName: String, workCurr: Int, workTotal: Int, percentDone: Int) =
            onProgress(GitProgress(taskName, percentDone))
        override fun onEndTask(taskName: String, workCurr: Int, workTotal: Int, percentDone: Int) =
            onProgress(GitProgress(taskName, percentDone))
        override fun isCancelled(): Boolean = cancelled()
    }

    private companion object {
        const val TIMEOUT_SECONDS = 60
    }
}
