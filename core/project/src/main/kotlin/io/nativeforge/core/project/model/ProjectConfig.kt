package io.nativeforge.core.project.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Programming language of the project's sources. */
@Serializable
enum class Language(val defaultStandard: String) {
    @SerialName("c") C("c17"),
    @SerialName("cpp") CPP("c++20"),
}

/**
 * How the project is built. Each kind is independent: a CMake project does not need
 * a Makefile and the other way round. New build systems plug in through core:build's registry.
 */
@Serializable
enum class BuildSystemKind(val displayName: String, val scriptFile: String?) {
    /** NativeForge compiles every source under the source folder itself; no build script. */
    @SerialName("single") SINGLE("Built-in", null),

    /** CMake configures the project's CMakeLists.txt with [ProjectConfig.cmakeGenerator], then builds. */
    @SerialName("cmake") CMAKE("CMake", "CMakeLists.txt"),

    /** GNU Make runs the project's Makefile directly. */
    @SerialName("make") MAKE("Make", "Makefile"),

    /** Ninja runs the project's build.ninja directly. */
    @SerialName("ninja") NINJA("Ninja", "build.ninja"),
}

/** Generator CMake writes the build with. */
@Serializable
enum class CMakeGenerator(val cmakeName: String) {
    @SerialName("ninja") NINJA("Ninja"),
    @SerialName("makefiles") MAKEFILES("Unix Makefiles"),
}

/**
 * Determines how the compiled program is hosted when it runs.
 * Console programs are executables run inside a terminal; every other kind
 * is a shared library loaded by a host activity.
 */
@Serializable
enum class RuntimeKind(val producesSharedLibrary: Boolean) {
    /** Executable attached to a terminal (stdin/stdout). */
    @SerialName("console") CONSOLE(false),

    /** Shared library hosted by an SDL2 activity (entry point: SDL_main). */
    @SerialName("sdl2") SDL2(true),

    /** Shared library hosted by an SDL3 activity (entry point: SDL_main). */
    @SerialName("sdl3") SDL3(true),

    /**
     * Shared library exporting ANativeActivity_onCreate, usually via
     * android_native_app_glue. Used by raylib, SFML and raw OpenGL ES / EGL code.
     */
    @SerialName("native_activity") NATIVE_ACTIVITY(true),
}

@Serializable
enum class BuildType {
    @SerialName("debug") DEBUG,
    @SerialName("release") RELEASE,
}

/** Settings used when the project is exported as a standalone APK. */
@Serializable
data class ExportSettings(
    val appName: String,
    val packageId: String,
    val versionName: String = "1.0",
    val versionCode: Int = 1,
    val orientation: Orientation = Orientation.UNSPECIFIED,
    val abis: List<String> = emptyList(),
    /** Launcher icon: an image file relative to the project root, or null for the default icon. */
    val iconPath: String? = null,
    /** Scale the icon image to fill the whole icon shape (true) or fit it inside the safe zone (false). */
    val iconFill: Boolean = true,
    /** Hide the status and navigation bars while the program runs (graphical programs). */
    val immersive: Boolean = true,
    /** Keep the screen on while the program runs. */
    val keepScreenOn: Boolean = false,
    /** Copy the project's `assets/` folder into the APK, readable as `assets/<file>`. */
    val bundleAssets: Boolean = true,
    /** Optimized build (-O2) instead of a debug build (-O0 -g). */
    val releaseBuild: Boolean = true,
    /** APK to install or share, or an Android App Bundle for Google Play. */
    val format: Format = Format.APK,
    /** Id of a keystore saved in the IDE (see the app's signing keys), or null for the device key. */
    val signingKeyId: String? = null,
) {
    @Serializable
    enum class Format(val displayName: String, val extension: String) {
        @SerialName("apk") APK("APK", "apk"),
        @SerialName("aab") AAB("App Bundle (AAB)", "aab"),
    }

    /** Screen orientation; [androidValue] is the `android:screenOrientation` enum value. */
    @Serializable
    enum class Orientation(val displayName: String, val androidValue: Int) {
        @SerialName("unspecified") UNSPECIFIED("Auto", -1),
        @SerialName("portrait") PORTRAIT("Portrait", 1),
        @SerialName("landscape") LANDSCAPE("Landscape", 0),
        @SerialName("sensor_portrait") SENSOR_PORTRAIT("Portrait (both ways)", 7),
        @SerialName("sensor_landscape") SENSOR_LANDSCAPE("Landscape (both ways)", 6),
        @SerialName("full_sensor") FULL_SENSOR("Any rotation", 10),
    }
}

/**
 * Persistent project configuration, stored as `nativeforge.json` at the project root.
 * All fields have defaults so older files keep loading when new fields are added.
 */
@Serializable
data class ProjectConfig(
    val name: String,
    val language: Language = Language.CPP,
    val standard: String = language.defaultStandard,
    val buildSystem: BuildSystemKind = BuildSystemKind.SINGLE,
    val runtime: RuntimeKind = RuntimeKind.CONSOLE,
    /** Package names from the package manager this project depends on. */
    val packages: List<String> = emptyList(),
    val compilerFlags: List<String> = emptyList(),
    val linkerFlags: List<String> = emptyList(),
    /** Extra -D definitions passed to CMake (CMake projects only). */
    val cmakeOptions: Map<String, String> = emptyMap(),
    /** Generator used by CMake projects. */
    val cmakeGenerator: CMakeGenerator = CMakeGenerator.NINJA,
    /**
     * CMake target producing the program (CMake projects only). It must be an
     * executable for [RuntimeKind.CONSOLE] and a SHARED library otherwise.
     */
    val cmakeTarget: String = "main",
    /** Target passed to make or ninja; empty builds the default target. */
    val buildTarget: String = "",
    /** Extra arguments for make, ninja or `cmake --build` (for example `-j4` or `VERBOSE=1`). */
    val buildArguments: List<String> = emptyList(),
    /**
     * The program the build produces, relative to the project root. Null uses the build
     * system's default and, for Make and Ninja, the newest program the build wrote.
     */
    val artifactPath: String? = null,
    /** Folder holding the sources, relative to the project root ("." for the root itself). */
    val sourceDir: String = Project.SOURCE_DIR,
    /** Command line arguments passed to the program when it runs. */
    val runArguments: List<String> = emptyList(),
    val export: ExportSettings = ExportSettings(appName = name, packageId = defaultPackageId(name)),
) {
    companion object {
        const val FILE_NAME = "nativeforge.json"

        /** Derives a valid Java package id such as `io.nativeforge.user.my_game` from a project name. */
        fun defaultPackageId(projectName: String): String {
            val segment = projectName.lowercase()
                .replace(Regex("[^a-z0-9_]"), "_")
                .trim('_')
                .ifEmpty { "app" }
                .let { if (it.first().isDigit()) "app_$it" else it }
            return "io.nativeforge.user.$segment"
        }
    }
}
