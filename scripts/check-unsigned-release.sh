#!/usr/bin/env bash
# Read an already built release APK. Confirm it is unsigned, and that the
# manifest and plugin entry survived R8. Does not sign, install, or assemble.
#
# Requires aapt2 from the public Android SDK build-tools. The binary manifest
# is not searched as plain text. If aapt2 is missing, this exits 1.
set -euo pipefail

APK="${1:-app/build/outputs/apk/civ/release/app-civ-release-unsigned.apk}"
PACKAGE_NAME="dev.meshcore.atak.plugin"
PLUGIN_CLASS="dev.meshcore.atak.host.MeshCoreAtakPlugin"
PLUGIN_API="com.atakmap.app@5.6.0.CIV"

if [[ ! -f "$APK" ]]; then
  echo "unsigned apk not found: $APK" >&2
  exit 1
fi

find_aapt2() {
  if [[ -n "${AAPT2:-}" ]]; then
    if [[ -x "$AAPT2" ]]; then
      printf '%s\n' "$AAPT2"
      return 0
    fi
    echo "AAPT2 is set but is not an executable file: $AAPT2" >&2
    return 1
  fi
  local found home
  if found="$(command -v aapt2 2>/dev/null)"; then
    printf '%s\n' "$found"
    return 0
  fi
  home="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [[ -n "$home" && -d "$home/build-tools" ]]; then
    found="$(find "$home/build-tools" -type f -name aapt2 | sort | tail -n 1)"
    if [[ -n "$found" && -x "$found" ]]; then
      printf '%s\n' "$found"
      return 0
    fi
  fi
  echo "aapt2 not found. Install Android SDK build-tools and set ANDROID_HOME, or set AAPT2 to that binary. The manifest check is not skipped." >&2
  return 1
}

AAPT2_BIN="$(find_aapt2)"

if unzip -l "$APK" | awk '/META-INF\/.*\.(SF|RSA|DSA|EC)$/ {found=1} END{exit found+0}'; then
  :
else
  echo "apk contains a signature block; this check expects an unsigned release" >&2
  exit 1
fi

badging="$(mktemp)"
xmltree="$(mktemp)"
trap 'rm -f "$badging" "$xmltree"' EXIT

if ! "$AAPT2_BIN" dump badging "$APK" >"$badging" 2>"$badging.err"; then
  echo "aapt2 dump badging failed for $APK" >&2
  cat "$badging.err" >&2
  exit 1
fi
if ! "$AAPT2_BIN" dump xmltree "$APK" --file AndroidManifest.xml >"$xmltree" 2>"$xmltree.err"; then
  echo "aapt2 dump xmltree failed for $APK. The binary manifest is not grepped as text." >&2
  cat "$xmltree.err" >&2
  exit 1
fi

require_line() {
  local file="$1"
  local pattern="$2"
  local label="$3"
  if ! grep -q "$pattern" "$file"; then
    echo "aapt2 output missing ${label}: ${pattern}" >&2
    exit 1
  fi
}

require_line "$badging" "package: name='${PACKAGE_NAME}'" "package name"
require_line "$badging" "versionCode='12'" "versionCode"
require_line "$badging" "versionName='0.7.5'" "versionName"
require_line "$badging" "minSdkVersion:'21'" "minSdkVersion"
require_line "$badging" "targetSdkVersion:'36'" "targetSdkVersion"
require_line "$xmltree" "${PACKAGE_NAME}" "manifest package in xmltree"
require_line "$xmltree" "plugin-api" "plugin-api metadata name"
require_line "$xmltree" "${PLUGIN_API}" "plugin-api value"
require_line "$xmltree" "com.atakmap.app.component" "discovery activity"

tmp="$(mktemp -d)"
unzip -qo "$APK" 'classes*.dex' 'assets/plugin.xml' -d "$tmp"
if ! grep -a -q 'dev/meshcore/atak/host/MeshCoreAtakPlugin' "$tmp"/classes*.dex \
  && ! grep -a -q "$PLUGIN_CLASS" "$tmp"/classes*.dex; then
  echo "R8 output is missing ${PLUGIN_CLASS}" >&2
  exit 1
fi
if ! grep -q "impl=\"${PLUGIN_CLASS}\"" "$tmp/assets/plugin.xml"; then
  echo "assets/plugin.xml does not name ${PLUGIN_CLASS}" >&2
  exit 1
fi
rm -rf "$tmp"

echo "unsigned release: ${PACKAGE_NAME} versionName 0.7.5 keeps ${PLUGIN_CLASS}"
echo "aapt2=${AAPT2_BIN}"
