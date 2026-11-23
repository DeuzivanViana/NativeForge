package io.nativeforge.core.vcs

import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.storage.file.WindowCacheConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.FS_POSIX
import org.eclipse.jgit.util.ProcessResult
import org.eclipse.jgit.util.SystemReader
import java.io.File
import java.io.OutputStream

/**
 * Process-wide JGit configuration for Android:
 * - no JMX (java.lang.management does not exist on Android);
 * - the user config lives in the app's home folder, and no system config is read, which
 *   would otherwise make JGit look for and run a `git` binary;
 * - repository hooks never run (a cloned or imported folder could carry scripts).
 */
internal object JGitSetup {

    @Volatile private var installedHome: File? = null

    @Synchronized
    fun install(homeDir: File) {
        if (installedHome == homeDir) return
        homeDir.mkdirs()
        WindowCacheConfig().apply { setExposeStatsViaJmx(false) }.install()
        SystemReader.setInstance(AppSystemReader(SystemReader.getInstance(), homeDir))
        installedHome = homeDir
    }

    fun fs(homeDir: File): FS = NoHooksFs().setUserHome(homeDir)
}

/**
 * POSIX file system support without hooks and without symbolic links: repositories
 * created through it get `core.symlinks=false`, so links are checked out as small text
 * files and can never point the editor or the build at files outside the project.
 */
internal class NoHooksFs : FS_POSIX() {
    override fun newInstance(): FS = NoHooksFs().also { it.setUserHome(userHome()) }

    override fun supportsSymlinks(): Boolean = false

    override fun findHook(repository: Repository, hookName: String): File? = null

    override fun runHookIfPresent(
        repository: Repository,
        hookName: String,
        args: Array<out String>,
        outRedirect: OutputStream?,
        errRedirect: OutputStream?,
        stdinArgs: String?,
    ): ProcessResult = ProcessResult(ProcessResult.Status.NOT_PRESENT)
}

private class AppSystemReader(private val delegate: SystemReader, private val homeDir: File) : SystemReader() {
    override fun getHostname(): String = "android"
    override fun getenv(variable: String): String? = when (variable) {
        "GIT_CONFIG_NOSYSTEM" -> "1"
        "HOME" -> homeDir.path
        "XDG_CONFIG_HOME" -> File(homeDir, ".config").path
        else -> delegate.getenv(variable)
    }
    override fun getProperty(key: String): String? =
        if (key == "user.home") homeDir.path else delegate.getProperty(key)

    override fun openUserConfig(parent: Config?, fs: FS): FileBasedConfig =
        FileBasedConfig(parent, File(homeDir, ".gitconfig"), fs)

    override fun openSystemConfig(parent: Config?, fs: FS): FileBasedConfig = EmptyConfig(parent, fs)

    override fun openJGitConfig(parent: Config?, fs: FS): FileBasedConfig =
        FileBasedConfig(parent, File(homeDir, ".config/jgit/config"), fs)

    override fun getCurrentTime(): Long = delegate.currentTime
    override fun getTimezone(time: Long): Int = delegate.getTimezone(time)
}

private class EmptyConfig(parent: Config?, fs: FS) : FileBasedConfig(parent, null, fs) {
    override fun load() = Unit
    override fun isOutdated(): Boolean = false
    override fun toString(): String = "EmptyConfig"
}
