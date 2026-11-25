# NativeForge architecture

## Goals

1. Compile and run C/C++ entirely on an Android device, with no server.
2. Run both console programs (in a real terminal) and graphical programs
   (OpenGL ES, raylib, SFML, SDL3).
3. Download libraries only when a project needs them.
4. Export a program as a standalone APK (name, package id, version, orientation).
5. Keep every subsystem behind a small interface so features can be added
   without touching unrelated code.

## Module graph

```
app ──► every feature/* and core/* module; embeds runtime-template's APK
runtime-template ──► feature/runner, feature/terminal

feature/editor   ──► core/ui, core/lsp
feature/terminal ──► core/ui
feature/runner   ──► core/project          (+ NDK shim in src/main/cpp)
feature/assets   ──► core/ui, core/assets

core/codeintel ──► core/lsp, core/build
core/build     ──► core/toolchain, core/project, core/common
core/export    ──► core/toolchain, core/project, core/common
core/packages  ──► core/toolchain, core/common
core/toolchain ──► core/project ──► core/common
core/vcs (JGit) ──► core/common
core/remote    ──► core/codeintel, core/build, core/project (+ Bouncy Castle for its TLS key)
core/lsp, core/assets: no project dependencies
```

* `core/*` modules except `core/ui` are **pure Kotlin/JVM**. They have no Android
  dependency and are covered by JUnit tests that run on any machine.
* `feature/*` modules are Android libraries with UI or platform code.
* `app` is the only place that chooses implementations (`di/AppContainer.kt`).

## Key decisions

### Executing a toolchain on Android (targetSdk 28)

Since API 29, apps cannot `execve()` files from their writable data directory.
An on-device compiler therefore requires `targetSdkVersion 28`, the same choice
Termux makes. The IDE is distributed outside Google Play for that reason.
Exported apps are not affected: they target API 35 because they only load
libraries bundled in their own APK (see `NativeForgeConfig`).

### Projects outside the workspace

Workspace projects are folders of `files/workspace/` and are named by their
folder. A project can also live in any folder of shared storage or an SD card:
the same target SDK gives the classic storage model, so with
`READ/WRITE_EXTERNAL_STORAGE` the toolchain, clangd and JGit use ordinary
paths. The folder is chosen with `ACTION_OPEN_DOCUMENT_TREE`;
`DeviceFolders.pathOf` maps an `externalstorage` tree URI to its path, and
other providers (cloud drives) are copied into the workspace instead.
`FileSystemProjectRepository` keeps the linked paths in
`files/linked-projects.json`, and such projects are keyed by their canonical
path (`Project.key`), which the navigation routes carry. Shared storage is
mounted noexec, so `Project.buildDirectory` points to
`files/external-builds/<name>-<hash>/`, and `ProgramRunner` copies programs
that Make or Ninja wrote into the project folder to `files/run/staged/`
before running them. Removing such a project from the list deletes only its
build folder.

### Toolchain from Termux packages

clang, lld, cmake, ninja, make and pkgconf are installed from the Termux APT
repository into `files/usr`, mirroring Termux's own layout (`files/usr` is the
prefix, `files` is the sysroot). Termux binaries hard-code
`/data/data/com.termux/files/usr`, which we handle without patching ELF files:

* clang receives `--sysroot=<files>` and `--target=<triple>24` explicitly;
* `LD_LIBRARY_PATH` and `PATH` point at our prefix (`ClangToolchain.environment()`);
* text files (scripts, `.pc`, CMake configs) are rewritten by `PrefixRelocator`;
* the one path binaries must not keep, Termux's `bin/sh` (ninja, make and cmake
  run commands through it), is rewritten in place by `TermuxShellPatch` to a
  same-length spelling of `/system/bin/sh` (`ClangToolchain.prepare()`).

### Android-targeted libraries from a separate index

Termux builds SDL, SFML and friends for X11, which cannot draw on an Android
Surface. Graphical libraries are therefore built with the NDK and described by a
JSON index (`JsonIndexSource`, format in `packages/README.md`). They install into
`files/android-libs/<name>` and contribute include/lib flags and CMake prefixes
through `NativeDependency`.

### Terminal

`ProgramRunner.openShell` asks `core/toolchain`'s `ShellSetup` what to start:
Termux's bash with a generated `--rcfile` (sourcing the relocated
`$PREFIX/etc/profile` and `~/.bashrc`), or `/system/bin/sh` until bash is
installed. `ShellSetup.prepare()` also writes `$PREFIX/bin/pkg` (and `apt`), a
script that runs `app_process` with the app's APK (`$NATIVEFORGE_APK`) and
`io.nativeforge.app.cli.PkgMain`: the app's package manager without a Context,
with the command logic in `core/packages`' `PackageCli`. Termux's compiled-in
paths are redirected through the environment (`TERMINFO`, `INPUTRC`,
`SSL_CERT_FILE`, `GIT_EXEC_PATH`, in `ClangToolchain.environment()`).

### Runtime hosts

| Runtime | Program artifact | Host |
| --- | --- | --- |
| `CONSOLE` | executable | `ConsoleActivity` (Termux terminal emulator, PTY) |
| `NATIVE_ACTIVITY` | `libmain.so` exporting `ANativeActivity_onCreate` | `NativeProgramActivity` + `libnativeforge_shim.so` |
| `SDL3` | shared object exporting `SDL_main` | `SdlProgramActivity` (SDLActivity with the SDL 3.4.20 Java glue) |
| `SDL2` | shared object exporting `SDL_main` | `Sdl2ProgramActivity` (SDL2's glue, moved to `org.libsdl.sdl2`) |

`NativeActivity` can only load libraries packaged in the APK, so it loads a tiny
shim that `dlopen()`s the user's program (path passed through an environment
variable) and forwards `ANativeActivity_onCreate`. Programs run in the `:runner`
process, which is killed between runs so native globals start fresh and a crash
never takes the IDE down.

### Exporting APKs on the device

There is no aapt2 or Gradle on the phone, so export works on binary files:

1. `runtime-template` is built once, at IDE build time, and embedded in the IDE
   as `assets/runtime/template.apk`. It is shrunk with R8 and carries no Compose
   (the console is plain views) and no IDE resources (project templates are
   excluded in its `packaging` block); CI fails if its dex grows past 4 MB.
2. `ManifestPatcher` edits the template's compiled `AndroidManifest.xml` (AXML):
   package id, label, version, orientation, and every string prefixed with the
   template package (permissions, provider authorities). It also inserts the
   `<uses-permission>` elements picked on the export screen
   (`ExportSettings.permissions`); the template declares none, and its
   `LauncherActivity` asks for the dangerous ones before starting the program.
3. `ApkExporter` drops the template's signature and other ABIs, adds the
   program as `lib/<abi>/libnf_program.so` with the runtime libraries it loads
   (`RuntimeLibraries`: a library is kept when its file name or soname appears in
   the program or in a kept library, which covers DT_NEEDED and `dlopen("libx.so")`),
   the project's `assets/` folder under `assets/assets/`, and writes
   `assets/nativeforge/launch.json` (a `LaunchSpec`). On start, the exported
   app's `LauncherActivity` copies `assets/assets/` to `<files dir>/assets`
   (`BundledAssets`, once per install or update) and starts the program in the
   files dir, so `fopen("assets/x")` works as in the IDE.
4. `ApksigSigningEngine` signs with v1+v2+v3 using a per-install key from
   `SigningKeyStore`, or a keystore the user created or imported
   (`app/.../signing/SigningKeyRepository`, files read by `Keystores`, JKS by
   `JksKeystore`), so exported apps can be updated in place.

App bundles (AAB) follow the same steps on the bundle layout. At IDE build time
`copyRuntimeTemplate` stores the template's bundle without `base/dex/` and
`base/lib/` as `assets/runtime/bundle-skeleton.zip` (those files are identical
in the APK, so they are taken from there). `ProtoManifestPatcher` edits the
protocol buffer manifest through the small wire-format codec in
`bundle/Protobuf.kt`, `base/native.pb` is trimmed to the included ABIs, and the
bundle gets a JAR signature only, with `META-INF/MANIFEST.MF` first.

### Exporting to the web (WebAssembly)

Console programs can also be exported as a web page. Emscripten does not fit on
a phone (its own LLVM, Node.js and Python), so the export uses the clang and
wasm-ld that the toolchain already has, with the `wasm32-wasip1` target and the
WASI sysroot of Termux's `wasi-libc` package (C, C++ and compiler-rt builtins,
downloaded once after a consent dialog):

1. `WasiToolchain` (`core/toolchain`) wraps the device toolchain. Its flags start
   with `--no-default-config`, so clang ignores the `clang.cfg` written for
   Android (target, `-L<prefix>/lib`, which holds Android's `libc.a`, and
   `-rpath`). It links wasi-libc's signal, clock, mmap and getpid emulations and
   a 1 MiB stack, and links the wasm builtins into the resource directory of the
   installed clang when `wasi-libc` was built for another clang version.
   Packages contribute only header folders outside the Termux prefix
   (`webDependencies`), so header-only libraries work.
2. The export screen builds with the built-in build system into
   `build/web/<type>/`, so web objects never replace Android ones.
3. `WebExporter` (`core/export`) writes `exports/web/<name>/` and
   `exports/<name>-web.zip` with a self-contained `index.html`, the `.wasm` and
   a README. The page inlines the module and the project's `assets/` (base64),
   the WASI preview 1 host (`resources/.../web/wasi.js`: args, environment,
   clocks, random, an in-memory file system with the assets preopened at `/`,
   stdin) and a terminal (`page.js`: ANSI colours, `\r`, clear screen). Its
   Content-Security-Policy allows only that script and style by SHA-256 plus
   `'wasm-unsafe-eval'`, with no network access.
4. Programs run on the page's thread. With JavaScript Promise Integration
   (Chromium 137+, Android WebView) `fd_read`, `fd_write` and `poll_oneoff` are
   `WebAssembly.Suspending` imports: input is typed in the terminal, output
   appears while the program runs, `sleep()` does not spin and Stop works.
   Other browsers ask for input with `prompt()`. The export screen previews the
   page in a WebView loaded as data, without file, content or network access.

Graphical runtimes (SDL, raylib, OpenGL ES) are not exported to the web.

### Independent build systems

`BuildSystemRegistry` holds four `BuildSystem`s, chosen per project by
`ProjectConfig.buildSystem`. None depends on another:

| Kind | Command | Notes |
| --- | --- | --- |
| `SINGLE` | clang per file, then link | incremental, sources from `sourceDir` |
| `CMAKE` | `cmake -G Ninja` or `-G "Unix Makefiles"`, then `cmake --build` | packages are added through `CMAKE_<LANG>_STANDARD_LIBRARIES`, so any CMakeLists links them |
| `MAKE` | `make -C <root> -jN SHELL=/system/bin/sh` | `CC`, `CXX`, `CFLAGS`, `CXXFLAGS`, `LDFLAGS`, `LDLIBS` in the environment |
| `NINJA` | `ninja -C <root>` | `clang.cfg`/`clang++.cfg` next to the compilers carry the target and sysroot |

External builds don't say where the program is, so `ArtifactLocator` picks the
newest ELF of the right kind (read from its header, not its name) written since
the build started, then the last program the compiler shims linked, unless
`artifactPath` is set.

Makefiles written for desktop systems call `gcc`/`g++` by name and overwrite
CFLAGS/LDFLAGS. During Make and Ninja builds, `CompilerShims` puts scripts named
`gcc`, `g++`, `cc`, `c++`, `clang` and `clang++` first on PATH (in
`usr/libexec/nativeforge/bin`). They add the Android target, the project's
packages (as `-I`/`-L` ahead of vendored copies), drop desktop-only libraries,
and link a shared library with the NativeActivity entry point for graphical
runtimes. Commands that already carry NativeForge's flags (`-DNATIVEFORGE=1`,
also present in LDFLAGS) are left alone apart from that clean-up.
`ProjectDetector` fills `nativeforge.json` for cloned or imported folders.

### Code intelligence (clangd)

`core/lsp` is a small JSON-RPC/LSP client with no Android dependency.
`core/codeintel` starts clangd from the installed toolchain, writes the compile
database from the same `BuildRequest` the build uses (`CompileDatabase`), and
puts the Android target and sysroot in clangd's user config, so the database
stays identical to the real compile. The app's `CodeIntelService` keeps open
documents in sync (debounced full-text changes), exposes diagnostics as a
`StateFlow`, and implements the editor's `EditorIntelligence` interface for
completion. clang-format runs as a separate process with
`--output-replacements-xml`; its byte offsets are converted to UTF-16.

### Crash reports

An exception that escapes any thread ends an Android app, so threads around process
pipes (which throw "Stream closed" or "Broken pipe" when a process is stopped) are
started with `core/common`'s `BackgroundThreads`, never as plain threads. Crashes that
still happen are kept on the device: `CrashReportStore` (`core/common`, plain text files
in `files/crashes/`) is filled by the app's `CrashReporter` from an uncaught exception
handler, from `feature/runner`'s native signal handler (`nf_crash_handler.c`, IDE
process only; it chains to debuggerd so tombstones still happen) and, on Android 11+,
from `ApplicationExitInfo` (tombstones decoded by `TombstoneDecoder`). The next start
shows new reports; Settings lists them. Nothing is uploaded.

### Asset viewer

`core/assets` holds pure-JVM parsers (OBJ, STL, PLY, glTF/GLB, TGA) so they can
be unit tested; `feature/assets` renders them (Compose for images, audio and
fonts, a GLES2 `GLSurfaceView` for models). Opening a file whose extension maps
to an `AssetKind` shows the viewer instead of the editor.

### Remote access (browser IDE)

The phone can serve the IDE to a browser on a computer (Home › cast icon). All
work still happens on the phone; the browser is the screen and keyboard.

* `core/remote` is pure JVM: a small HTTP/1.1 + WebSocket server on blocking
  sockets (`http/`), pairing and session tokens (`security/AccessControl`), the
  TLS key (`security/TlsIdentity`), peer/host/origin rules
  (`security/NetworkPolicy`), project-confined file access (`ProjectFiles`),
  terminal sessions that survive reconnects (`TerminalHub`), clangd for the
  browser (`CodeIntelHub`) and the routes (`RemoteServer`). The app plugs in
  through `RemoteBackend` (`app/.../remote/AndroidRemoteBackend`): the same
  project repository, package manager, build service, settings and theme as
  the screens.
* The web UI lives in `core/remote/src/main/resources/.../web/` as plain ES
  modules with no build step. CodeMirror 6 and xterm.js are copied unchanged
  from npm by `core/remote/web/vendor.sh` (checked against `vendor.sha256`)
  and loaded through an import map. `StaticAssets` serves them gzip'd under a
  content-hash prefix (`/a/<hash>/`, cached for a year); `web/MANIFEST` lists
  the files that make up the hash.
* The page applies the app's palette as CSS variables and turns the selected
  editor theme (the same TextMate JSON the app uses) into a CodeMirror theme;
  changing the theme in the app updates open browsers.
* Browser settings (`web/js/settings.js`, gear icon or Ctrl+,): interface size
  (CSS `zoom`), editor font, size, line height, ligatures, tab width, wrap,
  format/auto save and terminal font size. They start from the phone's values
  sent with the theme and only the changes are kept, in the browser's
  `localStorage` (`nf-prefs`); the phone and other browsers are not affected.
* Right-click menus (`menu()` in `web/js/util.js`) in the editor, tabs,
  explorer, terminals, output and problems; Shift+right-click keeps the
  browser's own menu. Duplicating uses the `copy` file operation and "Open in
  terminal" passes `dir` to `/api/terminals`, both confined by `ProjectFiles`.
* `RemoteAccessService` is a foreground service with a partial wake lock and
  a high-performance Wi-Fi lock, so builds and terminals keep running with the
  screen off; it stops after the user's idle time. Terminals use Termux's PTY
  JNI through `com.termux.terminal.PtyBridge` (raw bytes for xterm.js).
* `RemoteScreenActivity` (on by default, "Black screen while on") shows pure
  black full screen while remote access runs: OLED pixels stay off, but the
  screen stays on and unlocked with NativeForge in front, which Android
  requires before the browser may start graphical programs (background
  activity starts are blocked). A tap shows the address and buttons for 10
  seconds. The runner's placeholder on the phone behaves the same way while a
  streamed program runs (`RemoteRun.blackScreen`).
* Graphical programs started from the browser are streamed to it by default
  (the run button's menu can send them to the phone's screen instead):
  * `AndroidProgramStream` (app) creates a private `VirtualDisplay` whose
    surface is the input of the hardware H.264 encoder, and a local socket
    for input. Android only lets an app launch activities on displays it may
    embed into, which its own virtual display is not, so the host activity
    still opens on the phone (showing a note) and the program draws into a
    `Presentation` on the virtual display (`feature/runner/.../RemoteHost`).
    SDL's surface view moves into the presentation; NativeActivity gets the
    presentation window's surface and input queue instead of its own. The
    presentation is made from the application context, so it does not stop
    with the activity, and the hosts keep the program running without focus.
  * `RemoteInputBridge` (in the `:runner` process) reads the browser's keys,
    mouse and touches from that socket (only this app's uid may connect) and
    injects them with `Window.injectInputEvent` into the presentation's
    window, so programs see them like a USB keyboard and mouse.
  * The display follows the size of the browser's panel (`resize` messages;
    `AndroidProgramStream.resize` starts an encoder of the new size and points
    the display at it; the presentation ignores the cancel Android sends when
    its display changes). When the program asks for a window size it keeps
    that size instead and the browser letterboxes it: SDL reports
    SDL_CreateWindow's size and resizable flag through `setOrientationBis`,
    and for raylib, whose InitWindow size Android ignores, the native shim
    redirects the program's PLT slot for `InitWindow` (`plt_hook.c`) and
    waits in `NativeProgramActivity.onProgramWindow` until the surface has
    the new size, before raylib sets up its framebuffer.
  * Mouse hovering is bracketed with HOVER_ENTER/HOVER_EXIT as on a real
    device: raylib treats a pointer that never entered hovering as a finger.
  * `ScreenHub` (`core/remote/screen`) fans the encoded frames out over
    `/ws/screen` with a queue per browser (a slow browser drops back to the
    next key frame instead of stalling the encoder) and turns browser input
    into Android key codes and pixels (`KeyMap`, `StreamSize`). The page
    decodes with WebCodecs (`web/js/screen.js`). Pointer and wheel input come
    as small binary messages (13 and 17 bytes), keys and control as JSON.
  * Quality adapts (`AdaptiveQuality`, `StreamQuality`): a browser whose queue
    overflows or stays long, or whose decoder drops frames (`lag`), steps the
    stream down (less bit rate, then 30, 20 or 15 fps); short queues for 8 s
    step it back up. Bit rate changes on the running encoder; a new frame rate
    cap (`max-fps-to-encoder`) starts a new one. The app's `ThermalMonitor`
    caps it on a hot phone (30 fps from MODERATE, 20 from SEVERE), and with no
    browser watching for 5 s the encoder idles at 10 fps. The stream prefers a
    hardware encoder that takes a surface (`MediaCodecList`) and holds a
    low-latency Wi-Fi lock only while the program runs.
  * The same `ThermalMonitor` lowers the CPU priority builds start at
    (`JvmProcessRunner`'s `priority`: the starting thread's nice value, which
    make, ninja and clang inherit).

See `docs/SECURITY.md` for the security model.

### Git

`core/vcs` wraps JGit 5.13 (Java 8 APIs, which Android supports) behind
`GitClient`. JGit is configured to ignore system/user config, never run hooks
and never create symlinks; clones are HTTPS-only without submodules. See
[SECURITY.md](SECURITY.md).

## Extension points

| To add… | Implement | Register in |
| --- | --- | --- |
| A build system (Meson, xmake…) | `BuildSystem` + a `BuildSystemKind` value | `BuildSystemRegistry.default()` |
| A package repository | `PackageSource` | `AppContainer.packageSources` |
| A project template | `ProjectTemplate` (or a `SimpleTemplate`) | `ProjectTemplates.all` |
| A runtime (e.g. GLFW-style host) | host `Activity` + `RuntimeKind` value | `ProgramLauncher.hostFor()` |
| A diagnostic format | `DiagnosticParser` | `BuildExecutor` constructor |
| An editor language/theme | TextMate grammar/theme JSON | `assets/textmate/languages.json`, `EditorTheme` |
| A screen | Composable + ViewModel | `Routes` / `NativeForgeNavHost` |
| A remote (browser) API | route in `RemoteServer.authenticated` + UI in `web/js/` | `RemoteBackend` when it needs the app |

## Conventions

* All code, comments and docs in English.
* Errors crossing module boundaries use `Outcome<T>` with a typed `NfError`.
* Long-running work exposes cold `Flow`s of events (`BuildEvent`, `InstallEvent`).
* Blocking I/O runs on injected dispatchers (`DispatcherProvider`).
* Gradle configuration lives in `build-logic` convention plugins; module build
  files only declare their namespace and dependencies.
* Every pure module ships unit tests next to its sources.
