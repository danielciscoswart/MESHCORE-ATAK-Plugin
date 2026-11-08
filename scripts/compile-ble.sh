#!/usr/bin/env bash
# Compile the GATT client against the public Android SDK. Does not build an APK.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/ble-classes"
CACHE="$ROOT/build/android-sdk"
ZIP_URL="https://dl.google.com/android/repository/platform-35_r02.zip"
ZIP_SHA256="0988cacad01b38a18a47bac14a0695f246bc76c1b06c0eeb8eb0dc825ab0c8e0"
JAR="${ANDROID_JAR:-$CACHE/android-35/android.jar}"

if [[ ! -f "$JAR" ]]; then
  mkdir -p "$CACHE"
  tmp="$CACHE/platform-35_r02.zip"
  curl -fsSL -o "$tmp" "$ZIP_URL"
  echo "$ZIP_SHA256  $tmp" | sha256sum -c -
  unzip -qo "$tmp" -d "$CACHE"
fi

if [[ ! -f "$JAR" ]]; then
  echo "android.jar not found at $JAR" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT"
find "$ROOT/src/main/java" -name '*.java' | sort > "$ROOT/build/ble-core-sources.txt"
javac --release 21 -encoding UTF-8 -d "$OUT" @"$ROOT/build/ble-core-sources.txt"
javac --release 21 -encoding UTF-8 -classpath "$JAR:$OUT" -d "$OUT" \
  "$ROOT/android/ble/src/dev/meshcore/atak/ble/MeshcoreGattLink.java"

echo "compiled dev.meshcore.atak.ble.MeshcoreGattLink against $JAR"
echo "class files only; not an APK, not dex, ATAK SDK not used"
