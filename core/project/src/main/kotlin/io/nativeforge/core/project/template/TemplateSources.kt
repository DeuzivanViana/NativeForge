package io.nativeforge.core.project.template

import io.nativeforge.core.project.model.Language
import io.nativeforge.core.project.model.RuntimeKind

/** Source code of the built-in templates. `@PROJECT_NAME@` is replaced on generation. */
internal object TemplateSources {

    val CONSOLE_C = """
        |#include <stdio.h>
        |
        |int main(void) {
        |    char name[64];
        |
        |    printf("Hello from @PROJECT_NAME@!\n");
        |    printf("What is your name? ");
        |    fflush(stdout);
        |
        |    if (fgets(name, sizeof name, stdin) != NULL) {
        |        printf("Nice to meet you, %s", name);
        |    }
        |    return 0;
        |}
        |""".trimMargin()

    val CONSOLE_CPP = """
        |#include <iostream>
        |#include <string>
        |
        |int main() {
        |    std::cout << "Hello from @PROJECT_NAME@!\n";
        |    std::cout << "What is your name? " << std::flush;
        |
        |    std::string name;
        |    std::getline(std::cin, name);
        |    std::cout << "Nice to meet you, " << name << "!\n";
        |    return 0;
        |}
        |""".trimMargin()

    /** CMakeLists.txt for a template: every source under src/, linked with the project's libraries. */
    fun cmakeLists(language: Language, standard: String, runtime: RuntimeKind): String {
        val cmakeLanguage = if (language == Language.C) "C" else "CXX"
        val version = standard.filter(Char::isDigit)
        val target = if (runtime.producesSharedLibrary) {
            "# Graphical programs are shared libraries loaded by NativeForge's activity.\n" +
                "add_library(main SHARED ${'$'}{SOURCES})"
        } else {
            "add_executable(main ${'$'}{SOURCES})"
        }
        return """
            |# 3.22 or newer; policies up to CMake 4.2 use their new behaviour.
            |cmake_minimum_required(VERSION 3.22...4.2)
            |project(@PROJECT_NAME@ LANGUAGES C CXX)
            |
            |set(CMAKE_${cmakeLanguage}_STANDARD $version)
            |set(CMAKE_${cmakeLanguage}_STANDARD_REQUIRED ON)
            |set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
            |
            |file(GLOB_RECURSE SOURCES CONFIGURE_DEPENDS src/*.c src/*.cpp src/*.cc)
            |
            |# NativeForge runs the target named "main" (Build settings › CMake target).
            |$target
            |target_include_directories(main PRIVATE src include)
            |
            |# Libraries added in More › Libraries are linked automatically;
            |# NATIVEFORGE_LIBRARIES lists them if a target needs them explicitly.
            |target_link_libraries(main PRIVATE ${'$'}{NATIVEFORGE_LIBRARIES})
            |""".trimMargin()
    }

    /** Output folder and program of [makefile]. */
    fun makeArtifact(runtime: RuntimeKind): String =
        if (runtime.producesSharedLibrary) "build/make/libmain.so" else "build/make/main"

    /** GNU Makefile for a template; NativeForge passes CC, CXX, CFLAGS, CXXFLAGS, LDFLAGS and LDLIBS. */
    fun makefile(language: Language, standard: String, runtime: RuntimeKind): String {
        val cStandard = if (language == Language.C) standard else Language.C.defaultStandard
        val cxxStandard = if (language == Language.CPP) standard else Language.CPP.defaultStandard
        val linker = if (language == Language.C) "${'$'}(CC)" else "${'$'}(CXX)"
        return """
            |# Built with GNU Make. NativeForge passes CC, CXX, CFLAGS, CXXFLAGS, LDFLAGS and LDLIBS
            |# with the Android toolchain and the libraries from More › Libraries.
            |OUT := build/make
            |PROGRAM := ${makeArtifact(runtime)}
            |SRCS := ${'$'}(shell find src -name '*.c' -o -name '*.cpp' -o -name '*.cc')
            |OBJS := ${'$'}(patsubst %,${'$'}(OUT)/obj/%.o,${'$'}(SRCS))
            |
            |all: ${'$'}(PROGRAM)
            |
            |${'$'}(PROGRAM): ${'$'}(OBJS)
            |»$linker ${'$'}(OBJS) -o ${'$'}@ ${'$'}(LDFLAGS) ${'$'}(LDLIBS)
            |
            |${'$'}(OUT)/obj/%.c.o: %.c
            |»@mkdir -p ${'$'}(dir ${'$'}@)
            |»${'$'}(CC) ${'$'}(CFLAGS) -std=$cStandard -MMD -MP -c ${'$'}< -o ${'$'}@
            |
            |${'$'}(OUT)/obj/%.cpp.o: %.cpp
            |»@mkdir -p ${'$'}(dir ${'$'}@)
            |»${'$'}(CXX) ${'$'}(CXXFLAGS) -std=$cxxStandard -MMD -MP -c ${'$'}< -o ${'$'}@
            |
            |${'$'}(OUT)/obj/%.cc.o: %.cc
            |»@mkdir -p ${'$'}(dir ${'$'}@)
            |»${'$'}(CXX) ${'$'}(CXXFLAGS) -std=$cxxStandard -MMD -MP -c ${'$'}< -o ${'$'}@
            |
            |clean:
            |»rm -rf ${'$'}(OUT)
            |
            |-include ${'$'}(OBJS:.o=.d)
            |.PHONY: all clean
            |""".trimMargin().replace('»', '\t')
    }

    val SDL3 = """
        |#include <SDL3/SDL.h>
        |#include <SDL3/SDL_main.h>
        |#include <algorithm>
        |#include <cmath>
        |
        |int main(int, char*[]) {
        |    if (!SDL_Init(SDL_INIT_VIDEO)) {
        |        SDL_Log("SDL_Init failed: %s", SDL_GetError());
        |        return 1;
        |    }
        |
        |    SDL_Window* window = nullptr;
        |    SDL_Renderer* renderer = nullptr;
        |    SDL_CreateWindowAndRenderer("@PROJECT_NAME@", 0, 0, SDL_WINDOW_FULLSCREEN, &window, &renderer);
        |
        |    bool running = true;
        |    while (running) {
        |        SDL_Event event;
        |        while (SDL_PollEvent(&event)) {
        |            if (event.type == SDL_EVENT_QUIT) running = false;
        |        }
        |
        |        int width = 0, height = 0;
        |        SDL_GetRenderOutputSize(renderer, &width, &height);
        |        const float t = SDL_GetTicks() / 1000.0f;
        |
        |        SDL_SetRenderDrawColor(renderer, 24, 26, 33, 255);
        |        SDL_RenderClear(renderer);
        |
        |        // A box 1/6 of the screen's short side, the same share on every phone.
        |        const float side = std::min(width, height) / 6.0f;
        |        SDL_FRect box{width / 2 + std::cos(t) * width / 3 - side / 2,
        |                      height / 2 + std::sin(t) * height / 4 - side / 2, side, side};
        |        SDL_SetRenderDrawColor(renderer, 198, 120, 221, 255);
        |        SDL_RenderFillRect(renderer, &box);
        |        SDL_RenderPresent(renderer);
        |    }
        |
        |    SDL_DestroyRenderer(renderer);
        |    SDL_DestroyWindow(window);
        |    SDL_Quit();
        |    return 0;
        |}
        |""".trimMargin()

    val SDL3_C = """
        |#include <SDL3/SDL.h>
        |#include <SDL3/SDL_main.h>
        |#include <math.h>
        |#include <stdbool.h>
        |
        |int main(int argc, char* argv[]) {
        |    (void)argc;
        |    (void)argv;
        |    if (!SDL_Init(SDL_INIT_VIDEO)) {
        |        SDL_Log("SDL_Init failed: %s", SDL_GetError());
        |        return 1;
        |    }
        |
        |    SDL_Window* window = NULL;
        |    SDL_Renderer* renderer = NULL;
        |    SDL_CreateWindowAndRenderer("@PROJECT_NAME@", 0, 0, SDL_WINDOW_FULLSCREEN, &window, &renderer);
        |
        |    bool running = true;
        |    while (running) {
        |        SDL_Event event;
        |        while (SDL_PollEvent(&event)) {
        |            if (event.type == SDL_EVENT_QUIT) running = false;
        |        }
        |
        |        int width = 0, height = 0;
        |        SDL_GetRenderOutputSize(renderer, &width, &height);
        |        const float t = SDL_GetTicks() / 1000.0f;
        |
        |        SDL_SetRenderDrawColor(renderer, 24, 26, 33, 255);
        |        SDL_RenderClear(renderer);
        |
        |        /* A box 1/6 of the screen's short side, the same share on every phone. */
        |        const float side = SDL_min(width, height) / 6.0f;
        |        SDL_FRect box = {width / 2 + cosf(t) * width / 3 - side / 2,
        |                         height / 2 + sinf(t) * height / 4 - side / 2, side, side};
        |        SDL_SetRenderDrawColor(renderer, 198, 120, 221, 255);
        |        SDL_RenderFillRect(renderer, &box);
        |        SDL_RenderPresent(renderer);
        |    }
        |
        |    SDL_DestroyRenderer(renderer);
        |    SDL_DestroyWindow(window);
        |    SDL_Quit();
        |    return 0;
        |}
        |""".trimMargin()

    val SFML = """
        |#include <SFML/Graphics.hpp>
        |
        |#include <algorithm>
        |#include <cmath>
        |
        |int main() {
        |    sf::RenderWindow window(sf::VideoMode::getDesktopMode(), "@PROJECT_NAME@");
        |    window.setFramerateLimit(60);
        |
        |    sf::CircleShape shape;
        |    shape.setFillColor(sf::Color(152, 195, 121));
        |    sf::Vector2f direction(0.8f, 0.6f);
        |    sf::Clock clock;
        |
        |    while (window.isOpen()) {
        |        while (const auto event = window.pollEvent()) {
        |            if (event->is<sf::Event::Closed>()) window.close();
        |        }
        |
        |        // Sizes follow the screen's short side, so the ball looks the same on every phone.
        |        const sf::Vector2f size(window.getSize());
        |        const float unit = std::min(size.x, size.y) / 360.f;
        |        const float radius = 24.f * unit;
        |        shape.setRadius(radius);
        |        sf::Vector2f position = shape.getPosition() + direction * (180.f * unit * clock.restart().asSeconds());
        |        if (position.x < 0) direction.x = std::abs(direction.x);
        |        if (position.x > size.x - 2 * radius) direction.x = -std::abs(direction.x);
        |        if (position.y < 0) direction.y = std::abs(direction.y);
        |        if (position.y > size.y - 2 * radius) direction.y = -std::abs(direction.y);
        |        shape.setPosition(position);
        |
        |        window.clear(sf::Color(24, 26, 33));
        |        window.draw(shape);
        |        window.display();
        |    }
        |    return 0;
        |}
        |""".trimMargin()

    val OPENGL_ES = """
        |#include <android_native_app_glue.h>
        |#include <EGL/egl.h>
        |#include <GLES3/gl3.h>
        |#include <math.h>
        |#include <time.h>
        |
        |typedef struct {
        |    EGLDisplay display;
        |    EGLSurface surface;
        |    EGLContext context;
        |} Renderer;
        |
        |static int renderer_init(Renderer* r, ANativeWindow* window) {
        |    const EGLint attributes[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_RED_SIZE, 8,
        |                                 EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE};
        |    const EGLint context_attributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        |    EGLConfig config;
        |    EGLint count;
        |
        |    r->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        |    eglInitialize(r->display, NULL, NULL);
        |    eglChooseConfig(r->display, attributes, &config, 1, &count);
        |    r->surface = eglCreateWindowSurface(r->display, config, window, NULL);
        |    r->context = eglCreateContext(r->display, config, EGL_NO_CONTEXT, context_attributes);
        |    return eglMakeCurrent(r->display, r->surface, r->surface, r->context) == EGL_TRUE;
        |}
        |
        |static void renderer_destroy(Renderer* r) {
        |    if (r->display == EGL_NO_DISPLAY) return;
        |    eglMakeCurrent(r->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        |    eglDestroyContext(r->display, r->context);
        |    eglDestroySurface(r->display, r->surface);
        |    eglTerminate(r->display);
        |    r->display = EGL_NO_DISPLAY;
        |}
        |
        |static void handle_command(struct android_app* app, int32_t command) {
        |    Renderer* renderer = (Renderer*)app->userData;
        |    if (command == APP_CMD_INIT_WINDOW && app->window != NULL) renderer_init(renderer, app->window);
        |    if (command == APP_CMD_TERM_WINDOW) renderer_destroy(renderer);
        |}
        |
        |void android_main(struct android_app* app) {
        |    Renderer renderer = {EGL_NO_DISPLAY, EGL_NO_SURFACE, EGL_NO_CONTEXT};
        |    app->userData = &renderer;
        |    app->onAppCmd = handle_command;
        |
        |    while (!app->destroyRequested) {
        |        int events;
        |        struct android_poll_source* source;
        |        while (ALooper_pollOnce(0, NULL, &events, (void**)&source) >= 0) {
        |            if (source != NULL) source->process(app, source);
        |        }
        |        if (renderer.display == EGL_NO_DISPLAY) continue;
        |
        |        struct timespec now;
        |        clock_gettime(CLOCK_MONOTONIC, &now);
        |        const float t = now.tv_sec + now.tv_nsec / 1e9f;
        |        glClearColor(0.5f + 0.5f * sinf(t), 0.3f, 0.5f + 0.5f * cosf(t), 1.0f);
        |        glClear(GL_COLOR_BUFFER_BIT);
        |        eglSwapBuffers(renderer.display, renderer.surface);
        |    }
        |    renderer_destroy(&renderer);
        |}
        |""".trimMargin()

    /** The OpenGL ES sample compiled as C++: only the entry point needs C linkage. */
    val OPENGL_ES_CPP: String = OPENGL_ES
        .replace("#include <math.h>", "#include <cmath>")
        .replace("void android_main(struct android_app* app) {", "extern \"C\" void android_main(struct android_app* app) {")

    val SDL2_CPP = """
        |#include <SDL.h>
        |#include <algorithm>
        |#include <cmath>
        |
        |// SDL 2 with its 2D renderer. SDL.h renames main() to SDL_main, which SDL's activity calls.
        |int main(int, char*[]) {
        |    if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_EVENTS) != 0) {
        |        SDL_Log("SDL_Init failed: %s", SDL_GetError());
        |        return 1;
        |    }
        |    SDL_Window* window = SDL_CreateWindow("@PROJECT_NAME@", SDL_WINDOWPOS_UNDEFINED, SDL_WINDOWPOS_UNDEFINED,
        |                                          0, 0, SDL_WINDOW_FULLSCREEN_DESKTOP | SDL_WINDOW_ALLOW_HIGHDPI);
        |    SDL_Renderer* renderer = SDL_CreateRenderer(window, -1, SDL_RENDERER_ACCELERATED | SDL_RENDERER_PRESENTVSYNC);
        |    if (renderer == nullptr) {
        |        SDL_Log("SDL_CreateRenderer failed: %s", SDL_GetError());
        |        return 1;
        |    }
        |
        |    float targetX = -1, targetY = -1;
        |    bool running = true;
        |    while (running) {
        |        SDL_Event event;
        |        while (SDL_PollEvent(&event)) {
        |            if (event.type == SDL_QUIT) running = false;
        |            if (event.type == SDL_FINGERDOWN || event.type == SDL_FINGERMOTION) {
        |                // Finger positions are 0..1 across the window.
        |                int w = 0, h = 0;
        |                SDL_GetRendererOutputSize(renderer, &w, &h);
        |                targetX = event.tfinger.x * w;
        |                targetY = event.tfinger.y * h;
        |            }
        |        }
        |
        |        int width = 0, height = 0;
        |        SDL_GetRendererOutputSize(renderer, &width, &height);
        |        const float t = SDL_GetTicks() / 1000.0f;
        |        float x = targetX >= 0 ? targetX : width / 2 + std::cos(t) * width / 3;
        |        float y = targetY >= 0 ? targetY : height / 2 + std::sin(t) * height / 4;
        |
        |        SDL_SetRenderDrawColor(renderer, 24, 26, 33, 255);
        |        SDL_RenderClear(renderer);
        |        // A box 1/6 of the screen's short side, the same share on every phone.
        |        const int side = std::min(width, height) / 6;
        |        SDL_Rect box{int(x) - side / 2, int(y) - side / 2, side, side};
        |        SDL_SetRenderDrawColor(renderer, 97, 175, 239, 255);
        |        SDL_RenderFillRect(renderer, &box);
        |        SDL_RenderPresent(renderer);
        |    }
        |
        |    SDL_DestroyRenderer(renderer);
        |    SDL_DestroyWindow(window);
        |    SDL_Quit();
        |    return 0;
        |}
        |""".trimMargin()

    val SDL2_C = """
        |#include <SDL.h>
        |#include <math.h>
        |
        |// SDL 2 with its 2D renderer. SDL.h renames main() to SDL_main, which SDL's activity calls.
        |int main(int argc, char* argv[]) {
        |    (void)argc;
        |    (void)argv;
        |    if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_EVENTS) != 0) {
        |        SDL_Log("SDL_Init failed: %s", SDL_GetError());
        |        return 1;
        |    }
        |    SDL_Window* window = SDL_CreateWindow("@PROJECT_NAME@", SDL_WINDOWPOS_UNDEFINED, SDL_WINDOWPOS_UNDEFINED,
        |                                          0, 0, SDL_WINDOW_FULLSCREEN_DESKTOP | SDL_WINDOW_ALLOW_HIGHDPI);
        |    SDL_Renderer* renderer = SDL_CreateRenderer(window, -1, SDL_RENDERER_ACCELERATED | SDL_RENDERER_PRESENTVSYNC);
        |    if (renderer == NULL) {
        |        SDL_Log("SDL_CreateRenderer failed: %s", SDL_GetError());
        |        return 1;
        |    }
        |
        |    float targetX = -1, targetY = -1;
        |    int running = 1;
        |    while (running) {
        |        SDL_Event event;
        |        while (SDL_PollEvent(&event)) {
        |            if (event.type == SDL_QUIT) running = 0;
        |            if (event.type == SDL_FINGERDOWN || event.type == SDL_FINGERMOTION) {
        |                // Finger positions are 0..1 across the window.
        |                int w = 0, h = 0;
        |                SDL_GetRendererOutputSize(renderer, &w, &h);
        |                targetX = event.tfinger.x * w;
        |                targetY = event.tfinger.y * h;
        |            }
        |        }
        |
        |        int width = 0, height = 0;
        |        SDL_GetRendererOutputSize(renderer, &width, &height);
        |        const float t = SDL_GetTicks() / 1000.0f;
        |        float x = targetX >= 0 ? targetX : width / 2 + cosf(t) * width / 3;
        |        float y = targetY >= 0 ? targetY : height / 2 + sinf(t) * height / 4;
        |
        |        SDL_SetRenderDrawColor(renderer, 24, 26, 33, 255);
        |        SDL_RenderClear(renderer);
        |        /* A box 1/6 of the screen's short side, the same share on every phone. */
        |        const int side = SDL_min(width, height) / 6;
        |        SDL_Rect box = {(int)x - side / 2, (int)y - side / 2, side, side};
        |        SDL_SetRenderDrawColor(renderer, 97, 175, 239, 255);
        |        SDL_RenderFillRect(renderer, &box);
        |        SDL_RenderPresent(renderer);
        |    }
        |
        |    SDL_DestroyRenderer(renderer);
        |    SDL_DestroyWindow(window);
        |    SDL_Quit();
        |    return 0;
        |}
        |""".trimMargin()

    val IMGUI = """
        |// Dear ImGui on SDL 3 with OpenGL ES 3. Tap the widgets; the demo window shows
        |// everything ImGui can do (ImGui::ShowDemoWindow is in imgui_demo.cpp).
        |#include <SDL3/SDL.h>
        |#include <SDL3/SDL_main.h>
        |#include <GLES3/gl3.h>
        |#include "imgui.h"
        |#include "imgui_impl_opengl3.h"
        |#include "imgui_impl_sdl3.h"
        |
        |int main(int, char*[]) {
        |    if (!SDL_Init(SDL_INIT_VIDEO)) {
        |        SDL_Log("SDL_Init failed: %s", SDL_GetError());
        |        return 1;
        |    }
        |    SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_ES);
        |    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3);
        |    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 0);
        |    SDL_Window* window = SDL_CreateWindow("@PROJECT_NAME@", 0, 0,
        |                                          SDL_WINDOW_OPENGL | SDL_WINDOW_FULLSCREEN | SDL_WINDOW_HIGH_PIXEL_DENSITY);
        |    SDL_GLContext context = SDL_GL_CreateContext(window);
        |    if (context == nullptr) {
        |        SDL_Log("SDL_GL_CreateContext failed: %s", SDL_GetError());
        |        return 1;
        |    }
        |    SDL_GL_MakeCurrent(window, context);
        |    SDL_GL_SetSwapInterval(1);
        |
        |    IMGUI_CHECKVERSION();
        |    ImGui::CreateContext();
        |    ImGuiIO& io = ImGui::GetIO();
        |    io.IniFilename = nullptr; // Do not write imgui.ini next to the program.
        |    ImGui::StyleColorsDark();
        |    // Phones have dense screens: scale sizes and fonts by the display's content scale.
        |    const float scale = SDL_GetDisplayContentScale(SDL_GetPrimaryDisplay());
        |    ImGuiStyle& style = ImGui::GetStyle();
        |    style.ScaleAllSizes(scale);
        |    style.FontScaleDpi = scale;
        |    ImGui_ImplSDL3_InitForOpenGL(window, context);
        |    ImGui_ImplOpenGL3_Init("#version 300 es");
        |
        |    bool showDemo = false;
        |    float speed = 1.0f;
        |    int clicks = 0;
        |    ImVec4 background(0.10f, 0.11f, 0.14f, 1.0f);
        |    bool running = true;
        |    while (running) {
        |        SDL_Event event;
        |        while (SDL_PollEvent(&event)) {
        |            ImGui_ImplSDL3_ProcessEvent(&event);
        |            if (event.type == SDL_EVENT_QUIT) running = false;
        |        }
        |
        |        ImGui_ImplOpenGL3_NewFrame();
        |        ImGui_ImplSDL3_NewFrame();
        |        ImGui::NewFrame();
        |
        |        ImGui::SetNextWindowPos(ImVec2(40 * scale, 60 * scale), ImGuiCond_FirstUseEver);
        |        ImGui::Begin("@PROJECT_NAME@");
        |        ImGui::Text("Hello from Dear ImGui %s", IMGUI_VERSION);
        |        ImGui::SliderFloat("Speed", &speed, 0.0f, 5.0f);
        |        ImGui::ColorEdit3("Background", &background.x);
        |        if (ImGui::Button("Tap me")) clicks++;
        |        ImGui::SameLine();
        |        ImGui::Text("%d taps", clicks);
        |        ImGui::Checkbox("Show the demo window", &showDemo);
        |        ImGui::Text("%.0f FPS", io.Framerate);
        |        ImGui::End();
        |        if (showDemo) ImGui::ShowDemoWindow(&showDemo);
        |
        |        ImGui::Render();
        |        int width = 0, height = 0;
        |        SDL_GetWindowSizeInPixels(window, &width, &height);
        |        glViewport(0, 0, width, height);
        |        const float pulse = 0.5f + 0.5f * SDL_sinf(SDL_GetTicks() / 1000.0f * speed);
        |        glClearColor(background.x * pulse, background.y * pulse, background.z * pulse, 1.0f);
        |        glClear(GL_COLOR_BUFFER_BIT);
        |        ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
        |        SDL_GL_SwapWindow(window);
        |    }
        |
        |    ImGui_ImplOpenGL3_Shutdown();
        |    ImGui_ImplSDL3_Shutdown();
        |    ImGui::DestroyContext();
        |    SDL_GL_DestroyContext(context);
        |    SDL_DestroyWindow(window);
        |    SDL_Quit();
        |    return 0;
        |}
        |""".trimMargin()

    val VULKAN = """
        |// Vulkan on Android without an engine: android_native_app_glue gives the window, Vulkan
        |// draws a spinning triangle. The shaders are in shaders/; their SPIR-V is embedded below.
        |// To change them, compile with glslc (Termux package "shaderc"):
        |//   glslc -O shaders/triangle.vert -mfmt=num -o -
        |#include <android_native_app_glue.h>
        |#define VK_USE_PLATFORM_ANDROID_KHR
        |#include <vulkan/vulkan.h>
        |#include <android/log.h>
        |#include <math.h>
        |#include <string.h>
        |#include <time.h>
        |
        |#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "@PROJECT_NAME@", __VA_ARGS__)
        |#define CHECK(call) do { VkResult result_ = (call); if (result_ != VK_SUCCESS) { LOG("%s failed: %d", #call, result_); return 0; } } while (0)
        |#define MAX_IMAGES 8
        |
        |static const uint32_t VERTEX_SPIRV[] = {
        |    0x07230203, 0x00010000, 0x000d000a, 0x00000070, 0x00000000, 0x00020011, 0x00000001, 0x0006000b,
        |    0x00000001, 0x4c534c47, 0x6474732e, 0x3035342e, 0x00000000, 0x0003000e, 0x00000000, 0x00000001,
        |    0x0008000f, 0x00000000, 0x00000004, 0x6e69616d, 0x00000000, 0x00000018, 0x00000045, 0x00000054,
        |    0x00040047, 0x00000018, 0x0000000b, 0x0000002a, 0x00050048, 0x00000020, 0x00000000, 0x00000023,
        |    0x00000000, 0x00050048, 0x00000020, 0x00000001, 0x00000023, 0x00000004, 0x00030047, 0x00000020,
        |    0x00000002, 0x00050048, 0x00000043, 0x00000000, 0x0000000b, 0x00000000, 0x00050048, 0x00000043,
        |    0x00000001, 0x0000000b, 0x00000001, 0x00050048, 0x00000043, 0x00000002, 0x0000000b, 0x00000003,
        |    0x00050048, 0x00000043, 0x00000003, 0x0000000b, 0x00000004, 0x00030047, 0x00000043, 0x00000002,
        |    0x00040047, 0x00000054, 0x0000001e, 0x00000000, 0x00020013, 0x00000002, 0x00030021, 0x00000003,
        |    0x00000002, 0x00030016, 0x00000006, 0x00000020, 0x00040017, 0x00000007, 0x00000006, 0x00000002,
        |    0x00040020, 0x00000008, 0x00000007, 0x00000007, 0x00040015, 0x0000000a, 0x00000020, 0x00000000,
        |    0x0004002b, 0x0000000a, 0x0000000b, 0x00000003, 0x0004001c, 0x0000000c, 0x00000007, 0x0000000b,
        |    0x0004002b, 0x00000006, 0x0000000d, 0x00000000, 0x0004002b, 0x00000006, 0x0000000e, 0xbf19999a,
        |    0x0005002c, 0x00000007, 0x0000000f, 0x0000000d, 0x0000000e, 0x0004002b, 0x00000006, 0x00000010,
        |    0x3f0ccccd, 0x0004002b, 0x00000006, 0x00000011, 0x3ee66666, 0x0005002c, 0x00000007, 0x00000012,
        |    0x00000010, 0x00000011, 0x0004002b, 0x00000006, 0x00000013, 0xbf0ccccd, 0x0005002c, 0x00000007,
        |    0x00000014, 0x00000013, 0x00000011, 0x0006002c, 0x0000000c, 0x00000015, 0x0000000f, 0x00000012,
        |    0x00000014, 0x00040015, 0x00000016, 0x00000020, 0x00000001, 0x00040020, 0x00000017, 0x00000001,
        |    0x00000016, 0x0004003b, 0x00000017, 0x00000018, 0x00000001, 0x00040020, 0x0000001a, 0x00000007,
        |    0x0000000c, 0x0004001e, 0x00000020, 0x00000006, 0x00000006, 0x00040020, 0x00000021, 0x00000009,
        |    0x00000020, 0x0004003b, 0x00000021, 0x00000022, 0x00000009, 0x0004002b, 0x00000016, 0x00000023,
        |    0x00000000, 0x00040020, 0x00000024, 0x00000009, 0x00000006, 0x0004002b, 0x0000000a, 0x00000032,
        |    0x00000001, 0x00040017, 0x00000041, 0x00000006, 0x00000004, 0x0004001c, 0x00000042, 0x00000006,
        |    0x00000032, 0x0006001e, 0x00000043, 0x00000041, 0x00000006, 0x00000042, 0x00000042, 0x00040020,
        |    0x00000044, 0x00000003, 0x00000043, 0x0004003b, 0x00000044, 0x00000045, 0x00000003, 0x0004002b,
        |    0x00000016, 0x00000048, 0x00000001, 0x0004002b, 0x00000006, 0x0000004e, 0x3f800000, 0x00040020,
        |    0x00000050, 0x00000003, 0x00000041, 0x00040017, 0x00000052, 0x00000006, 0x00000003, 0x00040020,
        |    0x00000053, 0x00000003, 0x00000052, 0x0004003b, 0x00000053, 0x00000054, 0x00000003, 0x0004001c,
        |    0x00000055, 0x00000052, 0x0000000b, 0x0004002b, 0x00000006, 0x00000056, 0x3f6147ae, 0x0004002b,
        |    0x00000006, 0x00000057, 0x3ed70a3d, 0x0004002b, 0x00000006, 0x00000058, 0x3eeb851f, 0x0006002c,
        |    0x00000052, 0x00000059, 0x00000056, 0x00000057, 0x00000058, 0x0004002b, 0x00000006, 0x0000005a,
        |    0x3f19999a, 0x0004002b, 0x00000006, 0x0000005b, 0x3f4ccccd, 0x0004002b, 0x00000006, 0x0000005c,
        |    0x3ef0a3d7, 0x0006002c, 0x00000052, 0x0000005d, 0x0000005a, 0x0000005b, 0x0000005c, 0x0004002b,
        |    0x00000006, 0x0000005e, 0x3ec28f5c, 0x0004002b, 0x00000006, 0x0000005f, 0x3f30a3d7, 0x0004002b,
        |    0x00000006, 0x00000060, 0x3f70a3d7, 0x0006002c, 0x00000052, 0x00000061, 0x0000005e, 0x0000005f,
        |    0x00000060, 0x0006002c, 0x00000055, 0x00000062, 0x00000059, 0x0000005d, 0x00000061, 0x00040020,
        |    0x00000064, 0x00000007, 0x00000055, 0x00040020, 0x00000066, 0x00000007, 0x00000052, 0x00050036,
        |    0x00000002, 0x00000004, 0x00000000, 0x00000003, 0x000200f8, 0x00000005, 0x0004003b, 0x0000001a,
        |    0x0000001b, 0x00000007, 0x0004003b, 0x00000064, 0x00000065, 0x00000007, 0x0004003d, 0x00000016,
        |    0x00000019, 0x00000018, 0x0003003e, 0x0000001b, 0x00000015, 0x00050041, 0x00000008, 0x0000001c,
        |    0x0000001b, 0x00000019, 0x0004003d, 0x00000007, 0x0000001d, 0x0000001c, 0x00050041, 0x00000024,
        |    0x00000025, 0x00000022, 0x00000023, 0x0004003d, 0x00000006, 0x00000026, 0x00000025, 0x0006000c,
        |    0x00000006, 0x00000027, 0x00000001, 0x0000000e, 0x00000026, 0x0006000c, 0x00000006, 0x0000002b,
        |    0x00000001, 0x0000000d, 0x00000026, 0x00050051, 0x00000006, 0x0000002f, 0x0000001d, 0x00000000,
        |    0x00050051, 0x00000006, 0x00000034, 0x0000001d, 0x00000001, 0x00050085, 0x00000006, 0x00000035,
        |    0x0000002b, 0x00000034, 0x0004007f, 0x00000006, 0x0000006f, 0x00000035, 0x0008000c, 0x00000006,
        |    0x00000036, 0x00000001, 0x00000032, 0x00000027, 0x0000002f, 0x0000006f, 0x00050085, 0x00000006,
        |    0x0000003e, 0x00000027, 0x00000034, 0x0008000c, 0x00000006, 0x0000003f, 0x00000001, 0x00000032,
        |    0x0000002b, 0x0000002f, 0x0000003e, 0x00050041, 0x00000024, 0x00000049, 0x00000022, 0x00000048,
        |    0x0004003d, 0x00000006, 0x0000004a, 0x00000049, 0x00050088, 0x00000006, 0x0000004b, 0x00000036,
        |    0x0000004a, 0x00070050, 0x00000041, 0x0000004f, 0x0000004b, 0x0000003f, 0x0000000d, 0x0000004e,
        |    0x00050041, 0x00000050, 0x00000051, 0x00000045, 0x00000023, 0x0003003e, 0x00000051, 0x0000004f,
        |    0x0003003e, 0x00000065, 0x00000062, 0x00050041, 0x00000066, 0x00000067, 0x00000065, 0x00000019,
        |    0x0004003d, 0x00000052, 0x00000068, 0x00000067, 0x0003003e, 0x00000054, 0x00000068, 0x000100fd,
        |    0x00010038,
        |};
        |static const uint32_t FRAGMENT_SPIRV[] = {
        |    0x07230203, 0x00010000, 0x000d000a, 0x00000013, 0x00000000, 0x00020011, 0x00000001, 0x0006000b,
        |    0x00000001, 0x4c534c47, 0x6474732e, 0x3035342e, 0x00000000, 0x0003000e, 0x00000000, 0x00000001,
        |    0x0007000f, 0x00000004, 0x00000004, 0x6e69616d, 0x00000000, 0x00000009, 0x0000000c, 0x00030010,
        |    0x00000004, 0x00000007, 0x00040047, 0x00000009, 0x0000001e, 0x00000000, 0x00040047, 0x0000000c,
        |    0x0000001e, 0x00000000, 0x00020013, 0x00000002, 0x00030021, 0x00000003, 0x00000002, 0x00030016,
        |    0x00000006, 0x00000020, 0x00040017, 0x00000007, 0x00000006, 0x00000004, 0x00040020, 0x00000008,
        |    0x00000003, 0x00000007, 0x0004003b, 0x00000008, 0x00000009, 0x00000003, 0x00040017, 0x0000000a,
        |    0x00000006, 0x00000003, 0x00040020, 0x0000000b, 0x00000001, 0x0000000a, 0x0004003b, 0x0000000b,
        |    0x0000000c, 0x00000001, 0x0004002b, 0x00000006, 0x0000000e, 0x3f800000, 0x00050036, 0x00000002,
        |    0x00000004, 0x00000000, 0x00000003, 0x000200f8, 0x00000005, 0x0004003d, 0x0000000a, 0x0000000d,
        |    0x0000000c, 0x00050051, 0x00000006, 0x0000000f, 0x0000000d, 0x00000000, 0x00050051, 0x00000006,
        |    0x00000010, 0x0000000d, 0x00000001, 0x00050051, 0x00000006, 0x00000011, 0x0000000d, 0x00000002,
        |    0x00070050, 0x00000007, 0x00000012, 0x0000000f, 0x00000010, 0x00000011, 0x0000000e, 0x0003003e,
        |    0x00000009, 0x00000012, 0x000100fd, 0x00010038,
        |};
        |
        |typedef struct {
        |    VkInstance instance;
        |    VkSurfaceKHR surface;
        |    VkPhysicalDevice gpu;
        |    uint32_t queue_family;
        |    VkDevice device;
        |    VkQueue queue;
        |    VkSwapchainKHR swapchain;
        |    VkExtent2D extent;
        |    uint32_t image_count;
        |    VkImageView views[MAX_IMAGES];
        |    VkFramebuffer framebuffers[MAX_IMAGES];
        |    VkCommandBuffer commands[MAX_IMAGES];
        |    VkRenderPass render_pass;
        |    VkPipelineLayout layout;
        |    VkPipeline pipeline;
        |    VkCommandPool pool;
        |    VkSemaphore acquired, rendered;
        |    VkFence fence;
        |    int ready;
        |} Renderer;
        |
        |typedef struct { float angle, aspect; } Push;
        |
        |static VkShaderModule shader(VkDevice device, const uint32_t* code, size_t size) {
        |    VkShaderModuleCreateInfo info = {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        |    info.codeSize = size;
        |    info.pCode = code;
        |    VkShaderModule module = VK_NULL_HANDLE;
        |    vkCreateShaderModule(device, &info, NULL, &module);
        |    return module;
        |}
        |
        |static int create_device(Renderer* r, ANativeWindow* window) {
        |    const char* instance_extensions[] = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
        |    VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO};
        |    app.pApplicationName = "@PROJECT_NAME@";
        |    app.apiVersion = VK_API_VERSION_1_0;
        |    VkInstanceCreateInfo instance_info = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        |    instance_info.pApplicationInfo = &app;
        |    instance_info.enabledExtensionCount = 2;
        |    instance_info.ppEnabledExtensionNames = instance_extensions;
        |    CHECK(vkCreateInstance(&instance_info, NULL, &r->instance));
        |
        |    VkAndroidSurfaceCreateInfoKHR surface_info = {VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
        |    surface_info.window = window;
        |    CHECK(vkCreateAndroidSurfaceKHR(r->instance, &surface_info, NULL, &r->surface));
        |
        |    uint32_t gpu_count = 1;
        |    vkEnumeratePhysicalDevices(r->instance, &gpu_count, &r->gpu);
        |    if (gpu_count == 0) { LOG("No Vulkan device"); return 0; }
        |
        |    uint32_t family_count = 0;
        |    vkGetPhysicalDeviceQueueFamilyProperties(r->gpu, &family_count, NULL);
        |    VkQueueFamilyProperties families[16];
        |    if (family_count > 16) family_count = 16;
        |    vkGetPhysicalDeviceQueueFamilyProperties(r->gpu, &family_count, families);
        |    r->queue_family = UINT32_MAX;
        |    for (uint32_t i = 0; i < family_count; i++) {
        |        VkBool32 present = VK_FALSE;
        |        vkGetPhysicalDeviceSurfaceSupportKHR(r->gpu, i, r->surface, &present);
        |        if ((families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && present) { r->queue_family = i; break; }
        |    }
        |    if (r->queue_family == UINT32_MAX) { LOG("No queue can draw and present"); return 0; }
        |
        |    const float priority = 1.0f;
        |    VkDeviceQueueCreateInfo queue_info = {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        |    queue_info.queueFamilyIndex = r->queue_family;
        |    queue_info.queueCount = 1;
        |    queue_info.pQueuePriorities = &priority;
        |    const char* device_extensions[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
        |    VkDeviceCreateInfo device_info = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        |    device_info.queueCreateInfoCount = 1;
        |    device_info.pQueueCreateInfos = &queue_info;
        |    device_info.enabledExtensionCount = 1;
        |    device_info.ppEnabledExtensionNames = device_extensions;
        |    CHECK(vkCreateDevice(r->gpu, &device_info, NULL, &r->device));
        |    vkGetDeviceQueue(r->device, r->queue_family, 0, &r->queue);
        |    return 1;
        |}
        |
        |static int create_swapchain(Renderer* r, ANativeWindow* window) {
        |    VkSurfaceCapabilitiesKHR caps;
        |    CHECK(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(r->gpu, r->surface, &caps));
        |    uint32_t format_count = 8;
        |    VkSurfaceFormatKHR formats[8];
        |    vkGetPhysicalDeviceSurfaceFormatsKHR(r->gpu, r->surface, &format_count, formats);
        |    VkSurfaceFormatKHR format = formats[0];
        |    for (uint32_t i = 0; i < format_count; i++) {
        |        if (formats[i].format == VK_FORMAT_R8G8B8A8_UNORM) format = formats[i];
        |    }
        |
        |    r->extent = caps.currentExtent;
        |    if (r->extent.width == UINT32_MAX) {
        |        r->extent.width = (uint32_t)ANativeWindow_getWidth(window);
        |        r->extent.height = (uint32_t)ANativeWindow_getHeight(window);
        |    }
        |    uint32_t images = caps.minImageCount + 1;
        |    if (caps.maxImageCount > 0 && images > caps.maxImageCount) images = caps.maxImageCount;
        |    if (images > MAX_IMAGES) images = MAX_IMAGES;
        |
        |    VkSwapchainCreateInfoKHR info = {VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
        |    info.surface = r->surface;
        |    info.minImageCount = images;
        |    info.imageFormat = format.format;
        |    info.imageColorSpace = format.colorSpace;
        |    info.imageExtent = r->extent;
        |    info.imageArrayLayers = 1;
        |    info.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        |    info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        |    // Identity lets the system compositor rotate the picture: simple, at a small cost.
        |    info.preTransform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
        |        ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR : caps.currentTransform;
        |    info.compositeAlpha = (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
        |        ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
        |    info.presentMode = VK_PRESENT_MODE_FIFO_KHR;
        |    info.clipped = VK_TRUE;
        |    CHECK(vkCreateSwapchainKHR(r->device, &info, NULL, &r->swapchain));
        |
        |    VkAttachmentDescription color = {0};
        |    color.format = format.format;
        |    color.samples = VK_SAMPLE_COUNT_1_BIT;
        |    color.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        |    color.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        |    color.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        |    color.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        |    color.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        |    color.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        |    VkAttachmentReference color_ref = {0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        |    VkSubpassDescription subpass = {0};
        |    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        |    subpass.colorAttachmentCount = 1;
        |    subpass.pColorAttachments = &color_ref;
        |    VkSubpassDependency dependency = {0};
        |    dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
        |    dependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        |    dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        |    dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        |    VkRenderPassCreateInfo pass_info = {VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
        |    pass_info.attachmentCount = 1;
        |    pass_info.pAttachments = &color;
        |    pass_info.subpassCount = 1;
        |    pass_info.pSubpasses = &subpass;
        |    pass_info.dependencyCount = 1;
        |    pass_info.pDependencies = &dependency;
        |    CHECK(vkCreateRenderPass(r->device, &pass_info, NULL, &r->render_pass));
        |
        |    VkImage swapchain_images[MAX_IMAGES];
        |    r->image_count = MAX_IMAGES;
        |    CHECK(vkGetSwapchainImagesKHR(r->device, r->swapchain, &r->image_count, swapchain_images));
        |    for (uint32_t i = 0; i < r->image_count; i++) {
        |        VkImageViewCreateInfo view = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        |        view.image = swapchain_images[i];
        |        view.viewType = VK_IMAGE_VIEW_TYPE_2D;
        |        view.format = format.format;
        |        view.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        |        view.subresourceRange.levelCount = 1;
        |        view.subresourceRange.layerCount = 1;
        |        CHECK(vkCreateImageView(r->device, &view, NULL, &r->views[i]));
        |        VkFramebufferCreateInfo framebuffer = {VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
        |        framebuffer.renderPass = r->render_pass;
        |        framebuffer.attachmentCount = 1;
        |        framebuffer.pAttachments = &r->views[i];
        |        framebuffer.width = r->extent.width;
        |        framebuffer.height = r->extent.height;
        |        framebuffer.layers = 1;
        |        CHECK(vkCreateFramebuffer(r->device, &framebuffer, NULL, &r->framebuffers[i]));
        |    }
        |    return 1;
        |}
        |
        |static int create_pipeline(Renderer* r) {
        |    VkShaderModule vertex = shader(r->device, VERTEX_SPIRV, sizeof(VERTEX_SPIRV));
        |    VkShaderModule fragment = shader(r->device, FRAGMENT_SPIRV, sizeof(FRAGMENT_SPIRV));
        |    VkPipelineShaderStageCreateInfo stages[2] = {
        |        {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, NULL, 0, VK_SHADER_STAGE_VERTEX_BIT, vertex, "main", NULL},
        |        {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, NULL, 0, VK_SHADER_STAGE_FRAGMENT_BIT, fragment, "main", NULL},
        |    };
        |    VkPipelineVertexInputStateCreateInfo input = {VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
        |    VkPipelineInputAssemblyStateCreateInfo assembly = {VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
        |    assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        |    VkPipelineViewportStateCreateInfo viewport = {VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
        |    viewport.viewportCount = 1;
        |    viewport.scissorCount = 1;
        |    VkPipelineRasterizationStateCreateInfo raster = {VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
        |    raster.polygonMode = VK_POLYGON_MODE_FILL;
        |    raster.cullMode = VK_CULL_MODE_NONE;
        |    raster.lineWidth = 1.0f;
        |    VkPipelineMultisampleStateCreateInfo multisample = {VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
        |    multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
        |    VkPipelineColorBlendAttachmentState blend_attachment = {0};
        |    blend_attachment.colorWriteMask = 0xF;
        |    VkPipelineColorBlendStateCreateInfo blend = {VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
        |    blend.attachmentCount = 1;
        |    blend.pAttachments = &blend_attachment;
        |    VkDynamicState dynamic_states[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
        |    VkPipelineDynamicStateCreateInfo dynamic = {VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
        |    dynamic.dynamicStateCount = 2;
        |    dynamic.pDynamicStates = dynamic_states;
        |
        |    VkPushConstantRange push = {VK_SHADER_STAGE_VERTEX_BIT, 0, sizeof(Push)};
        |    VkPipelineLayoutCreateInfo layout = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        |    layout.pushConstantRangeCount = 1;
        |    layout.pPushConstantRanges = &push;
        |    CHECK(vkCreatePipelineLayout(r->device, &layout, NULL, &r->layout));
        |
        |    VkGraphicsPipelineCreateInfo info = {VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
        |    info.stageCount = 2;
        |    info.pStages = stages;
        |    info.pVertexInputState = &input;
        |    info.pInputAssemblyState = &assembly;
        |    info.pViewportState = &viewport;
        |    info.pRasterizationState = &raster;
        |    info.pMultisampleState = &multisample;
        |    info.pColorBlendState = &blend;
        |    info.pDynamicState = &dynamic;
        |    info.layout = r->layout;
        |    info.renderPass = r->render_pass;
        |    VkResult result = vkCreateGraphicsPipelines(r->device, VK_NULL_HANDLE, 1, &info, NULL, &r->pipeline);
        |    vkDestroyShaderModule(r->device, vertex, NULL);
        |    vkDestroyShaderModule(r->device, fragment, NULL);
        |    CHECK(result);
        |
        |    VkCommandPoolCreateInfo pool = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        |    pool.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        |    pool.queueFamilyIndex = r->queue_family;
        |    CHECK(vkCreateCommandPool(r->device, &pool, NULL, &r->pool));
        |    VkCommandBufferAllocateInfo allocate = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        |    allocate.commandPool = r->pool;
        |    allocate.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        |    allocate.commandBufferCount = r->image_count;
        |    CHECK(vkAllocateCommandBuffers(r->device, &allocate, r->commands));
        |
        |    VkSemaphoreCreateInfo semaphore = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        |    CHECK(vkCreateSemaphore(r->device, &semaphore, NULL, &r->acquired));
        |    CHECK(vkCreateSemaphore(r->device, &semaphore, NULL, &r->rendered));
        |    VkFenceCreateInfo fence = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        |    fence.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        |    CHECK(vkCreateFence(r->device, &fence, NULL, &r->fence));
        |    return 1;
        |}
        |
        |static void renderer_destroy(Renderer* r) {
        |    if (r->device) {
        |        vkDeviceWaitIdle(r->device);
        |        vkDestroyFence(r->device, r->fence, NULL);
        |        vkDestroySemaphore(r->device, r->rendered, NULL);
        |        vkDestroySemaphore(r->device, r->acquired, NULL);
        |        vkDestroyCommandPool(r->device, r->pool, NULL);
        |        vkDestroyPipeline(r->device, r->pipeline, NULL);
        |        vkDestroyPipelineLayout(r->device, r->layout, NULL);
        |        for (uint32_t i = 0; i < r->image_count; i++) {
        |            vkDestroyFramebuffer(r->device, r->framebuffers[i], NULL);
        |            vkDestroyImageView(r->device, r->views[i], NULL);
        |        }
        |        vkDestroyRenderPass(r->device, r->render_pass, NULL);
        |        vkDestroySwapchainKHR(r->device, r->swapchain, NULL);
        |        vkDestroyDevice(r->device, NULL);
        |    }
        |    if (r->instance) {
        |        vkDestroySurfaceKHR(r->instance, r->surface, NULL);
        |        vkDestroyInstance(r->instance, NULL);
        |    }
        |    memset(r, 0, sizeof(*r));
        |}
        |
        |static void renderer_init(Renderer* r, ANativeWindow* window) {
        |    renderer_destroy(r);
        |    r->ready = create_device(r, window) && create_swapchain(r, window) && create_pipeline(r);
        |    if (!r->ready) renderer_destroy(r);
        |}
        |
        |static void draw(Renderer* r, float t) {
        |    vkWaitForFences(r->device, 1, &r->fence, VK_TRUE, UINT64_MAX);
        |    uint32_t index = 0;
        |    VkResult acquired = vkAcquireNextImageKHR(r->device, r->swapchain, UINT64_MAX, r->acquired, VK_NULL_HANDLE, &index);
        |    if (acquired != VK_SUCCESS && acquired != VK_SUBOPTIMAL_KHR) return;
        |    vkResetFences(r->device, 1, &r->fence);
        |
        |    VkCommandBuffer cmd = r->commands[index];
        |    vkResetCommandBuffer(cmd, 0);
        |    VkCommandBufferBeginInfo begin = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        |    vkBeginCommandBuffer(cmd, &begin);
        |    VkClearValue clear = {{{0.08f, 0.09f, 0.12f + 0.05f * sinf(t), 1.0f}}};
        |    VkRenderPassBeginInfo pass = {VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
        |    pass.renderPass = r->render_pass;
        |    pass.framebuffer = r->framebuffers[index];
        |    pass.renderArea.extent = r->extent;
        |    pass.clearValueCount = 1;
        |    pass.pClearValues = &clear;
        |    vkCmdBeginRenderPass(cmd, &pass, VK_SUBPASS_CONTENTS_INLINE);
        |    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, r->pipeline);
        |    VkViewport viewport = {0, 0, (float)r->extent.width, (float)r->extent.height, 0, 1};
        |    VkRect2D scissor = {{0, 0}, r->extent};
        |    vkCmdSetViewport(cmd, 0, 1, &viewport);
        |    vkCmdSetScissor(cmd, 0, 1, &scissor);
        |    Push push = {t, (float)r->extent.width / (float)r->extent.height};
        |    vkCmdPushConstants(cmd, r->layout, VK_SHADER_STAGE_VERTEX_BIT, 0, sizeof(push), &push);
        |    vkCmdDraw(cmd, 3, 1, 0, 0);
        |    vkCmdEndRenderPass(cmd);
        |    vkEndCommandBuffer(cmd);
        |
        |    VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        |    VkSubmitInfo submit = {VK_STRUCTURE_TYPE_SUBMIT_INFO};
        |    submit.waitSemaphoreCount = 1;
        |    submit.pWaitSemaphores = &r->acquired;
        |    submit.pWaitDstStageMask = &wait_stage;
        |    submit.commandBufferCount = 1;
        |    submit.pCommandBuffers = &cmd;
        |    submit.signalSemaphoreCount = 1;
        |    submit.pSignalSemaphores = &r->rendered;
        |    vkQueueSubmit(r->queue, 1, &submit, r->fence);
        |
        |    VkPresentInfoKHR present = {VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
        |    present.waitSemaphoreCount = 1;
        |    present.pWaitSemaphores = &r->rendered;
        |    present.swapchainCount = 1;
        |    present.pSwapchains = &r->swapchain;
        |    present.pImageIndices = &index;
        |    vkQueuePresentKHR(r->queue, &present);
        |}
        |
        |static void handle_command(struct android_app* app, int32_t command) {
        |    Renderer* renderer = (Renderer*)app->userData;
        |    switch (command) {
        |        case APP_CMD_INIT_WINDOW:
        |        case APP_CMD_WINDOW_RESIZED:
        |        case APP_CMD_CONFIG_CHANGED:
        |            if (app->window != NULL) renderer_init(renderer, app->window);
        |            break;
        |        case APP_CMD_TERM_WINDOW:
        |            renderer_destroy(renderer);
        |            break;
        |    }
        |}
        |
        |void android_main(struct android_app* app) {
        |    Renderer renderer;
        |    memset(&renderer, 0, sizeof(renderer));
        |    app->userData = &renderer;
        |    app->onAppCmd = handle_command;
        |
        |    while (!app->destroyRequested) {
        |        int events;
        |        struct android_poll_source* source;
        |        while (ALooper_pollOnce(renderer.ready ? 0 : -1, NULL, &events, (void**)&source) >= 0) {
        |            if (source != NULL) source->process(app, source);
        |            if (app->destroyRequested) break;
        |        }
        |        if (!renderer.ready) continue;
        |        struct timespec now;
        |        clock_gettime(CLOCK_MONOTONIC, &now);
        |        draw(&renderer, (float)now.tv_sec + now.tv_nsec / 1e9f);
        |    }
        |    renderer_destroy(&renderer);
        |}
        |""".trimMargin()

    /** The Vulkan sample compiled as C++: Vulkan's headers are C++-ready, only the entry point needs C linkage. */
    val VULKAN_CPP: String = VULKAN
        .replace("#include <math.h>", "#include <cmath>")
        .replace("#include <string.h>", "#include <cstring>")
        .replace("#include <time.h>", "#include <ctime>")
        .replace("void android_main(struct android_app* app) {", "extern \"C\" void android_main(struct android_app* app) {")

    val VULKAN_VERTEX_SHADER = """
        |#version 450
        |layout(push_constant) uniform Push { float angle; float aspect; } push;
        |layout(location = 0) out vec3 color;
        |const vec2 positions[3] = vec2[](vec2(0.0, -0.6), vec2(0.55, 0.45), vec2(-0.55, 0.45));
        |const vec3 colors[3] = vec3[](vec3(0.88, 0.42, 0.46), vec3(0.6, 0.8, 0.47), vec3(0.38, 0.69, 0.94));
        |void main() {
        |    vec2 p = positions[gl_VertexIndex];
        |    float c = cos(push.angle), s = sin(push.angle);
        |    p = vec2(c * p.x - s * p.y, s * p.x + c * p.y);
        |    gl_Position = vec4(p.x / push.aspect, p.y, 0.0, 1.0);
        |    color = colors[gl_VertexIndex];
        |}
        |""".trimMargin()

    val VULKAN_FRAGMENT_SHADER = """
        |#version 450
        |layout(location = 0) in vec3 color;
        |layout(location = 0) out vec4 outColor;
        |void main() { outColor = vec4(color, 1.0); }
        |""".trimMargin()
}
