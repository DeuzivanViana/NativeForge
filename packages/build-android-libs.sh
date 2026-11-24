#!/usr/bin/env bash
# Builds the Android-targeted graphics libraries bundled with the IDE
# (SDL3, raylib, SFML, LVGL, native_app_glue, EGL/GLES headers) with the NDK, packages each one as a
# NativeForge "isolated" package and writes the index the IDE reads.
#
# Output: app/src/main/assets/packages/{index.json,<arch>/<name>-<version>.tar.xz}
#
# Usage: packages/build-android-libs.sh [abi...]   (default: arm64-v8a armeabi-v7a x86_64)
#        ONLY="sdl2 imgui" packages/build-android-libs.sh   builds just those packages (and their
#        satellites) and merges them into the existing index, leaving the other archives untouched
# Env:   ANDROID_HOME (SDK with NDK 27.2.12479018 and CMake 3.22.1), ANDROID_NDK_HOME (another NDK r27c path),
#        WORK_DIR (default: build/android-libs),
#        PACKAGE_ONLY=1 to repack a previous build, FETCH_ONLY=1 to only download sources
#
# Keep SDL3_VERSION in sync with the SDL Java glue in
# feature/runner/src/main/java/org/libsdl/app/ (SDL refuses to start on a mismatch).
#
# Sources come from the Ubuntu archive (upstream tarballs) because it is a stable,
# checksummed mirror; files Debian strips from those tarballs are fetched from the
# upstream repositories at the same tag.
set -euo pipefail

REPO=$(cd "$(dirname "$0")/.." && pwd)
SDK=${ANDROID_HOME:?set ANDROID_HOME}
NDK=${ANDROID_NDK_HOME:-$SDK/ndk/27.2.12479018}
export PATH=$SDK/cmake/3.22.1/bin:$PATH
LLVM=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
WORK=${WORK_DIR:-$REPO/build/android-libs}
SRC=$WORK/src
OUT=$REPO/app/src/main/assets/packages
API=24
JOBS=$(nproc)
ABIS=${*:-arm64-v8a armeabi-v7a x86_64}

SDL3_VERSION=3.4.20
RAYLIB_VERSION=6.2
SFML_VERSION=3.0.2
FREETYPE_VERSION=2.14.3
OGG_VERSION=1.3.5
VORBIS_VERSION=1.3.7
FLAC_VERSION=1.4.3
SDL3_IMAGE_VERSION=3.4.8
SDL3_TTF_VERSION=3.2.2
SDL3_MIXER_VERSION=3.2.4
BOX2D_VERSION=2.4.1
BOX3D_VERSION=0.1.0
LUA_VERSION=5.4.9
GLM_VERSION=1.0.1
JSON_VERSION=3.11.3
RAYGUI_VERSION=4.0
ENTT_VERSION=3.15.0
STB_COMMIT=f58f558c120e9b32c217290b80bad1a0729fbb2c
MINIAUDIO_VERSION=0.11.22
SDL2_VERSION=2.32.10
SDL2_IMAGE_VERSION=2.8.12
SDL2_TTF_VERSION=2.24.0
SDL2_MIXER_VERSION=2.8.2
IMGUI_VERSION=1.92.2b
LVGL_VERSION=9.6.0
LVGL_COMMIT=80ca777e37a2b176770726a02e07a6fb79ef0b39
# Packages built by ONLY=... runs (names as in the index); empty means everything.
ONLY=${ONLY:-}
wanted() { [ -z "$ONLY" ] || [[ " $ONLY " == *" $1 "* ]]; }
UBUNTU=https://archive.ubuntu.com/ubuntu/pool
RAW=https://raw.githubusercontent.com
BOX3D_COMMIT=8441b4a06d6d09dcfb0b0f704df4d847d1437b92
SUMS=$REPO/packages/sources.sha256

# Every downloaded file must match its SHA-256 in packages/sources.sha256.
verify() { # path relative to $SRC
  local expected
  expected=$(awk -v f="$1" '$2 == f { print $1 }' "$SUMS")
  [ -n "$expected" ] || { echo "No SHA-256 for $1 in $SUMS" >&2; exit 1; }
  echo "$expected  $SRC/$1" | sha256sum -c --quiet - || { echo "SHA-256 mismatch: $1" >&2; exit 1; }
}

download() { # url path-relative-to-$SRC
  [ -f "$SRC/$2" ] || { mkdir -p "$(dirname "$SRC/$2")"; curl -sSfL -o "$SRC/$2.part" "$1"; mv "$SRC/$2.part" "$SRC/$2"; }
  verify "$2"
}

fetch() { # url dir
  local name; name=$(basename "$1")
  download "$1" "$name"
  [ -d "$SRC/$2" ] || tar -xf "$SRC/$name" -C "$SRC"
}

fetch_new_sources() {
  fetch "$UBUNTU/main/f/freetype/freetype_${FREETYPE_VERSION}+dfsg.orig.tar.xz" "freetype-$FREETYPE_VERSION"
  fetch "$UBUNTU/main/libs/libsdl2/libsdl2_${SDL2_VERSION}+dfsg.orig.tar.gz" "SDL2-$SDL2_VERSION"
  fetch "$UBUNTU/universe/libs/libsdl2-image/libsdl2-image_${SDL2_IMAGE_VERSION}+dfsg.orig.tar.gz" "SDL2_image-$SDL2_IMAGE_VERSION"
  fetch "$UBUNTU/universe/libs/libsdl2-ttf/libsdl2-ttf_${SDL2_TTF_VERSION}+dfsg.orig.tar.gz" "SDL2_ttf-$SDL2_TTF_VERSION"
  fetch "$UBUNTU/universe/libs/libsdl2-mixer/libsdl2-mixer_${SDL2_MIXER_VERSION}+dfsg.orig.tar.gz" "SDL2_mixer-$SDL2_MIXER_VERSION"
  fetch "$UBUNTU/universe/i/imgui/imgui_${IMGUI_VERSION}+ds.orig.tar.xz" "imgui-$IMGUI_VERSION"
  # minimp3 (CC0), removed from the Debian SDL2_mixer tarball.
  local mp3=$SRC/SDL2_mixer-$SDL2_MIXER_VERSION/src/codecs/minimp3
  mkdir -p "$mp3"
  for f in minimp3.h minimp3_ex.h LICENSE; do
    download "$RAW/libsdl-org/SDL_mixer/release-$SDL2_MIXER_VERSION/src/codecs/minimp3/$f" \
      "SDL2_mixer-$SDL2_MIXER_VERSION/src/codecs/minimp3/$f"
  done
  fetch_lvgl
}

# LVGL is not in the Ubuntu archive: shallow clone of the release tag, checked against its commit.
fetch_lvgl() {
  local L=$SRC/lvgl-$LVGL_VERSION
  [ -d "$L" ] || git -c advice.detachedHead=false clone -q --depth 1 --branch "v$LVGL_VERSION" \
    https://github.com/lvgl/lvgl "$L"
  [ "$(git -C "$L" rev-parse HEAD)" = "$LVGL_COMMIT" ] ||
    { echo "LVGL tag v$LVGL_VERSION is not commit $LVGL_COMMIT" >&2; exit 1; }
  # Upstream bug: finger positions are divided by the zoom twice (hor_res is already the
  # zoomed-out size), so touches land in the wrong place when lv_sdl_window_set_zoom() is used.
  sed -i 's|event->tfinger\.\([xy]\) / zoom)|event->tfinger.\1)|' "$L/src/drivers/sdl/lv_sdl_mouse.c"
}

fetch_sources() {
  mkdir -p "$SRC"
  fetch_new_sources
  [ -n "$ONLY" ] && return
  fetch "$UBUNTU/main/libs/libsdl3/libsdl3_${SDL3_VERSION}+ds.orig.tar.xz" "SDL3-$SDL3_VERSION"
  fetch "$UBUNTU/universe/r/raylib/raylib_${RAYLIB_VERSION}+ds.orig.tar.xz" "raylib"
  fetch "$UBUNTU/universe/libs/libsfml/libsfml_${SFML_VERSION}+dfsg.orig.tar.xz" "SFML-$SFML_VERSION"
  fetch "$UBUNTU/main/f/freetype/freetype_${FREETYPE_VERSION}+dfsg.orig.tar.xz" "freetype-$FREETYPE_VERSION"
  fetch "$UBUNTU/main/libo/libogg/libogg_${OGG_VERSION}.orig.tar.xz" "libogg-$OGG_VERSION"
  fetch "$UBUNTU/main/libv/libvorbis/libvorbis_${VORBIS_VERSION}.orig.tar.gz" "libvorbis-$VORBIS_VERSION"
  fetch "$UBUNTU/main/f/flac/flac_${FLAC_VERSION}+ds.orig.tar.xz" "flac-$FLAC_VERSION"
  fetch "$UBUNTU/universe/libs/libsdl3-image/libsdl3-image_${SDL3_IMAGE_VERSION}+ds.orig.tar.xz" "libsdl3-image-$SDL3_IMAGE_VERSION"
  fetch "$UBUNTU/universe/libs/libsdl3-ttf/libsdl3-ttf_${SDL3_TTF_VERSION}+ds.orig.tar.xz" "SDL3_ttf-$SDL3_TTF_VERSION"
  fetch "$UBUNTU/universe/libs/libsdl3-mixer/libsdl3-mixer_${SDL3_MIXER_VERSION}+ds.orig.tar.xz" "SDL3_mixer-$SDL3_MIXER_VERSION"
  fetch "$UBUNTU/universe/b/box2d/box2d_${BOX2D_VERSION}.orig.tar.gz" "box2d-$BOX2D_VERSION"
  fetch "$UBUNTU/main/l/lua5.4/lua5.4_${LUA_VERSION}.orig.tar.gz" "lua-$LUA_VERSION"
  fetch "$UBUNTU/universe/g/glm/glm_${GLM_VERSION}+ds.orig.tar.xz" "glm-${GLM_VERSION}+ds"
  fetch "$UBUNTU/main/n/nlohmann-json3/nlohmann-json3_${JSON_VERSION}.orig.tar.gz" "json-$JSON_VERSION"
  # Single-header libraries straight from their repositories, pinned to a tag or commit.
  mkdir -p "$SRC/headers"
  raw() { download "$RAW/$1" "headers/$2"; }
  raw "raysan5/raygui/$RAYGUI_VERSION/src/raygui.h" raygui.h
  raw "raysan5/raygui/$RAYGUI_VERSION/LICENSE" raygui-LICENSE
  raw "skypjack/entt/v$ENTT_VERSION/single_include/entt/entt.hpp" entt.hpp
  raw "skypjack/entt/v$ENTT_VERSION/LICENSE" entt-LICENSE
  raw "mackron/miniaudio/$MINIAUDIO_VERSION/miniaudio.h" miniaudio.h
  raw "mackron/miniaudio/$MINIAUDIO_VERSION/LICENSE" miniaudio-LICENSE
  for f in stb_image.h stb_image_write.h stb_truetype.h stb_rect_pack.h stb_vorbis.c stb_perlin.h stb_ds.h stb_sprintf.h LICENSE; do
    raw "nothings/stb/$STB_COMMIT/$f" "stb-$f"
  done
  # Box3D is not in any distribution archive yet: shallow clone of the release tag.
  [ -d "$SRC/box3d-$BOX3D_VERSION" ] || git -c advice.detachedHead=false clone -q --depth 1 \
    --branch "v$BOX3D_VERSION" https://github.com/erincatto/box3d "$SRC/box3d-$BOX3D_VERSION"
  [ "$(git -C "$SRC/box3d-$BOX3D_VERSION" rev-parse HEAD)" = "$BOX3D_COMMIT" ] ||
    { echo "Box3D tag v$BOX3D_VERSION is not commit $BOX3D_COMMIT" >&2; exit 1; }
  # The raylib tarball unpacks to raysan5-raylib-<commit>.
  [ -d "$SRC/raylib" ] || mv "$SRC"/raysan5-raylib-* "$SRC/raylib"
  # Headers removed from the Debian SFML tarball.
  local sfml=$SRC/SFML-$SFML_VERSION/extlibs/headers
  for f in stb_image/stb_image.h stb_image/stb_image_write.h miniaudio/miniaudio.h; do
    mkdir -p "$sfml/$(dirname $f)"
    download "$RAW/SFML/SFML/$SFML_VERSION/extlibs/headers/$f" "SFML-$SFML_VERSION/extlibs/headers/$f"
  done
}

common() {
  echo -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=$1 \
    -DANDROID_PLATFORM=android-$API -DANDROID_STL=c++_shared -DCMAKE_BUILD_TYPE=Release -GNinja \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
}

cmk() { # src build-dir cmake-args...
  local src=$1 bld=$2; shift 2
  cmake -S "$src" -B "$bld" "$@" >"$bld.log" 2>&1 || { tail -30 "$bld.log"; exit 1; }
  cmake --build "$bld" -j"$JOBS" >>"$bld.log" 2>&1 || { grep -m5 -A8 "FAILED:" "$bld.log"; exit 1; }
}

triple() {
  case $1 in
    arm64-v8a) echo aarch64-linux-android ;; armeabi-v7a) echo armv7a-linux-androideabi ;;
    x86_64) echo x86_64-linux-android ;; x86) echo i686-linux-android ;;
  esac
}

build_abi() {
  local abi=$1 B=$WORK/build/$1 S=$WORK/stage/$1 D=$WORK/build/$1/deps
  mkdir -p "$B" "$S"
  echo "== $abi"
  if [ ! -f "$S/sdl3/lib/libSDL3.so" ]; then
    cmk "$SRC/SDL3-$SDL3_VERSION" "$B/sdl3" $(common $abi) -DSDL_SHARED=ON -DSDL_STATIC=OFF \
      -DSDL_TEST_LIBRARY=OFF -DSDL_TESTS=OFF -DSDL_EXAMPLES=OFF -DSDL_ANDROID_JAR=OFF -DCMAKE_INSTALL_PREFIX="$S/sdl3" \
      -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384"
    cmake --install "$B/sdl3" --strip >/dev/null
  fi
  if [ ! -f "$S/raylib/lib/libraylib.a" ]; then
    cmk "$SRC/raylib" "$B/raylib" $(common $abi) -DPLATFORM=Android -DBUILD_EXAMPLES=OFF \
      -DBUILD_SHARED_LIBS=OFF -DCMAKE_INSTALL_PREFIX="$S/raylib"
    cmake --install "$B/raylib" >/dev/null
    "$LLVM/llvm-strip" --strip-debug "$S/raylib/lib/libraylib.a"
  fi
  if [ ! -f "$S/native-app-glue/lib/libnative_app_glue.a" ]; then
    local glue=$NDK/sources/android/native_app_glue
    mkdir -p "$S/native-app-glue/include" "$S/native-app-glue/lib" "$B/glue"
    "$LLVM/clang" --target=$(triple $abi)$API -fPIC -O2 -c "$glue/android_native_app_glue.c" -o "$B/glue/glue.o"
    "$LLVM/llvm-ar" rcs "$S/native-app-glue/lib/libnative_app_glue.a" "$B/glue/glue.o"
    cp "$glue/android_native_app_glue.h" "$S/native-app-glue/include/"
  fi
  if [ ! -f "$D/lib/libFLAC.a" ]; then
    cmk "$SRC/freetype-$FREETYPE_VERSION" "$B/freetype" $(common $abi) -DBUILD_SHARED_LIBS=OFF \
      -DFT_DISABLE_ZLIB=ON -DFT_DISABLE_BZIP2=ON -DFT_DISABLE_PNG=ON -DFT_DISABLE_HARFBUZZ=ON \
      -DFT_DISABLE_BROTLI=ON -DCMAKE_INSTALL_PREFIX="$D"
    cmake --install "$B/freetype" >/dev/null
    cmk "$SRC/libogg-$OGG_VERSION" "$B/ogg" $(common $abi) -DBUILD_SHARED_LIBS=OFF -DINSTALL_DOCS=OFF \
      -DCMAKE_INSTALL_PREFIX="$D"
    cmake --install "$B/ogg" >/dev/null
    cmk "$SRC/libvorbis-$VORBIS_VERSION" "$B/vorbis" $(common $abi) -DBUILD_SHARED_LIBS=OFF \
      -DCMAKE_PREFIX_PATH="$D" -DCMAKE_FIND_ROOT_PATH="$D" -DCMAKE_INSTALL_PREFIX="$D"
    cmake --install "$B/vorbis" >/dev/null
    cmk "$SRC/flac-$FLAC_VERSION" "$B/flac" $(common $abi) -DBUILD_SHARED_LIBS=OFF -DBUILD_CXXLIBS=OFF \
      -DBUILD_PROGRAMS=OFF -DBUILD_EXAMPLES=OFF -DBUILD_TESTING=OFF -DBUILD_DOCS=OFF -DINSTALL_MANPAGES=OFF \
      -DWITH_OGG=ON -DWITH_FORTIFY_SOURCE=OFF -DWITH_STACK_PROTECTOR=OFF \
      -DCMAKE_PREFIX_PATH="$D" -DCMAKE_FIND_ROOT_PATH="$D" -DCMAKE_INSTALL_PREFIX="$D"
    cmake --install "$B/flac" >/dev/null
  fi
  if [ ! -f "$S/sfml/lib/libsfml-graphics.so" ]; then
    # On Android, SFML always builds shared libraries and installs into the NDK tree.
    local ndk_sfml=$NDK/sources/third_party/sfml
    rm -rf "$ndk_sfml" "$B/sfml"
    cmk "$SRC/SFML-$SFML_VERSION" "$B/sfml" $(common $abi) -DSFML_USE_SYSTEM_DEPS=ON -DSFML_BUILD_AUDIO=ON \
      -DSFML_BUILD_NETWORK=ON -DCMAKE_PREFIX_PATH="$D" -DCMAKE_FIND_ROOT_PATH="$D" \
      -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384"
    cmake --install "$B/sfml" --strip >/dev/null
    mkdir -p "$S/sfml/lib"
    cp -r "$ndk_sfml/include" "$S/sfml/"
    cp "$ndk_sfml/lib/$abi/"*.so "$ndk_sfml/lib/$abi/"*.a "$S/sfml/lib/"
    "$LLVM/llvm-strip" --strip-debug "$S/sfml/lib/libsfml-main.a"
    rm -rf "$ndk_sfml"
  fi
}

# SDL satellites, physics and scripting (built after SDL3 and freetype).
build_extras() { # abi
  local abi=$1 B=$WORK/build/$1 S=$WORK/stage/$1 D=$WORK/build/$1/deps
  local sdl_args="-DSDL3_DIR=$S/sdl3/lib/cmake/SDL3 -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
  if [ ! -f "$S/sdl3-image/lib/libSDL3_image.so" ]; then
    cmk "$SRC/libsdl3-image-$SDL3_IMAGE_VERSION" "$B/sdl3-image" $(common $abi) $sdl_args -DBUILD_SHARED_LIBS=ON \
      -DSDLIMAGE_VENDORED=OFF -DSDLIMAGE_DEPS_SHARED=OFF -DSDLIMAGE_BACKEND_STB=ON -DSDLIMAGE_PNG_LIBPNG=OFF \
      -DSDLIMAGE_AVIF=OFF -DSDLIMAGE_JXL=OFF -DSDLIMAGE_TIF=OFF -DSDLIMAGE_WEBP=OFF -DSDLIMAGE_SVG=ON \
      -DSDLIMAGE_SAMPLES=OFF -DSDLIMAGE_TESTS=OFF -DCMAKE_INSTALL_PREFIX="$S/sdl3-image"
    cmake --install "$B/sdl3-image" --strip >/dev/null
  fi
  if [ ! -f "$S/sdl3-ttf/lib/libSDL3_ttf.so" ]; then
    cmk "$SRC/SDL3_ttf-$SDL3_TTF_VERSION" "$B/sdl3-ttf" $(common $abi) $sdl_args -DBUILD_SHARED_LIBS=ON \
      -DSDLTTF_VENDORED=OFF -DSDLTTF_HARFBUZZ=OFF -DSDLTTF_PLUTOSVG=OFF -DSDLTTF_SAMPLES=OFF \
      -DCMAKE_PREFIX_PATH="$D" -DCMAKE_FIND_ROOT_PATH="$D;$S/sdl3" -DCMAKE_INSTALL_PREFIX="$S/sdl3-ttf"
    cmake --install "$B/sdl3-ttf" --strip >/dev/null
  fi
  if [ ! -f "$S/sdl3-mixer/lib/libSDL3_mixer.so" ]; then
    cmk "$SRC/SDL3_mixer-$SDL3_MIXER_VERSION" "$B/sdl3-mixer" $(common $abi) $sdl_args -DBUILD_SHARED_LIBS=ON \
      -DSDLMIXER_VENDORED=OFF -DSDLMIXER_DEPS_SHARED=OFF -DSDLMIXER_TESTS=OFF -DSDLMIXER_EXAMPLES=OFF \
      -DSDLMIXER_FLAC_LIBFLAC=OFF -DSDLMIXER_GME=OFF -DSDLMIXER_MOD=OFF -DSDLMIXER_MP3_MPG123=OFF \
      -DSDLMIXER_MIDI_FLUIDSYNTH=OFF -DSDLMIXER_OPUS=OFF -DSDLMIXER_VORBIS_VORBISFILE=OFF \
      -DSDLMIXER_WAVPACK=OFF -DCMAKE_INSTALL_PREFIX="$S/sdl3-mixer"
    cmake --install "$B/sdl3-mixer" --strip >/dev/null
  fi
  if [ ! -f "$S/box2d/lib/libbox2d.a" ]; then
    cmk "$SRC/box2d-$BOX2D_VERSION" "$B/box2d" $(common $abi) -DBUILD_SHARED_LIBS=OFF \
      -DBOX2D_BUILD_UNIT_TESTS=OFF -DBOX2D_BUILD_TESTBED=OFF -DBOX2D_BUILD_DOCS=OFF -DCMAKE_INSTALL_PREFIX="$S/box2d"
    cmake --install "$B/box2d" >/dev/null
    "$LLVM/llvm-strip" --strip-debug "$S/box2d/lib/libbox2d.a"
  fi
  if [ ! -f "$S/box3d/lib/libbox3d.a" ]; then
    # Box3D's SIMD path targets SSE2/AVX2 and 64-bit NEON; 32-bit ARM uses scalar math.
    local no_simd=OFF; [ "$abi" = armeabi-v7a ] && no_simd=ON
    cmk "$SRC/box3d-$BOX3D_VERSION" "$B/box3d" $(common $abi) -DBUILD_SHARED_LIBS=OFF -DBOX3D_SAMPLES=OFF \
      -DBOX3D_UNIT_TESTS=OFF -DBOX3D_BENCHMARKS=OFF -DBOX3D_VALIDATE=OFF -DBOX3D_DISABLE_SIMD=$no_simd
    mkdir -p "$S/box3d/lib" "$S/box3d/include"
    cp "$(find "$B/box3d" -name libbox3d.a | head -1)" "$S/box3d/lib/"
    cp -r "$SRC/box3d-$BOX3D_VERSION/include/box3d" "$S/box3d/include/"
    "$LLVM/llvm-strip" --strip-debug "$S/box3d/lib/libbox3d.a"
  fi
  if [ ! -f "$S/lua/lib/liblua.a" ]; then
    local lua=$SRC/lua-$LUA_VERSION/src
    rm -rf "$B/lua"; mkdir -p "$B/lua" "$S/lua/include" "$S/lua/lib"
    for c in "$lua"/*.c; do
      case $(basename "$c") in lua.c|luac.c) continue ;; esac
      "$LLVM/clang" --target=$(triple $abi)$API -fPIC -O2 -DLUA_USE_POSIX -DLUA_USE_DLOPEN -c "$c" -o "$B/lua/$(basename "$c" .c).o"
    done
    "$LLVM/llvm-ar" rcs "$S/lua/lib/liblua.a" "$B/lua"/*.o
    cp "$lua"/lua.h "$lua"/luaconf.h "$lua"/lualib.h "$lua"/lauxlib.h "$lua"/lua.hpp "$S/lua/include/"
  fi
}

# SDL2 and its satellites. The IDE also hosts SDL3, whose Java glue uses the same package
# (org.libsdl.app), so SDL2's JNI names are moved to org.libsdl.sdl2 (see
# feature/runner/src/main/java/org/libsdl/sdl2/, which must match SDL2_VERSION).
build_sdl2() { # abi
  local abi=$1 B=$WORK/build/$1 S=$WORK/stage/$1 D=$WORK/build/$1/deps
  local sdl2=$SRC/SDL2-$SDL2_VERSION
  mkdir -p "$B" "$S"
  if ! grep -q org_libsdl_sdl2 "$sdl2/src/core/android/SDL_android.c"; then
    sed -i -e 's/org_libsdl_app/org_libsdl_sdl2/g' -e 's|org/libsdl/app/|org/libsdl/sdl2/|g' \
      "$sdl2/src/core/android/SDL_android.c" "$sdl2/src/hidapi/android/hid.cpp"
  fi
  if [ ! -f "$S/sdl2/lib/libSDL2.so" ]; then
    cmk "$sdl2" "$B/sdl2" $(common $abi) -DSDL_SHARED=ON -DSDL_STATIC=OFF -DSDL_TEST=OFF -DSDL2_DISABLE_SDL2MAIN=OFF \
      -DCMAKE_INSTALL_PREFIX="$S/sdl2" -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384"
    cmake --install "$B/sdl2" --strip >/dev/null
  fi
  local sdl_args="-DSDL2_DIR=$S/sdl2/lib/cmake/SDL2 -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
  if [ ! -f "$S/sdl2-image/lib/libSDL2_image.so" ]; then
    cmk "$SRC/SDL2_image-$SDL2_IMAGE_VERSION" "$B/sdl2-image" $(common $abi) $sdl_args -DBUILD_SHARED_LIBS=ON \
      -DSDL2IMAGE_VENDORED=OFF -DSDL2IMAGE_DEPS_SHARED=OFF -DSDL2IMAGE_BACKEND_STB=ON \
      -DSDL2IMAGE_AVIF=OFF -DSDL2IMAGE_JXL=OFF -DSDL2IMAGE_TIF=OFF -DSDL2IMAGE_WEBP=OFF -DSDL2IMAGE_SVG=ON \
      -DSDL2IMAGE_SAMPLES=OFF -DSDL2IMAGE_TESTS=OFF -DCMAKE_INSTALL_PREFIX="$S/sdl2-image"
    cmake --install "$B/sdl2-image" --strip >/dev/null
  fi
  if [ ! -f "$D/lib/libfreetype.a" ]; then
    cmk "$SRC/freetype-$FREETYPE_VERSION" "$B/freetype" $(common $abi) -DBUILD_SHARED_LIBS=OFF \
      -DFT_DISABLE_ZLIB=ON -DFT_DISABLE_BZIP2=ON -DFT_DISABLE_PNG=ON -DFT_DISABLE_HARFBUZZ=ON \
      -DFT_DISABLE_BROTLI=ON -DCMAKE_INSTALL_PREFIX="$D"
    cmake --install "$B/freetype" >/dev/null
  fi
  if [ ! -f "$S/sdl2-ttf/lib/libSDL2_ttf.so" ]; then
    cmk "$SRC/SDL2_ttf-$SDL2_TTF_VERSION" "$B/sdl2-ttf" $(common $abi) $sdl_args -DBUILD_SHARED_LIBS=ON \
      -DSDL2TTF_VENDORED=OFF -DSDL2TTF_HARFBUZZ=OFF -DSDL2TTF_SAMPLES=OFF \
      -DCMAKE_PREFIX_PATH="$D" -DCMAKE_FIND_ROOT_PATH="$D;$S/sdl2" -DCMAKE_INSTALL_PREFIX="$S/sdl2-ttf"
    cmake --install "$B/sdl2-ttf" --strip >/dev/null
  fi
  if [ ! -f "$S/sdl2-mixer/lib/libSDL2_mixer.so" ]; then
    cmk "$SRC/SDL2_mixer-$SDL2_MIXER_VERSION" "$B/sdl2-mixer" $(common $abi) $sdl_args -DBUILD_SHARED_LIBS=ON \
      -DSDL2MIXER_VENDORED=OFF -DSDL2MIXER_DEPS_SHARED=OFF -DSDL2MIXER_SAMPLES=OFF \
      -DSDL2MIXER_FLAC_LIBFLAC=OFF -DSDL2MIXER_GME=OFF -DSDL2MIXER_MOD=OFF -DSDL2MIXER_MP3_MPG123=OFF \
      -DSDL2MIXER_MIDI_FLUIDSYNTH=OFF -DSDL2MIXER_OPUS=OFF -DSDL2MIXER_VORBIS=STB \
      -DSDL2MIXER_WAVPACK=OFF -DCMAKE_INSTALL_PREFIX="$S/sdl2-mixer"
    cmake --install "$B/sdl2-mixer" --strip >/dev/null
  fi
}

# Dear ImGui as a static library: the core, imgui_stdlib and the backends that work on
# Android (SDL2, SDL3, SDL renderers, OpenGL ES 3, Vulkan, android_native_app_glue).
build_imgui() { # abi
  local abi=$1 B=$WORK/build/$1/imgui S=$WORK/stage/$1/imgui I=$SRC/imgui-$IMGUI_VERSION
  [ -f "$S/lib/libimgui.a" ] && return
  local sdl3=${SDL3_STAGE:-$WORK/stage/$1/sdl3} sdl2=$WORK/stage/$1/sdl2
  rm -rf "$B" "$S"; mkdir -p "$B" "$S/include" "$S/lib"
  local flags="--target=$(triple $abi)$API -fPIC -O2 -std=c++17 -DIMGUI_IMPL_OPENGL_ES3 -I$I -I$I/backends"
  for c in "$I"/imgui*.cpp "$I/misc/cpp/imgui_stdlib.cpp" \
           "$I"/backends/imgui_impl_{sdl3,sdlrenderer3,opengl3,vulkan,android}.cpp; do
    local inc=""
    case $c in *sdl3*|*sdlrenderer3*) inc="-I$sdl3/include" ;; esac
    "$LLVM/clang++" $flags $inc -c "$c" -o "$B/$(basename "$c" .cpp).o"
  done
  for c in "$I"/backends/imgui_impl_{sdl2,sdlrenderer2}.cpp; do
    "$LLVM/clang++" $flags -I"$sdl2/include/SDL2" -c "$c" -o "$B/$(basename "$c" .cpp).o"
  done
  "$LLVM/llvm-ar" rcs "$S/lib/libimgui.a" "$B"/*.o
  "$LLVM/llvm-strip" --strip-debug "$S/lib/libimgui.a"
  cp "$I"/imgui.h "$I"/imconfig.h "$I"/imgui_internal.h "$I"/imstb_*.h "$I/misc/cpp/imgui_stdlib.h" "$S/include/"
  cp "$I"/backends/imgui_impl_{sdl2,sdl3,sdlrenderer2,sdlrenderer3,opengl3,opengl3_loader,vulkan,android}.h "$S/include/"
}

# LVGL as a static library: the core, its SDL2 display/input driver, the widgets demo and
# lv_nativeforge (packages/lvgl/), configured by packages/lvgl/lv_conf.h, which is installed
# with the headers so programs see the same settings. Compiled through a generated
# build.ninja (about 500 files per ABI).
build_lvgl() { # abi sdl2-include-dir
  local abi=$1 sdl2_include=$2 B=$WORK/build/$1/lvgl S=$WORK/stage/$1/lvgl L=$SRC/lvgl-$LVGL_VERSION
  local P=$REPO/packages/lvgl
  [ -f "$S/lib/liblvgl.a" ] && return
  rm -rf "$B" "$S"; mkdir -p "$B" "$S/include/lvgl/include" "$S/lib"
  {
    echo "rule cc"
    echo "  command = $LLVM/clang --target=$(triple $abi)$API -fPIC -O2 -ffunction-sections -fdata-sections" \
      "-I$P -I$L -I$sdl2_include -DLV_LVGL_H_INCLUDE_SIMPLE -include $P/android_compat.h -c \$in -o \$out"
    (cd "$L" && find src demos/widgets -name '*.c' && echo demos/lv_demos.c) | LC_ALL=C sort | while read -r c; do
      echo "build obj/${c%.c}.o: cc $L/$c"
    done
    echo "build obj/lv_nativeforge.o: cc $P/lv_nativeforge.c"
  } > "$B/build.ninja"
  ninja -C "$B" -j"$JOBS" >"$B.log" 2>&1 || { grep -m5 -A8 "FAILED:" "$B.log"; exit 1; }
  (cd "$B" && find obj -name '*.o' | LC_ALL=C sort | xargs "$LLVM/llvm-ar" rcsD "$S/lib/liblvgl.a")
  "$LLVM/llvm-strip" --strip-debug "$S/lib/liblvgl.a"
  # Headers in LVGL's own layout under include/lvgl/, so both "lvgl.h" and "lvgl/lvgl.h"
  # (and "lvgl/demos/lv_demos.h") work as in LVGL's desktop simulator projects.
  cp -r "$L/include/lvgl" "$S/include/lvgl/include/"
  cp "$L/lvgl.h" "$S/include/lvgl/"
  (cd "$L" && find demos -name '*.h' | while read -r h; do mkdir -p "$S/include/lvgl/$(dirname "$h")"; cp "$h" "$S/include/lvgl/$h"; done)
  printf '#include "lvgl/lvgl.h"\n' > "$S/include/lvgl.h"
  cp "$P/lv_conf.h" "$P/lv_nativeforge.h" "$S/include/"
  mkdir -p "$S/share/licenses/lvgl"
  cp "$L/LICENCE.txt" "$S/share/licenses/lvgl/LICENCE.txt"
  cp "$L/COPYRIGHTS.md" "$S/share/licenses/lvgl/COPYRIGHTS.md"
  cp "$REPO/LICENSE.runtime" "$S/share/licenses/lvgl/lv_nativeforge-LICENSE"
}

# Vulkan headers and the NDK's libvulkan.so link stub (the library itself is part of Android).
stage_vulkan() { # abi
  local S=$WORK/stage/$1/vulkan sys=$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr
  rm -rf "$S"; mkdir -p "$S/include" "$S/lib"
  cp -r "$sys/include/vulkan" "$sys/include/vk_video" "$S/include/"
  cp "$sys/lib/$(triple $1 | sed 's/armv7a/arm/')/$API/libvulkan.so" "$S/lib/"
}

licenses_new() { # abi
  local S=$WORK/stage/$1
  lic() { mkdir -p "$S/$1/share/licenses/$1"; cp "$2" "$S/$1/share/licenses/$1/$3"; }
  lic sdl2 "$SRC/SDL2-$SDL2_VERSION/LICENSE.txt" LICENSE.txt
  lic sdl2-image "$SRC/SDL2_image-$SDL2_IMAGE_VERSION/LICENSE.txt" LICENSE.txt
  lic sdl2-ttf "$SRC/SDL2_ttf-$SDL2_TTF_VERSION/LICENSE.txt" LICENSE.txt
  lic sdl2-ttf "$SRC/freetype-$FREETYPE_VERSION/docs/FTL.TXT" freetype-FTL.TXT
  lic sdl2-mixer "$SRC/SDL2_mixer-$SDL2_MIXER_VERSION/LICENSE.txt" LICENSE.txt
  lic imgui "$SRC/imgui-$IMGUI_VERSION/LICENSE.txt" LICENSE.txt
  lic vulkan "$NDK/NOTICE" NDK-NOTICE
}

# Header-only packages, the same for every ABI (architecture "all").
stage_headers() {
  local S=$WORK/stage/all H=$SRC/headers
  rm -rf "$S"; mkdir -p "$S"
  hdr() { mkdir -p "$S/$1/include" "$S/$1/lib" "$S/$1/share/licenses/$1"; }
  hdr glm; cp -r "$SRC/glm-${GLM_VERSION}+ds/glm" "$S/glm/include/"; find "$S/glm/include" -name 'CMakeLists.txt' -delete
  cp "$SRC/glm-${GLM_VERSION}+ds/copying.txt" "$S/glm/share/licenses/glm/copying.txt"
  hdr nlohmann-json; cp -r "$SRC/json-$JSON_VERSION/single_include/nlohmann" "$S/nlohmann-json/include/"
  cp "$SRC/json-$JSON_VERSION/LICENSE.MIT" "$S/nlohmann-json/share/licenses/nlohmann-json/"
  hdr raygui; cp "$H/raygui.h" "$S/raygui/include/"; cp "$H/raygui-LICENSE" "$S/raygui/share/licenses/raygui/LICENSE"
  hdr entt; mkdir -p "$S/entt/include/entt"; cp "$H/entt.hpp" "$S/entt/include/entt/"; cp "$H/entt-LICENSE" "$S/entt/share/licenses/entt/LICENSE"
  hdr miniaudio; cp "$H/miniaudio.h" "$S/miniaudio/include/"; cp "$H/miniaudio-LICENSE" "$S/miniaudio/share/licenses/miniaudio/LICENSE"
  hdr stb; for f in "$H"/stb-stb_*; do cp "$f" "$S/stb/include/$(basename "$f" | sed 's/^stb-//')"; done
  cp "$H/stb-LICENSE" "$S/stb/share/licenses/stb/LICENSE"
}

# Header-only and post-build steps; cheap, so they also run with PACKAGE_ONLY=1.
finish_abi() { # abi
  local S=$WORK/stage/$1
  # EGL and OpenGL ES headers: Termux's ndk-sysroot package deletes them.
  rm -rf "$S/opengles"; mkdir -p "$S/opengles/include" "$S/opengles/lib"
  for d in EGL GLES GLES2 GLES3 KHR; do cp -r "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/$d" "$S/opengles/include/"; done
  # raylib's fopen wrapper calls __real_fopen, which only exists when the final link uses
  # -Wl,--wrap=fopen. Ship a fallback so links without the flag still work (the member is
  # only pulled in when __real_fopen is unresolved, i.e. when the flag is missing).
  if ! "$LLVM/llvm-nm" "$S/raylib/lib/libraylib.a" 2>/dev/null | grep -q " T __real_fopen"; then
    mkdir -p "$WORK/build/$1/realfopen"
    printf '#include <stdio.h>\nFILE *__real_fopen(const char *path, const char *mode) { return fopen(path, mode); }\n' \
      > "$WORK/build/$1/realfopen/nf_real_fopen.c"
    "$LLVM/clang" --target=$(triple $1)$API -fPIC -O2 -c "$WORK/build/$1/realfopen/nf_real_fopen.c" \
      -o "$WORK/build/$1/realfopen/nf_real_fopen.o"
    "$LLVM/llvm-ar" rs "$S/raylib/lib/libraylib.a" "$WORK/build/$1/realfopen/nf_real_fopen.o"
  fi
  # pkg-config files carry the build machine's prefix; make them relocatable.
  find "$S" -path '*/lib/pkgconfig/*.pc' | while read -r pc; do
    sed -i -e 's|^prefix=.*|prefix=${pcfiledir}/../..|' -e '/^Requires.private: *glfw3/d' "$pc"
  done
  licenses "$1"
}

# Copies each project's license text into share/licenses/<package>/ of its stage dir.
licenses() { # abi
  local S=$WORK/stage/$1
  lic() { mkdir -p "$S/$1/share/licenses/$1"; cp "$2" "$S/$1/share/licenses/$1/$3"; }
  lic sdl3 "$SRC/SDL3-$SDL3_VERSION/LICENSE.txt" LICENSE.txt
  lic raylib "$SRC/raylib/LICENSE" LICENSE
  lic native-app-glue "$NDK/sources/android/native_app_glue/NOTICE" NOTICE
  lic opengles "$NDK/NOTICE" NDK-NOTICE
  lic sdl3-image "$SRC/libsdl3-image-$SDL3_IMAGE_VERSION/LICENSE.txt" LICENSE.txt
  lic sdl3-ttf "$SRC/SDL3_ttf-$SDL3_TTF_VERSION/LICENSE.txt" LICENSE.txt
  lic sdl3-ttf "$SRC/freetype-$FREETYPE_VERSION/docs/FTL.TXT" freetype-FTL.TXT
  lic sdl3-mixer "$SRC/SDL3_mixer-$SDL3_MIXER_VERSION/LICENSE.txt" LICENSE.txt
  lic box2d "$SRC/box2d-$BOX2D_VERSION/LICENSE" LICENSE
  lic box3d "$SRC/box3d-$BOX3D_VERSION/LICENSE" LICENSE
  mkdir -p "$S/lua/share/licenses/lua"
  sed -n '/Copyright (C) 1994/,/\*\*\*\*\*/p' "$SRC/lua-$LUA_VERSION/src/lua.h" > "$S/lua/share/licenses/lua/LICENSE"
  lic sfml "$SRC/SFML-$SFML_VERSION/license.md" license.md
  lic sfml "$SRC/freetype-$FREETYPE_VERSION/docs/FTL.TXT" freetype-FTL.TXT
  lic sfml "$SRC/libogg-$OGG_VERSION/COPYING" libogg-COPYING
  lic sfml "$SRC/libvorbis-$VORBIS_VERSION/COPYING" libvorbis-COPYING
  lic sfml "$SRC/flac-$FLAC_VERSION/COPYING.Xiph" flac-COPYING.Xiph
}

package() {
  [ -z "$ONLY" ] && rm -rf "$OUT"; mkdir -p "$OUT"
  ONLY="$ONLY" SDL2_VERSION=$SDL2_VERSION SDL2_IMAGE_VERSION=$SDL2_IMAGE_VERSION SDL2_TTF_VERSION=$SDL2_TTF_VERSION \
  SDL2_MIXER_VERSION=$SDL2_MIXER_VERSION IMGUI_VERSION=$IMGUI_VERSION LVGL_VERSION=$LVGL_VERSION \
  SDL3_IMAGE_VERSION=$SDL3_IMAGE_VERSION SDL3_TTF_VERSION=$SDL3_TTF_VERSION SDL3_MIXER_VERSION=$SDL3_MIXER_VERSION \
  BOX2D_VERSION=$BOX2D_VERSION BOX3D_VERSION=$BOX3D_VERSION LUA_VERSION=$LUA_VERSION GLM_VERSION=$GLM_VERSION JSON_VERSION=$JSON_VERSION \
  RAYGUI_VERSION=$RAYGUI_VERSION ENTT_VERSION=$ENTT_VERSION MINIAUDIO_VERSION=$MINIAUDIO_VERSION \
  python3 - "$WORK/stage" "$OUT" "$SDL3_VERSION" "$RAYLIB_VERSION" "$SFML_VERSION" $ABIS <<'PY'
import hashlib, json, os, subprocess, sys
stage, out, sdl3, raylib, sfml, *abis = sys.argv[1:]
v = os.environ
arch = {"arm64-v8a": "aarch64", "armeabi-v7a": "arm", "x86_64": "x86_64", "x86": "i686"}
sfml_libs = ["system", "window", "network", "graphics", "audio"]
specs = {
    "sdl3": dict(version=sdl3, description="Simple DirectMedia Layer 3 (Android backend, runs in the SDL host)",
                 build=dict(linkLibraries=["SDL3"], runtimeLibraries=["lib/libSDL3.so"])),
    "raylib": dict(version=raylib, description="raylib game library (OpenGL ES 2, NativeActivity)",
                   # raylib wraps fopen() to read APK assets; the wrap must happen at the final link.
                   build=dict(linkLibraries=["raylib", "EGL", "GLESv2", "OpenSLES", "android", "log", "m"],
                              linkFlags=["-Wl,--wrap=fopen"])),
    "sfml": dict(version=sfml, description="SFML 3: graphics, window, audio, network and system (NativeActivity)",
                 build=dict(linkLibraries=["sfml-" + m for m in reversed(sfml_libs)] + ["sfml-main", "android", "log", "EGL"],
                            runtimeLibraries=["lib/libsfml-%s.so" % m for m in sfml_libs])),
    "native-app-glue": dict(version="ndk-r27c", description="android_native_app_glue from the NDK (static)",
                            build=dict(linkLibraries=["native_app_glue", "android", "log"])),
    "opengles": dict(version="ndk-r27c", description="EGL and OpenGL ES 2/3 headers and libraries",
                     build=dict(linkLibraries=["EGL", "GLESv3", "GLESv2", "android", "log", "m"])),
    "sdl3-image": dict(version=v["SDL3_IMAGE_VERSION"], depends=["sdl3"],
                       description="SDL3_image: load PNG, JPEG, GIF, BMP, QOI, SVG... into SDL textures",
                       build=dict(linkLibraries=["SDL3_image"], runtimeLibraries=["lib/libSDL3_image.so"])),
    "sdl3-ttf": dict(version=v["SDL3_TTF_VERSION"], depends=["sdl3"],
                     description="SDL3_ttf: TrueType text rendering for SDL (FreeType inside)",
                     build=dict(linkLibraries=["SDL3_ttf"], runtimeLibraries=["lib/libSDL3_ttf.so"])),
    "sdl3-mixer": dict(version=v["SDL3_MIXER_VERSION"], depends=["sdl3"],
                       description="SDL3_mixer: play WAV, OGG, MP3, FLAC and MIDI music and sounds",
                       build=dict(linkLibraries=["SDL3_mixer"], runtimeLibraries=["lib/libSDL3_mixer.so"])),
    "box2d": dict(version=v["BOX2D_VERSION"], description="Box2D 2.4: 2D rigid body physics (C++)",
                  build=dict(linkLibraries=["box2d"])),
    "box3d": dict(version=v["BOX3D_VERSION"], description="Box3D 0.1 (alpha): 3D rigid body physics by the Box2D author (C API)",
                  build=dict(linkLibraries=["box3d", "m"])),
    "lua": dict(version=v["LUA_VERSION"], description="Lua 5.4 scripting language, embeddable from C and C++",
                build=dict(linkLibraries=["lua", "m"])),
    "sdl2": dict(version=v["SDL2_VERSION"], description="Simple DirectMedia Layer 2 (Android backend, runs in the SDL2 host)",
                 build=dict(includeDirs=["include", "include/SDL2"], linkLibraries=["SDL2"], runtimeLibraries=["lib/libSDL2.so"])),
    "sdl2-image": dict(version=v["SDL2_IMAGE_VERSION"], depends=["sdl2"],
                       description="SDL2_image: load PNG, JPEG, GIF, BMP, QOI, SVG... into SDL2 surfaces and textures",
                       build=dict(includeDirs=["include", "include/SDL2"], linkLibraries=["SDL2_image"], runtimeLibraries=["lib/libSDL2_image.so"])),
    "sdl2-ttf": dict(version=v["SDL2_TTF_VERSION"], depends=["sdl2"],
                     description="SDL2_ttf: TrueType text rendering for SDL2 (FreeType inside)",
                     build=dict(includeDirs=["include", "include/SDL2"], linkLibraries=["SDL2_ttf"], runtimeLibraries=["lib/libSDL2_ttf.so"])),
    "sdl2-mixer": dict(version=v["SDL2_MIXER_VERSION"], depends=["sdl2"],
                       description="SDL2_mixer: play WAV, OGG, MP3, FLAC and MIDI with SDL2",
                       build=dict(includeDirs=["include", "include/SDL2"], linkLibraries=["SDL2_mixer"], runtimeLibraries=["lib/libSDL2_mixer.so"])),
    "imgui": dict(version=v["IMGUI_VERSION"],
                  description="Dear ImGui: immediate-mode GUI with SDL2, SDL3, OpenGL ES 3 and Vulkan backends (C++)",
                  build=dict(linkLibraries=["imgui"], compileFlags=["-DIMGUI_IMPL_OPENGL_ES3"])),
    "vulkan": dict(version="ndk-r27c", description="Vulkan 1.3 headers and loader stub (the driver comes with Android)",
                   build=dict(linkLibraries=["vulkan", "android", "log", "m"])),
    "lvgl": dict(version=v["LVGL_VERSION"], depends=["sdl2"],
                 description="LVGL 9: widgets for touch apps (buttons, lists, tabs, charts, keyboard...) on SDL2 (C)",
                 build=dict(linkLibraries=["lvgl", "m"])),
}
only = v.get("ONLY", "").split()
if only:
    # Satellites are rebuilt with their base package.
    groups = {"sdl2": ["sdl2", "sdl2-image", "sdl2-ttf", "sdl2-mixer"]}
    only = sorted({n for o in only for n in groups.get(o, [o])})
# Header-only packages: one archive for every ABI.
headers = {
    "glm": dict(version=v["GLM_VERSION"], description="GLM: vector and matrix math for graphics (C++, header-only)", build={}),
    "nlohmann-json": dict(version=v["JSON_VERSION"], description="JSON for Modern C++: read and write JSON (header-only)", build={}),
    "raygui": dict(version=v["RAYGUI_VERSION"], depends=["raylib"],
                   description="raygui: immediate-mode buttons, sliders and windows for raylib (header-only)", build={}),
    "entt": dict(version=v["ENTT_VERSION"], description="EnTT: fast entity-component-system for games (C++17, header-only)", build={}),
    "miniaudio": dict(version=v["MINIAUDIO_VERSION"], description="miniaudio: sound playback and capture in one header (C)",
                      build=dict(linkLibraries=["OpenSLES", "m"])),
    "stb": dict(version="2025", description="stb: image, font, Ogg Vorbis and noise single-file libraries (C)", build={}),
}
packages = []
if only:
    previous = json.load(open(os.path.join(out, "index.json")))["packages"]
    packages = [p for p in previous if p["name"] not in only]
def pack(name, spec, stage_dir, architecture):
    rel = "%s/%s-%s.tar.xz" % (architecture, name, spec["version"])
    archive = os.path.join(out, rel)
    os.makedirs(os.path.dirname(archive), exist_ok=True)
    os.makedirs(os.path.join(stage_dir, "lib"), exist_ok=True)
    subprocess.run(["tar", "--sort=name", "--owner=0", "--group=0", "--numeric-owner", "--mtime=@0",
                    "-cJf", archive, "-C", stage_dir, "include", "lib", "share"], check=True)
    data = open(archive, "rb").read()
    packages.append(dict(name=name, version=spec["version"], description=spec["description"],
                         architecture=architecture, depends=spec.get("depends", []),
                         downloadUrl="asset:packages/" + rel,
                         sha256=hashlib.sha256(data).hexdigest(), sizeBytes=len(data),
                         format="tar.xz", target="isolated",
                         build=dict(dict(includeDirs=["include"], libDirs=["lib"]), **spec["build"])))
for abi in abis:
    for name, spec in specs.items():
        if not only or name in only:
            pack(name, spec, os.path.join(stage, abi, name), arch[abi])
for name, spec in headers.items():
    if not only or name in only:
        pack(name, spec, os.path.join(stage, "all", name), "all")
json.dump(dict(schemaVersion=1, packages=packages), open(os.path.join(out, "index.json"), "w"), indent=2)
print("wrote %d packages" % len(packages))
PY
}

fetch_sources
[ -n "${FETCH_ONLY:-}" ] && exit 0
# PACKAGE_ONLY=1 repacks an existing stage dir without rebuilding.
if [ -z "${PACKAGE_ONLY:-}" ]; then
  for abi in $ABIS; do
    if [ -z "$ONLY" ]; then build_abi "$abi"; build_extras "$abi"; fi
    if wanted sdl2 || wanted imgui; then build_sdl2 "$abi"; fi
    if wanted imgui; then
      # ImGui's SDL3 backend needs SDL3's headers; an ONLY run reads them from the bundled archive.
      if [ ! -d "$WORK/stage/$abi/sdl3/include" ]; then
        mkdir -p "$WORK/stage/$abi/sdl3-headers"
        tar -xJf "$OUT/$(python3 -c "print({'arm64-v8a':'aarch64','armeabi-v7a':'arm','x86_64':'x86_64'}['$abi'])")/sdl3-$SDL3_VERSION.tar.xz" \
          -C "$WORK/stage/$abi/sdl3-headers" include
        SDL3_STAGE=$WORK/stage/$abi/sdl3-headers build_imgui "$abi"
      else
        build_imgui "$abi"
      fi
    fi
    if wanted lvgl; then
      # LVGL's SDL driver needs SDL2's headers; an ONLY run reads them from the bundled archive.
      if [ -d "$WORK/stage/$abi/sdl2/include/SDL2" ]; then
        build_lvgl "$abi" "$WORK/stage/$abi/sdl2/include/SDL2"
      else
        mkdir -p "$WORK/stage/$abi/sdl2-headers"
        tar -xJf "$OUT/$(python3 -c "print({'arm64-v8a':'aarch64','armeabi-v7a':'arm','x86_64':'x86_64'}['$abi'])")/sdl2-$SDL2_VERSION.tar.xz" \
          -C "$WORK/stage/$abi/sdl2-headers" include
        build_lvgl "$abi" "$WORK/stage/$abi/sdl2-headers/include/SDL2"
      fi
    fi
  done
fi
[ -z "$ONLY" ] && stage_headers
for abi in $ABIS; do
  [ -z "$ONLY" ] && finish_abi "$abi"
  wanted vulkan && stage_vulkan "$abi"
  licenses_new "$abi" 2>/dev/null || true
done
package
