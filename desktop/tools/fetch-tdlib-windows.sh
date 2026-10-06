#!/usr/bin/env bash
# Put TDLib's generated Java API and its Windows JNI bridge where the desktop build looks for them.
#
# Only the jar is committed (it is what the compiler needs, and it is small); tdjni.dll is tens of
# megabytes, so it comes out of the release this repo publishes instead of into the tree. CI runs this
# before packaging; it is also the answer for anyone whose desktop/libs/tdlib.jar went missing.
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
DESKTOP="$(dirname "$HERE")"
REPO="${TDLIB_REPO:-akggautamasar/Airdriveapks}"
ASSET="tdlib-windows-x86_64.zip"
TAG="${1:-$(cat "$DESKTOP/libs/TDLIB_BUILD" 2>/dev/null || true)}"

if [ -z "$TAG" ]; then
  echo "no TDLib build to fetch: pass a release tag, or put one in desktop/libs/TDLIB_BUILD"
  echo "(the tags look like tdlib-win-1a2b3c4 and are listed at /$REPO/releases)"
  exit 1
fi

URL="https://github.com/$REPO/releases/download/$TAG/$ASSET"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# gh where it is available (it authenticates, which matters for a private repo), curl otherwise.
if command -v gh >/dev/null 2>&1 && gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  gh release download "$TAG" --repo "$REPO" -p "$ASSET" -D "$WORK" >/dev/null
else
  curl -fL "$URL" -o "$WORK/$ASSET"
fi
[ -f "$WORK/$ASSET" ] || { echo "nothing came down from $URL"; exit 1; }

mkdir -p "$DESKTOP/libs/native"
unzip -o -q "$WORK/$ASSET" -d "$WORK/unpacked"
JAR="$(find "$WORK/unpacked" -name tdlib.jar -print -quit)"
if [ -z "$JAR" ]; then
  echo "$TAG holds no tdlib.jar; the release may predate the Java bindings being built"
  exit 1
fi
cp "$JAR" "$DESKTOP/libs/tdlib.jar"
find "$WORK/unpacked" -name '*.dll' -exec cp {} "$DESKTOP/libs/native/" \;
echo "desktop/libs/tdlib.jar installed from $TAG"
echo "desktop/libs/native now holds: $(cd "$DESKTOP/libs/native" && ls | tr '\n' ' ')"
