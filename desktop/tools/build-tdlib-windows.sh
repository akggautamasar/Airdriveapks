#!/usr/bin/env bash
# Build TDLib for Windows x86_64 - the native half of "back up from a PC to Telegram".
#
# tdlib/td publishes no binaries, so everything is compiled from source here: OpenSSL (static, so
# the shipped DLL needs nothing beside it), then TDLib with the JNI interface, then tdjni against the
# installed TDLib, then the generated typed Java API into a jar. The TDLib commit is the one the phone
# app pins, so both speak exactly the same protocol version and the same generated field names.
#
# Cross-compiled from Linux with mingw-w64 because it is faster and far more reproducible than
# installing MSVC. The Linux jni.h is ABI-correct for x86_64 Windows - the only difference is the
# JNICALL calling convention, and on 64-bit Windows that is the same as the SysV one.
set -uo pipefail

TDLIB_SHA="${TDLIB_SHA:-d1085f9cebc5a62379991ae1652673954f229c1f}"
OPENSSL_TAG="${OPENSSL_TAG:-OpenSSL_1_1_1w}"
ZLIB_TAG="${ZLIB_TAG:-v1.3.1}"
TRIPLE=x86_64-w64-mingw32
ROOT="$(pwd)/tdlib-win"
OUT_DIR="${OUT_DIR:-tdlib-windows-out}"
ZIP_NAME="${ZIP_NAME:-tdlib-windows-x86_64.zip}"
NPROC="$(nproc 2>/dev/null || echo 2)"

log() { echo "[tdlib-win] $*"; }
die() { echo "FAILED: $*"; echo "::error::stopped while building: $*"; exit 1; }

fetch() { # fetch <url> <ref> <dir>
  local url="$1" ref="$2" dir="$3"
  mkdir -p "$dir" || die "cannot create $dir"
  ( cd "$dir" \
    && git init -q . >/dev/null 2>&1 \
    && git remote add origin "$url" >/dev/null 2>&1 \
    && git fetch -q --depth 1 origin "$ref" \
    && git checkout -q FETCH_HEAD ) || die "could not fetch $url at $ref"
}

mkdir -p "$ROOT"
cd "$ROOT" || die "cannot enter $ROOT"

if [ -n "${JAVA_HOME:-}" ]; then
  JAVA_HOME_DIR="$JAVA_HOME"
else
  JAVAC_BIN="$(command -v javac 2>/dev/null || true)"
  [ -n "$JAVAC_BIN" ] || die "no javac on PATH and JAVA_HOME is not set"
  JAVA_HOME_DIR="$(dirname "$(dirname "$JAVAC_BIN")")"
fi
[ -d "$JAVA_HOME_DIR/include" ] || die "no JDK include directory at $JAVA_HOME_DIR"
JNI_INC="$JAVA_HOME_DIR/include"
JNI_INC2="$JAVA_HOME_DIR/include/linux"
STATIC_LINK="-static-libgcc -static-libstdc++"
# Threads pulls in winpthread dynamically by default, which would leave tdjni.dll needing a DLL we
# are not shipping; prefer the static archive when the toolchain has one.
WINPTHREAD_STATIC="$($TRIPLE-gcc -print-file-name=libwinpthread.a 2>/dev/null || true)"
if [ -n "$WINPTHREAD_STATIC" ] && [ -f "$WINPTHREAD_STATIC" ]; then
  STATIC_LINK="$STATIC_LINK -Wl,-Bstatic -lwinpthread -Wl,-Bdynamic"
fi
KERNEL32="$($TRIPLE-gcc -print-file-name=libkernel32.a 2>/dev/null || true)"
[ -n "$KERNEL32" ] && [ -f "$KERNEL32" ] || KERNEL32="$ROOT/empty.lib"

if [ ! -f "$KERNEL32" ] || [ "$KERNEL32" = "$ROOT/empty.lib" ]; then
  # Nothing in td_jni.cpp calls the JVM itself, so the link line only needs something to point at.
  : > "$ROOT/empty.lib"
  ar rcs "$ROOT/empty.lib" 2>/dev/null || true
  KERNEL32="$ROOT/empty.lib"
fi

cat > toolchain-mingw.cmake <<TC
set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR x86_64)
set(CMAKE_C_COMPILER $TRIPLE-gcc)
set(CMAKE_CXX_COMPILER $TRIPLE-g++)
set(CMAKE_RC_COMPILER $TRIPLE-windres)
set(CMAKE_AR $TRIPLE-ar CACHE FILEPATH "Archiver")
set(CMAKE_RANLIB $TRIPLE-ranlib CACHE FILEPATH "Ranlib")
set(CMAKE_STRIP $TRIPLE-strip CACHE FILEPATH "Strip")
set(CMAKE_FIND_ROOT_PATH /usr/$TRIPLE $ROOT)
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY BOTH)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE BOTH)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)
set(CMAKE_SHARED_LINKER_FLAGS "$STATIC_LINK" CACHE STRING "" FORCE)
set(CMAKE_EXE_LINKER_FLAGS "$STATIC_LINK" CACHE STRING "")
TC

# JNI is normally located by CMake's FindJNI, which would look for a Windows JDK. Every variable it
# would set is given here instead, so nothing is searched for and nothing is guessed.
JNI_ARGS=(
  "-DJNI_FOUND=TRUE" "-DJNI_INCLUDE_DIRS=$JNI_INC;$JNI_INC2" "-DJNI_LIBRARIES=$KERNEL32"
  "-DJAVA_INCLUDE_PATH=$JNI_INC" "-DJAVA_INCLUDE_PATH2=$JNI_INC2" "-DJAVA_JVM_LIBRARY=$KERNEL32"
)

log "sources: tdlib $TDLIB_SHA, openssl $OPENSSL_TAG, zlib $ZLIB_TAG"
fetch https://github.com/openssl/openssl.git "refs/tags/$OPENSSL_TAG" openssl
fetch https://github.com/tdlib/td.git "$TDLIB_SHA" td
fetch https://github.com/madler/zlib.git "refs/tags/$ZLIB_TAG" zlib

log "building OpenSSL for $TRIPLE (static, so the result needs no companion DLLs)"
(
  cd openssl || exit 1
  ./Configure mingw64 shared --cross-compile-prefix="$TRIPLE"- no-tests no-engine no-dso \
    --libdir=. --openssldir=/ssl > "$ROOT/openssl-configure.log" 2>&1 || exit 1
  make -j"$NPROC" build_libs > "$ROOT/openssl-build.log" 2>&1 || exit 1
) || { tail -25 "$ROOT/openssl-build.log" "$ROOT/openssl-configure.log" 2>/dev/null; die "OpenSSL failed"; }
[ -f openssl/libssl.a ] && [ -f openssl/libcrypto.a ] || die "OpenSSL static libraries are missing"
log "OpenSSL done"

# TDLib's root CMakeLists does `find_package(ZLIB)` and then returns out of the build with only a
# warning if zlib is not there, and a Windows zlib is not on a Linux runner: cross-build one.
# Static only, the same reason OpenSSL is static - the shipped DLLs then need no zlib1.dll.
log "building zlib for Windows (TDLib refuses to configure without it)"
(
  cd zlib || exit 1
  make -f win32/Makefile.gcc PREFIX="$TRIPLE-" clean > /dev/null 2>&1
  make -f win32/Makefile.gcc PREFIX="$TRIPLE-" -j"$NPROC" libz.a > "$ROOT/zlib-build.log" 2>&1 || exit 1
) || { tail -25 "$ROOT/zlib-build.log" 2>/dev/null; die "zlib build failed"; }
mkdir -p zlib-win/include zlib-win/lib || die "cannot create the zlib prefix"
cp zlib/zlib.h zlib/zconf.h zlib-win/include/ || die "zlib headers are missing"
cp zlib/libz.a zlib-win/lib/ || die "libz.a is missing after its own build"
log "zlib done"

# TDLib cannot generate its own sources while cross-compiling: every generated file, including
# TdApi.java, comes out of a host build first. That is what TDLib's own example/android/build-tdlib.sh
# does for Android, and example/android is the one project in TDLib that wires JNI up for a cross
# build, so both stages configure that directory instead of the top level.
log "generating TDLib's sources and the Java API in a native stage"
cmake -S td/example/android -B td-native -DCMAKE_BUILD_TYPE=Release -DTD_GENERATE_SOURCE_FILES=ON \
  > "$ROOT/td-native-configure.log" 2>&1 \
  || { tail -40 "$ROOT/td-native-configure.log"; die "the generation stage would not configure"; }
cmake --build td-native -j "$NPROC" > "$ROOT/td-native-build.log" 2>&1 \
  || { tail -40 "$ROOT/td-native-build.log"; die "TDLib source generation failed"; }
cmake --build td-native -j "$NPROC" --target tl_generate_java > "$ROOT/td-java-gen.log" 2>&1 \
  || { tail -40 "$ROOT/td-java-gen.log"; die "the Java API generator failed"; }
TD_API_JAVA="$(find td/example/android -name 'TdApi.java' -print -quit)"
[ -n "$TD_API_JAVA" ] || die "TdApi.java was never generated"
log "TdApi.java generated at $TD_API_JAVA"

log "cross-building tdjni.dll for $TRIPLE"
cmake -S td/example/android -B td-win -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ROOT/toolchain-mingw.cmake" \
  -DCMAKE_BUILD_TYPE=Release \
  -DTD_ENABLE_JNI=ON \
  "${JNI_ARGS[@]}" \
  -DZLIB_INCLUDE_DIR="$ROOT/zlib-win/include" \
  -DZLIB_LIBRARY="$ROOT/zlib-win/lib/libz.a" \
  -DOPENSSL_ROOT_DIR="$ROOT/openssl" \
  -DOPENSSL_INCLUDE_DIR="$ROOT/openssl/include" \
  -DOPENSSL_SSL_LIBRARY="$ROOT/openssl/libssl.a" \
  -DOPENSSL_CRYPTO_LIBRARY="$ROOT/openssl/libcrypto.a" \
  > "$ROOT/tdlib-configure.log" 2>&1 \
  || { tail -40 "$ROOT/tdlib-configure.log"; die "TDLib configure failed"; }
cmake --build td-win -j "$NPROC" --target tdjni > "$ROOT/tdlib-build.log" 2>&1 \
  || { tail -40 "$ROOT/tdlib-build.log"; die "TDLib build failed"; }
TDJNI_DLL="$(find td-win -name 'tdjni.dll' -print -quit)"
[ -n "$TDJNI_DLL" ] && [ -f "$TDJNI_DLL" ] || die "tdjni.dll is missing after its build"
mkdir -p "$ROOT/$OUT_DIR/bin/x64"
cp "$TDJNI_DLL" "$ROOT/$OUT_DIR/bin/x64/"
# What the DLL actually needs at runtime decides whether the zip has to carry anything else; the
# answer is reported instead of assumed, because a missing companion DLL on somebody's PC is a
# crash on launch with no explanation.
log "runtime dependencies of tdjni.dll:"
x86_64-w64-mingw32-objdump -p "$TDJNI_DLL" | awk '/DLL Name/ {print "[tdlib-win]   needs " $3}'
if x86_64-w64-mingw32-objdump -p "$TDJNI_DLL" | grep -qi "DLL Name: libwinpthread-1.dll"; then
  PTHREAD_DLL="$(find /usr -name 'libwinpthread-1.dll' 2>/dev/null | head -1)"
  if [ -n "$PTHREAD_DLL" ]; then
    cp "$PTHREAD_DLL" "$ROOT/$OUT_DIR/bin/x64/"
    log "libwinpthread-1.dll is needed at runtime and ships beside tdjni.dll"
  else
    log "WARNING: tdjni.dll needs libwinpthread-1.dll and no copy of it was found"
  fi
fi

log "packing the Java API"
mkdir -p src/org/drinkless/tdlib classes "$ROOT/$OUT_DIR"
cp "$TD_API_JAVA" src/org/drinkless/tdlib/TdApi.java
cp td/example/java/org/drinkless/tdlib/Client.java src/org/drinkless/tdlib/Client.java
javac -J-Xmx1536m -encoding UTF-8 -d classes src/org/drinkless/tdlib/*.java \
  > "$ROOT/javac.log" 2>&1 || { tail -30 "$ROOT/javac.log"; die "the generated Java API did not compile"; }
jar --create --file "$ROOT/$OUT_DIR/tdlib.jar" -C classes . \
  || die "could not build the jar"
cp src/org/drinkless/tdlib/TdApi.java src/org/drinkless/tdlib/Client.java "$ROOT/$OUT_DIR/"

{
  echo "TDLib for Windows x86_64"
  echo "tdlib/td commit: $TDLIB_SHA  (the same one the phone app builds)"
  echo "openssl: $OPENSSL_TAG and zlib $ZLIB_TAG, both linked in"
  echo
  echo "bin/x64/tdjni.dll   - the bridge org.drinkless.tdlib.Client loads by name"
  echo "TdApi.java and Client.java - the sources, for anyone who would rather compile them"
  echo "tdlib.jar           - Client.java and the generated TdApi.java, compiled"
} > "$ROOT/$OUT_DIR/README.txt"

( cd "$ROOT/$OUT_DIR" && rm -f "$ROOT/$ZIP_NAME" && zip -q -r "$ROOT/$ZIP_NAME" . ) || die "zipping failed"
log "contents of $ZIP_NAME:"
unzip -l "$ROOT/$ZIP_NAME" | sed 's/^/[tdlib-win] /'
log "artifact at $ROOT/$ZIP_NAME"
