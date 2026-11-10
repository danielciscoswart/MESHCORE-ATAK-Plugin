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
# v7 source rules for the Android BLE adapter (R4, R5, B2, B5). No phone: these are source checks.
link = (java.parents[8] / "android/ble/src/dev/meshcore/atak/ble/MeshcoreGattLink.java").read_text()
start_body = link[link.index("public StartResult start("):link.index("public void stop()")]
for needed, why in (
    ("machine.canStart()", "start() must refuse to touch a link in progress (B1)"),
    ("session.setWritesEnabled(true)", "start() must re-enable writes (B2)"),
    ("getBondedDevices", "bonded device first (R4)"),
):
    if needed not in (start_body + link[link.index("private static List<BluetoothDevice> bondedMeshCore("):][:600]):
        raise SystemExit("MeshcoreGattLink: " + why)
for needed, why in (
    ("PHY_LE_1M_MASK, mainHandler", "connectGatt with the main handler (B5)"),
    ("session.peekWrite()", "frame leaves the queue only after the write is accepted (B3)"),
    ("session.writeRefused()", "refused write is re-queued (B3)"),
    ("createBond()", "connect first, pair second (R4)"),
):
    if needed not in link:
        raise SystemExit("MeshcoreGattLink: " + why)
if "pollWrite()" in link[:link.index("session.peekWrite()")]:
    raise SystemExit("MeshcoreGattLink: pollWrite before peekWrite")
# v7.1 source rules (C1 to C6). The Android callbacks cannot run on a JVM, so check the wiring.
def body(text, start, end):
    return text[text.index(start):text.index(end, text.index(start))]
connect_to = body(link, "private void connectTo(", "private boolean heldByAnotherApp(")
if "heldByAnotherApp(device)" not in connect_to or "LinkRules.HELD_REASON" not in connect_to:
    raise SystemExit("MeshcoreGattLink: connectTo must refuse a radio held by another app (C1)")
held = body(link, "private boolean heldByAnotherApp(", "private void enterDiscover(")
if "getConnectedDevices(BluetoothProfile.GATT)" not in held or "LinkRules.heldByAnotherApp" not in held:
    raise SystemExit("MeshcoreGattLink: held check must read getConnectedDevices(GATT) (C1)")
cb = link[link.index("public void onCharacteristicWrite("):]
cb = cb[:cb.index("private static byte[] copyOf")]
if "LinkRules.writeFailureEndsAttempt(machine.state())" not in cb or '"write failed", status' not in cb:
    raise SystemExit("MeshcoreGattLink: a failed write must end the attempt with its status (C2)")
conn = body(link, "private void handleConnectionState(", "private void handleServices(")
if "createBond()" not in conn or "getBondState()" not in conn[conn.index("createBond()"):] or "LinkRules.bondRefusalIsFatal(again)" not in conn:
    raise SystemExit("MeshcoreGattLink: createBond false must re-read the bond state (C4)")
mtu = body(link, "private void handleMtu(", "private void handleDescriptorWrite(")
if "LinkRules.mtuProblem(negotiated)" not in mtu or "failLink(now, problem" not in mtu:
    raise SystemExit("MeshcoreGattLink: MTU 23 must fail visibly (C5)")
if "session.handshakeProgress()" not in link:
    raise SystemExit("MeshcoreGattLink: status line must show handshake progress (C6)")
manifest_text = manifest.read_text()
if "@mipmap/ic_launcher" not in manifest_text or "ic_meshcore" in manifest_text:
    raise SystemExit("manifest icon is not the png mipmap")
print("ok plugin startup source")
PY
