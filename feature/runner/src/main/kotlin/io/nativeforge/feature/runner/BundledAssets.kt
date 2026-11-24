package io.nativeforge.feature.runner

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import io.nativeforge.core.project.runtime.LaunchSpec
import java.io.File

/**
 * Copies the project's `assets/` folder, bundled in an exported APK, to `<files dir>/assets`.
 * Programs start in the files dir, so "assets/level1.txt" opens with plain fopen(), std::ifstream
 * or Lua's io.open, as in the IDE. Libraries that read APK assets directly (raylib, SDL) keep
 * working too. The copy is redone only when the app is installed or updated.
 */
object BundledAssets {
    private const val TAG = "BundledAssets"
    private const val STAMP = ".nf_assets_stamp"

    /** Extracts the assets if needed; returns the directory to start the program in. Blocking. */
    @Synchronized
    fun prepare(context: Context): File {
        val home = context.filesDir
        runCatching {
            val stampFile = File(home, STAMP)
            val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
            if (stampFile.isFile && stampFile.readText() == stamp) return home
            val target = File(home, LaunchSpec.BUNDLED_ASSETS_DIR)
            target.deleteRecursively()
            copy(context.assets, LaunchSpec.BUNDLED_ASSETS_DIR, target)
            stampFile.writeText(stamp)
        }.onFailure { Log.w(TAG, "Could not extract the bundled assets", it) }
        return home
    }

    /** AssetManager has no "is directory": a path that lists children is a folder. */
    private fun copy(assets: AssetManager, path: String, target: File) {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            // An empty folder also lists nothing; opening it fails, and it is skipped.
            runCatching { assets.open(path) }.getOrNull()?.use { input ->
                target.parentFile?.mkdirs()
                target.outputStream().use { input.copyTo(it) }
            }
            return
        }
        target.mkdirs()
        children.forEach { copy(assets, "$path/$it", File(target, it)) }
    }
}
