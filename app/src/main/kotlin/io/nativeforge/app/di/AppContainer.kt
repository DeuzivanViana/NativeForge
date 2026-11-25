package io.nativeforge.app.di

import android.content.Context
import android.os.Build
import android.util.Log
import io.nativeforge.app.BuildConfig
import io.nativeforge.app.settings.SettingsRepository
import io.nativeforge.app.signing.PasswordVault
import io.nativeforge.app.signing.SigningKeyRepository
import io.nativeforge.core.build.BuildExecutor
import io.nativeforge.core.build.BuildService
import io.nativeforge.core.build.system.BuildSystemRegistry
import io.nativeforge.core.common.Logger
import io.nativeforge.core.common.process.JvmProcessRunner
import io.nativeforge.core.export.ApkExporter
import io.nativeforge.core.export.web.WebExporter
import io.nativeforge.core.export.signing.ApksigSigningEngine
import io.nativeforge.core.export.signing.SigningKeyStore
import io.nativeforge.core.packages.PackageManager
import io.nativeforge.core.packages.install.InstalledPackageDatabase
import io.nativeforge.core.packages.net.UrlConnectionHttpClient
import io.nativeforge.core.packages.source.JsonIndexSource
import io.nativeforge.core.packages.source.PackageSource
import io.nativeforge.core.packages.source.TermuxAptSource
import io.nativeforge.core.project.FileSystemProjectRepository
import io.nativeforge.core.project.ProjectRepository
import io.nativeforge.core.toolchain.Abi
import io.nativeforge.core.toolchain.ClangToolchain
import io.nativeforge.core.toolchain.Toolchain
import io.nativeforge.core.toolchain.ToolchainLayout
import io.nativeforge.core.toolchain.WasiToolchain
import io.nativeforge.core.vcs.GitClient
import java.io.File

/**
 * Manual dependency injection: the single place where implementations are
 * chosen. Swap an implementation here (another package source, build system
 * or signer) and every screen picks it up.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val logger: Logger = AndroidLogger
    val settings = SettingsRepository(appContext)
    val abi: Abi = Abi.preferred(Build.SUPPORTED_ABIS.toList())
    val layout = ToolchainLayout(appContext.filesDir).also { it.ensureDirectories() }
    val toolchain: Toolchain = ClangToolchain(layout, abi)

    /** Builds console programs as WebAssembly for the web export (same clang, WASI sysroot). */
    val wasiToolchain by lazy { WasiToolchain(toolchain, layout) }

    val projectRepository: ProjectRepository = FileSystemProjectRepository(
        workspaceRoot = File(appContext.filesDir, "projects").also { it.mkdirs() },
        // Folders opened from shared storage or an SD card, and where they build.
        linkedProjectsFile = File(appContext.filesDir, "linked-projects.json"),
        externalBuildRoot = File(appContext.filesDir, "external-builds"),
    )

    private val http = AssetAwareHttpClient(
        appContext.assets::open,
        UrlConnectionHttpClient(userAgent = "NativeForge/${BuildConfig.VERSION_NAME}"),
    )

    /**
     * Package sources in precedence order: the libraries bundled in the APK, the build's
     * optional index, the user's own repositories (Settings › Package repositories), then Termux.
     */
    private fun packageSources(userRepositories: List<String>): List<PackageSource> = buildList {
        // SDL, raylib, SFML, ImGui, Vulkan... ship inside the APK (see packages/build-android-libs.sh).
        add(JsonIndexSource(http, AssetAwareHttpClient.BUNDLED_INDEX, id = BUNDLED_SOURCE_ID, displayName = "NativeForge Android libraries"))
        if (BuildConfig.PACKAGE_INDEX_URL.isNotBlank()) add(JsonIndexSource(http, BuildConfig.PACKAGE_INDEX_URL))
        userRepositories.distinct().filter(::isRepositoryUrl).forEach { url ->
            add(JsonIndexSource(http, url, id = "user:$url", displayName = runCatching { java.net.URI(url).host }.getOrNull() ?: url))
        }
        add(TermuxAptSource(http))
    }

    /** Uses the repositories saved in Settings; the next catalog refresh reads them. */
    fun applyPackageRepositories(urls: List<String>) = packageManager.setSources(packageSources(urls))

    val packageManager = PackageManager(
        sources = packageSources(settings.settings.value.packageRepositories),
        http = http,
        layout = layout,
        database = InstalledPackageDatabase(File(appContext.filesDir, "packages/installed.json")),
        abi = abi,
        logger = logger,
    )

    /** How hot the phone is: builds start at a lower CPU priority and streams cap their frame rate when it is hot. */
    val thermal = io.nativeforge.app.device.ThermalMonitor(appContext)

    val buildService = BuildService(
        BuildSystemRegistry.default(),
        BuildExecutor(JvmProcessRunner(priority = { thermal.level.value.buildThreadPriority })),
    )

    // Only the Export screen needs these: created on first use, which keeps the app's start short.
    val apkExporter by lazy { ApkExporter(ApksigSigningEngine()) }
    val webExporter by lazy { WebExporter() }

    /** The remote web IDE (Remote access screen): trusted browsers, TLS key and server. */
    val remoteAccess: io.nativeforge.app.remote.RemoteAccessManager by lazy { io.nativeforge.app.remote.RemoteAccessManager(appContext, this) }

    /** Git without a git binary; its config files live in the toolchain's home folder. */
    val git: GitClient by lazy { GitClient(layout.homeDir) }

    /**
     * Reinstalls bundled libraries whose archive changed since they were installed
     * (an app update can ship fixed builds or new link flags).
     * @return the names of the updated packages.
     */
    suspend fun updateBundledPackages(): List<String> {
        packageManager.refreshSource(BUNDLED_SOURCE_ID)
        val outdated = packageManager.outdatedPackages(BUNDLED_SOURCE_ID)
        if (outdated.isEmpty()) return emptyList()
        var failed = false
        packageManager.update(outdated).collect { if (it is io.nativeforge.core.packages.model.InstallEvent.Failed) failed = true }
        return if (failed) emptyList() else outdated
    }

    companion object {
        /** Source id of the Android-targeted libraries bundled in the APK. */
        const val BUNDLED_SOURCE_ID = "nativeforge-bundled"

        /** Only HTTPS indexes: archives are checked against the index's SHA-256, so the index must not be tampered with. */
        fun isRepositoryUrl(url: String): Boolean =
            runCatching { java.net.URI(url) }.getOrNull()?.let { it.scheme == "https" && !it.host.isNullOrBlank() } == true
    }

    /** The keystore lives in private app storage, which other apps cannot read. */
    val signingKeyStore by lazy { SigningKeyStore(File(appContext.filesDir, "signing/export.p12"), "nativeforge".toCharArray()) }

    /** Keystores the user created or imported (see Export › Signing). */
    val signingKeys by lazy { SigningKeyRepository(File(appContext.filesDir, "signing/keystores"), PasswordVault()) }

    val exportsDirectory: File get() = File(appContext.filesDir, "exports").also { it.mkdirs() }

    /** Copies the bundled runtime template APK out of the assets (ZipFile needs a real file). */
    fun runtimeTemplateApk(): File {
        val target = File(appContext.cacheDir, "runtime-template-${BuildConfig.VERSION_CODE}.apk")
        if (!target.isFile) {
            appContext.assets.open("runtime/template.apk").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        return target
    }

    /** The runtime template's app bundle without dex and native libraries (see app/build.gradle.kts). */
    fun runtimeBundleSkeleton(): File {
        val target = File(appContext.cacheDir, "runtime-bundle-${BuildConfig.VERSION_CODE}.zip")
        if (!target.isFile) {
            appContext.assets.open("runtime/bundle-skeleton.zip").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        return target
    }
}

private object AndroidLogger : Logger {
    override fun debug(tag: String, message: String) { Log.d(tag, message) }
    override fun info(tag: String, message: String) { Log.i(tag, message) }
    override fun warn(tag: String, message: String, error: Throwable?) { Log.w(tag, message, error) }
    override fun error(tag: String, message: String, error: Throwable?) { Log.e(tag, message, error) }
}
