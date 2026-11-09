#!/usr/bin/env bash
# Source checks for plugin startup. Does not load ATAK.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PNG="$ROOT/app/src/main/res/drawable/ic_launcher.png"
MIPMAP="$ROOT/app/src/main/res/mipmap-mdpi/ic_launcher.png"
JAVA="$ROOT/app/src/main/java/dev/meshcore/atak/host/MeshCoreAtakPlugin.java"
MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"
python3 - "$PNG" "$MIPMAP" "$JAVA" "$MANIFEST" <<'PY'
import pathlib, sys
png, mipmap, java, manifest = [pathlib.Path(p) for p in sys.argv[1:]]
magic = b"\x89PNG\r\n\x1a\n"
for path in (png, mipmap):
    if path.read_bytes()[:8] != magic:
        raise SystemExit(path.name + " is not a PNG")
if (png.parent / "ic_meshcore.xml").exists():
    raise SystemExit("vector toolbar icon is still present")
text = java.read_text()
start = text.index("public MeshCoreAtakPlugin(")
end = text.index("public void onStart()")
ctor = text[start:end]
for banned in ("new MeshcoreGattLink", "new PluginHost", "onCreate(", "restoreSequence(", "FileWriter", "FileReader", "getDrawable"):
    if banned in ctor:
        raise SystemExit("constructor still contains " + banned)
if "getDrawable" in text or "VectorDrawable" in text or "ic_meshcore" in text:
    raise SystemExit("plugin still references the vector icon path")
body = text[text.index("public void onStart()"):text.index("public void onStop()")]
for required in ("created.onCreate()", "new MeshcoreGattLink", "restoreSequence()", "catch (Throwable", "Log.e(TAG, \"onStart failed\""):
    if required not in body:
        raise SystemExit("onStart is missing " + required)
stop = text[text.index("public void onStop()"):]
if "radio != null" not in stop or "host != null" not in stop or "toolbarItem != null" not in stop:
    raise SystemExit("onStop is missing a null guard")
manifest_text = manifest.read_text()
if "@mipmap/ic_launcher" not in manifest_text or "ic_meshcore" in manifest_text:
    raise SystemExit("manifest icon is not the png mipmap")
print("ok plugin startup source")
PY
