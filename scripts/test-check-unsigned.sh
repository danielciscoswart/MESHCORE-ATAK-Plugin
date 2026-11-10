#!/usr/bin/env bash
# Fail-closed checks for scripts/check-unsigned-release.sh. Uses a fake aapt2
# so a missing Android SDK cannot be mistaken for a pass.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CHECK="$ROOT/scripts/check-unsigned-release.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fail_if_zero() {
  local name="$1"
  shift
  set +e
  "$@" >"$WORK/out.txt" 2>"$WORK/err.txt"
  local code=$?
  set -e
  if [[ "$code" -eq 0 ]]; then
    echo "FALSE PASS: $name" >&2
    cat "$WORK/out.txt" "$WORK/err.txt" >&2
    exit 1
  fi
  echo "rejected $name (exit $code)"
}

cat >"$WORK/aapt2" <<'EOF'
#!/usr/bin/env bash
mode=""
apk=""
for arg in "$@"; do
  case "$arg" in
    badging) mode=badging ;;
    xmltree) mode=xmltree ;;
    --file) ;;
    AndroidManifest.xml) ;;
    dump) ;;
    *) apk="$arg" ;;
  esac
done
if [[ ! -f "$apk" ]]; then
  echo "fake aapt2 missing apk $apk" >&2
  exit 2
fi
if [[ "$mode" == badging ]]; then
  if [[ -f "$apk.badging" ]]; then
    cat "$apk.badging"
    exit 0
  fi
  echo "package: name='dev.meshcore.atak.plugin' versionCode='11' versionName='0.7.4'"
  echo "minSdkVersion:'21'"
  echo "targetSdkVersion:'36'"
  exit 0
fi
if [[ "$mode" == xmltree ]]; then
  echo 'A: package="dev.meshcore.atak.plugin"'
  echo 'A: android:name="plugin-api"'
  echo 'A: android:value="com.atakmap.app@5.6.0.CIV"'
  echo 'A: android:name="com.atakmap.app.component"'
  exit 0
fi
echo "fake aapt2 unexpected args: $*" >&2
exit 2
EOF
chmod +x "$WORK/aapt2"

make_apk() {
  local dir="$1"
  mkdir -p "$dir/assets"
  printf 'dev/meshcore/atak/host/MeshCoreAtakPlugin' >"$dir/classes.dex"
  printf '%s\n' '<plugin><extension type="gov.tak.api.plugin.IPlugin" impl="dev.meshcore.atak.host.MeshCoreAtakPlugin" singleton="true" /></plugin>' >"$dir/assets/plugin.xml"
  # A binary-looking manifest with no plaintext package, so a text grep would miss it.
  printf 'AXML\x00\x00\x00binary-manifest' >"$dir/AndroidManifest.xml"
  (cd "$dir" && zip -qr "$dir.apk" classes.dex assets AndroidManifest.xml)
}

fail_if_zero "missing apk" "$CHECK" "$WORK/missing.apk"

export AAPT2="$WORK/aapt2"
make_apk "$WORK/good"
mkdir -p "$WORK/signed/META-INF"
printf 'x' >"$WORK/signed/META-INF/CERT.SF"
make_apk "$WORK/signed"
(cd "$WORK/signed" && zip -q "$WORK/signed.apk" META-INF/CERT.SF)
fail_if_zero "signature block" env -u ANDROID_HOME -u ANDROID_SDK_ROOT AAPT2="$WORK/aapt2" "$CHECK" "$WORK/signed.apk"

printf "%s\n" "package: name='com.example.other' versionCode='11' versionName='0.7.4'" "minSdkVersion:'21'" "targetSdkVersion:'36'" >"$WORK/good.apk.badging"
fail_if_zero "wrong package" env -u ANDROID_HOME -u ANDROID_SDK_ROOT AAPT2="$WORK/aapt2" "$CHECK" "$WORK/good.apk"
rm -f "$WORK/good.apk.badging"

printf 'not-the-plugin' >"$WORK/good/classes.dex"
(cd "$WORK/good" && zip -qr "$WORK/nodex.apk" classes.dex assets AndroidManifest.xml)
fail_if_zero "missing dex class" env -u ANDROID_HOME -u ANDROID_SDK_ROOT AAPT2="$WORK/aapt2" "$CHECK" "$WORK/nodex.apk"

printf 'dev/meshcore/atak/host/MeshCoreAtakPlugin' >"$WORK/good/classes.dex"
printf '%s\n' '<plugin><extension impl="com.example.Other" /></plugin>' >"$WORK/good/assets/plugin.xml"
(cd "$WORK/good" && zip -qr "$WORK/noxml.apk" classes.dex assets AndroidManifest.xml)
fail_if_zero "wrong plugin.xml" env -u ANDROID_HOME -u ANDROID_SDK_ROOT AAPT2="$WORK/aapt2" "$CHECK" "$WORK/noxml.apk"

# Restore a valid plugin.xml and dex for the positive case.
printf 'dev/meshcore/atak/host/MeshCoreAtakPlugin' >"$WORK/good/classes.dex"
printf '%s\n' '<plugin><extension type="gov.tak.api.plugin.IPlugin" impl="dev.meshcore.atak.host.MeshCoreAtakPlugin" singleton="true" /></plugin>' >"$WORK/good/assets/plugin.xml"
(cd "$WORK/good" && zip -qr "$WORK/good.apk" classes.dex assets AndroidManifest.xml)
env -u ANDROID_HOME -u ANDROID_SDK_ROOT AAPT2="$WORK/aapt2" "$CHECK" "$WORK/good.apk"

fail_if_zero "aapt2 missing" env -u AAPT2 -u ANDROID_HOME -u ANDROID_SDK_ROOT PATH="/usr/bin:/bin" "$CHECK" "$WORK/good.apk"

echo "ok unsigned-release checker"
