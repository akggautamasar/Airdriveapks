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
TRIPLE=x86_64-w64-mingw32
ROOT="$(pwd)/tdlib-win"
OUT_DIR="${OUT_DIR:-tdlib-windows-out}"
ZIP_NAME="${ZIP_NAME:-tdlib-windows-x86_64.zip}"
NPROC="$(nproc 2>/dev/null || echo 2)"

log() { echo "[tdlib-win] $*"; }
die() { echo "FAILED: $*"; exit 1; }

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
STATIC_LINK="-static-libgcc -static-libgcc -static-libstdc++"
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

log "sources: tdlib $TDLIB_SHA, openssl $OPENSSL_TAG"
fetch https://github.com/openssl/openssl.git "refs/tags/$OPENSSL_TAG" openssl
fetch https://github.com/tdlib/td.git "$TDLIB_SHA" td

log "building OpenSSL for $TRIPLE (static, so the result needs no companion DLLs)"
(
  cd openssl || exit 1
  ./Configure mingw64 shared --cross-compile-prefix="$TRIPLE"- no-tests no-engine no-dso \
    --libdir=. --openssldir=/ssl > "$ROOT/openssl-configure.log" 2>&1 || exit 1
  make -j"$NPROC" build_libs > "$ROOT/openssl-build.log" 2>&1 || exit 1
) || { tail -25 "$ROOT/openssl-build.log" "$ROOT/openssl-configure.log" 2>/dev/null; die "OpenSSL failed"; }
[ -f openssl/libssl.a ] && [ -f openssl/libcrypto.a ] || die "OpenSSL static libraries are missing"
log "OpenSSL done"

log "building TDLib"
cmake -S td -B td-build -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ROOT/toolchain-mingw.cmake" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="$ROOT/install" \
  -DTD_ENABLE_JNI=ON \
  "${JNI_ARGS[@]}" \
  -DOPENSSL_ROOT_DIR="$ROOT/openssl" \
  -DOPENSSL_INCLUDE_DIR="$ROOT/openssl/include" \
  -DOPENSSL_SSL_LIBRARY="$ROOT/openssl/libssl.a" \
  -DOPENSSL_CRYPTO_LIBRARY="$ROOT/openssl/libcrypto.a" \
  > "$ROOT/tdlib-configure.log" 2>&1 \
  || { tail -40 "$ROOT/tdlib-configure.log"; die "TDLib configure failed"; }
cmake --build td-build -j "$NPROC" --target tdjson tdcore td_generate_java_api \
  > "$ROOT/tdlib-build.log" 2>&1 \
  || { tail -40 "$ROOT/tdlib-build.log"; die "TDLib build failed"; }
cmake --install td-build > "$ROOT/tdlib-install.log" 2>&1 \
  || { tail -20 "$ROOT/tdlib-install.log"; die "TDLib install failed"; }
log "TDLib built; tdjson is at $(find td-build -name 'tdjson*.dll' | head -1)"

log "building tdjni (the JNI bridge the Java bindings load)"
cmake -S td/example/java -B java-build -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ROOT/toolchain-mingw.cmake" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_PREFIX_PATH="$ROOT/install" \
  -DCMAKE_INSTALL_PREFIX="$ROOT/install" \
  "${JNI_ARGS[@]}" \
  > "$ROOT/java-configure.log" 2>&1 \
  || { tail -40 "$ROOT/java-configure.log"; die "tdjni configure failed"; }
cmake --build java-build -j "$NPROC" --target tdjni > "$ROOT/java-build.log" 2>&1 \
  || { tail -40 "$ROOT/java-build.log"; die "tdjni build failed"; }

log "packing the Java API"
mkdir -p src/org/drinkless/tdlib classes "$ROOT/$OUT_DIR"
TD_API_JAVA="$(find td/example/java -name 'TdApi.java' | head -1)"
[ -n "$TD_API_JAVA" ] || die "TdApi.java was never generated"
cp "$TD_API_JAVA" src/org/drinkless/tdlib/TdApi.java
cp td/example/java/org/drinkless/tdlib/Client.java src/org/drinkless/tdlib/Client.java
javac -J-Xmx1536m -encoding UTF-8 -d classes src/org/drinkless/tdlib/*.java \
  > "$ROOT/javac.log" 2>&1 || { tail -30 "$ROOT/javac.log"; die "the generated Java API did not compile"; }
jar --create --file "$ROOT/$OUT_DIR/tdlib.jar" -C classes . \
  || die "could not build the jar"
cp src/org/drinkless/tdlib/TdApi.java src/org/drinkless/tdlib/Client.java "$ROOT/$OUT_DIR/"

for found in "$(find td-build -name 'tdjson.dll' | head -1)" "$(find java-build -name 'tdjni.dll' | head -1)"; do
  [ -n "$found" ] && [ -f "$found" ] || die "a built DLL is missing"
  mkdir -p "$ROOT/$OUT_DIR/bin/x64"
  cp "$found" "$ROOT/$OUT_DIR/bin/x64/"
done

{
  echo "TDLib for Windows x86_64"
  echo "tdlib/td commit: $TDLIB_SHA  (the same one the phone app builds)"
  echo "openssl: $OPENSSL_TAG, linked statically"
  echo
  echo "bin/x64/tdjson.dll  - the C JSON interface"
  echo "bin/x64/tdjni.dll   - the bridge org.drinkless.tdlib.Client loads by name"
  echo "tdlib.jar           - Client.java and the generated TdApi.java, compiled"
  echo "TdApi.java, Client.java - the sources, so a build can compile against them directly"
} > "$ROOT/$OUT_DIR/README.txt"

( cd "$ROOT/$OUT_DIR" && rm -f "$ROOT/$ZIP_NAME" && zip -q -r "$ROOT/$ZIP_NAME" . ) || die "zipping failed"
log "contents of $ZIP_NAME:"
unzip -l "$ROOT/$ZIP_NAME" | sed 's/^/[tdlib-win] /'
log "artifact at $ROOT/$ZIP_NAME"
