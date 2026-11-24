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
                    app ──────────────┬──────────────┬─────────────┐
                     │                │              │             │
   feature/editor  feature/terminal  feature/runner  core/ui   runtime-template
                                          │                     (runner + terminal)
                                          ▼
 core/build ──► core/toolchain ──► core/project ──► core/common
 core/packages ─┘                       ▲
 core/export ───────────────────────────┘
 core/vcs (JGit) ──► core/common
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

### Runtime hosts

| Runtime | Program artifact | Host |
| --- | --- | --- |
| `CONSOLE` | executable | `ConsoleActivity` (Termux terminal emulator, PTY) |
| `NATIVE_ACTIVITY` | `libmain.so` exporting `ANativeActivity_onCreate` | `NativeProgramActivity` + `libnativeforge_shim.so` |
| `SDL3` | shared object exporting `SDL_main` | `SdlProgramActivity` (SDLActivity with the SDL 3.4.20 Java glue) |

`NativeActivity` can only load libraries packaged in the APK, so it loads a tiny
shim that `dlopen()`s the user's program (path passed through an environment
variable) and forwards `ANativeActivity_onCreate`. Programs run in the `:runner`
process, which is killed between runs so native globals start fresh and a crash
never takes the IDE down.

### Exporting APKs on the device

There is no aapt2 or Gradle on the phone, so export works on binary files:

1. `runtime-template` is built once, at IDE build time, and embedded in the IDE
   as `assets/runtime/template.apk`.
2. `ManifestPatcher` edits the template's compiled `AndroidManifest.xml` (AXML):
   package id, label, version, orientation, and every string prefixed with the
   template package (permissions, provider authorities).
3. `ApkExporter` drops the template's signature and other ABIs, adds the
   program as `lib/<abi>/libnf_program.so` with its runtime libraries, and
   writes `assets/nativeforge/launch.json` (a `LaunchSpec`).
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

### Asset viewer

`core/assets` holds pure-JVM parsers (OBJ, STL, PLY, glTF/GLB, TGA) so they can
be unit tested; `feature/assets` renders them (Compose for images, audio and
fonts, a GLES2 `GLSurfaceView` for models). Opening a file whose extension maps
to an `AssetKind` shows the viewer instead of the editor.

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

## Conventions

* All code, comments and docs in English.
* Errors crossing module boundaries use `Outcome<T>` with a typed `NfError`.
* Long-running work exposes cold `Flow`s of events (`BuildEvent`, `InstallEvent`).
* Blocking I/O runs on injected dispatchers (`DispatcherProvider`).
* Gradle configuration lives in `build-logic` convention plugins; module build
  files only declare their namespace and dependencies.
* Every pure module ships unit tests next to its sources.
