#!/usr/bin/env bash
# Builds the native SIP stack (PJSIP + OpenSSL + Opus) as static libraries for Android.
#
#   native/build-deps.sh [abi ...]      default: arm64-v8a armeabi-v7a x86_64
#
# Output: native/build/<abi>/{include,lib}. Gradle's CMake build (app/src/main/cpp) links
# these into libsipdialer.so. Sources are downloaded once into native/.downloads and
# checked against pinned SHA-256 sums.
#
# AMR-NB/AMR-WB come from PJSIP's Android MediaCodec codec, which uses the phone's own
# AMR encoder/decoder. No AMR code (opencore etc.) is compiled in.
#
# Needs: the Android NDK (ANDROID_NDK_ROOT, or ANDROID_HOME/ndk/$NDK_VERSION), cmake,
# make, perl, curl.
set -euo pipefail

NDK_VERSION=29.0.14206865
CMAKE_VERSION=4.1.2 # SDK package; a cmake already on PATH is used if present
API=29 # = minSdk

PJSIP_VERSION=2.17
PJSIP_SHA256=065fe06c06788d97c35f563796d59f00ce52fe9558a52d7b490a042a966facce
OPENSSL_VERSION=3.5.9
OPENSSL_SHA256=603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a
OPUS_VERSION=1.5.2
OPUS_SHA256=65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1

HERE=$(cd "$(dirname "$0")" && pwd)
DL=$HERE/.downloads
BUILD=$HERE/build
WORK=$BUILD/work
STAMP_KEY="pjsip=$PJSIP_VERSION openssl=$OPENSSL_VERSION opus=$OPUS_VERSION ndk=$NDK_VERSION api=$API cfg=$(sha256sum "$HERE/config_site.h" "$0" | sha256sum | cut -c1-16)"

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
ANDROID_NDK_ROOT=${ANDROID_NDK_ROOT:-$SDK/ndk/$NDK_VERSION}
command -v cmake >/dev/null || PATH=$SDK/cmake/$CMAKE_VERSION/bin:$PATH
[ -f "$ANDROID_NDK_ROOT/source.properties" ] || {
  echo "Android NDK not found at $ANDROID_NDK_ROOT (install ndk;$NDK_VERSION with sdkmanager)" >&2
  exit 1
}
export ANDROID_NDK_ROOT
TOOLCHAIN=$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64
JOBS=$(nproc 2>/dev/null || echo 4)

fetch() { # url file sha256
  local url=$1 file=$DL/$2 sum=$3
  mkdir -p "$DL"
  if [ ! -f "$file" ] || ! echo "$sum  $file" | sha256sum -c --status; then
    echo ">> downloading $url"
    curl -fsSL --retry 3 -o "$file.part" "$url"
    echo "$sum  $file.part" | sha256sum -c --status || {
      echo "checksum mismatch for $url" >&2
      exit 1
    }
    mv "$file.part" "$file"
  fi
}

fetch "https://github.com/pjsip/pjproject/archive/refs/tags/$PJSIP_VERSION.tar.gz" \
  "pjproject-$PJSIP_VERSION.tar.gz" "$PJSIP_SHA256"
fetch "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz" \
  "openssl-$OPENSSL_VERSION.tar.gz" "$OPENSSL_SHA256"
fetch "https://downloads.xiph.org/releases/opus/opus-$OPUS_VERSION.tar.gz" \
  "opus-$OPUS_VERSION.tar.gz" "$OPUS_SHA256"

unpack() { # tarball dir
  rm -rf "$2"
  mkdir -p "$2"
  tar xzf "$DL/$1" -C "$2" --strip-components=1
}

build_abi() {
  local abi=$1 out=$BUILD/$abi src=$WORK/$abi
  if [ -f "$out/.stamp" ] && [ "$(cat "$out/.stamp")" = "$STAMP_KEY" ]; then
    echo ">> $abi: up to date"
    return
  fi
  local ossl_target
  case $abi in
    arm64-v8a) ossl_target=android-arm64 ;;
    armeabi-v7a) ossl_target=android-arm ;;
    x86_64) ossl_target=android-x86_64 ;;
    x86) ossl_target=android-x86 ;;
    *) echo "unknown ABI $abi" >&2; exit 1 ;;
  esac
  rm -rf "$out" "$src"
  mkdir -p "$out" "$src"

  echo ">> $abi: OpenSSL $OPENSSL_VERSION"
  unpack "openssl-$OPENSSL_VERSION.tar.gz" "$src/openssl"
  (
    cd "$src/openssl"
    export PATH=$TOOLCHAIN/bin:$PATH
    # The NDK clang defaults to its lowest API level; fine for a static library.
    ./Configure "$ossl_target" no-shared no-tests no-apps no-docs \
      no-engine no-dso no-comp --prefix="$out" --libdir=lib >/dev/null
    make -j"$JOBS" build_libs >/dev/null 2>&1
    make install_dev >/dev/null
  )

  echo ">> $abi: Opus $OPUS_VERSION"
  unpack "opus-$OPUS_VERSION.tar.gz" "$src/opus"
  cmake -S "$src/opus" -B "$src/opus/build" -G "Unix Makefiles" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM=$API -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DCMAKE_INSTALL_PREFIX="$out" -DCMAKE_INSTALL_LIBDIR=lib \
    -DOPUS_BUILD_PROGRAMS=OFF -DOPUS_BUILD_TESTING=OFF -DOPUS_DRED=OFF -DOPUS_OSCE=OFF \
    -DOPUS_DEEP_PLC=OFF >/dev/null
  cmake --build "$src/opus/build" -j"$JOBS" >/dev/null
  cmake --install "$src/opus/build" >/dev/null

  echo ">> $abi: PJSIP $PJSIP_VERSION"
  unpack "pjproject-$PJSIP_VERSION.tar.gz" "$src/pjproject"
  cp "$HERE/config_site.h" "$src/pjproject/pjlib/include/pj/config_site.h"
  (
    cd "$src/pjproject"
    TARGET_ABI=$abi APP_PLATFORM=android-$API ./configure-android --use-ndk-cflags \
      --with-ssl="$out" --with-opus="$out" \
      --disable-video --disable-sdl --disable-ffmpeg --disable-v4l2 --disable-openh264 \
      --disable-vpx --disable-libyuv --disable-libwebrtc-aec3 --disable-opencore-amr \
      --disable-silk --disable-bcg729 --disable-lyra --disable-darwin-ssl \
      --disable-ilbc-codec --disable-g7221-codec --disable-speex-codec \
      --disable-resample-dll >"$src/pjsip-configure.log" 2>&1 ||
      { tail -40 "$src/pjsip-configure.log"; exit 1; }
    grep -q "PJMEDIA_HAS_ANDROID_MEDIACODEC" pjmedia/include/pjmedia-codec/config_auto.h &&
      grep -q "define PJMEDIA_HAS_ANDROID_MEDIACODEC 1" pjmedia/include/pjmedia-codec/config_auto.h ||
      { echo "PJSIP configure did not enable Android MediaCodec (needed for AMR-WB)" >&2; exit 1; }
    grep -q "define PJ_HAS_SSL_SOCK 1" pjlib/include/pj/compat/os_auto.h ||
      { echo "PJSIP configure did not find OpenSSL" >&2; exit 1; }
    make -j"$JOBS" lib >"$src/pjsip-build.log" 2>&1 ||
      { tail -60 "$src/pjsip-build.log"; exit 1; }
    make install prefix="$out" >"$src/pjsip-install.log" 2>&1 ||
      { tail -40 "$src/pjsip-install.log"; exit 1; }
  )
  # Drop the target-triple suffix: libpj-aarch64-unknown-linux-android.a -> libpj.a
  for a in "$out"/lib/*-linux-android*.a; do
    mv "$a" "$(echo "$a" | sed -E 's/-[a-z0-9_]+-[a-z]+-linux-android(eabi)?\.a$/.a/')"
  done
  # Code that includes PJSIP headers must use the same -D flags (endianness, features).
  sed -n 's/^Cflags: *//p' "$out/lib/pkgconfig/libpjproject.pc" | tr ' ' '\n' | grep '^-D' >"$out/defines.txt"
  rm -rf "$out/lib/cmake" "$out/lib/pkgconfig"
  echo "$STAMP_KEY" >"$out/.stamp"
  rm -rf "$src"
  echo ">> $abi: done ($(ls "$out/lib" | tr '\n' ' '))"
}

ABIS=("$@")
[ ${#ABIS[@]} -gt 0 ] || ABIS=(arm64-v8a armeabi-v7a x86_64)
for abi in "${ABIS[@]}"; do build_abi "$abi"; done
