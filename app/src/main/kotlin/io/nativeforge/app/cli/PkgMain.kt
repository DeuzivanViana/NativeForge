package io.nativeforge.app.cli

import io.nativeforge.app.BuildConfig
import io.nativeforge.app.di.AppContainer
import io.nativeforge.app.di.AssetAwareHttpClient
import io.nativeforge.core.packages.PackageCli
import io.nativeforge.core.packages.PackageManager
import io.nativeforge.core.packages.install.InstalledPackageDatabase
import io.nativeforge.core.packages.net.UrlConnectionHttpClient
import io.nativeforge.core.packages.source.JsonIndexSource
import io.nativeforge.core.packages.source.PackageSource
import io.nativeforge.core.packages.source.TermuxAptSource
import io.nativeforge.core.toolchain.Abi
import io.nativeforge.core.toolchain.ClangToolchain
import io.nativeforge.core.toolchain.ShellSetup
import io.nativeforge.core.toolchain.ToolchainLayout
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import kotlin.system.exitProcess

/**
 * Entry point of the terminal's `pkg` command. It is not started by Android: the `pkg`
 * script (see [ShellSetup]) runs `app_process` with this APK as class path, in the app's
 * own user, so there is no Context. Paths come from the terminal's environment and the
 * bundled library index is read straight from the APK.
 */
object PkgMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val code = try {
            run(args.toList())
        } catch (e: Throwable) {
            System.err.println("pkg: ${e.message ?: e.javaClass.name}")
            1
        }
        System.out.flush()
        exitProcess(code)
    }

    private fun run(args: List<String>): Int {
        val prefix = System.getenv("PREFIX")?.takeIf { it.isNotBlank() }
            ?: throw IOException("PREFIX is not set; open the terminal from NativeForge")
        val apk = System.getenv(ShellSetup.APK_ENV)?.takeIf { File(it).isFile }
            ?: System.getProperty("java.class.path").orEmpty().split(':').firstOrNull { it.endsWith(".apk") && File(it).isFile }
            ?: throw IOException("the app's APK was not found")
        val layout = ToolchainLayout(File(prefix).parentFile ?: throw IOException("bad PREFIX: $prefix"))
        val abi = Abi.preferred(android.os.Build.SUPPORTED_ABIS.toList())

        return ZipFile(apk).use { zip ->
            val http = AssetAwareHttpClient(
                openAsset = { path -> zip.getEntry("assets/$path")?.let(zip::getInputStream) ?: throw IOException("asset $path not found") },
                delegate = UrlConnectionHttpClient(userAgent = "NativeForge/${BuildConfig.VERSION_NAME}"),
            )
            val sources = buildList<PackageSource> {
                add(JsonIndexSource(http, AssetAwareHttpClient.BUNDLED_INDEX, id = AppContainer.BUNDLED_SOURCE_ID, displayName = "NativeForge Android libraries"))
                if (BuildConfig.PACKAGE_INDEX_URL.isNotBlank()) add(JsonIndexSource(http, BuildConfig.PACKAGE_INDEX_URL))
                add(TermuxAptSource(http))
            }
            val manager = PackageManager(
                sources = sources,
                http = http,
                layout = layout,
                database = InstalledPackageDatabase(File(layout.filesDir, "packages/installed.json")),
                abi = abi,
            )
            val cli = PackageCli(manager, System.out, System.err) {
                // Same steps the app runs before a build and when the terminal opens.
                runCatching { ClangToolchain(layout, abi).prepare() }
                runCatching { ShellSetup(layout).prepare() }
            }
            runBlocking { cli.run(args) }
        }
    }
}
