#!/usr/bin/env bash
# Installs packages with the release APK on the running emulator, the way the phone does:
# bundled libraries from the APK, then the compiler toolchain from Termux, then builds and
# runs a C program with it.
#
# It drives the terminal's `pkg` command (io.nativeforge.app.cli.PkgMain), which is the app's
# own install code (PackageManager, the bundled index read from the APK, the Termux source)
# after R8, exactly as users get it. It runs with app_process as the shell user, in a scratch
# prefix under /data/local/tmp, so the APK does not even need to be signed or installed.
# Usage: install-smoke.sh <app.apk>
set -euo pipefail

apk=$1
remote_apk=/data/local/tmp/nativeforge.apk
files=/data/local/tmp/nf
prefix=$files/usr

adb push "$apk" "$remote_apk" > /dev/null
adb shell "rm -rf $files && mkdir -p $prefix $files/home"

# Runs `pkg <args>` on the device; fails when pkg does not exit with 0.
pkg() {
  local out
  out=$(adb shell "cd $files && PREFIX=$prefix HOME=$files/home NATIVEFORGE_APK=$remote_apk CLASSPATH=$remote_apk \
    /system/bin/app_process -Xnoimage-dex2oat /system/bin io.nativeforge.app.cli.PkgMain $*; echo pkg-exit=\$?" | tr -d '\r')
  echo "$out"
  if ! grep -q '^pkg-exit=0$' <<< "$out"; then
    echo "::error::pkg $* failed"
    return 1
  fi
}

on_device() { adb shell "$1" | tr -d '\r'; }

require() { # <path on device> <what it is>
  if ! adb shell "test -e $1"; then
    echo "::error::$2 is missing after the install ($1)"
    exit 1
  fi
}

echo "::group::Bundled libraries (from the APK, no network)"
start=$(date +%s)
pkg install sdl2 lvgl ncursesw raylib sqlite3
require "$files/android-libs/lvgl/include" "LVGL's headers"
require "$files/android-libs/sdl2/include" "SDL2's headers"
require "$files/android-libs/ncursesw/lib/libncursesw.a" "ncursesw"
bundled=$(( $(date +%s) - start ))
echo "::endgroup::"

echo "::group::Compiler toolchain (from Termux)"
start=$(date +%s)
pkg install clang lld cmake ninja make pkg-config ndk-sysroot libc++ ccache
require "$prefix/bin/clang" "clang"
require "$prefix/bin/ld.lld" "ld.lld"
require "$prefix/bin/cmake" "cmake"
require "$prefix/bin/ninja" "ninja"
require "$prefix/bin/clang.cfg" "clang's config (written by ClangToolchain.prepare)"
toolchain=$(( $(date +%s) - start ))
echo "::endgroup::"

echo "::group::Build and run a C program with the installed clang"
env="cd $files/home && PATH=$prefix/bin:/system/bin LD_LIBRARY_PATH=$prefix/lib TMPDIR=$prefix/tmp"
adb shell "mkdir -p $prefix/tmp && printf '#include <stdio.h>\nint main(void) { puts(\"hello from nativeforge\"); return 0; }\n' > $files/home/hello.c"
on_device "$env clang --version"
on_device "$env clang -fuse-ld=lld hello.c -o hello; echo cc-exit=\$?" | tee "$RUNNER_TEMP/cc.txt"
grep -q '^cc-exit=0$' "$RUNNER_TEMP/cc.txt" || { echo "::error::clang could not build a C program"; exit 1; }
output=$(on_device "cd $files/home && ./hello")
echo "$output"
[ "$output" = "hello from nativeforge" ] || { echo "::error::the program built by clang did not run"; exit 1; }
echo "::endgroup::"

{
  echo "### Package install smoke test (emulator)"
  echo ""
  echo "Bundled libraries: ${bundled}s. Toolchain from Termux: ${toolchain}s. clang built and ran a C program."
} >> "${GITHUB_STEP_SUMMARY:-/dev/stdout}"
