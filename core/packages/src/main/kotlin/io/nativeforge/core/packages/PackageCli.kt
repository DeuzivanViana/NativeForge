package io.nativeforge.core.packages

import io.nativeforge.core.common.Outcome
import io.nativeforge.core.packages.model.InstallEvent
import io.nativeforge.core.toolchain.ShellSetup
import java.io.PrintStream

/**
 * The terminal's `pkg` command (Termux style) on top of [PackageManager]. The app's
 * `PkgMain` builds the manager and calls [run]; everything else is here so it can be
 * tested on the JVM.
 *
 * [afterChange] runs after packages were installed or removed (the app re-applies the
 * toolchain set-up and restores the `pkg` script, which termux-tools would replace).
 */
class PackageCli(
    private val manager: PackageManager,
    private val out: PrintStream,
    private val err: PrintStream,
    private val afterChange: () -> Unit = {},
) {

    /** Runs one command; returns the process exit code. */
    suspend fun run(args: List<String>): Int {
        // apt-style flags (-y, --yes, --no-install-recommends...) are accepted and ignored.
        val words = args.filterNot { it.startsWith("-") && it !in HELP_FLAGS }
        val command = words.firstOrNull() ?: return usage(err, 1)
        val names = words.drop(1)
        return when (command) {
            "install", "i", "add" -> install(names, reinstall = false)
            "reinstall" -> install(names, reinstall = true)
            "uninstall", "remove", "rm", "purge" -> uninstall(names)
            "update" -> update()
            "upgrade", "up" -> upgrade()
            "search", "se" -> search(names)
            "list-installed", "list", "li" -> listInstalled()
            "list-all", "la" -> listAll()
            "show", "info" -> show(names)
            "setup" -> install(ShellSetup.ESSENTIAL_PACKAGES, reinstall = false)
            "help", "-h", "--help" -> usage(out, 0)
            else -> {
                err.println("pkg: unknown command '$command'")
                usage(err, 1)
            }
        }
    }

    private suspend fun install(names: List<String>, reinstall: Boolean): Int {
        if (names.isEmpty()) return fail("no packages given")
        manager.loadInstalled()
        val reinstallSet = if (reinstall) names.toSet() else emptySet()
        val requested = if (reinstall) names else names.filterNot(manager::isInstalled).also { skipped ->
            (names - skipped.toSet()).forEach { out.println("$it is already installed.") }
        }
        if (requested.isEmpty()) return 0
        return runInstall(requested, reinstallSet)
    }

    private suspend fun runInstall(names: List<String>, reinstall: Set<String>): Int {
        var code = 0
        var total = 0
        var done = 0
        manager.install(names, reinstall).collect { event ->
            when (event) {
                is InstallEvent.Resolved -> {
                    total = event.packages.size
                    if (total == 0) out.println("Nothing to do.") else out.println("Installing $total package(s): " + event.packages.joinToString(" ") { it.name })
                }
                is InstallEvent.Downloading -> Unit
                is InstallEvent.Extracting -> out.println("[${done + 1}/$total] ${event.pkg.name} ${event.pkg.version}")
                is InstallEvent.Installed -> done++
                is InstallEvent.Failed -> {
                    err.println("pkg: " + event.error.message)
                    code = 1
                }
                InstallEvent.Completed -> out.println("Done.")
            }
        }
        if (done > 0) afterChange()
        return code
    }

    private suspend fun uninstall(names: List<String>): Int {
        if (names.isEmpty()) return fail("no packages given")
        manager.loadInstalled()
        var code = 0
        names.forEach { name ->
            when (val result = manager.uninstall(name)) {
                is Outcome.Success -> out.println("Removed $name.")
                is Outcome.Failure -> {
                    err.println("pkg: " + result.error.message)
                    code = 1
                }
            }
        }
        afterChange()
        return code
    }

    private suspend fun update(): Int = when (val result = manager.refresh()) {
        is Outcome.Success -> {
            out.println("${result.value.size} packages available.")
            manager.loadInstalled()
            val outdated = outdated()
            if (outdated.isNotEmpty()) out.println("${outdated.size} can be upgraded: run 'pkg upgrade'.")
            0
        }
        is Outcome.Failure -> fail(result.error.message)
    }

    private suspend fun upgrade(): Int {
        if (update() != 0) return 1
        val outdated = outdated()
        if (outdated.isEmpty()) {
            out.println("All packages are up to date.")
            return 0
        }
        return runInstall(outdated, outdated.toSet())
    }

    /** Installed packages whose source now publishes another version or archive. */
    private fun outdated(): List<String> {
        val catalog = manager.catalog.value.associateBy { it.name }
        return manager.installed.value.filter { installed ->
            val available = catalog[installed.info.name] ?: return@filter false
            available.sourceId == installed.info.sourceId &&
                (available.version != installed.info.version ||
                    (available.sha256 != null && !available.sha256.equals(installed.info.sha256, ignoreCase = true)))
        }.map { it.info.name }
    }

    private suspend fun search(terms: List<String>): Int {
        if (terms.isEmpty()) return fail("search needs a word")
        if (!loadCatalog()) return 1
        val matches = manager.catalog.value.filter { pkg ->
            terms.all { term -> pkg.name.contains(term, ignoreCase = true) || pkg.description.contains(term, ignoreCase = true) }
        }
        if (matches.isEmpty()) out.println("No packages found.")
        matches.forEach { pkg ->
            val mark = if (manager.isInstalled(pkg.name)) " [installed]" else ""
            out.println("${pkg.name}/${pkg.version}$mark")
            if (pkg.description.isNotBlank()) out.println("  ${pkg.description}")
        }
        return 0
    }

    private suspend fun listInstalled(): Int {
        manager.loadInstalled()
        manager.installed.value.forEach { out.println("${it.info.name}/${it.info.version}") }
        return 0
    }

    private suspend fun listAll(): Int {
        if (!loadCatalog()) return 1
        manager.catalog.value.forEach { out.println("${it.name}/${it.version}") }
        return 0
    }

    private suspend fun show(names: List<String>): Int {
        if (names.isEmpty()) return fail("no packages given")
        if (!loadCatalog()) return 1
        var code = 0
        names.forEach { name ->
            val pkg = manager.catalog.value.firstOrNull { it.name == name }
            if (pkg == null) {
                err.println("pkg: package '$name' not found")
                code = 1
                return@forEach
            }
            out.println("Package: ${pkg.name}")
            out.println("Version: ${pkg.version}")
            if (pkg.depends.isNotEmpty()) out.println("Depends: ${pkg.depends.joinToString(", ")}")
            pkg.sizeBytes?.let { out.println("Size: ${it / 1024} KB") }
            out.println("Installed: ${if (manager.isInstalled(pkg.name)) "yes" else "no"}")
            if (pkg.description.isNotBlank()) out.println("Description: ${pkg.description}")
            out.println()
        }
        return code
    }

    private suspend fun loadCatalog(): Boolean {
        manager.loadInstalled()
        if (manager.allSourcesLoaded) return true
        return when (val result = manager.refresh()) {
            is Outcome.Success -> true
            is Outcome.Failure -> {
                err.println("pkg: " + result.error.message)
                false
            }
        }
    }

    private fun fail(message: String): Int {
        err.println("pkg: $message")
        return 1
    }

    private fun usage(stream: PrintStream, code: Int): Int {
        stream.println(USAGE)
        return code
    }

    companion object {
        private val HELP_FLAGS = setOf("-h", "--help")

        val USAGE = """
            |Usage: pkg <command> [packages...]
            |
            |NativeForge's package manager (Termux packages and NativeForge's Android libraries).
            |
            |  install <pkg...>    install packages and their dependencies
            |  reinstall <pkg...>  install packages again
            |  uninstall <pkg...>  remove packages
            |  update              download the package lists
            |  upgrade             install newer versions of installed packages
            |  search <word...>    find packages by name or description
            |  show <pkg...>       details of a package
            |  list-installed      installed packages
            |  list-all            every available package
            |  setup               install bash and the usual command-line tools
        """.trimMargin()
    }
}
