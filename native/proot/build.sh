#!/usr/bin/env bash

set -euo pipefail
export LC_ALL=C

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
WORK=$HERE/work
OUT=$HERE/out
SRC=$WORK/src
PREFIX=$WORK/prefix
LOGS=$WORK/logs

LIBS=(libproot.so libproot_loader.so libtalloc.so libandroid-shmem.so)

PROOT_VERSION=5.1.107.92
PROOT_REPO=https://github.com/termux/proot.git
PROOT_COMMIT=7266fb3e8516535682f5a9c8f3a7e70f6506eddb # tag v5.1.107.92

SHMEM_VERSION=0.7
SHMEM_REPO=https://github.com/termux/libandroid-shmem.git
SHMEM_COMMIT=7f0bd7e25dbdd146265aff7c6a890029e374622d # tag v0.7

TALLOC_VERSION=2.5.0
TALLOC_SHA256=${TALLOC_SHA256:-912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007}
if [ -n "${TALLOC_URL:-}" ]; then
  TALLOC_URLS=("$TALLOC_URL")
else
  TALLOC_URLS=(
    "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz"
    "https://download.samba.org/pub/talloc/talloc-$TALLOC_VERSION.tar.gz"
  )
fi

# Same target as the Termux builds these binaries came from.
TARGET=aarch64-linux-android
API=24

PROOT_LOADER_DIR=/data/data/com.androsid/files/libexec/proot

# Where libandroid-shmem keeps its System V key links. The Termux build pointed at Termux's own tmp dir.
SHMEM_TMP_DIR=/data/data/com.androsid/files/proot-tmp

# Same hardening as the Termux packages: stack protector when compiling, RELRO and BIND_NOW when linking.
CFLAGS_HARDEN="-fstack-protector-strong"
LDFLAGS_HARDEN="-Wl,--as-needed -Wl,-z,relro,-z,now"

# Helpers

log() { printf '\n==> %s\n' "$*"; }
die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}
sha256_of() { sha256sum "$1" | cut -d ' ' -f 1; }

logged() {
  local name=$1
  shift
  mkdir -p "$LOGS"
  "$@" 2>&1 | tee "$LOGS/$name.log"
}

need_tools() {
  local tool
  for tool in "$@"; do
    command -v "$tool" >/dev/null || die "missing required tool: $tool"
  done
}

# PRoot's makefile runs `readelf -s loader | awk -f loader-info.awk` on arm64, and that script
# uses strtonum(), which only GNU awk has.
ensure_gnu_awk() {
  if awk 'BEGIN { exit !(strtonum("0x10") == 16) }' 2>/dev/null; then
    return 0
  fi
  command -v gawk >/dev/null || die "GNU awk is required (sudo apt-get install gawk)"
  mkdir -p "$WORK/shims"
  ln -sf "$(command -v gawk)" "$WORK/shims/awk"
  PATH=$WORK/shims:$PATH
}

setup_toolchain() {
  NDK=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}
  [ -n "$NDK" ] || die "set ANDROID_NDK_HOME to the Android NDK (r29) directory"
  TOOLS=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
  [ -x "$TOOLS/$TARGET$API-clang" ] ||
    die "$TOOLS/$TARGET$API-clang not found; is ANDROID_NDK_HOME an NDK r29 directory?"

  export CC=$TOOLS/$TARGET$API-clang
  export AR=$TOOLS/llvm-ar
  export RANLIB=$TOOLS/llvm-ranlib
  export STRIP=$TOOLS/llvm-strip
  export OBJCOPY=$TOOLS/llvm-objcopy
  export OBJDUMP=$TOOLS/llvm-objdump
  unset CFLAGS CPPFLAGS CXXFLAGS LDFLAGS
}

# Sources

fetch_git() {
  local name=$1 url=$2 commit=$3 dir=$SRC/$1
  if [ "$(git -C "$dir" rev-parse HEAD 2>/dev/null || true)" != "$commit" ]; then
    rm -rf "$dir"
    mkdir -p "$dir"
    git -C "$dir" init -q
    git -C "$dir" remote add origin "$url"
    git -C "$dir" fetch -q --depth 1 origin "$commit"
    git -C "$dir" checkout -q --detach FETCH_HEAD
  fi
  [ "$(git -C "$dir" rev-parse HEAD)" = "$commit" ] || die "$name: wrong commit checked out"
  git -C "$dir" clean -fdxq
  git -C "$dir" reset -q --hard
}

fetch_talloc() {
  local tarball=$WORK/downloads/talloc-$TALLOC_VERSION.tar.gz url got
  mkdir -p "$WORK/downloads" "$SRC"
  if [ ! -f "$tarball" ] || [ "$(sha256_of "$tarball")" != "$TALLOC_SHA256" ]; then
    rm -f "$tarball"
    for url in "${TALLOC_URLS[@]}"; do
      log "downloading $url"
      if curl -fsSL --retry 3 --connect-timeout 20 -o "$tarball.part" "$url"; then
        mv "$tarball.part" "$tarball"
        break
      fi
      rm -f "$tarball.part"
    done
    [ -f "$tarball" ] ||
      die "could not download talloc; to build offline, put the tarball at $tarball"
  fi
  got=$(sha256_of "$tarball")
  [ "$got" = "$TALLOC_SHA256" ] || {
    rm -f "$tarball"
    die "talloc tarball does not match the pinned SHA-256 (expected $TALLOC_SHA256, got $got)"
  }
  rm -rf "${SRC:?}/talloc-$TALLOC_VERSION"
  tar -xzf "$tarball" -C "$SRC"
}

# Builds

build_talloc() (
  cd "$SRC/talloc-$TALLOC_VERSION"
  # waf can't run test programs when cross-compiling, so it takes its answers from here.
  cat >cross-answers.txt <<'EOF'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
EOF
  logged talloc-configure env LDFLAGS="$LDFLAGS_HARDEN" ./configure --prefix="$PREFIX" \
    --disable-rpath --disable-python --cross-compile --cross-answers=cross-answers.txt
  logged talloc-make make -j"$JOBS"
  logged talloc-install make install
)

build_shmem() (
  cd "$SRC/libandroid-shmem"
  # shmem.c relies on Termux's patched NDK headers for open() and _PATH_TMP; the stock NDK has neither.
  local cflags="-O2 $CFLAGS_HARDEN -include fcntl.h -D_PATH_TMP=\\\"$SHMEM_TMP_DIR/\\\""
  logged shmem-make env CFLAGS="$cflags" LDFLAGS="$LDFLAGS_HARDEN" make -j"$JOBS" libandroid-shmem.so
  install -D -m 0755 libandroid-shmem.so "$PREFIX/lib/libandroid-shmem.so"
  # PRoot includes <sys/shm.h> when built with libandroid-shmem; Bionic has none of its own.
  install -D -m 0644 shm.h "$PREFIX/include/sys/shm.h"
)

build_proot() (
  cd "$SRC/proot/src"
  local cppflags="-I$PREFIX/include -DARG_MAX=131072 -DVERSION=\\\"$PROOT_VERSION\\\""
  # GIT=false: otherwise the makefile swaps VERSION for the output of `git describe`.
  # ashmem_memfd.c calls strcmp and memset without <string.h>; Termux's patched <stdio.h> includes it.
  # Since clang 16 that is an error, so make it a warning again.
  logged proot-make env CFLAGS="$CFLAGS_HARDEN -Wno-error=implicit-function-declaration" CPPFLAGS="$cppflags" \
    LDFLAGS="-L$PREFIX/lib $LDFLAGS_HARDEN" \
    make -j"$JOBS" V=1 proot \
    GIT=false \
    HAS_LOADER_32BIT= \
    PROOT_WITH_LIBANDROID_SHMEM=true \
    PROOT_UNBUNDLE_LOADER="$PROOT_LOADER_DIR"
)

# Packaging and checks

package() {
  install -D -m 0755 "$SRC/proot/src/proot" "$OUT/libproot.so"
  install -D -m 0755 "$SRC/proot/src/loader/loader" "$OUT/libproot_loader.so"
  install -D -m 0755 "$(readlink -f "$PREFIX/lib/libtalloc.so.2")" "$OUT/libtalloc.so"
  install -D -m 0755 "$PREFIX/lib/libandroid-shmem.so" "$OUT/libandroid-shmem.so"
  local f
  for f in "${LIBS[@]}"; do
    "$STRIP" --strip-unneeded "$OUT/$f"
  done
}

# check_elf FILE TYPE [LIB...]: FILE must be an AArch64 ELF of that type (DYN or EXEC), need
# every LIB listed, and nothing outside the small set of system libraries and our own.
check_elf() {
  local file=$1 type=$2 want lib header dynamic needed
  shift 2
  header=$(readelf -hW "$file")
  grep -q 'Machine:.*AArch64' <<<"$header" || die "$file is not an AArch64 binary"
  grep -q "Type:.*$type" <<<"$header" || die "$file: expected ELF type $type"
  dynamic=$(readelf -dW "$file")
  needed=$(sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' <<<"$dynamic" | sort -u)
  for want in "$@"; do
    grep -qx "$want" <<<"$needed" || die "$file does not need $want"
  done
  while read -r lib; do
    case $lib in
      '' | libc.so | libm.so | libdl.so | liblog.so | libtalloc.so.2 | libandroid-shmem.so) ;;
      *) die "$file needs unexpected library $lib" ;;
    esac
  done <<<"$needed"
  printf 'ok  %-20s %-5s needs: %s\n' "$(basename "$file")" "$type" "$(paste -sd ' ' <<<"$needed")"
}

# Everything libproot.so wants from talloc and libandroid-shmem has to be exported by our copies.
check_symbols() {
  local dir=$1 wanted exported missing
  wanted=$(readelf -Ws --dyn-syms "$dir/libproot.so" |
    awk '$7 == "UND" && $8 ~ /^(_?talloc|libandroid_)/ { print $8 }' | sort -u)
  exported=$(readelf -Ws --dyn-syms "$dir/libtalloc.so" "$dir/libandroid-shmem.so" |
    awk '$7 != "UND" { print $8 }' | sort -u)
  missing=$(comm -23 <(printf '%s\n' "$wanted") <(printf '%s\n' "$exported"))
  [ -z "$missing" ] || die "libproot.so needs symbols that nothing exports: $(paste -sd ' ' <<<"$missing")"
}

verify_outputs() {
  local dir=$1 f segments dynamic
  need_tools readelf
  for f in "${LIBS[@]}"; do
    [ -f "$dir/$f" ] || die "missing $dir/$f"
  done
  check_elf "$dir/libproot.so" DYN libtalloc.so.2 libandroid-shmem.so
  check_elf "$dir/libproot_loader.so" EXEC
  check_elf "$dir/libtalloc.so" DYN
  check_elf "$dir/libandroid-shmem.so" DYN
  segments=$(readelf -lW "$dir/libproot_loader.so")
  ! grep -q INTERP <<<"$segments" || die "the loader must be statically linked"
  dynamic=$(readelf -dW "$dir/libtalloc.so")
  grep -q 'Library soname: \[libtalloc.so.2\]' <<<"$dynamic" ||
    die "libtalloc.so should have the SONAME libtalloc.so.2"
  check_symbols "$dir"
}

archive_sources() {
  mkdir -p "$OUT/sources"
  git -C "$SRC/proot" archive --format=tar.gz --prefix="proot-$PROOT_VERSION/" \
    -o "$OUT/sources/proot-$PROOT_VERSION-source.tar.gz" HEAD
  git -C "$SRC/libandroid-shmem" archive --format=tar.gz --prefix="libandroid-shmem-$SHMEM_VERSION/" \
    -o "$OUT/sources/libandroid-shmem-$SHMEM_VERSION-source.tar.gz" HEAD
  cp "$WORK/downloads/talloc-$TALLOC_VERSION.tar.gz" "$OUT/sources/"
}

write_metadata() {
  (cd "$OUT" && sha256sum "${LIBS[@]}" >SHA256SUMS)

  {
    echo "# PRoot binaries the app build downloads (see native/proot/README.md)."
    echo "# release is the tag of the GitHub release that holds them; the rest are SHA-256 sums."
    echo "release=${RELEASE_TAG:-}"
    local f
    for f in "${LIBS[@]}"; do
      echo "$f=$(sha256_of "$OUT/$f")"
    done
  } >"$OUT/proot-binaries.properties"

  {
    echo "androsid PRoot binaries"
    echo
    echo "built:     $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "recipe:    native/proot/build.sh at $(git -C "$HERE" rev-parse HEAD 2>/dev/null || echo unknown)"
    if [ -n "${GITHUB_RUN_ID:-}" ]; then
      echo "workflow:  ${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-}/actions/runs/$GITHUB_RUN_ID"
    fi
    echo "toolchain: NDK $(sed -n 's/^Pkg.Revision *= *//p' "$NDK/source.properties"), API $API, $TARGET"
    echo "compiler:  $("$TOOLS/clang" --version | head -n 1)"
    echo
    echo "sources:"
    echo "  PRoot            $PROOT_VERSION  $PROOT_REPO @ $PROOT_COMMIT"
    echo "  libandroid-shmem $SHMEM_VERSION  $SHMEM_REPO @ $SHMEM_COMMIT"
    echo "  talloc           $TALLOC_VERSION  ${TALLOC_URLS[0]} (sha256 $TALLOC_SHA256)"
    echo
    echo "sha256:"
    sed 's/^/  /' "$OUT/SHA256SUMS"
  } >"$OUT/BUILD-INFO.txt"
}

build_all() {
  [ "$(uname -s)" = Linux ] || die "this only runs on Linux (CI uses ubuntu-24.04)"
  need_tools git curl make python3 readelf sha256sum tar install
  setup_toolchain
  ensure_gnu_awk
  JOBS=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)

  rm -rf "$OUT" "$PREFIX" "$LOGS"
  mkdir -p "$OUT" "$PREFIX" "$SRC" "$LOGS"

  log "fetching sources"
  fetch_git proot "$PROOT_REPO" "$PROOT_COMMIT"
  fetch_git libandroid-shmem "$SHMEM_REPO" "$SHMEM_COMMIT"
  fetch_talloc

  log "building talloc $TALLOC_VERSION"
  build_talloc
  log "building libandroid-shmem $SHMEM_VERSION"
  build_shmem
  log "building PRoot $PROOT_VERSION"
  build_proot

  log "packaging"
  package
  verify_outputs "$OUT"
  archive_sources
  write_metadata

  log "done: $OUT"
  cat "$OUT/BUILD-INFO.txt"
}

case ${1:-build} in
  build) build_all ;;
  verify)
    [ $# -eq 2 ] || die "usage: $0 verify DIR"
    verify_outputs "$2"
    ;;
  *) die "usage: $0 [build | verify DIR]" ;;
esac
