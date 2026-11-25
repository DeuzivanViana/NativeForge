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
            id = "ncurses-tui",
            displayName = "Text UI (ncurses)",
            description = "Full screen terminal app with windows, colors, keys and taps.",
            details = "A menu and a panel drawn with ncurses: arrow keys, Enter and taps on the menu, colors, " +
                "a clock that ticks while waiting for keys, and a layout that follows screen rotation. " +
                "The bundled ncurses also has panels, menus and forms.",
            category = TemplateCategory.CONSOLE,
            runtime = RuntimeKind.CONSOLE,
            languages = C_AND_CPP,
            packages = listOf("ncursesw"),
            sources = { language -> resourceSources(language, "ncurses-tui") },
        ),
        SimpleTemplate(
            id = "multi-file",
            displayName = "Multi-file program",
            description = "Code split into modules with headers in include/.",
            details = "A grade book split into a module (header in include/, code in src/) and a main file that reads " +
                "names and grades and prints a report. Shows declarations vs. definitions, static helpers and " +
                "dynamic arrays in C, classes and the standard library in C++. Every file in src/ is built.",
            category = TemplateCategory.CONSOLE,
            runtime = RuntimeKind.CONSOLE,
            languages = C_AND_CPP,
            sources = { language ->
                if (language == Language.C) {
                    mapOf(
                        "src/main.c" to TemplateResources.read("multi-file/main.c"),
                        "src/grades.c" to TemplateResources.read("multi-file/grades.c"),
                        "include/grades.h" to TemplateResources.read("multi-file/grades.h"),
                    )
                } else {
                    mapOf(
                        "src/main.cpp" to TemplateResources.read("multi-file/main.cpp"),
                        "src/gradebook.cpp" to TemplateResources.read("multi-file/gradebook.cpp"),
                        "include/gradebook.hpp" to TemplateResources.read("multi-file/gradebook.hpp"),
                    )
                }
            },
        ),
        SimpleTemplate(
            id = "unit-tests",
            displayName = "Unit tests",
            description = "Functions with tests that run when you press Run.",
            details = "A small module and its unit tests with a tiny test helper (no library needed): each check " +
                "reports its file and line, the run lists PASS or FAIL per test, and the exit code counts the " +
                "failures. The C++ version registers tests with TEST(name), like GoogleTest.",
            category = TemplateCategory.CONSOLE,
            runtime = RuntimeKind.CONSOLE,
            languages = C_AND_CPP,
            sources = { language ->
                if (language == Language.C) {
                    mapOf(
                        "src/main.c" to TemplateResources.read("unit-tests/main.c"),
                        "src/textutil.c" to TemplateResources.read("unit-tests/textutil.c"),
                        "include/textutil.h" to TemplateResources.read("unit-tests/textutil.h"),
                        "include/check.h" to TemplateResources.read("unit-tests/check.h"),
                    )
                } else {
                    mapOf(
                        "src/main.cpp" to TemplateResources.read("unit-tests/main.cpp"),
                        "src/textutil.cpp" to TemplateResources.read("unit-tests/textutil.cpp"),
                        "include/textutil.hpp" to TemplateResources.read("unit-tests/textutil.hpp"),
                        "include/check.hpp" to TemplateResources.read("unit-tests/check.hpp"),
                    )
                }
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
                if (language == Language.C) mapOf("src/main.c" to TemplateResources.read("raylib/main.c"))
                else mapOf("src/main.cpp" to TemplateResources.read("raylib/main.cpp"))
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
            id = "lvgl",
            displayName = "LVGL app",
            description = "Touch app with buttons, lists, sliders and a keyboard.",
            details = "LVGL 9.6 draws real app screens: buttons, switches, sliders, lists, tabs, charts, " +
                "message boxes and an on-screen keyboard, with themes and animations. Runs in the SDL2 host; " +
                "lv_nativeforge.h sets up the full screen display and touch. Sizes are in dp, like Android layouts.",
            category = TemplateCategory.GRAPHICS,
            runtime = RuntimeKind.SDL2,
            languages = C_AND_CPP,
            packages = listOf("sdl2", "lvgl"),
            sources = { language ->
                if (language == Language.C) mapOf("src/main.c" to TemplateResources.read("lvgl/main.c"))
                else mapOf("src/main.cpp" to TemplateResources.read("lvgl/main.cpp"))
            },
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
                "triangle. The shaders are in shaders/ with their SPIR-V embedded in the source. Needs a device with Vulkan.",
            category = TemplateCategory.GRAPHICS,
            runtime = RuntimeKind.NATIVE_ACTIVITY,
            languages = C_AND_CPP,
            packages = listOf("native-app-glue", "vulkan"),
            sources = { language ->
                mapOf(
                    if (language == Language.C) "src/main.c" to TemplateSources.VULKAN
                    else "src/main.cpp" to TemplateSources.VULKAN_CPP,
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
            sources = { mapOf("src/main.cpp" to TemplateResources.read("raylib-physics/main.cpp")) },
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
            sources = { mapOf("src/main.cpp" to TemplateResources.read("raylib-3d-physics/main.cpp")) },
        ),
        SimpleTemplate(
            id = "voxel-sandbox",
            displayName = "Voxel sandbox",
            description = "raylib + Box3D: an endless block world to build in and throw things around.",
            details = "A first-person block world without end, made from noise as you walk: seas, beaches, " +
                "forests and snowy peaks. Walk, run, jump, sneak, swim and fly with block-game movement; break " +
                "blocks, place them, or punch things with your bare hand. A creative inventory holds every block " +
                "and ten physical things (balls, crates, barrels, dominoes, tyres, bombs that blow craters...) " +
                "that Box3D makes roll, float and topple. Split into a few files; keyboard and mouse work too.",
            category = TemplateCategory.EXAMPLE,
            runtime = RuntimeKind.NATIVE_ACTIVITY,
            languages = listOf(Language.CPP),
            packages = listOf("raylib", "box3d"),
            sources = {
                mapOf(
                    "src/main.cpp" to TemplateResources.read("voxel-sandbox/main.cpp"),
                    "src/game.hpp" to TemplateResources.read("voxel-sandbox/game.hpp"),
                    "src/world.cpp" to TemplateResources.read("voxel-sandbox/world.cpp"),
                    "src/player.cpp" to TemplateResources.read("voxel-sandbox/player.cpp"),
                    "src/things.cpp" to TemplateResources.read("voxel-sandbox/things.cpp"),
                    "src/ui.cpp" to TemplateResources.read("voxel-sandbox/ui.cpp"),
                )
            },
        ),
        SimpleTemplate(
            id = "lvgl-widgets-demo",
            displayName = "LVGL widgets demo",
            description = "LVGL's showcase: forms, charts and a shop page.",
            details = "Runs lv_demo_widgets(), LVGL's own demo with a profile form, an analytics dashboard " +
                "and a shop page in tabs. A quick way to see what LVGL apps look like on your phone.",
            category = TemplateCategory.EXAMPLE,
            runtime = RuntimeKind.SDL2,
            languages = C_AND_CPP,
            packages = listOf("sdl2", "lvgl"),
            sources = { language -> resourceSources(language, "lvgl-widgets-demo") },
        ),
        SimpleTemplate(
            id = "lvgl-tasks",
            displayName = "To-do list app",
            description = "LVGL: tabs, check boxes, keyboard, chart; saves its data.",
            details = "A complete small app with LVGL (C or C++): add tasks with the on-screen keyboard, tick them off, " +
                "delete them, and follow your progress on an arc and a bar chart. Tasks are saved in the app's " +
                "private folder and loaded at the next start.",
            category = TemplateCategory.EXAMPLE,
            runtime = RuntimeKind.SDL2,
            languages = C_AND_CPP,
            packages = listOf("sdl2", "lvgl"),
            sources = { language -> resourceSources(language, "lvgl-tasks") },
        ),
        SimpleTemplate(
            id = "lvgl-calculator",
            displayName = "Calculator app",
            description = "LVGL in C++: button matrix and large display.",
            details = "A calculator written in C++ with LVGL: the keys are one lv_buttonmatrix, the logic is a " +
                "plain C++ class with no LVGL code, which keeps it easy to test. Dark theme, orange accents.",
            category = TemplateCategory.EXAMPLE,
            runtime = RuntimeKind.SDL2,
            languages = listOf(Language.CPP),
            packages = listOf("sdl2", "lvgl"),
            sources = { mapOf("src/main.cpp" to TemplateResources.read("lvgl-calculator/main.cpp")) },
        ),
        SimpleTemplate(
            id = "gles-cube",
            displayName = "3D cube with shaders",
            description = "OpenGL ES 3 + GLM math on an SDL3 window.",
            details = "A lit, spinning cube drawn with your own GLSL shaders, vertex buffers and GLM matrices " +
                "(glm::perspective, glm::lookAt, glm::rotate). Drag to spin it. The base for learning 3D graphics " +
                "without an engine.",
            category = TemplateCategory.EXAMPLE,
            runtime = RuntimeKind.SDL3,
            languages = listOf(Language.CPP),
            packages = listOf("sdl3", "opengles", "glm"),
            sources = { mapOf("src/main.cpp" to TemplateResources.read("gles-cube/main.cpp")) },
        ),
        SimpleTemplate(
            id = "sdl3-synth",
            displayName = "Touch synthesizer",
            description = "SDL3 audio: play notes with up to 10 fingers.",
            details = "Generates sound in real time with an SDL3 audio stream: each finger plays a note of a " +
                "pentatonic scale, brighter towards the top of the screen. Shows multi-touch, audio callbacks " +
                "and SDL's built-in debug text. No sound files needed.",
            category = TemplateCategory.EXAMPLE,
            runtime = RuntimeKind.SDL3,
            languages = C_AND_CPP,
            packages = listOf("sdl3"),
            sources = { language -> resourceSources(language, "sdl3-synth") },
        ),
        SimpleTemplate(
            id = "sdl3-snake",
            displayName = "Snake game",
            description = "SDL3 2D renderer: swipe to steer, best score saved.",
            details = "A complete little game with SDL3's 2D renderer: swipe or use the arrow keys to steer, eat to " +
                "grow, and the game speeds up. The board and text are sized from the screen, so it fits every phone " +
                "upright or sideways. The best score is saved in the app's private folder. The same source builds " +
                "as C or C++.",
            category = TemplateCategory.EXAMPLE,
            runtime = RuntimeKind.SDL3,
            languages = C_AND_CPP,
            packages = listOf("sdl3"),
            // Written in the common part of C and C++: the same file serves both languages.
            sources = { language ->
                mapOf((if (language == Language.C) "src/main.c" else "src/main.cpp") to TemplateResources.read("sdl3-snake/main.c"))
            },
        ),
        SimpleTemplate(
            id = "raylib-paint",
            displayName = "Finger painting",
            description = "raylib + raygui: colours, brush size, undo.",
            details = "Paint with your finger on a canvas kept in a raylib render texture. raygui provides the brush " +
                "size slider and the Undo and Clear buttons. Strokes are stored as point lists in C++ vectors, so " +
                "Undo just paints the others again and the drawing survives turning the phone.",
            category = TemplateCategory.EXAMPLE,
            runtime = RuntimeKind.NATIVE_ACTIVITY,
            languages = listOf(Language.CPP),
            packages = listOf("raylib", "raygui"),
            sources = { mapOf("src/main.cpp" to TemplateResources.read("raylib-paint/main.cpp")) },
        ),
        SimpleTemplate(
            id = "ecs-particles",
            displayName = "Particles with EnTT",
            description = "raylib + EnTT entity-component-system.",
            details = "Thousands of particles stored as EnTT entities with position, velocity, lifetime and colour " +
                "components, updated by small systems and drawn by raylib. Touch and drag to spray more. " +
                "Shows how games organise data with an ECS.",
            category = TemplateCategory.EXAMPLE,
            runtime = RuntimeKind.NATIVE_ACTIVITY,
            languages = listOf(Language.CPP),
            packages = listOf("raylib", "entt"),
            sources = { mapOf("src/main.cpp" to TemplateResources.read("ecs-particles/main.cpp")) },
        ),
        SimpleTemplate(
            id = "lua-console",
            displayName = "Lua scripting console",
            description = "Embed Lua 5.4 in C; interactive prompt.",
            details = "A C or C++ program that hosts the Lua interpreter: it exposes native functions to Lua, calls a " +
                "Lua function from native code, runs scripts/startup.lua (edit it without rebuilding) and opens a Lua prompt " +
                "in the terminal.",
            category = TemplateCategory.EXAMPLE,
            runtime = RuntimeKind.CONSOLE,
            languages = C_AND_CPP,
            packages = listOf("lua"),
            sources = { language ->
                val startup = TemplateResources.read("lua-console/startup.lua")
                resourceSources(language, "lua-console") + mapOf(
                    "scripts/startup.lua" to
                        if (language == Language.C) startup
                        else startup.replace("main.c ", "main.cpp ").replace("inside C.", "inside C++."),
                )
            },
        ),
        SimpleTemplate(
            id = "sqlite-todo",
            displayName = "To-do list (SQLite)",
            description = "Console app that keeps its data in an SQLite database.",
            details = "A terminal to-do list stored in todo.db with SQLite 3: create a table, add, finish, find and " +
                "remove tasks with prepared statements, and type any SQL query to see its rows. The C++ version " +
                "wraps the connection and statements in small RAII classes.",
            category = TemplateCategory.EXAMPLE,
            runtime = RuntimeKind.CONSOLE,
            languages = C_AND_CPP,
            packages = listOf("sqlite3"),
            sources = { language -> resourceSources(language, "sqlite-todo") },
        ),
        SimpleTemplate(
            id = "json-contacts",
            displayName = "Contact book (JSON)",
            description = "Console app that saves its data as JSON.",
            details = "A terminal menu to list, add, search and delete contacts, stored in contacts.json with " +
                "JSON for Modern C++ (nlohmann/json). Structs convert to and from JSON with one macro.",
            category = TemplateCategory.EXAMPLE,
            runtime = RuntimeKind.CONSOLE,
            languages = listOf(Language.CPP),
            packages = listOf("nlohmann-json"),
            sources = { mapOf("src/main.cpp" to TemplateResources.read("json-contacts/main.cpp")) },
        ),
    )

    fun byId(id: String): ProjectTemplate? = all.firstOrNull { it.id == id }

    /** src/main.c or src/main.cpp from the template's resource folder, for the chosen language. */
    private fun resourceSources(language: Language, folder: String): Map<String, String> =
        if (language == Language.C) mapOf("src/main.c" to TemplateResources.read("$folder/main.c"))
        else mapOf("src/main.cpp" to TemplateResources.read("$folder/main.cpp"))
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
