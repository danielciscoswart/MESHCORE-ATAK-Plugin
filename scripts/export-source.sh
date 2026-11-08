#!/usr/bin/env bash
# Two secret-free archives.
# meshcore-atak-source.zip is the full tree.
# meshcore-atak-tpp.zip is the same buildable tree under the single folder
# name the TAK pipeline uses for APK names. Neither archive is an upload.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${1:-/opt/cursor/artifacts}"
mkdir -p "$DEST"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

REVISION="$(git -C "$ROOT" rev-parse HEAD)"
SUBJECT="$(git -C "$ROOT" log -1 --format=%s)"

copy_tree() {
  local name="$1"
  mkdir -p "$STAGE/$name"
  tar -C "$ROOT" \
    --exclude='.git' \
    --exclude='build' \
    --exclude='.gradle' \
    --exclude='local.properties' \
    --exclude='*.keystore' \
    --exclude='android_keystore' \
    --exclude='sdk' \
    --exclude='sdk-*' \
    --exclude='*.apk' \
    --exclude='*.class' \
    --exclude='agent-tools' \
    -cf - . | tar -C "$STAGE/$name" -xf -
  find "$STAGE/$name" -type f -name '*.jar' ! -name 'gradle-wrapper.jar' -delete
  find "$STAGE/$name" -type d -empty -delete
  # Commit id of the tree that was packed. The zip hash is not written here.
  cat >"$STAGE/$name/SOURCE_REVISION.txt" <<EOF
git-commit: ${REVISION}
subject: ${SUBJECT}
note: This file records the git revision. The archive hash is outside the zip so it does not change this file.
EOF
}

verify_tree() {
  local name="$1"
  local root="$STAGE/$name"
  test -x "$root/gradlew"
  test -f "$root/gradle/wrapper/gradle-wrapper.jar"
  test -f "$root/gradle/wrapper/gradle-wrapper.properties"
  grep -q 'gradle-8.14.3-bin.zip' "$root/gradle/wrapper/gradle-wrapper.properties"
  grep -q 'distributionSha256Sum=bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531' \
    "$root/gradle/wrapper/gradle-wrapper.properties"
  (cd "$root/gradle/wrapper" && sha256sum -c gradle-wrapper.jar.sha256)
  test -f "$root/app/build.gradle"
  test -f "$root/app/proguard-rules.pro"
  test -f "$root/app/src/main/assets/plugin.xml"
  test -f "$root/app/src/main/java/dev/meshcore/atak/host/MeshCoreAtakPlugin.java"
  test -f "$root/src/main/java/dev/meshcore/atak/engine/Engine.java"
  test -f "$root/android/ble/src/dev/meshcore/atak/ble/MeshcoreGattLink.java"
  grep -q "productFlavors" "$root/app/build.gradle"
  grep -q "civ {" "$root/app/build.gradle"
  grep -q "ext.ATAK_VERSION = '5.6.0'" "$root/app/build.gradle"
  grep -q "ext.ATAK_VERSION = '5.6.0'" "$root/build.gradle"
  test -f "$root/SOURCE_REVISION.txt"
  grep -q "^git-commit: ${REVISION}$" "$root/SOURCE_REVISION.txt"
  if grep -R -n "task assembleCivRelease" "$root/app/build.gradle" "$root/build.gradle"; then
    echo "refusing a dummy assembleCivRelease task" >&2
    exit 1
  fi
  if find "$root" \( -name 'local.properties' -o -name '*.keystore' -o -name 'main.jar' -o -name '*.apk' -o -name 'android_keystore' \) | grep -q .; then
    echo "archive contains a forbidden file" >&2
    exit 1
  fi
  local tops
  tops="$(find "$STAGE/$name" -mindepth 1 -maxdepth 1 | wc -l)"
  if [[ "$tops" -lt 5 ]]; then
    echo "tree $name looks empty" >&2
    exit 1
  fi
}

write_zip() {
  local name="$1"
  local out="$DEST/${name}.zip"
  rm -f "$out"
  (cd "$STAGE" && zip -qr "$out" "$name")
  local count
  count="$(unzip -Z1 "$out" | awk -F/ 'NF>0 {print $1}' | sort -u | wc -l)"
  if [[ "$count" -ne 1 ]]; then
    echo "$out has $count top-level entries" >&2
    exit 1
  fi
  local bytes
  bytes="$(wc -c < "$out")"
  echo "wrote $out ($bytes bytes, root $name)"
  if [[ "$bytes" -gt 25000000 ]]; then
    echo "archive exceeds 25MB" >&2
    exit 1
  fi
  sha256sum "$out" | tee "$out.sha256"
  unzip -p "$out" "$name/SOURCE_REVISION.txt"
}

copy_tree meshcore-atak-source
verify_tree meshcore-atak-source
copy_tree meshcore-atak-plugin
verify_tree meshcore-atak-plugin
write_zip meshcore-atak-source
write_zip meshcore-atak-plugin
echo "submission zip root is meshcore-atak-plugin; source zip root is meshcore-atak-source"
echo "both contain the same build files and neither is an upload"
