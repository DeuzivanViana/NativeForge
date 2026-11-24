# Android library index

Graphical libraries must be built for the Android platform (SurfaceView/EGL),
not for X11, so they cannot come from the Termux repository. NativeForge reads
them from a JSON index instead.

The IDE ships its own index inside the APK (`app/src/main/assets/packages/`,
read through the `asset:` URL scheme), so SDL3, raylib, SFML,
native-app-glue and opengles (EGL/GLES headers, which Termux's ndk-sysroot
deletes) show up under "Android graphics" with no network access.
Extra remote indexes can be added at build time with
`-Pnativeforge.packageIndexUrl=<url>`, or by users in Settings › Package
repositories (HTTPS only). Every package is an archive per ABI. Sources are
searched in order: bundled, build-time, user repositories, then Termux.

### Hosting your own repository

Put `index.json` and the archives on any static HTTPS host (GitHub Pages works)
and add the index URL in Settings. A relative `downloadUrl` such as
`aarch64/foo-1.0.tar.xz` is resolved against the index URL, so the folder can be
moved without editing the index. Every entry needs a `sha256`: entries without
one are ignored, and archives are rejected when their SHA-256 does not match.

## Rebuilding the bundled libraries

```bash
export ANDROID_HOME=~/android-sdk          # NDK 27.2.12479018 and CMake 3.22.1 installed
WORK_DIR=$HOME/nfwork packages/build-android-libs.sh            # arm64-v8a armeabi-v7a x86_64
ABIS="arm64-v8a" WORK_DIR=$HOME/nfwork packages/build-android-libs.sh
```

The script downloads the sources, builds every library with the NDK (API 24,
`c++_shared`, 16 KB page alignment), packs reproducible `tar.xz` archives and
rewrites `index.json`.

`ONLY="sdl2 imgui" packages/build-android-libs.sh` rebuilds a subset and keeps
the other index entries.

When `SDL3_VERSION` changes, replace the Java files in
`feature/runner/src/main/java/org/libsdl/app/` with the ones from the same SDL
release (`android-project/app/src/main/java/org/libsdl/app/`). SDL checks at
start-up that the Java glue and the native library have the same version.

SDL2 and SDL3 both define `org.libsdl.app.SDLActivity`, so SDL2's glue is moved
to `org.libsdl.sdl2` (`feature/runner/src/main/java/org/libsdl/sdl2/`) and the
script renames the JNI symbols in `libSDL2.so` to match. Apply the same rename
when updating `SDL2_VERSION`.

## Format

```json
{
  "schemaVersion": 1,
  "packages": [
    {
      "name": "sdl3",
      "version": "3.4.20",
      "description": "Simple DirectMedia Layer 3 (Android backend)",
      "architecture": "aarch64",
      "depends": [],
      "downloadUrl": "asset:packages/aarch64/sdl3-3.4.20.tar.xz",
      "sha256": "…",
      "format": "tar.xz",
      "target": "isolated",
      "build": {
        "includeDirs": ["include"],
        "libDirs": ["lib"],
        "linkLibraries": ["SDL3"],
        "runtimeLibraries": ["lib/libSDL3.so"]
      }
    }
  ]
}
```

* `downloadUrl` may be `https://…` or a path relative to the index URL; `asset:<path>`
  (a file inside the IDE APK) is only accepted in the bundled index. Plain `http://`
  entries are ignored.
* `architecture` uses Debian names: `aarch64`, `arm`, `x86_64`, `i686` (or `all`).
* `target: "isolated"` installs into `files/android-libs/<name>/`, which is also
  added to `CMAKE_PREFIX_PATH`, so ship `lib/cmake/<Name>/<Name>Config.cmake`
  for `find_package()` support.
* `linkFlags` are added to the final link (raylib needs `-Wl,--wrap=fopen`, which
  cannot be recorded in a static archive).
* `runtimeLibraries` are preloaded before the program and bundled into exported APKs.

## Bundled packages

| Package | Kind | Notes |
| --- | --- | --- |
| sdl3, sdl3-image, sdl3-ttf, sdl3-mixer | shared | satellites depend on sdl3 and are preloaded after it |
| sdl2, sdl2-image, sdl2-ttf, sdl2-mixer | shared | SDL 2.32 for older code; JNI glue relocated to `org.libsdl.sdl2` |
| imgui | static | Dear ImGui core and backends for SDL2/SDL3, SDL renderer, OpenGL ES 3, Vulkan, Android |
| lvgl | static | LVGL 9.6 on the SDL2 driver (depends on sdl2), widgets demo, `lv_nativeforge.h` for a full screen, density-scaled display with touch and keyboard; configuration in `packages/lvgl/lv_conf.h` |
| vulkan | headers + stub | Vulkan headers from the NDK and a `libvulkan.so` link stub |
| raylib, raygui | static / header | raylib links with `-Wl,--wrap=fopen`; `libraylib.a` also carries a `__real_fopen` fallback |
| sfml | shared | graphics, window, audio, network, system |
| box2d, box3d, lua | static | Box3D 0.1 is an alpha C API; SIMD is off on armeabi-v7a |
| native-app-glue, opengles | static / headers | raw NativeActivity + EGL/GLES programs |
| glm, nlohmann-json, entt, stb, miniaudio | header-only | one archive for every ABI (`architecture: all`) |

A project lists packages in `nativeforge.json` (`packages`), edited from
More › Libraries in the editor. Dependencies (`depends`) are installed and linked
automatically; static archives are linked dependents first.

When an app update ships a rebuilt archive (new `sha256`), installed copies are
reinstalled automatically at start-up and before builds, because build metadata
such as `linkFlags` is recorded at install time.

## Next packages

bgfx (needs its shader compiler, `shaderc`, built for the host too), SDL3_net,
physfs, enet. Add a build step and an index entry in `build-android-libs.sh`.
