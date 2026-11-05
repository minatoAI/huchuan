#!/usr/bin/env bash
# Build libtorrent4j for Android arm64-v8a from source.
#
# Produces (in $OUT):
#   libtorrent4j.so                  stripped shared object for android arm64-v8a
#   libtorrent4j.so.debug            unstripped copy (keep for symbolication)
#   libtorrent4j-android-arm64.jar   drop-in replacement for
#                                    org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-39
#   sha256.txt, readelf-dynamic.txt, build-info.txt
#
# Usage:
#   ./build.sh                 # full build into ./build / ./build/out
#   ./build.sh --fresh         # wipe cached sources and third-party builds first
#   ./build.sh --out DIR       # write artifacts to DIR
#   ./build.sh --work DIR      # use DIR as the scratch tree (default ./build)
#   ./build.sh --stage deps    # deps | native | package | all  (default all)
#
# Everything is pinned in versions.env. The script is idempotent: downloads,
# Boost extraction and the OpenSSL build are cached inside $WORK.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# shellcheck source=versions.env
. "$HERE/versions.env"

WORK="$HERE/build"
OUT="$HERE/build/out"
STAGE="all"
FRESH=0
JOBS="${JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 8)}"

while [ $# -gt 0 ]; do
  case "$1" in
    --fresh) FRESH=1; shift ;;
    --out)   OUT="$2"; shift 2 ;;
    --work)  WORK="$2"; shift 2 ;;
    --jobs|-j) JOBS="$2"; shift 2 ;;
    --stage) STAGE="$2"; shift 2 ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

mkdir -p "$WORK/dl" "$WORK/src" "$OUT"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# --------------------------------------------------------------------------
# 0. preflight
# --------------------------------------------------------------------------
find_ndk() {
  if [ -n "${ANDROID_NDK_ROOT:-}" ] && [ -d "$ANDROID_NDK_ROOT" ]; then
    echo "$ANDROID_NDK_ROOT"; return
  fi
  local root
  for root in "${ANDROID_SDK_ROOT:-}" /d/Android/Sdk /e/agentHome/android-sdk "$HOME/android-sdk" \
              /c/Users/*/AppData/Local/Android/Sdk; do
    if [ -n "$root" ] && [ -d "$root/ndk/$NDK_VERSION" ]; then echo "$root/ndk/$NDK_VERSION"; return; fi
  done
  die "NDK $NDK_VERSION not found; set ANDROID_NDK_ROOT"
}

NDK_ROOT="$(find_ndk)"
SRC="$WORK/src/libtorrent4j"
LIBTORRENT="$SRC/swig/deps/libtorrent"
BOOST_PREFIX="$WORK/boost"
OSSL_PREFIX="$WORK/openssl"
BUILD_DIR="$WORK/cmake-build"
BOOST_ARCHIVE="boost_$(echo "$BOOST_VERSION" | tr . _).tar.gz"

# native Windows tools need Windows-style paths in argv
path_of() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }

for t in cmake ninja git curl python; do
  command -v "$t" >/dev/null 2>&1 || die "missing tool: $t"
done
HOST_TAG="$(ls "$NDK_ROOT/toolchains/llvm/prebuilt" | head -1)"
log "ndk      : $NDK_ROOT ($HOST_TAG)"
log "work dir : $WORK"
log "out dir  : $OUT"
log "jobs     : $JOBS"

# --------------------------------------------------------------------------
# 1. host helpers (Windows/MSYS only): GNU make and Perl shims
# --------------------------------------------------------------------------
HOSTBIN="$WORK/dl/host/root/usr/bin"
ensure_make() {
  if ! command -v make >/dev/null 2>&1 && [ ! -x "$HOSTBIN/make.exe" ]; then
    log "GNU make missing, fetching $MAKE_PKG"
    mkdir -p "$WORK/dl/host"
    curl -fsSL --retry "$DL_RETRIES" -o "$WORK/dl/host/make.pkg.tar.zst" "$MAKE_PKG_URL" \
      || die "cannot download GNU make package"
    python "$HERE/scripts/unpack_zst.py" "$WORK/dl/host/make.pkg.tar.zst" "$WORK/dl/host/root" \
      || die "cannot unpack GNU make package"
  fi
  if [ -x "$HOSTBIN/make.exe" ]; then export PATH="$HOSTBIN:$PATH"; fi
  export PERL5LIB="$HERE/host/perl5${PERL5LIB:+:$PERL5LIB}"
}

# --------------------------------------------------------------------------
# 2. downloads
# --------------------------------------------------------------------------
fetch() { # fetch <url> <file>
  local url="$1" file="$2" i
  [ -s "$file" ] && return 0
  for i in $(seq 1 "$DL_RETRIES"); do
    log "download $url (attempt $i)"
    if curl -fsSL --retry 3 --retry-all-errors --max-time 1800 -o "$file.part" "$url" ; then
      mv "$file.part" "$file"; return 0
    fi
    sleep 3
  done
  rm -f "$file.part"
  die "download failed: $url"
}

# GitHub over HTTPS drops connections often enough that every clone gets a loop.
git_retry() {
  local i
  for i in $(seq 1 "$GIT_RETRIES"); do
    if "$@"; then return 0; fi
    log "git command failed (attempt $i/$GIT_RETRIES), retrying"
    sleep 5
  done
  return 1
}

stage_deps() {
  log "== stage: deps"

  if [ ! -s "$WORK/dl/$BOOST_ARCHIVE" ]; then
    fetch "$BOOST_URL" "$WORK/dl/$BOOST_ARCHIVE"
  fi
  if [ ! -d "$BOOST_PREFIX/boost" ]; then
    log "extract boost $BOOST_VERSION"
    mkdir -p "$BOOST_PREFIX"
    tar -xzf "$WORK/dl/$BOOST_ARCHIVE" -C "$BOOST_PREFIX" --strip-components=1
  fi

  if [ ! -d "$SRC/swig" ]; then
    log "clone libtorrent4j $LIBTORRENT4J_TAG"
    rm -rf "$SRC"
    git_retry git clone --depth 1 --branch "$LIBTORRENT4J_TAG" "$LIBTORRENT4J_URL" "$SRC" \
      || die "cannot clone libtorrent4j"
  fi
  if [ ! -f "$LIBTORRENT/CMakeLists.txt" ]; then
    log "init libtorrent submodule"
    git_retry git -C "$SRC" submodule update --init --depth 1 swig/deps/libtorrent \
      || die "cannot init libtorrent submodule"
  fi
  if [ "$(git -C "$LIBTORRENT" rev-parse HEAD)" != "$LIBTORRENT_SHA" ]; then
    log "checkout libtorrent $LIBTORRENT_SHA"
    git_retry git -C "$LIBTORRENT" fetch --depth 1 origin "$LIBTORRENT_SHA" || die "cannot fetch $LIBTORRENT_SHA"
    git -C "$LIBTORRENT" checkout -f "$LIBTORRENT_SHA"
  fi
  if [ ! -f "$LIBTORRENT/deps/try_signal/try_signal.cpp" ]; then
    log "init libtorrent deps/try_signal"
    git_retry git -C "$LIBTORRENT" submodule update --init --depth 1 deps/try_signal \
      || die "cannot init deps/try_signal"
  fi

  apply_patches
  build_openssl
  log "== deps ready"
}

# --------------------------------------------------------------------------
# 3. patches
# --------------------------------------------------------------------------
apply_patches() {
  local p="$HERE/patches/0001-export-filenames-stub.patch"
  if git -C "$SRC" apply --check "$p" 2>/dev/null; then
    log "apply $(basename "$p")"
    git -C "$SRC" apply "$p"
  elif git -C "$SRC" apply --reverse --check "$p" 2>/dev/null; then
    log "patch already applied"
  else
    die "patch does not apply cleanly: $p"
  fi
}

# --------------------------------------------------------------------------
# 4. OpenSSL (static, android arm64)
# --------------------------------------------------------------------------
build_openssl() {
  if [ -s "$OSSL_PREFIX/lib/libcrypto.a" ] && [ -s "$OSSL_PREFIX/lib/libssl.a" ]; then
    log "openssl $OPENSSL_VERSION already built"
    return 0
  fi
  log "== stage: openssl $OPENSSL_VERSION"
  ensure_make
  if [ ! -s "$WORK/dl/openssl-$OPENSSL_VERSION.tar.gz" ]; then
    fetch "$OPENSSL_URL" "$WORK/dl/openssl-$OPENSSL_VERSION.tar.gz"
  fi
  rm -rf "$WORK/openssl-src"
  mkdir -p "$WORK/openssl-src"
  tar -xzf "$WORK/dl/openssl-$OPENSSL_VERSION.tar.gz" -C "$WORK/openssl-src" --strip-components=1

  local TC="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG"
  (
    cd "$WORK/openssl-src"
    # Without this OpenSSL writes "built on: <wall clock>" into the version
    # string, so libcrypto.a - and therefore the .so - changes every build.
    export SOURCE_DATE_EPOCH="$SOURCE_DATE_EPOCH_VALUE"
    export CC="$TC/bin/aarch64-linux-android$ANDROID_API-clang"
    export AR="$TC/bin/llvm-ar"
    export RANLIB="$TC/bin/llvm-ranlib"
    export ANDROID_NDK_ROOT="$NDK_ROOT"
    # shellcheck disable=SC2086
    perl Configure linux-aarch64 $OPENSSL_OPTS -march=armv8-a+crypto -fPIC \
      --prefix="$OSSL_PREFIX" > "$OUT/openssl-configure.log" 2>&1 \
      || { cat "$OUT/openssl-configure.log"; die "openssl Configure failed"; }
    make -j"$JOBS" > "$OUT/openssl-make.log" 2>&1 \
      || { tail -40 "$OUT/openssl-make.log"; die "openssl make failed"; }
    make install_sw >> "$OUT/openssl-make.log" 2>&1 \
      || { tail -20 "$OUT/openssl-make.log"; die "openssl install failed"; }
  )
  log "openssl ok: $(ls -l "$OSSL_PREFIX/lib/libssl.a" "$OSSL_PREFIX/lib/libcrypto.a" | tr '\n' ' ')"
}

# --------------------------------------------------------------------------
# 5. libtorrent4j native build
# --------------------------------------------------------------------------
stage_native() {
  log "== stage: native"
  [ -d "$LIBTORRENT" ] || die "deps stage has not run"
  [ -s "$OSSL_PREFIX/lib/libcrypto.a" ] || die "openssl not built"
  rm -rf "$BUILD_DIR"
  local SRC_M BUILD_M OSSL_M BOOST_M TC prefix_maps
  SRC_M="$(path_of "$SRC")"
  BUILD_M="$(path_of "$BUILD_DIR")"
  OSSL_M="$(path_of "$OSSL_PREFIX")"
  BOOST_M="$(path_of "$BOOST_PREFIX")"
  TC="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG"

  # -ffile-prefix-map turns absolute build paths into stable ones, which is what
  # makes the .so independent of where the tree lives. NO_PREFIX_MAP=1 is a
  # diagnostic switch: it reproduces the "same source, different hash" failure
  # mode described in README.md ("noise sources").
  if [ "@{NO_PREFIX_MAP:-0}" = "1" ]; then
    prefix_maps=""
    log "NO_PREFIX_MAP=1: building without path normalisation"
  else
    prefix_maps="-ffile-prefix-map=$SRC_M=/src -ffile-prefix-map=$BUILD_M=/build"
    prefix_maps="$prefix_maps -ffile-prefix-map=$OSSL_M=/opt/openssl -ffile-prefix-map=$BOOST_M=/opt/boost"
  fi

  log "cmake configure"
  cmake -S "$SRC/swig" -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_ROOT/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ANDROID_ABI" \
    -DANDROID_PLATFORM="android-$ANDROID_API" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_CXX_FLAGS="$prefix_maps" \
    -DCMAKE_C_FLAGS="$prefix_maps" \
    -DBOOST_ROOT="$BOOST_M" \
    -DBoost_NO_BOOST_CMAKE=ON \
    -DBoost_INCLUDE_DIR="$BOOST_M" \
    -DOPENSSL_ROOT_DIR="$OSSL_M" \
    -DOPENSSL_USE_STATIC_LIBS=ON \
    -DOPENSSL_INCLUDE_DIR="$OSSL_M/include" \
    -DOPENSSL_SSL_LIBRARY="$OSSL_M/lib/libssl.a" \
    -DOPENSSL_CRYPTO_LIBRARY="$OSSL_M/lib/libcrypto.a" \
    -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH \
    -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH \
    -Dwebtorrent=OFF -DUSE_JUICE=OFF -Ddeprecated-functions=ON \
    -Dlogging=ON -Dbuild_tests=OFF -Dpython-bindings=OFF \
    > "$OUT/cmake-configure.log" 2>&1 \
    || { tail -60 "$OUT/cmake-configure.log"; die "cmake configure failed"; }

  log "cmake build (-j$JOBS)"
  cmake --build "$BUILD_DIR" -j "$JOBS" --target torrent4j \
    > "$OUT/cmake-build.log" 2>&1 \
    || { tail -80 "$OUT/cmake-build.log"; die "native build failed"; }

  [ -f "$BUILD_DIR/libtorrent4j.so" ] || die "libtorrent4j.so not produced"

  local STRIP="$TC/bin/llvm-strip" OBJCOPY="$TC/bin/llvm-objcopy"
  "$OBJCOPY" --only-keep-debug "$BUILD_DIR/libtorrent4j.so" "$OUT/libtorrent4j.so.debug"
  "$STRIP" --strip-unneeded -x "$BUILD_DIR/libtorrent4j.so" -o "$OUT/libtorrent4j.so"
  log "artifacts: $(stat -c%s "$OUT/libtorrent4j.so") bytes stripped"
}

# --------------------------------------------------------------------------
# 6. package + inspect
# --------------------------------------------------------------------------
stage_package() {
  log "== stage: package"
  [ -f "$OUT/libtorrent4j.so" ] || die "no .so to package"
  python "$HERE/scripts/make_jar.py" "$OUT/libtorrent4j.so" \
    "$OUT/libtorrent4j-android-arm64-$LIBTORRENT4J_VERSION.jar"

  local TC="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG"
  "$TC/bin/llvm-readelf" -d "$OUT/libtorrent4j.so" > "$OUT/readelf-dynamic.txt" 2>&1
  "$TC/bin/llvm-readelf" -h -l -S -n "$OUT/libtorrent4j.so" > "$OUT/readelf-headers.txt" 2>&1
  "$TC/bin/llvm-nm" -D --defined-only "$OUT/libtorrent4j.so" > "$OUT/nm-dynamic.txt" 2>&1 || true

  {
    echo "so size      : $(stat -c%s "$OUT/libtorrent4j.so")"
    echo "so sha256    : $(sha256sum "$OUT/libtorrent4j.so" | cut -d' ' -f1)"
    echo "SONAME       : $(sed -n 's/.*SONAME.*\[\(.*\)\]/\1/p' "$OUT/readelf-dynamic.txt" | head -1)"
    echo "NEEDED       : $(sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p' "$OUT/readelf-dynamic.txt" | tr '\n' ' ')"
    echo "RunPath/RPath: $(sed -n 's/.*\(RUNPATH\|RPATH\).*\[\(.*\)\]/\2/p' "$OUT/readelf-dynamic.txt" | tr '\n' ' ')"
    echo "BuildID      : $(sed -n 's/.*Build ID: //p' "$OUT/readelf-headers.txt" | head -1)"
    echo "Java_ symbols: $(grep -c ' Java_' "$OUT/nm-dynamic.txt" || true)"
    echo "libtorrent   : $LIBTORRENT_SHA"
    echo "libtorrent4j : $LIBTORRENT4J_TAG"
    echo "ndk          : $NDK_VERSION"
    echo "android api  : $ANDROID_API"
  } > "$OUT/build-info.txt"

  ( cd "$OUT" && sha256sum libtorrent4j.so libtorrent4j.so.debug \
      "libtorrent4j-android-arm64-$LIBTORRENT4J_VERSION.jar" > sha256.txt )
  cat "$OUT/sha256.txt"
}

if [ "$FRESH" = 1 ]; then
  log "fresh: removing cached sources and third-party builds"
  rm -rf "$WORK/src" "$WORK/boost" "$WORK/openssl" "$WORK/dl/boost_"* "$HOSTBIN"
fi

case "$STAGE" in
  deps)    ensure_make; stage_deps ;;
  native)  ensure_make; stage_native ;;
  package) stage_package ;;
  all)     ensure_make; stage_deps; stage_native; stage_package ;;
  *) die "unknown stage: $STAGE" ;;
esac
log "done: $OUT"
