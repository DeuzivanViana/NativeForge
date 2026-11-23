package io.nativeforge.core.project.template

import io.nativeforge.core.project.model.BuildSystemKind
import io.nativeforge.core.project.model.Language
import io.nativeforge.core.project.model.ProjectConfig
import io.nativeforge.core.project.model.RuntimeKind

/** Built-in templates. Order is the order shown in the "New project" screen. */
object ProjectTemplates {

    private val C_AND_CPP = listOf(Language.C, Language.CPP)
    private val CPP_AND_C = listOf(Language.CPP, Language.C)

    val all: List<ProjectTemplate> = listOf(
        SimpleTemplate(
            id = "console",
            displayName = "Console app",
            description = "Reads and writes text in the terminal.",
            details = "A program with main() that runs in the built-in terminal, with keyboard input. " +
                "Good for learning, algorithms and command line tools.",
            category = TemplateCategory.CONSOLE,
            runtime = RuntimeKind.CONSOLE,
            languages = CPP_AND_C,
            sources = { language ->
                if (language == Language.C) mapOf("src/main.c" to TemplateSources.CONSOLE_C)
                else mapOf("src/main.cpp" to TemplateSources.CONSOLE_CPP)
            },
        ),
        SimpleTemplate(
            id = "raylib",
            displayName = "raylib game",
            description = "Game loop with text, shapes and touch input.",
            details = "raylib 6.2 drawing with OpenGL ES 2, full screen. Uses main() as on the desktop; " +
                "touch counts as mouse input. Files you open are read from the project folder.",
            category = TemplateCategory.GRAPHICS,
            runtime = RuntimeKind.NATIVE_ACTIVITY,
            languages = CPP_AND_C,
            packages = listOf("raylib"),
            sources = { language ->
                if (language == Language.C) mapOf("src/main.c" to TemplateSources.RAYLIB)
                else mapOf("src/main.cpp" to TemplateSources.RAYLIB_CPP)
            },
        ),
        SimpleTemplate(
            id = "sdl3",
            displayName = "SDL3 app",
            description = "Window, renderer and events with SDL 3.",
            details = "SDL 3.4 with the 2D renderer, full screen. Handles touch, keyboard, gamepads and audio. " +
                "Runs in SDL's own Android activity.",
            category = TemplateCategory.GRAPHICS,
            runtime = RuntimeKind.SDL3,
            languages = CPP_AND_C,
            packages = listOf("sdl3"),
            sources = { language ->
                if (language == Language.C) mapOf("src/main.c" to TemplateSources.SDL3_C)
                else mapOf("src/main.cpp" to TemplateSources.SDL3)
            },
        ),
        SimpleTemplate(
            id = "sdl2",
            displayName = "SDL2 app",
            description = "Window, renderer and touch with SDL 2.",
            details = "SDL 2.32 with the 2D renderer, full screen, for code and tutorials written for SDL2. " +
                "#include <SDL.h> works as with sdl2-config. SDL2_image, SDL2_ttf and SDL2_mixer are in More › Libraries.",
            category = TemplateCategory.GRAPHICS,
            runtime = RuntimeKind.SDL2,
            languages = CPP_AND_C,
            packages = listOf("sdl2"),
            sources = { language ->
                if (language == Language.C) mapOf("src/main.c" to TemplateSources.SDL2_C)
                else mapOf("src/main.cpp" to TemplateSources.SDL2_CPP)
            },
        ),
        SimpleTemplate(
            id = "imgui",
            displayName = "Dear ImGui",
            description = "Immediate-mode GUI on SDL3 + OpenGL ES 3.",
            details = "Dear ImGui 1.92 with its SDL3 and OpenGL ES 3 backends: windows, sliders, buttons and the " +
                "full demo window, scaled for phone screens. Good for tools, debug overlays and editors.",
            category = TemplateCategory.GRAPHICS,
            runtime = RuntimeKind.SDL3,
            languages = listOf(Language.CPP),
            packages = listOf("sdl3", "imgui", "opengles"),
            sources = { mapOf("src/main.cpp" to TemplateSources.IMGUI) },
        ),
        SimpleTemplate(
            id = "sfml",
            displayName = "SFML app",
            description = "Graphics, window and audio with SFML 3.",
            details = "SFML 3.0 (C++17 or newer) rendering with OpenGL ES. sf::RenderWindow opens full screen; " +
                "touch events arrive as sf::Event::TouchBegan and friends.",
            category = TemplateCategory.GRAPHICS,
            runtime = RuntimeKind.NATIVE_ACTIVITY,
            languages = listOf(Language.CPP),
            packages = listOf("sfml"),
            sources = { mapOf("src/main.cpp" to TemplateSources.SFML) },
        ),
        SimpleTemplate(
            id = "opengles",
            displayName = "OpenGL ES 3",
            description = "Raw EGL and OpenGL ES with native_app_glue.",
            details = "No engine: android_main() from android_native_app_glue creates an EGL context and " +
                "draws with OpenGL ES 3. Best for learning graphics APIs.",
            category = TemplateCategory.GRAPHICS,
            runtime = RuntimeKind.NATIVE_ACTIVITY,
            languages = C_AND_CPP,
            packages = listOf("native-app-glue", "opengles"),
            sources = { language ->
                if (language == Language.C) mapOf("src/main.c" to TemplateSources.OPENGL_ES)
                else mapOf("src/main.cpp" to TemplateSources.OPENGL_ES_CPP)
            },
        ),
        SimpleTemplate(
            id = "vulkan",
            displayName = "Vulkan",
            description = "A spinning triangle with raw Vulkan.",
            details = "No engine: android_main() creates a Vulkan instance, swapchain and pipeline and draws a " +
                "triangle. The shaders are in shaders/ with their SPIR-V embedded in main.c. Needs a device with Vulkan.",
            category = TemplateCategory.GRAPHICS,
            runtime = RuntimeKind.NATIVE_ACTIVITY,
            languages = listOf(Language.C),
            packages = listOf("native-app-glue", "vulkan"),
            sources = {
                mapOf(
                    "src/main.c" to TemplateSources.VULKAN,
                    "shaders/triangle.vert" to TemplateSources.VULKAN_VERTEX_SHADER,
                    "shaders/triangle.frag" to TemplateSources.VULKAN_FRAGMENT_SHADER,
                )
            },
        ),
        SimpleTemplate(
            id = "raylib-physics",
            displayName = "2D physics playground",
            description = "raylib + Box2D physics + raygui buttons.",
            details = "A starting point for a complete game that combines three libraries: raylib draws, Box2D " +
                "simulates falling crates and raygui adds buttons and a slider. Tap to drop crates. " +
                "Add more libraries (Lua, EnTT, JSON...) from More › Libraries.",
            category = TemplateCategory.EXAMPLE,
            runtime = RuntimeKind.NATIVE_ACTIVITY,
            languages = listOf(Language.CPP),
            packages = listOf("raylib", "raygui", "box2d"),
            sources = { mapOf("src/main.cpp" to TemplateSources.RAYLIB_PHYSICS) },
        ),
        SimpleTemplate(
            id = "raylib-3d-physics",
            displayName = "3D physics playground",
            description = "raylib 3D + Box3D physics + raygui.",
            details = "A 3D scene with a pyramid of boxes simulated by Box3D 0.1 (the 3D engine by the author of " +
                "Box2D) and drawn by raylib. Drag to orbit, pinch to zoom, tap the floor to drop a box or a ball " +
                "and press Shoot to fire one from the camera.",
            category = TemplateCategory.EXAMPLE,
            runtime = RuntimeKind.NATIVE_ACTIVITY,
            languages = listOf(Language.CPP),
            packages = listOf("raylib", "raygui", "box3d"),
            sources = { mapOf("src/main.cpp" to TemplateSources.RAYLIB_3D_PHYSICS) },
        ),
    )

    fun byId(id: String): ProjectTemplate? = all.firstOrNull { it.id == id }
}

/** Template made of static files; covers every built-in template. */
class SimpleTemplate(
    override val id: String,
    override val displayName: String,
    override val description: String,
    override val details: String,
    override val category: TemplateCategory,
    override val runtime: RuntimeKind,
    override val languages: List<Language>,
    private val sources: (Language) -> Map<String, String>,
    override val buildSystems: List<BuildSystemKind> = SimpleTemplate.DEFAULT_BUILD_SYSTEMS,
    private val packages: List<String> = emptyList(),
    private val linkerFlags: List<String> = emptyList(),
) : ProjectTemplate {

    override val requiredPackages: List<String> get() = packages

    override fun generate(projectName: String, options: ProjectOptions): GeneratedProject {
        val language = options.language.takeIf { it in languages } ?: languages.first()
        val buildSystem = options.buildSystem.takeIf { it in buildSystems } ?: buildSystems.first()
        val standard = options.standard.takeIf { it in ProjectOptions.standardsFor(language) } ?: language.defaultStandard
        val config = ProjectConfig(
            name = projectName,
            language = language,
            standard = standard,
            buildSystem = buildSystem,
            runtime = runtime,
            packages = packages,
            linkerFlags = linkerFlags,
        ).let { it.copy(export = it.export.copy(orientation = options.orientation)) }
        val files = sources(language).toMutableMap()
        var generatedConfig = config
        when (buildSystem) {
            BuildSystemKind.CMAKE -> files["CMakeLists.txt"] = TemplateSources.cmakeLists(language, standard, runtime)
            BuildSystemKind.MAKE -> {
                files["Makefile"] = TemplateSources.makefile(language, standard, runtime)
                generatedConfig = config.copy(artifactPath = TemplateSources.makeArtifact(runtime))
            }
            BuildSystemKind.SINGLE, BuildSystemKind.NINJA -> Unit
        }
        files[".gitignore"] = "build/\n"
        val rendered = files.mapValues { (_, content) -> content.replace("@PROJECT_NAME@", projectName) }
        return GeneratedProject(generatedConfig, rendered)
    }

    companion object {
        /** Every template can be built by NativeForge itself, by CMake or by a generated Makefile. */
        val DEFAULT_BUILD_SYSTEMS = listOf(BuildSystemKind.SINGLE, BuildSystemKind.CMAKE, BuildSystemKind.MAKE)
    }
}
