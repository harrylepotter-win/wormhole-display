#!/bin/bash
# Cross-build OpenSSL libcrypto and libplist for android-arm64 into
# app/src/main/cpp/deps/. Already-built artifacts are committed, so this
# is only needed to upgrade or rebuild them. Requires the NDK at
# .tools/ndk-r27d (download: https://dl.google.com/android/repository/android-ndk-r27d-darwin.zip)
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
tools="$repo_dir/.tools"
ndk="$tools/ndk-r27d"
out="$repo_dir/app/src/main/cpp/deps"
api=29
host=aarch64-linux-android

mkdir -p "$tools"
[ -f "$tools/openssl.tar.gz" ] || curl -sL -o "$tools/openssl.tar.gz" \
  https://github.com/openssl/openssl/releases/download/openssl-3.0.16/openssl-3.0.16.tar.gz
[ -f "$tools/libplist.tar.gz" ] || curl -sL -o "$tools/libplist.tar.gz" \
  https://github.com/libimobiledevice/libplist/releases/download/2.6.0/libplist-2.6.0.tar.bz2
rm -rf "$tools/openssl-3.0.16" "$tools/libplist-2.6.0" "$tools/libplist-install"
tar xf "$tools/openssl.tar.gz" -C "$tools"
tar xf "$tools/libplist.tar.gz" -C "$tools"
openssl_src="$tools/openssl-3.0.16"
plist_src="$tools/libplist-2.6.0"

# OpenSSL: static libcrypto only (3.0 wants ANDROID_NDK_ROOT)
export ANDROID_NDK_HOME="$ndk"
export ANDROID_NDK_ROOT="$ndk"
export PATH="$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin:$PATH"
cd "$openssl_src"
./Configure android-arm64 -D__ANDROID_API__=$api no-shared no-tests no-ui-console
make -j"$(sysctl -n hw.ncpu)" build_libs
mkdir -p "$out/openssl/lib/arm64-v8a" "$out/openssl/include"
cp libcrypto.a "$out/openssl/lib/arm64-v8a/"
cp -r include/openssl "$out/openssl/include/"

# libplist: static C library (libplist-2.0) via autotools (the 2.6.0 release
# tarball has no CMake build). C++ bindings are unused but harmless.
tc="$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin"
cd "$plist_src"
./configure --host=$host CC=$host$api-clang CXX=$host$api-clang++ \
  AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip \
  --disable-shared --enable-static --without-cython --without-tests \
  --prefix="$tools/libplist-install"
make -j"$(sysctl -n hw.ncpu)"
make install || true   # ranlib lookup can fail post-install; archive is already in place
"$tc/llvm-ranlib" "$tools/libplist-install/lib/libplist-2.0.a"
# Drop DWARF debug info: it embeds absolute local build paths and isn't needed to link.
"$tc/llvm-strip" --strip-debug "$tools/libplist-install/lib/libplist-2.0.a"
mkdir -p "$out/plist/lib/arm64-v8a" "$out/plist/include/plist"
cp "$tools/libplist-install/lib/libplist-2.0.a" "$out/plist/lib/arm64-v8a/"
cp "$plist_src/include/plist/plist.h" "$out/plist/include/plist/"
echo "deps installed to $out"
