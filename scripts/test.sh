#!/usr/bin/env bash
# Reproducible SDK-free test run. Requires JDK 21+ and nothing else.
# Unset MESHCORE_TAK_FIXTURES and MESHCORE_ROOT skip those checks.
# A skip is printed as "skipped" and is not part of PASSED.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/classes"
rm -rf "$OUT"
mkdir -p "$OUT"
find "$ROOT/src/main/java" "$ROOT/src/test/java" -name '*.java' | sort > "$ROOT/build/sources.txt"
javac --release 21 -encoding UTF-8 -d "$OUT" @"$ROOT/build/sources.txt"
java -cp "$OUT" dev.meshcore.atak.TestMain
"$ROOT/scripts/test-check-unsigned.sh"
