package io.nativeforge.core.packages

import io.nativeforge.core.common.Logger
import io.nativeforge.core.common.NfError
import io.nativeforge.core.common.NoOpLogger
import io.nativeforge.core.common.Outcome
import io.nativeforge.core.common.coroutines.DefaultDispatcherProvider
import io.nativeforge.core.common.coroutines.DispatcherProvider
import io.nativeforge.core.common.outcomeOf
import io.nativeforge.core.packages.install.ArchiveExtractor
import io.nativeforge.core.packages.install.Checksums
import io.nativeforge.core.packages.install.InstalledPackageDatabase
import io.nativeforge.core.packages.install.PrefixRelocator
import io.nativeforge.core.packages.model.InstallEvent
import io.nativeforge.core.packages.model.InstallTarget
import io.nativeforge.core.packages.model.InstalledPackage
import io.nativeforge.core.packages.model.PackageInfo
import io.nativeforge.core.packages.net.HttpClient
import io.nativeforge.core.packages.source.PackageSource
import io.nativeforge.core.toolchain.Abi
import io.nativeforge.core.toolchain.NativeDependency
import io.nativeforge.core.toolchain.ToolchainLayout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Facade over package sources, dependency resolution and installation.
 *
 * Sources are queried in order; when two sources publish the same package
 * name, the first one wins. Put the most specific source (Android-targeted
 * libraries) before general ones (Termux).
 */
class PackageManager(
    sources: List<PackageSource>,
    private val http: HttpClient,
    private val layout: ToolchainLayout,
    private val database: InstalledPackageDatabase,
    private val abi: Abi,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
    private val logger: Logger = NoOpLogger,
    private val extractor: ArchiveExtractor = ArchiveExtractor(),
) {
    /**
     * Sources in precedence order. Replaced by [setSources] when the user edits their
     * package repositories; the next [refresh] reads the new list.
     */
    @Volatile private var sources: List<PackageSource> = sources

    fun setSources(sources: List<PackageSource>) {
        this.sources = sources
        val ids = sources.map { it.id }.toSet()
        loadedSources = loadedSources intersect ids
        _catalog.value = _catalog.value.filter { it.sourceId in ids }
    }

    private val mutex = Mutex()
    private val _catalog = MutableStateFlow<List<PackageInfo>>(emptyList())
    private val _installed = MutableStateFlow<List<InstalledPackage>>(emptyList())

    /** Packages available from every source, after the last [refresh]. */
    val catalog: StateFlow<List<PackageInfo>> = _catalog.asStateFlow()
    val installed: StateFlow<List<InstalledPackage>> = _installed.asStateFlow()

    /**
     * Sources whose index is in [catalog]. The catalog can hold only some of them, e.g.
     * just the bundled index after [refreshSource] at start-up, so a non-empty catalog
     * does not mean every package is known.
     */
    @Volatile private var loadedSources: Set<String> = emptySet()

    /** Why sources failed in the last [refresh], one line per source. */
    @Volatile private var lastFailures: List<String> = emptyList()

    /** True once every source's index has been read successfully. */
    val allSourcesLoaded: Boolean get() = sources.all { it.id in loadedSources }

    private val downloadDir get() = File(layout.filesDir, "cache/packages")

    suspend fun loadInstalled() = withContext(dispatchers.io) {
        mutex.withLock { _installed.value = database.all() }
    }

    /** Downloads every source index. Sources that fail are skipped and reported. */
    suspend fun refresh(): Outcome<List<PackageInfo>> = withContext(dispatchers.io) {
        val merged = LinkedHashMap<String, PackageInfo>()
        val failures = mutableListOf<String>()
        val loaded = mutableSetOf<String>()
        sources.forEach { source ->
            try {
                source.fetchIndex(abi).forEach { merged.putIfAbsent(it.name, it) }
                loaded += source.id
            } catch (e: Exception) {
                logger.warn(TAG, "Index refresh failed for ${source.id}", e)
                failures += "${source.displayName}: ${e.message ?: e.javaClass.simpleName}"
                // Keep what an earlier refresh of this source found.
                if (source.id in loadedSources) {
                    _catalog.value.filter { it.sourceId == source.id }.forEach { merged.putIfAbsent(it.name, it) }
                    loaded += source.id
                }
            }
        }
        loadedSources = loaded
        lastFailures = failures
        _catalog.value = merged.values.sortedBy { it.name }
        if (merged.isEmpty() && failures.isNotEmpty()) {
            Outcome.Failure(NfError.Network("Could not load package lists:\n" + failures.joinToString("\n")))
        } else {
            Outcome.Success(_catalog.value)
        }
    }

    /**
     * Re-reads a single source, e.g. the index bundled in the app, which needs no
     * network. Other sources keep their last result and their precedence.
     */
    suspend fun refreshSource(sourceId: String) = withContext(dispatchers.io) {
        val source = sources.firstOrNull { it.id == sourceId } ?: return@withContext
        val fresh = try {
            source.fetchIndex(abi)
        } catch (e: Exception) {
            logger.warn(TAG, "Index refresh failed for $sourceId", e)
            return@withContext
        }
        val merged = LinkedHashMap<String, PackageInfo>()
        sources.forEach { s ->
            val entries = if (s.id == sourceId) fresh else _catalog.value.filter { it.sourceId == s.id }
            entries.forEach { merged.putIfAbsent(it.name, it) }
        }
        loadedSources = loadedSources + sourceId
        _catalog.value = merged.values.sortedBy { it.name }
    }

    /**
     * Installed isolated packages whose source now publishes a different build
     * (new version or new archive), for example after an app update ships
     * rebuilt bundled libraries. Install metadata (link flags...) is stored at
     * install time, so such packages must be reinstalled to pick up fixes.
     */
    fun outdatedPackages(sourceId: String? = null): List<String> {
        val catalog = _catalog.value.associateBy { it.name }
        return _installed.value.filter { installed ->
            val info = installed.info
            val available = catalog[info.name] ?: return@filter false
            info.target == InstallTarget.ISOLATED &&
                available.sourceId == info.sourceId &&
                (sourceId == null || info.sourceId == sourceId) &&
                (available.version != info.version || (available.sha256 != null && !available.sha256.equals(info.sha256, ignoreCase = true)))
        }.map { it.info.name }
    }

    fun isInstalled(name: String): Boolean = _installed.value.any { it.info.name == name }

    /** Reinstalls [names] from their current source entries. */
    fun update(names: List<String>): Flow<InstallEvent> = install(names, reinstall = names.toSet())

    /**
     * Installs [names] and their dependencies, emitting progress. Packages in
     * [reinstall] are installed again even when present.
     */
    fun install(names: List<String>, reinstall: Set<String> = emptySet()): Flow<InstallEvent> = flow {
        mutex.withLock {
            val plan = try {
                // Every source must be read: the catalog may only hold the bundled index.
                if (!allSourcesLoaded) refresh()
                try {
                    DependencyResolver(_catalog.value).resolve(names, database.names() - reinstall)
                } catch (e: DependencyResolutionException) {
                    // A package list may be stale or have failed earlier; read them again once.
                    refresh()
                    DependencyResolver(_catalog.value).resolve(names, database.names() - reinstall)
                }
            } catch (e: DependencyResolutionException) {
                val failures = lastFailures
                emit(
                    InstallEvent.Failed(
                        if (failures.isEmpty()) {
                            NfError.NotFound(e.message.orEmpty())
                        } else {
                            NfError.Network("Could not download the package list. Check the internet connection and retry.\n" + failures.joinToString("\n"))
                        },
                    ),
                )
                return@withLock
            }
            emit(InstallEvent.Resolved(plan))
            for (pkg in plan) {
                val result = outcomeOf(errorFactory = { NfError.Io("Failed to install ${pkg.name}: ${it.message}", it) }) {
                    installOne(pkg) { event -> emit(event) }
                }
                if (result is Outcome.Failure) {
                    emit(InstallEvent.Failed(result.error))
                    return@withLock
                }
                emit(InstallEvent.Installed(pkg))
            }
            _installed.value = database.all()
            emit(InstallEvent.Completed)
        }
    }.flowOn(dispatchers.io)

    suspend fun uninstall(name: String): Outcome<Unit> = withContext(dispatchers.io) {
        mutex.withLock {
            val pkg = database.get(name) ?: return@withLock Outcome.Failure(NfError.NotFound("$name is not installed"))
            val root = File(pkg.root)
            pkg.files.sortedByDescending { it.length }.forEach { File(root, it).delete() }
            if (pkg.info.target == InstallTarget.ISOLATED) root.deleteRecursively()
            database.remove(name)
            _installed.value = database.all()
            Outcome.Success(Unit)
        }
    }

    /**
     * Build inputs for the given installed packages and the installed packages
     * they depend on, dependencies first (e.g. sdl3 before sdl3-image). Link
     * steps use the reverse order so static libraries resolve correctly.
     */
    fun nativeDependencies(names: List<String>): List<NativeDependency> {
        val installedByName = _installed.value.associateBy { it.info.name }
        val ordered = LinkedHashMap<String, InstalledPackage>()
        val visiting = HashSet<String>()
        fun visit(name: String) {
            val pkg = installedByName[name] ?: return
            if (name in ordered || !visiting.add(name)) return
            pkg.info.depends.forEach { clause ->
                clause.split('|').map(DependencyResolver::packageName).firstOrNull { it in installedByName }?.let(::visit)
            }
            ordered[name] = pkg
        }
        names.forEach(::visit)
        // Termux base packages (libc++, zlib...) are part of the toolchain prefix, not build inputs.
        return ordered.values.filter { it.info.target == InstallTarget.ISOLATED || it.info.name in names }.map { it.toNativeDependency() }
    }

    private suspend fun installOne(pkg: PackageInfo, emit: suspend (InstallEvent) -> Unit) {
        val archive = File(downloadDir, "${pkg.name}_${pkg.version}.${pkg.format.name.lowercase()}")
        emit(InstallEvent.Downloading(pkg, 0, pkg.sizeBytes))
        http.download(pkg.downloadUrl, archive)
        emit(InstallEvent.Downloading(pkg, archive.length(), archive.length()))

        pkg.sha256?.let { expected ->
            val actual = Checksums.sha256(archive)
            check(actual.equals(expected, ignoreCase = true)) { "Checksum mismatch for ${pkg.name}" }
        }

        emit(InstallEvent.Extracting(pkg))
        val root = installRoot(pkg)
        // A reinstall of an isolated package starts from an empty directory.
        if (pkg.target == InstallTarget.ISOLATED && database.get(pkg.name) != null) root.deleteRecursively()
        val strip = pkg.stripPrefix.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val files = extractor.extract(archive, pkg.format, root) { path ->
            when {
                strip.isEmpty() -> path
                path.startsWith(strip) -> path.removePrefix(strip)
                else -> null // Entries outside the package prefix are not installed.
            }
        }
        if (pkg.target == InstallTarget.PREFIX) {
            PrefixRelocator(ToolchainLayout.TERMUX_PREFIX, layout.prefix.path).relocate(root, files)
        }
        database.put(InstalledPackage(pkg, root.path, files, System.currentTimeMillis()))
        archive.delete()
    }

    private fun installRoot(pkg: PackageInfo): File = when (pkg.target) {
        InstallTarget.PREFIX -> layout.prefix
        InstallTarget.ISOLATED -> File(layout.androidLibrariesDir, pkg.name)
    }

    private fun InstalledPackage.toNativeDependency(): NativeDependency {
        val root = File(this.root)
        val build = info.build
        return NativeDependency(
            name = info.name,
            includeDirectories = if (info.target == InstallTarget.ISOLATED) build.includeDirs.map { File(root, it) } else emptyList(),
            libraryDirectories = if (info.target == InstallTarget.ISOLATED) build.libDirs.map { File(root, it) } else emptyList(),
            linkLibraries = build.linkLibraries,
            compileFlags = build.compileFlags,
            linkFlags = build.linkFlags,
            cmakePrefixes = if (info.target == InstallTarget.ISOLATED) listOf(root) else emptyList(),
            runtimeLibraries = build.runtimeLibraries.map { File(root, it) },
        )
    }

    private companion object {
        const val TAG = "PackageManager"
    }
}
