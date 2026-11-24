package io.nativeforge.core.project.runtime

import io.nativeforge.core.project.model.ExportSettings
import io.nativeforge.core.project.model.RuntimeKind
import kotlinx.serialization.Serializable

/**
 * Everything a runtime host needs to start a compiled program. The IDE passes it
 * to the runner through an Intent; exported APKs embed it as
 * `assets/nativeforge/launch.json` with library paths relative to the APK's
 * native library directory.
 */
@Serializable
data class LaunchSpec(
    val runtime: RuntimeKind,
    /** Executable (console) or shared library (graphical) to start. */
    val program: String,
    /** Shared libraries loaded with RTLD_GLOBAL before [program], in order. */
    val preloadLibraries: List<String> = emptyList(),
    val arguments: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap(),
    /** True when paths are file names relative to the native library directory. */
    val relativeToNativeLibraryDir: Boolean = false,
    val orientation: ExportSettings.Orientation = ExportSettings.Orientation.UNSPECIFIED,
    /** Hide the system bars while the program runs. */
    val immersive: Boolean = false,
    val keepScreenOn: Boolean = false,
) {
    companion object {
        const val ASSET_PATH = "nativeforge/launch.json"

        /**
         * APK assets folder that holds the project's `assets/` folder. Exported apps copy it to
         * `<files dir>/assets` and start the program there, so "assets/player.png" opens the
         * same way as in the IDE (working directory = project root), with plain fopen() too.
         */
        const val BUNDLED_ASSETS_DIR = "assets"
    }
}
