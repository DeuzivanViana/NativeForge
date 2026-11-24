# Roadmap

## 0.8.0 — search in project

* Search in project (Ctrl+Shift+F, the command palette, the menu or the
  search field in the explorer) looks through every text file of the project,
  including unsaved changes, with match case, whole word and regex options.
  `build/`, `.git` and other tool folders, links, binaries and files over
  4 MB are skipped; results stop at 5000 matches.
* Tapping a result opens the file and selects the match. Replace all edits
  files shown in an editor there (one undo step, left unsaved), files with
  unsaved changes in memory, and rewrites the others on disk. In regex mode
  `$1` and `${name}` insert groups.
* `ProjectSearch` (core/project) holds the search and replace logic, covered
  by `ProjectSearchTest`.

## 0.7.2 — release automation, F-Droid preparation

* Application ID `io.github.deuzivanviana.nativeforge`, based on the source
  repository as F-Droid asks for apps without their own domain. It installs
  as a new app next to test builds of earlier versions.
* `packages/build-android-libs.sh` checks every downloaded source file against
  `packages/sources.sha256` and pins the Box3D tag to its commit. A full run
  from scratch (all libraries, three ABIs) was checked against the committed
  index.
* GitHub Actions: `libs-from-source.yml` rebuilds the libraries as the F-Droid
  recipe does; `release.yml` turns a `v*` tag into a GitHub release with the
  APK built from those libraries.

## 0.7.1 — C++ highlighting fix

* Code after a comment in C++ files was colored as a comment. The VS Code C++
  grammars use regex features that Oniguruma 6.9 accepts but Joni (the engine
  sora-editor uses on Android) rejects: variable-length look-behinds and
  `\s+{1,0}`. tm4e drops such rules, and one of them was the end of `//`
  comments. `tools/textmate-joni-compat.py` rewrites the 45 affected patterns
  with the same meaning; `GrammarJoniTest` compiles every pattern with Joni and
  checks comment boundaries in C and C++.

## 0.7.0 — more graphics libraries, package repositories

* SDL2 2.32.10 with SDL2_image 2.8.12, SDL2_ttf 2.24.0 and SDL2_mixer 2.8.2.
  SDL2 and SDL3 both ship `org.libsdl.app.SDLActivity`, so SDL2's Java glue is
  moved to `org.libsdl.sdl2` and the build script renames the JNI symbols of
  `libSDL2.so` to match; a second host activity runs SDL2 programs. Templates
  in C and C++.
* Dear ImGui 1.92.2b as a static library with the SDL2, SDL3, SDL renderer,
  OpenGL ES 3, Vulkan and Android backends; template: SDL3 + OpenGL ES 3 with
  DPI-scaled fonts.
* Vulkan: NDK r27c headers and a link stub; template: a rotating triangle on
  NativeActivity with SPIR-V shaders embedded in the source (GLSL sources in
  `shaders/`).
* Settings › Package repositories: users add their own `index.json` over HTTPS;
  relative `downloadUrl`s resolve against the index URL; archives are still
  checked against their SHA-256.
* Checked: every new template compiles and links (`-Wl,--no-undefined`) against
  the bundled archives with the NDK clang; not yet run on a device.
* Not done: bgfx. Its build needs its own shader compiler (`shaderc`) built for
  the device as well, and its source archives could not be fetched from this
  build environment. Planned as a separate step.

## 0.6.1 — signing keys and app bundles

* Export › Signing key: create a PKCS#12 keystore (alias, password, name,
  organization, country, validity) or import one made by keytool or Android
  Studio (JKS, PKCS#12, BKS; JKS is read by NativeForge itself because Android
  has no JKS provider). Keys are kept in private storage; passwords can be
  remembered, encrypted with an Android Keystore AES key. Back up writes the
  original file anywhere through the system file picker.
* Export › Package: APK or Android App Bundle. The bundle is built from a
  200 KB skeleton of the runtime template's bundle (proto manifest, resources,
  BundleConfig) plus the dex files and native libraries of the template APK,
  which are identical. The proto manifest is patched like the binary one, the
  native targeting is trimmed to the included ABIs, and the bundle gets a JAR
  signature with MANIFEST.MF first, as jarsigner writes it. Checked with
  `bundletool validate` and `build-apks` (universal and split APKs) and
  `jarsigner -verify`.
* The exporter takes one program per ABI. Building for ABIs other than the
  device's still needs cross sysroots (see Milestone 4).

## 0.6.0 — code intelligence, keyboard, assets, F-Droid

* clangd from the Termux `clang` package: completion (also after `.`, `->`
  and `::`), live errors and warnings in the editor and status bar, go to
  definition (F12), rename (F2) and symbol information. The IDE writes
  `build/.nativeforge/compile_commands.json` for every build system (CMake's
  own database is used when it exists) and a clangd config with the Android
  target and sysroot. clangd restarts by itself up to three times.
* clang-format with the project's `.clang-format` or a style chosen in
  Settings, format document (Shift+Alt+F) and format on save; optional
  clang-tidy diagnostics.
* Physical keyboard and mouse: VS Code shortcuts, command palette, quick open,
  go to line, move/duplicate/delete lines, Ctrl+wheel font zoom, and a split
  editor (Ctrl+\\).
* Asset viewer: images with zoom and pixel mode (TGA decoded by the app),
  sound player, OpenGL ES preview of OBJ, STL, PLY and glTF/GLB models, font
  preview.
* The toolchain download asks for consent first and says where the programs
  come from.
* GPL-3.0-or-later, with the runtime that exported APKs include also under
  zlib (`LICENSE.runtime`). README, fastlane metadata (en-US, pt-BR) and a
  draft F-Droid recipe (`docs/FDROID.md`).
* The 0.5.1–0.5.3 source archives were missing `core/build`; it was restored
  and the 0.5.1 desktop Makefile support re-implemented and tested.

## 0.5.3 — CMake with Ninja on the device, copyable output

* CMake projects failed in the compiler check with `FAILED: [code=127]`: Termux
  builds ninja, make and cmake with `/data/data/com.termux/files/usr/bin/sh`
  compiled in (ninja runs every command through it, cmake writes it as
  `SHELL =` into Makefiles), and that file does not exist outside Termux.
  `TermuxShellPatch` rewrites the string in those binaries, in place and with
  the same length, to `/////////////////////////system/bin/sh` before each
  build; `usr/bin/sh` links to `/system/bin/sh` for relocated scripts.
* The build output can be selected (long press and drag) and the panel's copy
  button copies the whole output or the problems list.

## 0.5.2 — toolchain install on a fresh install

* Installing the toolchain on a new install failed with "Packages 'clang',
  'lld'... were not found": at start-up only the bundled library index is read
  (to update bundled libraries), and the installer took that non-empty list as
  the full catalog, so the Termux index was never downloaded. The package
  manager now tracks which sources were read, reads the missing ones before
  installing, and reports a failed download as a network problem.

## 0.5.1 — desktop Makefiles

* Makefiles written for desktop systems build unchanged: `gcc`, `g++`, `cc`,
  `c++`, `clang` and `clang++` are NativeForge scripts during Make and Ninja
  builds (and `CC`/`CXX` point at them). They run clang with the Android
  target, put the project's libraries ahead of vendored `third-party/` copies,
  drop desktop-only libraries (`-lGL`, `-lX11`, `-lpthread`...) and, for
  graphical runtimes, link a shared library whatever the output is called
  (`app.elf`).
* Programs are recognized by their ELF header instead of their file name, and
  the last linked program is remembered, so a Make build with nothing to
  relink still runs.

## 0.5.0 — git, any build system, 3D

* Build systems are independent: built-in, CMake (Ninja or Makefiles), Make,
  Ninja. Cloned projects are detected; Build settings changes target, arguments,
  program file and source folder.
* Clone a project from an HTTPS git URL; the explorer's Changes tab shows
  status and diffs and can commit, discard, pull and push.
* Explorer: no longer opened by horizontal swipes while scrolling code (optional
  in Settings), collapsible folders, file icons, rename/delete/new folder,
  docked on wide screens.
* "3D physics playground" example (raylib + Box3D + raygui); examples have
  their own section in New project.
* Slimmer launcher icon (with a monochrome layer), richer project cards,
  Settings redesigned with the app's form cards.
* Security pass, see [SECURITY.md](SECURITY.md).

## 0.4.0 — projects with many libraries

* raylib linked in 0.3.0 only for new installs: install metadata is stored at
  install time. Bundled packages whose archive changed are now reinstalled
  automatically, and `libraylib.a` carries a `__real_fopen` fallback.
* New bundled libraries: SDL3_image, SDL3_ttf, SDL3_mixer, Box2D, Lua, raygui,
  GLM, EnTT, nlohmann/json, stb, miniaudio.
* More › Libraries manages a project's packages and extra flags; transitive
  dependencies are linked in the right order; library headers are `-isystem`.
* "Physics game" template: raylib + Box2D + raygui.

## 0.3.0 — fixes from the first device test

* raylib programs closed at start: raylib 6 wraps fopen() and needs
  `-Wl,--wrap=fopen` at the final link; the package index now adds it.
* OpenGL ES template: Termux removes the EGL/GLES headers, so a bundled
  `opengles` package provides them; `-lm` is always linked.
* Graphical programs link with `-Wl,--no-undefined`, so a missing symbol fails
  the build instead of closing the window, and start-up errors from the runner
  show in the output panel (More › Program log shows the app's logcat).
* Templates: language (C/C++), standard and build system chosen per project.
* New project dialog and all dialogs redesigned; export screen with icon,
  orientation, full screen, keep screen on, assets, release/debug, arguments.

## 0.2.0 — usability pass

* Compact, VS Code-like layout across every screen; black default theme.
* 11 app themes and 15 editor themes (TextMate), chosen in Settings.
* Editor shortcut bar: undo/redo, tab, caret moves, line start/end, comment,
  duplicate line, select all, clipboard, snippets; find and replace.
* Settings screen; black terminal in the IDE and in exported APKs.

## Milestone 0 — architecture and skeleton

Done:
* Modular Gradle build with convention plugins, version catalog and CI.
* Project model (`nativeforge.json`) and 8 templates: console C/C++, CMake,
  SDL3, raylib, SFML, OpenGL ES (SDL2 removed in 0.2.0).
* Clang toolchain description, incremental single-folder build system,
  CMake + Ninja build system, clang/lld/CMake diagnostics parser.
* Package manager: Termux APT source, JSON index source, dependency resolver,
  `.deb`/tar/zip extraction with path-traversal protection, prefix relocation.
* APK export pipeline: binary manifest patcher, assembler, apksig signing.
* Editor (Sora + TextMate), console activity (Termux terminal), NativeActivity
  runner with native shim, runtime template app.
* Screens: projects, workspace (files, tabs, build output, problems),
  packages, export.

Not verified yet: the full on-device flow (toolchain install → build → run →
export) has only been checked by unit tests and CI compilation.

## Milestone 1 — first end-to-end run on a device

* Install the toolchain on a real arm64 device and fix relocation gaps.
* Run the console templates; tune terminal input and environment.
* ~~Settings screen (font size, theme, editor options).~~ Done in 0.2.0.
* Import/export projects to shared storage (Storage Access Framework).

## Milestone 2 — graphical libraries

* Done in 0.2.0: `packages/build-android-libs.sh` builds SDL3, raylib, SFML and
  native-app-glue with the NDK for arm64-v8a, armeabi-v7a and x86_64; the
  archives and index ship inside the IDE APK; SDL3 host activity with matching
  Java glue. SDL2 was dropped in favour of SDL3.
* Next: run that script in CI, satellite libs (SDL3_image/ttf/mixer, glm,
  box2d), and verify every graphical template on a real device.

## Milestone 3 — editor intelligence

* Done in 0.6.0 (clangd completion, diagnostics, definition, rename, hover,
  compile database, clang-format).
* Next: find references panel, signature help, code actions (quick fixes).

## Milestone 4 — export polish

* Done: custom app icon, bundling a project `assets/` folder (0.4/0.5), user
  keystores and app bundles (0.6.1).
* Next: more ABIs per APK/AAB. The exporter already packs one program per ABI;
  the build needs a sysroot per extra ABI (crt objects, libc stubs, libc++ and
  compiler-rt builtins from the NDK or Termux), clang run without the device's
  `clang.cfg` (`--no-default-config`), package archives installed per ABI, and
  build folders per ABI.

## Later

lldb debugger integration, project search/replace, more templates
(bgfx, GLFW-style wrappers), Meson, git branches and history view.
