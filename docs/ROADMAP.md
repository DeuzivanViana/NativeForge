# Roadmap

## Unreleased — faster builds, a lighter IDE

* "Voxel sandbox" example (raylib + Box3D): a first-person block world made from noise,
  with lakes, beaches, trees and snowy peaks. An on-screen pad walks, dragging looks, and
  one-block steps are climbed by themselves; the hotbar throws balls, crates, barrels,
  beach balls, planks and bombs that roll, float in water and explode with Box3D. Drawing
  is one mesh per 16 x 16 chunk with only the faces next to air (with ambient occlusion and
  distance fog); the terrain collides as a single Box3D triangle mesh. Keyboard and mouse
  work too (remote access). Screenshot in `docs/screenshots/voxel-sandbox.png`.
* CI: the NativeActivity examples (raylib, 2D/3D physics, voxel sandbox, paint, particles)
  are compiled and linked with the NDK against the bundled libraries, for arm64 and
  x86_64 (`.github/scripts/compile-examples.py`), so an example that stops building fails
  the pull request.
* Fixed: tapping Install for the toolchain, or building a new project with libraries,
  could look as if nothing happened. Every install waits for the one before it, and the
  update of bundled libraries that runs at start-up (LVGL changed in this version) first
  downloaded Termux's package list, even though bundled libraries need nothing from the
  network. Bundled libraries are now planned from the APK's index alone; the toolchain
  banner and the build log say when an install waits for another one or reads the package
  lists; the Termux source falls back to Termux's primary server (packages.termux.dev)
  when the Cloudflare address does not answer or serves a broken index.
* CI: the release APK is checked for every bundled archive and its SHA-256, and an
  emulator job installs bundled libraries and the Termux toolchain with the APK's own
  `pkg` code (after R8), then builds and runs a C program with that clang.
* Fixed: the app could close a few seconds after leaving a project (for example the
  Box3D + Raylib example). Closing the project stops clangd; when clangd took more than a
  second to exit (still building a big file) it was killed, which closes its stderr pipe
  under the thread reading it, and that thread's "Stream closed" exception ended the app.
  Threads reading or writing process pipes (clangd, clang-format) and the file watcher
  now end quietly (`BackgroundThreads`).
* Crash reports kept on the phone: Kotlin/Java exceptions (uncaught exception handler),
  native crashes (a small signal handler, `libnativeforge_crash.so`, that writes the
  signal and backtrace and then passes the signal on to Android) and, on Android 11+,
  Android's own exit records (native crash tombstones on 12+, "not responding" traces,
  kills for low memory while on screen). After a crash the next start shows the report
  with Share and Copy; all reports are in Settings › Crash reports. Nothing is sent.
* CMake: NativeForge's own variables (compilers, flags, `NATIVEFORGE_*`) go in an
  initial-cache script (`-C build/<type>/nativeforge-init.cmake`) instead of `-D` options,
  so CMake no longer warns about the ones a project ignores, without the deprecated
  `--no-warn-unused-cli` (CMake 4.2 prints a warning for it; `-Wno-unused-cli` is ignored
  by CMake 3.x). The project's own CMake options stay `-D` and win. New CMake templates
  use `cmake_minimum_required(VERSION 3.22...4.2)`.
* Syntax highlighting for the other files of a C/C++ project, in the app's editor and in
  the browser IDE: JSON, YAML (also `.clang-format`, `.clangd`, `.clang-tidy`), TOML,
  INI/`.editorconfig`, Makefiles, shell, Python, Markdown, XML, GLSL shaders and diffs
  (CMake already had it). Toggle comment uses each language's comment (`#`, `//`).

* Built-in build: ccache in front of clang when the `ccache` package is installed (now
  part of the toolchain install; `pkg install ccache` for existing installs), also for
  CMake (`CMAKE_<LANG>_COMPILER_LAUNCHER`) and Makefiles (the gcc/cc wrappers). Build
  settings › Compiler cache turns it off per project.
* Precompiled header for the built-in build (Build settings › Precompiled header, or
  `precompiledHeader` in nativeforge.json): compiled once per language and loaded with
  `-include-pch`; clangd gets the same header with `-include`.
* Debug builds keep DWARF in `.dwo` files (`-gsplit-dwarf`), so links read less; links use
  lld explicitly (`-fuse-ld=lld`). Release builds (and so release exports) add
  `-ffunction-sections -fdata-sections`, link with `--gc-sections` and strip the symbol
  table (`-s`); the built-in build also uses ThinLTO with a cache. `-fvisibility=hidden`
  is not used: it would hide entry points hosts look up by name.
* Parallel jobs follow the free memory as well as the cores (512 MB per job).
* clangd: threads and the background index sized by cores and free memory, background
  index at low priority, and clangd stops 30 s after the app leaves the screen (it starts
  again on return). Editor changes reach clangd without copying the file on every key.
* Auto save writes 1.5 s after the last key (without formatting); every save is atomic
  (temporary file and rename). The explorer reads folders only when they open and
  refreshes on its own when files appear or disappear (inotify); "Go to file" still
  lists the whole project.
* Image previews stay in a memory-bounded cache that Android can empty
  (`onTrimMemory`). The terminal's start-up files are written in the background when the
  app starts, and `etc` is scanned for old Termux paths only once.
* Release APK shrunk with R8 (IDE classes and reflective libraries kept whole,
  no obfuscation): about 48 MB → 37 MB. A Baseline Profile for start-up, the workspace
  and the editor is installed by androidx.profileinstaller, also outside Google Play.
  Graphical programs start faster: their `:runner` process no longer builds the IDE's
  services.
* Compose treats `java.io.File` and read-only collections as stable
  (`compose-stability.conf`).
* Benchmarks workflow (on main and on demand): build planning time for a 600-file
  project, APK and dex size, and cold start on an emulator.

## Unreleased — remote access: the IDE in a computer's browser

* Home › cast icon › Start: the phone serves the IDE over HTTPS to a browser
  on the same network (`core/remote`). Projects (create, open, delete),
  explorer (new, rename, move by drag and drop, delete, upload, download),
  editor tabs with CodeMirror 6 and clangd (completion, live errors, hover,
  F12/Ctrl+click definition, Shift+Alt+F format), quick open (Ctrl+P), build
  and run with output and problems, the built program as a download, and
  terminals on the app's bash that survive reconnects.
* Same look as the app: palette and editor theme come from the app's
  settings and follow changes live.
* Keeps working with the screen off (foreground service, wake and Wi-Fi
  locks, battery optimization exemption on request); stops after an idle
  time (30 minutes by default) or from the notification.
* Security: local networks and VPNs only, TLS with a device certificate whose
  fingerprint the app shows, one-time pairing codes, hashed session tokens,
  strict CSP, origin and host checks, project-confined file access
  (docs/SECURITY.md).
* Graphical programs (raylib, SFML, OpenGL ES, Vulkan, SDL2, SDL3, Dear
  ImGui, LVGL) show in the browser: the phone draws them on an off-screen
  display, encodes it as H.264 in hardware and the page decodes it with
  WebCodecs. Keyboard, mouse (buttons, wheel), touch and multi-touch go back
  to the program; a toggle sends the mouse as touch; full screen captures
  keys like Esc. The run button's menu still offers the phone's screen.
* Black screen while remote access is on (default on): pure black full
  screen for OLED phones that keeps the phone awake and unlocked, so the
  browser can start graphical programs; a tap shows the address, pairing code
  and buttons for 10 seconds.
* The program's picture follows the browser panel's size (also in full
  screen); a window size set in the code (raylib's InitWindow,
  SDL_CreateWindow without SDL_WINDOW_RESIZABLE) is kept and letterboxed.
  Fixed: moving the mouse over a raylib program without a button pressed
  counted as a touch.
* Drop files and whole folders from the computer on a folder in the
  explorer to upload them there (asks before replacing files).
* Fixed: every run of a graphical program (on the phone or in the browser)
  left its own entry in the phone's recent apps. Runs now share one task that
  leaves the recent apps when the program ends; streamed runs never show
  there, and entries left by earlier versions are removed on the next run.
* Streaming adapts to the network and the phone: a browser that falls behind
  (long queue, or its decoder drops frames) lowers the bit rate and then the
  frame rate (30, 20, 15 fps), which come back up when the network recovers;
  a hot phone caps it at 30 or 20 fps (the browser says so); with no browser
  watching the encoder idles at 10 fps. A hardware encoder is picked
  explicitly, Wi-Fi stays in low-latency mode only while a program streams,
  and pointer and wheel input travel as small binary messages.
* Builds start at a lower CPU priority while the phone is hot, so the IDE
  stays responsive and the phone throttles less. Static web files are
  compressed once at the best gzip level.
* Later: the program's sound in the browser, packages, export and git screens
  in the browser.
* New bundled library: SQLite 3.54.1 (`sqlite3`, static, with FTS5, R*Tree,
  JSON and math functions), linked automatically with the built-in build,
  CMake (`find_package(SQLite3)` also works) and Makefiles. New example: a
  to-do list in the terminal stored in `todo.db`, in C and C++.

## 0.11.1 — terminal start-up fix

* Package installs relocate Termux's whole files directory
  (`/data/data/com.termux/files` → the app's), not only its prefix: scripts
  that name Termux's home, such as termux-tools'
  `profile.d/init-termux-properties.sh`, printed `mkdir: cannot create
  directory '/data/data/com.termux'` every time the terminal opened.
* `ShellSetup.prepare()` rewrites the start-up scripts under `$PREFIX/etc`
  that were installed by 0.11.0.
* Exported apps are unchanged: they carry only the terminal view and the
  program, never bash or Termux packages.

## 0.11.0 — a full terminal: bash and `pkg`

* The terminal starts Termux's bash when it is installed, with a generated
  `--rcfile` that sources the relocated `$PREFIX/etc/profile` (and so
  `bash.bashrc`, `profile.d`) and `~/.bashrc`: history, completion, the
  coloured prompt and command-not-found suggestions work as in Termux.
* The toolchain install also brings bash, coreutils, grep, sed, findutils,
  gawk, diffutils, less, nano, ncurses-utils, procps, tar, gzip, xz, bzip2,
  zip/unzip, curl, ca-certificates and which (`ShellSetup.ESSENTIAL_PACKAGES`).
  Without bash, Android's sh prints a hint to run `pkg setup`.
* `pkg` in the terminal (`install`, `reinstall`, `uninstall`, `update`,
  `upgrade`, `search`, `show`, `list-installed`, `list-all`, `setup`; `apt`
  is an alias): a front end to the app's package manager, run with
  `app_process` from the app's APK (`PackageCli`, `app/cli/PkgMain`), so the
  same SHA-256-checked installs, relocation and toolchain patches apply. It
  replaces termux-tools' `pkg`, which needs apt and dpkg.
* Termux libraries find their files: `TERMINFO` (nano, less, clear, htop),
  `INPUTRC` (readline keys), `SSL_CERT_FILE`/`CURL_CA_BUNDLE` (curl, git,
  CMake downloads) and `GIT_EXEC_PATH` (git over HTTPS) point at the prefix.
* The terminal keeps Android's `ANDROID_*` and class path variables, which
  `app_process` (and so `pkg` and Termux's `am`) needs.
* The installed-packages file is read again when it changed on disk, so the
  Packages screen sees what `pkg` installed.

## 0.10.0 — examples that fit every screen, C++ everywhere, faster builds

* Examples are laid out from the screen size (a unit of 1/360 of the short
  side) instead of fixed pixels: raylib, the 2D and 3D physics toolbars,
  particles, the synthesizer, SDL2/SDL3 and SFML no longer overlap or run off
  small phones, tablets or landscape. LVGL texts wrap, and the widgets demo
  zooms out on narrow screens (`LV_NATIVEFORGE_SCALE_MIN_SIZE(n)`).
* C++ for every template: Vulkan, the LVGL widgets demo and to-do list, the
  synthesizer and the Lua console now come in C++ too. The bundled C
  libraries (SDL, raylib, raygui, LVGL, Lua through `lua.hpp`, Box3D,
  miniaudio, stb, Vulkan) all declare their API `extern "C"`.
* New examples: a snake game (SDL3 renderer, swipe controls, saved best
  score; one source for C and C++) and finger painting (raylib + raygui,
  colours, brush size, undo).
* The built-in build compiles source files on several cores (up to 4) and
  links when all are done; output of each file stays together in the log.
* Library downloads run three at a time while earlier ones are extracted, which
  speeds up the first toolchain install. The project list reads file counts
  and Git status for several projects at once.

## 0.9.1 — layout and security fixes

* Buttons in the home screen, dialogs and Export move to the next line when the
  screen is narrow, instead of breaking their labels letter by letter.
* Package repositories: an archive is installed only when its index gives a
  SHA-256, and only from HTTPS (or the APK, for the bundled index); entries
  without a checksum or over plain HTTP are ignored.
* Git pull and push check every address of the remote they use, as JGit will
  use it: the current branch's remote, push URLs and `url.*.insteadOf`
  rewrites. Projects opened from shared storage bring their own `.git/config`.
* GitHub Actions: the build workflows run with a read-only token.

## 0.9.0 — projects in any folder, LVGL, more examples

* Projects outside NativeForge's storage. "Open folder" on the home screen uses
  the system folder picker; a folder on the phone's storage or an SD card is
  opened and built where it is, through its path, so the compiler, CMake,
  Make, clangd and git work as with workspace projects. New projects can be
  created in such a folder too. This needs the "Files and media" permission
  (`READ/WRITE_EXTERNAL_STORAGE`, legacy storage on target SDK 28; no
  all-files access), asked only when the user opens a folder. Folders from
  other providers (cloud drives) or without the permission can be imported as
  a copy. Linked folders are remembered in `files/linked-projects.json`;
  removing one from the list keeps its files.
* Shared storage is mounted noexec, so external projects build into
  `files/external-builds/<name>-<hash>/`, and programs that Make or Ninja wrote
  into the project folder are copied to `files/run/staged/` before they run.
* LVGL 9.6.0 (MIT) as a static library on the SDL2 driver, built from the
  pinned git tag (commit checked) with the widgets demo, PNG/JPEG/BMP/GIF
  decoders, Tiny TTF, QR codes and barcodes. `lv_nativeforge.h` sets up a
  full screen display scaled to the screen density, touch, keyboard and the
  app's data folder. Qt was considered and left out: its Android port needs
  Java glue, moc and rcc on the device and a very large build.
* Templates: LVGL app (C and C++), and examples for the LVGL widgets demo,
  a to-do list (tabs, chart, saved tasks), a calculator, an OpenGL ES 3 cube
  with GLM on SDL3, a touch synthesizer with SDL3 audio, raylib particles with
  EnTT, a Lua console embedded in C and a JSON address book with
  nlohmann/json. Template sources live as resource files.

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
