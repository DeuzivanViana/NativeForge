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
| Draft build recipe for fdroiddata | [`docs/fdroid/io.nativeforge.ide.yml`](fdroid/io.nativeforge.ide.yml) |
| Only free dependencies: AndroidX, Compose, Kotlin, Sora Editor, JGit, apksig, Bouncy Castle, Commons Compress (Maven Central / Google) and the Termux terminal from JitPack, which F-Droid allows | `gradle/libs.versions.toml` |
| No ads, analytics, crash reporting or Google Play Services | |
| Opt-in toolchain download: the Install button explains that the programs come from the Termux repository and bypass F-Droid's checks, and nothing is downloaded until the user agrees (policy item 5) | `app/.../home/HomeScreen.kt` (`ToolchainConsentDialog`) |
| The runtime template APK used by Export is built from source in the same Gradle build | `runtime-template/` |
| The native shim is built from source with the NDK (`externalNativeBuild`) | `feature/runner/` |

## Blockers

1. **No public source repository.** F-Droid builds from a public git repository
   with a tag or commit per release. Create one (GitHub, GitLab or Codeberg),
   push the code, tag `v0.6.0`, and replace `OWNER` and the commit in the recipe.
2. **Prebuilt binaries in the source tree.** `app/src/main/assets/packages/`
   holds about 14 MB of `.tar.xz` archives with compiled libraries (SDL3,
   raylib, SFML...). F-Droid's scanner rejects them. The recipe deletes them
   (`scandelete`) and rebuilds them in `prebuild` with
   `packages/build-android-libs.sh`. Before submitting:
   * run that recipe locally with `fdroid build` to confirm the script works on
     the Debian build server (it needs CMake 3.22.1 from the SDK, `make`,
     `python3` and `xz`);
   * add a SHA-256 for every source tarball the script downloads (it does not
     check them yet), or move the sources to git submodules or `srclibs`, which
     reviewers prefer;
   * consider moving the archives out of git and generating them at build time
     for every build, so the repository itself holds no binaries.
3. **Executables downloaded at run time.** The compiler comes from the Termux
   package repository. This is allowed with explicit opt-in consent, which is
   in place. Expect reviewers to ask about it: mention it in the merge request
   and point to the consent dialog. No anti-feature fits exactly (the packages
   are free software), but reviewers may still choose to tag the app.
4. **targetSdk 28.** The IDE targets API 28 so that it can run the programs it
   downloads and builds (Android 10+ forbids exec from app data for newer
   targets). F-Droid accepts this (Termux does the same), but say why in the
   merge request.
5. **Signing.** F-Droid signs its build with its own key, so an F-Droid install
   cannot update a debug or self-signed install, and the other way round. Users
   have to uninstall first. Reproducible builds would let F-Droid ship the
   developer's signature instead; that needs the `.tar.xz` libraries to be
   bit-for-bit reproducible too, which is not checked yet.
6. **Screenshots.** `fastlane/metadata/android/*/images/phoneScreenshots/` is
   empty; see `fastlane/SCREENSHOTS.md` for the list to capture.

## Submitting

1. Fork <https://gitlab.com/fdroid/fdroiddata> and copy the recipe to
   `metadata/io.nativeforge.ide.yml`.
2. Check and build it locally:
   ```bash
   fdroid readmeta
   fdroid rewritemeta io.nativeforge.ide
   fdroid lint io.nativeforge.ide
   fdroid build -v -l io.nativeforge.ide
   ```
3. Open a merge request against fdroiddata using the "App inclusion" template
   and explain items 3 and 4 above.

After inclusion, F-Droid picks up new versions from git tags
(`UpdateCheckMode: Tags`), so each release needs a tag, a `versionCode` bump
and a `fastlane/metadata/android/*/changelogs/<versionCode>.txt`.
