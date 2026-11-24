package io.nativeforge.core.toolchain

import java.io.File

/**
 * The interactive terminal: which shell starts, its start-up files and the `pkg` command.
 *
 * Termux's bash, readline and ncurses have Termux's prefix compiled in, so their
 * system-wide files (`etc/profile`, `etc/bash.bashrc`, `etc/inputrc`, `share/terminfo`)
 * would never be found here. Bash is started with `--rcfile` pointing at [bashrc], which
 * sources the relocated copies of those files, and [ClangToolchain.environment] points
 * readline and ncurses at theirs (`INPUTRC`, `TERMINFO`).
 *
 * Termux's own `pkg` and `apt` need dpkg and Termux's prefix; [pkgScript] replaces them
 * with a front end to NativeForge's package manager, run with `app_process` from the app's
 * APK (`io.nativeforge.app.cli.PkgMain`), so packages installed from the terminal and from
 * the Packages screen are the same packages.
 */
class ShellSetup(private val layout: ToolchainLayout) {

    val bash: File get() = File(layout.binDir, "bash")
    val bashrc: File get() = File(layout.prefix, "etc/nativeforge/bashrc")
    val shrc: File get() = File(layout.prefix, "etc/nativeforge/shrc")
    val pkgScript: File get() = File(layout.binDir, "pkg")
    val aptScript: File get() = File(layout.binDir, "apt")

    /** What to start: an executable, its arguments and extra environment. */
    data class Shell(val executable: String, val arguments: List<String>, val environment: Map<String, String>)

    /**
     * Writes the start-up files and the `pkg`/`apt` scripts. Runs each time a terminal
     * opens and after installs: a Termux package (termux-tools) ships its own `pkg`,
     * which would replace ours.
     */
    fun prepare() {
        layout.ensureDirectories()
        writeIfChanged(bashrc, BASHRC, executable = false)
        writeIfChanged(shrc, SHRC, executable = false)
        writeIfChanged(pkgScript, PKG_SCRIPT, executable = true)
        writeIfChanged(aptScript, APT_SCRIPT, executable = true)
    }

    /** Bash when it is installed, otherwise Android's `/system/bin/sh` with a hint to install it. */
    fun shell(): Shell =
        if (bash.canExecute()) {
            Shell(bash.path, listOf("--rcfile", bashrc.path), mapOf("SHELL" to bash.path))
        } else {
            // mksh reads $ENV when interactive.
            Shell(SYSTEM_SHELL, emptyList(), mapOf("SHELL" to SYSTEM_SHELL, "ENV" to shrc.path))
        }

    private fun writeIfChanged(file: File, content: String, executable: Boolean) {
        // A package may have installed a symlink with this name; never write through it.
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) java.nio.file.Files.delete(file.toPath())
        if (!file.isFile || file.readText() != content) {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, ".${file.name}.tmp")
            temp.writeText(content)
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
        file.setReadable(true, false)
        if (executable) file.setExecutable(true, false)
    }

    companion object {
        const val SYSTEM_SHELL = "/system/bin/sh"

        /** Main class run by `pkg` (in the app module, which knows the APK's assets). */
        const val PKG_MAIN_CLASS = "io.nativeforge.app.cli.PkgMain"

        /** Environment variable holding the path of the app's APK, set by the terminal. */
        const val APK_ENV = "NATIVEFORGE_APK"

        /**
         * Command-line tools installed with the toolchain and by `pkg setup`: bash and the
         * programs people expect in a terminal (Termux's bash already pulls in coreutils,
         * grep, sed, tar... through termux-tools; they are listed so the set is explicit).
         */
        val ESSENTIAL_PACKAGES = listOf(
            "bash", "coreutils", "grep", "sed", "findutils", "gawk", "diffutils", "less", "nano",
            "ncurses-utils", "procps", "tar", "gzip", "xz-utils", "bzip2", "unzip", "zip", "curl",
            "ca-certificates", "which", "command-not-found",
        )

        internal val BASHRC = """
            |# NativeForge terminal start-up. Generated: put your own settings in ~/.bashrc.
            |# Termux's bash looks for these files under Termux's prefix, so source ours here.
            |if [ -r "${'$'}PREFIX/etc/profile" ]; then
            |    . "${'$'}PREFIX/etc/profile"
            |fi
            |if [ "${'$'}PS1" = '\s-\v\${'$'} ' ]; then
            |    PS1='\[\e[0;32m\]\w\[\e[0m\] \[\e[0;97m\]\${'$'}\[\e[0m\] '
            |fi
            |if [ -r "${'$'}HOME/.bashrc" ]; then
            |    . "${'$'}HOME/.bashrc"
            |fi
            |
        """.trimMargin()

        internal val SHRC = """
            |# NativeForge: start-up file of /system/bin/sh, used until bash is installed.
            |PS1='${'$'}PWD ${'$'} '
            |if [ ! -x "${'$'}PREFIX/bin/bash" ]; then
            |    echo "This is Android's basic shell. Run 'pkg setup' to install bash, coreutils, nano, curl and more."
            |fi
            |
        """.trimMargin()

        internal val PKG_SCRIPT = """
            |#!/system/bin/sh
            |# NativeForge package manager front end. Generated: rewritten when the terminal opens.
            |if [ -z "${'$'}$APK_ENV" ] || [ ! -f "${'$'}$APK_ENV" ]; then
            |    echo "pkg: open this terminal from NativeForge (the app's path is not known)." >&2
            |    exit 1
            |fi
            |# Termux's libraries must not replace the system's inside the Java runtime.
            |unset LD_LIBRARY_PATH LD_PRELOAD
            |export CLASSPATH="${'$'}$APK_ENV"
            |exec /system/bin/app_process -Xnoimage-dex2oat /system/bin $PKG_MAIN_CLASS "${'$'}@"
            |
        """.trimMargin()

        internal val APT_SCRIPT = """
            |#!/system/bin/sh
            |# NativeForge: apt is not available here; pkg installs the same Termux packages.
            |exec "${'$'}PREFIX/bin/pkg" "${'$'}@"
            |
        """.trimMargin()
    }
}
