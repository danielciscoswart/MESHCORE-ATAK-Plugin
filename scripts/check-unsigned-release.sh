#!/usr/bin/env bash
# After a local :app:assembleRelease, confirm the APK is unsigned and the
# reflective plugin class name survived R8. Does not sign or install.
set -euo pipefail
APK="${1:-atak-plugin/app/build/outputs/apk/release/app-release-unsigned.apk}"
if [[ ! -f "$APK" ]]; then
  echo "unsigned apk not found: $APK" >&2
  exit 1
fi
if unzip -l "$APK" | awk '/META-INF\/.*\.(SF|RSA|DSA|EC)$/ {found=1} END{exit found+0}'; then
  :
else
  echo "apk contains a signature block; this check expects an unsigned release" >&2
  exit 1
fi
tmp="$(mktemp -d)"
unzip -qo "$APK" 'classes*.dex' 'AndroidManifest.xml' -d "$tmp"
if ! grep -a -q 'dev/meshcore/atak/host/MeshCoreAtakPlugin' "$tmp"/classes*.dex; then
  if ! grep -a -q 'dev.meshcore.atak.host.MeshCoreAtakPlugin' "$tmp"/classes*.dex; then
    echo "R8 output is missing dev.meshcore.atak.host.MeshCoreAtakPlugin" >&2
    exit 1
  fi
fi
if ! grep -a -q 'dev.meshcore.atak.plugin' "$tmp/AndroidManifest.xml"; then
  echo "manifest package string dev.meshcore.atak.plugin not found" >&2
  exit 1
fi
echo "unsigned release keeps dev.meshcore.atak.plugin and MeshCoreAtakPlugin"
rm -rf "$tmp"
