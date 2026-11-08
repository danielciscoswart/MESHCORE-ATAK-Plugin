#!/usr/bin/env bash
# Secret-free source archive for a machine that already has the ATAK SDK.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${1:-/opt/cursor/artifacts}"
mkdir -p "$DEST"
STAGE="$(mktemp -d)"
NAME="meshcore-atak-source"
mkdir -p "$STAGE/$NAME"
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
  --exclude='*.jar' \
  --exclude='*.class' \
  -cf - . | tar -C "$STAGE/$NAME" -xf -
OUT="$DEST/${NAME}.zip"
rm -f "$OUT"
( cd "$STAGE" && zip -qr "$OUT" "$NAME" )
BYTES=$(wc -c < "$OUT")
echo "wrote $OUT ($BYTES bytes)"
if [ "$BYTES" -gt 25000000 ]; then
  echo "archive exceeds 25MB" >&2
  exit 1
fi
unzip -l "$OUT" | awk '/local.properties$|\.keystore|main\.jar|android_keystore|takrepo/ {found=1; print} END{exit found}'
echo "archive has no local.properties, keystore, or SDK jar"
rm -rf "$STAGE"
