# Publishing on F-Droid

This page lists what is ready, what still blocks inclusion in the main F-Droid
repository, and how to submit. Policy references are from the
[Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/) and
[Anti-Features](https://f-droid.org/docs/Anti-Features/) pages.

## Ready

| Item | Where |
| --- | --- |
| Free license, GPL-3.0-or-later | `LICENSE` |
| Store listing in English and Brazilian Portuguese (title, short and full description, changelog, icon) | `fastlane/metadata/android/` |
| Draft build recipe for fdroiddata | [`docs/fdroid/io.github.deuzivanviana.nativeforge.yml`](fdroid/io.github.deuzivanviana.nativeforge.yml) |
| Only free dependencies: AndroidX, Compose, Kotlin, Sora Editor, JGit, apksig, Bouncy Castle, Commons Compress (Maven Central / Google) and the Termux terminal from JitPack, which F-Droid allows | `gradle/libs.versions.toml` |
| No ads, analytics, crash reporting or Google Play Services | |
| Opt-in toolchain download: the Install button explains that the programs come from the Termux repository and bypass F-Droid's checks, and nothing is downloaded until the user agrees (policy item 5) | `app/.../home/HomeScreen.kt` (`ToolchainConsentDialog`) |
| The runtime template APK used by Export is built from source in the same Gradle build | `runtime-template/` |
| The native shim is built from source with the NDK (`externalNativeBuild`) | `feature/runner/` |

## Before submitting

1. **Release tag.** Tag the release (`v0.7.2` for the current recipe). The
   recipe builds that tag; `release.yml` publishes the same tag on GitHub.
2. **Prebuilt libraries in the source tree.** `app/src/main/assets/packages/`
   holds about 20 MB of `.tar.xz` archives (SDL2/SDL3, raylib, SFML, ImGui...).
   F-Droid's scanner rejects binaries, so the recipe deletes them (`rm`, which
   runs before `prebuild`) and rebuilds them in `prebuild` with
   `packages/build-android-libs.sh`, then deletes the script's work directory
   (`build/android-libs`) so the scanner only sees the new archives. The
   script only uses pinned upstream tarballs
   (Ubuntu archive), single files from upstream git tags and the Box3D and
   LVGL git tags (each checked against its commit), all checked against
   `packages/sources.sha256`. The LVGL clone needs `git`, which the recipe
   already installs. `libs-from-source.yml`
   runs the same rebuild on GitHub. Reviewers may still ask to turn the
   sources into `srclibs` or git submodules.
3. **Termux terminal from JitPack.** `terminal-view` and `terminal-emulator`
   (Apache-2.0) come from JitPack, which builds them from the Termux git tag.
   F-Droid accepts JitPack, but a reviewer may ask to build them from source
   instead (as `srclibs` or as modules copied into this repository).
4. **Executables downloaded at run time.** The compiler comes from the Termux
   package repository, and users can add their own package indexes. Nothing
   is downloaded until the user agrees in a dialog that says these programs
   bypass F-Droid's checks (Inclusion Policy, item 5). Mention it in the merge
   request; the packages are free software, so no anti-feature applies.
5. **targetSdk 28.** The IDE targets API 28 so that it can run the programs it
   downloads and builds (Android 10+ forbids exec from app data for newer
   targets). F-Droid accepts this (Termux does the same), but say why in the
   merge request.
   The same target gives the classic storage model: with the "Files and
   media" permission (`READ/WRITE_EXTERNAL_STORAGE`, asked only when the user
   opens or creates a project in a folder outside the app) the compiler,
   CMake, Make and git work on projects in shared storage and on SD cards
   through ordinary paths. All-files access (`MANAGE_EXTERNAL_STORAGE`) is
   not used.
6. **Signing.** F-Droid signs its build with its own key, so an F-Droid install
   cannot update a GitHub or debug install, and the other way round. Users
   have to uninstall first. Reproducible builds would let F-Droid ship the
   developer's signature instead; `libs-from-source.yml` reports how many
   rebuilt archives are byte-for-byte identical to the committed ones.
7. **Screenshots.** Eight phone screenshots are in
   `fastlane/metadata/android/en-US/images/phoneScreenshots/` (F-Droid uses
   them for every language without its own). See `fastlane/SCREENSHOTS.md`.

## Anti-features

None expected: no ads, tracking, non-free dependencies, non-free assets or
non-free network services. The run-time downloads (item 4) are free software
and opt-in.

## Submitting

1. Fork <https://gitlab.com/fdroid/fdroiddata> and copy the recipe to
   `metadata/io.github.deuzivanviana.nativeforge.yml`.
2. Check and build it locally:
   ```bash
   fdroid readmeta
   fdroid rewritemeta io.github.deuzivanviana.nativeforge
   fdroid lint io.github.deuzivanviana.nativeforge
   fdroid build -v -l io.github.deuzivanviana.nativeforge
   ```
3. Open a merge request against fdroiddata using the "App inclusion" template
   and explain items 2 to 5 above.

After inclusion, F-Droid picks up new versions from git tags
(`UpdateCheckMode: Tags`), so each release needs a tag, a `versionCode` bump
and a `fastlane/metadata/android/*/changelogs/<versionCode>.txt`.
