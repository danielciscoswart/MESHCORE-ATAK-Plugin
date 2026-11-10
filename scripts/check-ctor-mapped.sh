#!/usr/bin/env bash
# Usage: scripts/check-ctor-mapped.sh <apk> [dexdump]
# Checks that the plugin entry constructor in a pipeline-built APK takes the
# OBFUSCATED ATAK service controller type, the way a release ATAK expects.
# A local SDK build has an empty mapping, so it fails this check by design. Run
# it on the APK the pipeline returns, never on a local build.
set -euo pipefail
apk="${1:?apk path}"
dexdump="${2:-${DEXDUMP:-dexdump}}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
unzip -q -o "$apk" classes.dex assets/plugin.xml -d "$tmp"
impl="$(sed -n 's/.*impl="\([^"]*\)".*/\1/p' "$tmp/assets/plugin.xml" | head -1)"
[ -n "$impl" ] || { echo "FAIL: no impl in assets/plugin.xml"; exit 1; }
line="$("$dexdump" -d "$tmp/classes.dex" | grep -F "] ${impl}.<init>:(" | head -1 || true)"
[ -n "$line" ] || { echo "FAIL: ${impl}.<init> not found in dex"; exit 1; }
echo "$line" | sed 's/^.*|//'
case "$line" in
  *'(Lgov/tak/api/plugin/IServiceController;)V'*)
    echo "FAIL: constructor takes unmapped gov.tak.api.plugin.IServiceController."
    echo "      Release ATAK has no such class, so Constructor lookup fails and the plugin does not load."
    echo "      Check app/proguard-rules.pro has: -applymapping <atak.proguard.mapping>"
    exit 1 ;;
  *'(Lgov/tak/api/plugin/'*';)V'*)
    echo "PASS: constructor parameter type is mapped (obfuscated) ATAK type." ;;
  *)
    echo "WARN: unexpected constructor signature"; exit 2 ;;
esac
