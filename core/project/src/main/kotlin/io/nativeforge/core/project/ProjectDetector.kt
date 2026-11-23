package io.nativeforge.core.project

import io.nativeforge.core.project.model.BuildSystemKind
import io.nativeforge.core.project.model.Language
import io.nativeforge.core.project.model.Project
import io.nativeforge.core.project.model.ProjectConfig
import io.nativeforge.core.project.model.RuntimeKind
import io.nativeforge.core.project.model.SourceFiles
import java.io.File

/**
 * Guesses a [ProjectConfig] for a folder that was not created by NativeForge (a cloned
 * repository, for example): its build system, language, how it runs and which bundled
 * libraries its sources include. Everything can be changed later in Build settings.
 */
object ProjectDetector {

    private const val MAX_FILES = 400
    private const val MAX_BYTES_PER_FILE = 64 * 1024

    /** Header prefix → package that provides it. */
    private val HEADER_PACKAGES = listOf(
        "raylib.h" to "raylib", "raygui.h" to "raygui",
        "SDL3_image/" to "sdl3-image", "SDL3_ttf/" to "sdl3-ttf", "SDL3_mixer/" to "sdl3-mixer", "SDL3/" to "sdl3",
        "SFML/" to "sfml", "box2d/" to "box2d", "box3d/" to "box3d",
        "lua.h" to "lua", "lua.hpp" to "lua", "lauxlib.h" to "lua",
        "glm/" to "glm", "nlohmann/" to "nlohmann-json", "entt/" to "entt", "miniaudio.h" to "miniaudio",
        "stb_" to "stb", "android_native_app_glue.h" to "native-app-glue",
        "GLES2/" to "opengles", "GLES3/" to "opengles", "EGL/" to "opengles",
        "SDL2/" to "sdl2", "imgui.h" to "imgui", "imgui_impl_" to "imgui", "vulkan/" to "vulkan",
    )

    /** SDL2 headers are often included without a folder (`sdl2-config` adds include/SDL2). */
    private val SDL2_HEADERS = mapOf(
        "SDL.h" to "sdl2", "SDL_image.h" to "sdl2-image", "SDL_ttf.h" to "sdl2-ttf", "SDL_mixer.h" to "sdl2-mixer",
    )
    private val INCLUDE = Regex("""^\s*#\s*include\s*[<"]([^>"]+)[>"]""", RegexOption.MULTILINE)

    fun detectBuildSystem(root: File): BuildSystemKind = when {
        File(root, "CMakeLists.txt").isFile -> BuildSystemKind.CMAKE
        listOf("GNUmakefile", "makefile", "Makefile").any { File(root, it).isFile } -> BuildSystemKind.MAKE
        File(root, "build.ninja").isFile -> BuildSystemKind.NINJA
        else -> BuildSystemKind.SINGLE
    }

    fun detect(root: File, name: String): ProjectConfig {
        val buildSystem = detectBuildSystem(root)
        val sourceDir = if (File(root, Project.SOURCE_DIR).isDirectory) Project.SOURCE_DIR else "."
        val sources = SourceFiles.collectSources(if (sourceDir == ".") root else File(root, sourceDir)).take(MAX_FILES)
        val language = if (sources.any(SourceFiles::isCppSource)) Language.CPP else if (sources.isEmpty()) Language.CPP else Language.C

        val includes = mutableSetOf<String>()
        var hasAndroidMain = false
        sources.forEach { file ->
            val text = runCatching { file.inputStream().use { it.readNBytesCompat(MAX_BYTES_PER_FILE) }.decodeToString() }.getOrDefault("")
            INCLUDE.findAll(text).forEach { includes += it.groupValues[1] }
            if ("android_main" in text || "ANativeActivity_onCreate" in text) hasAndroidMain = true
        }
        val packages = HEADER_PACKAGES
            .filter { (prefix, _) -> includes.any { it.startsWith(prefix) || it.substringAfterLast('/').startsWith(prefix) } }
            .map { it.second }
            .plus(includes.mapNotNull { SDL2_HEADERS[it.removePrefix("SDL2/")] })
            .distinct()
        val runtime = when {
            "sdl3" in packages -> RuntimeKind.SDL3
            "sdl2" in packages -> RuntimeKind.SDL2
            hasAndroidMain || packages.any { it in GRAPHICAL } -> RuntimeKind.NATIVE_ACTIVITY
            else -> RuntimeKind.CONSOLE
        }
        return ProjectConfig(
            name = name,
            language = language,
            buildSystem = buildSystem,
            runtime = runtime,
            packages = packages,
            sourceDir = sourceDir,
            // Third-party CMake projects rarely name their program "main"; build everything.
            cmakeTarget = if (buildSystem == BuildSystemKind.CMAKE) "" else "main",
        )
    }

    private val GRAPHICAL = setOf("raylib", "sfml", "native-app-glue", "opengles")

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val buffer = ByteArray(max)
        var total = 0
        while (total < max) {
            val read = read(buffer, total, max - total)
            if (read < 0) break
            total += read
        }
        return buffer.copyOf(total)
    }
}
