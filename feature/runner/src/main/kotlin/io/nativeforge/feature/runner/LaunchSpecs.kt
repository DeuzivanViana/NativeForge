package io.nativeforge.feature.runner

import android.content.Context
import android.content.Intent
import io.nativeforge.core.project.ProjectJson
import io.nativeforge.core.project.runtime.LaunchSpec
import java.io.File

/** Reads and writes [LaunchSpec]s from Intents (IDE runs) or APK assets (exported apps). */
object LaunchSpecs {
    private const val EXTRA_SPEC = "io.nativeforge.runner.LAUNCH_SPEC"

    fun putInto(intent: Intent, spec: LaunchSpec): Intent =
        intent.putExtra(EXTRA_SPEC, ProjectJson.encodeToString(LaunchSpec.serializer(), spec))

    fun fromIntent(intent: Intent): LaunchSpec? =
        intent.getStringExtra(EXTRA_SPEC)?.let { ProjectJson.decodeFromString(LaunchSpec.serializer(), it) }

    fun fromAssets(context: Context): LaunchSpec? = runCatching {
        context.assets.open(LaunchSpec.ASSET_PATH).bufferedReader().use {
            ProjectJson.decodeFromString(LaunchSpec.serializer(), it.readText())
        }
    }.getOrNull()

    /** Converts relative library names into absolute paths inside the native library directory. */
    fun resolvePaths(context: Context, spec: LaunchSpec): LaunchSpec {
        if (!spec.relativeToNativeLibraryDir) return spec
        val libDir = context.applicationInfo.nativeLibraryDir
        return spec.copy(
            program = File(libDir, spec.program).path,
            preloadLibraries = spec.preloadLibraries.map { File(libDir, it).path },
            relativeToNativeLibraryDir = false,
        )
    }
}
