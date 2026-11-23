# Third-party notices

| Component | Use | License |
| --- | --- | --- |
| [Sora Editor](https://github.com/Rosemoe/sora-editor) | Code editor widget | LGPL-2.1 |
| [Termux terminal-view / terminal-emulator](https://github.com/termux/termux-app) | Terminal emulator and view | Apache-2.0 |
| TextMate grammars for C, C++ and C++ macros ([microsoft/vscode](https://github.com/microsoft/vscode)), some patterns rewritten for Joni by `tools/textmate-joni-compat.py` | Syntax highlighting | MIT |
| TextMate grammar for CMake ([twxs/vs.language.cmake](https://github.com/twxs/vs.language.cmake)) | Syntax highlighting | MIT |
| Editor themes via [tm-themes](https://github.com/shikijs/textmate-grammars-themes): One Dark Pro, One Light, Dracula, Monokai, Nord, Tokyo Night, Catppuccin Mocha, Gruvbox Dark, GitHub Dark/Light, Solarized Dark/Light, Ayu Dark, Night Owl | Editor themes (`nativeforge-black.json` is One Dark Pro with black backgrounds) | MIT (each theme's own copyright holders) |
| [apksig](https://android.googlesource.com/platform/tools/apksig/) | APK signing | Apache-2.0 |
| [Bouncy Castle](https://www.bouncycastle.org/) | Signing certificate generation | MIT |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) and [XZ for Java](https://tukaani.org/xz/java.html) | Package extraction | Apache-2.0 / 0BSD |
| [SDL 3](https://github.com/libsdl-org/SDL) 3.4.20, native library and `org.libsdl.app` Java glue (`feature/runner/src/main/java`) | Bundled Android library, SDL3 program host | Zlib |
| [SDL 2](https://github.com/libsdl-org/SDL/tree/SDL2) 2.32.10, native library and Java glue relocated to `org.libsdl.sdl2` (`feature/runner/src/main/java/org/libsdl/sdl2`) | Bundled Android library, SDL2 program host | Zlib |
| [SDL2_image](https://github.com/libsdl-org/SDL_image/tree/SDL2) 2.8.12, [SDL2_ttf](https://github.com/libsdl-org/SDL_ttf/tree/SDL2) 2.24.0, [SDL2_mixer](https://github.com/libsdl-org/SDL_mixer/tree/SDL2) 2.8.2 (with stb_image, stb_vorbis, dr_libs, [minimp3](https://github.com/lieff/minimp3) inside) | Bundled Android libraries | Zlib (bundled decoders: public domain / MIT / CC0) |
| [Dear ImGui](https://github.com/ocornut/imgui) 1.92.2b | Bundled Android library | MIT |
| Vulkan headers (from Android NDK r27c, [Vulkan-Headers](https://github.com/KhronosGroup/Vulkan-Headers)) | Bundled Android headers | Apache-2.0 / MIT |
| [raylib](https://github.com/raysan5/raylib) 6.2 | Bundled Android library | Zlib |
| [SFML](https://github.com/SFML/SFML) 3.0.2 | Bundled Android library | Zlib |
| [FreeType](https://freetype.org/) 2.14.3 (inside SFML graphics) | Font rendering | FreeType License (FTL) |
| [libogg](https://xiph.org/ogg/) 1.3.5, [libvorbis](https://xiph.org/vorbis/) 1.3.7, [FLAC](https://xiph.org/flac/) 1.4.3 (inside SFML audio) | Audio decoding | BSD-3-Clause |
| [miniaudio](https://github.com/mackron/miniaudio) and [stb_image](https://github.com/nothings/stb) (inside SFML) | Audio output, image loading | Public domain / MIT-0 |
| `android_native_app_glue` from the Android NDK r27c | Bundled Android library | Apache-2.0 |
| [SDL_image](https://github.com/libsdl-org/SDL_image) 3.4.8, [SDL_ttf](https://github.com/libsdl-org/SDL_ttf) 3.2.2, [SDL_mixer](https://github.com/libsdl-org/SDL_mixer) 3.2.4 (with stb_image, stb_vorbis, dr_libs inside) | Bundled Android libraries | Zlib (bundled decoders: public domain / MIT) |
| [Box2D](https://box2d.org/) 2.4.1 | Bundled Android library | MIT |
| [Box3D](https://github.com/erincatto/box3d) 0.1.0 | Bundled Android library | MIT |
| [JGit](https://www.eclipse.org/jgit/) 5.13.5 | Git client | EDL-1.0 (BSD-3-Clause) |
| [JavaEWAH](https://github.com/lemire/javaewah) (JGit dependency) | Git bitmaps | Apache-2.0 |
| [SLF4J](https://www.slf4j.org/) 1.7.36 API and no-op binding | Logging facade used by JGit | MIT |
| [Lua](https://www.lua.org/) 5.4.9 | Bundled Android library | MIT |
| [raygui](https://github.com/raysan5/raygui) 4.0 | Bundled header-only library | Zlib |
| [GLM](https://github.com/g-truc/glm) 1.0.1 | Bundled header-only library | MIT (or the Happy Bunny License) |
| [JSON for Modern C++](https://github.com/nlohmann/json) 3.11.3 | Bundled header-only library | MIT |
| [EnTT](https://github.com/skypjack/entt) 3.15.0 | Bundled header-only library | MIT |
| [stb](https://github.com/nothings/stb) and [miniaudio](https://miniaud.io/) 0.11.22 | Bundled header-only libraries | Public domain / MIT(-0) |
| EGL, OpenGL ES and KHR headers from the Android NDK r27c (Khronos Group) | Bundled `opengles` package | Khronos (MIT-style) / Apache-2.0 |
| clangd, clang-format and clang-tidy ([LLVM](https://llvm.org/), from the Termux `clang` package, downloaded at run time) | Code intelligence and formatting | Apache-2.0 WITH LLVM-exception |
| Packages downloaded at run time from the [Termux repository](https://github.com/termux/termux-packages) | Toolchain and libraries | Per package |

Grammar and theme JSON files were taken from the `tm-grammars` 1.32.22 and
`tm-themes` 1.12.12 npm packages.

The Android libraries in `app/src/main/assets/packages/` are built from the
Ubuntu source packages of each project (Box3D from its v0.1.0 git tag) by `packages/build-android-libs.sh`.
Each archive ships the project's license file under `share/licenses/`.
