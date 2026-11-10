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
for banned in ("new MeshcoreGattLink", "new PluginHost", "onCreate(", "restoreSequence(", "FileWriter", "FileReader", "getDrawable", "new PluginState", "getSharedPreferences"):
    if banned in ctor:
        raise SystemExit("constructor still contains " + banned)
if "getDrawable" in text or "VectorDrawable" in text or "ic_meshcore" in text:
    raise SystemExit("plugin still references the vector icon path")
onstart = text[text.index("public void onStart()"):text.index("public void onStop()")]
for required in ("created.onCreate()", "new MeshcoreGattLink", "restoreSequence()", "catch (Throwable", "Log.e(TAG, \"onStart failed\""):
    if required not in onstart:
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
    ("RadioChoice.plan(", "start() follows the saved-radio plan (E1)"),
):
    if needed not in start_body:
        raise SystemExit("MeshcoreGattLink: " + why)
# E1: no automatic radio choice. The v7.1 shortcuts must be gone.
for gone in ("earlyConnect", "bestMatch", "firstMatchMs", "bondedMeshCore("):
    if gone in link.replace("private static List<BluetoothDevice> bondedMeshCore(", "", 1):
        raise SystemExit("MeshcoreGattLink: automatic radio pick is back: " + gone)
if "onChooseRadio(" not in link or "chooseRadio(" not in link or "cancelChoose(" not in link:
    raise SystemExit("MeshcoreGattLink: the user must choose the radio (E1)")
# E4: the next command follows the previous reply; the host tick is only a fallback.
for needed in ("scheduleStep", "writeInFlight", "nextActionDelayMs"):
    if needed not in link:
        raise SystemExit("MeshcoreGattLink: missing E4 piece " + needed)

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
if "LinkRules.writeFailureEndsAttempt(machine.state())" not in cb or "writeFailure.begin(" not in cb:
    raise SystemExit("MeshcoreGattLink: a failed write must end the attempt (C2/E5)")
if '"write failed"' not in link and "resolveByTimeout" not in link:
    raise SystemExit("MeshcoreGattLink: a failed write must end with its status (C2/E5)")
if "resolveByDisconnect" not in link or "resolveByTimeout" not in link:
    raise SystemExit("MeshcoreGattLink: failed write waits for the disconnect status (E5)")
conn = body(link, "private void handleConnectionState(", "private void handleServices(")
if "createBond()" not in conn or "getBondState()" not in conn[conn.index("createBond()"):] or "LinkRules.bondRefusalIsFatal(again)" not in conn:
    raise SystemExit("MeshcoreGattLink: createBond false must re-read the bond state (C4)")
mtu = body(link, "private void handleMtu(", "private void handleDescriptorWrite(")
if "LinkRules.mtuProblem(negotiated)" not in mtu or "failLink(now, problem" not in mtu:
    raise SystemExit("MeshcoreGattLink: MTU 23 must fail visibly (C5)")
if "session.handshakeProgress()" not in link:
    raise SystemExit("MeshcoreGattLink: status line must show handshake progress (C6)")
# v7.2 source rules (E2, E3, E9).
if "getFilesDir" in text or "selectFirstTeamChannel" in text or "selectFirstTeamChannel" in link:
    raise SystemExit("plugin must not use getFilesDir (E3) or pick a channel itself (E2)")
if text.count("restoreSequence()") < 1 or "restoreSequence()" not in onstart:
    raise SystemExit("onStart must restore the sequence (E3)")
if 'BUILD_TAG = "v7.2"' not in text:
    raise SystemExit("on-screen tag must be v7.2")
root = java.parents[8]
engine_src = (root / "src/main/java/dev/meshcore/atak/engine/Engine.java").read_text()
if "selectFirstTeamChannel" in engine_src:
    raise SystemExit("Engine must not auto-select a channel (E2)")
# Opcode 3 (native channel text) is built in exactly one place, the opt-in native send path.
users = []
for path in sorted(list((root / "src/main").rglob("*.java")) + list((root / "app/src").rglob("*.java")) + list((root / "android").rglob("*.java"))):
    t = path.read_text()
    if "sendChannelText(" in t and path.name != "Companion.java":
        users.append(path.name)
if users != ["Engine.java"]:
    raise SystemExit("sendChannelText must be called only from Engine: " + str(users))
if engine_src.count("Companion.sendChannelText(") != 1:
    raise SystemExit("Engine must build native text in exactly one place")
native = engine_src[engine_src.index("public Send sendNativeChannelText("):engine_src.index("private Send refuseUnsafeChannel(")]
for needed in ("refuseUnsafeChannel()", "maxNativeTextBytes", "tooLong"):
    if needed not in native:
        raise SystemExit("native send path is missing " + needed)
if "boolean pluginOnlyChat = true;" not in engine_src:
    raise SystemExit("the Plugin users only switch must default to ON")
# v7.2 review fixes (F1 to F3).
if "governor = null" in engine_src or "new Governor(" in engine_src.replace("governor = new Governor(counters, DEFAULT_FREQ_KHZ, nowMs);", "").replace("governor = new Governor(counters, info.freqKhz, nowMs);", ""):
    raise SystemExit("F1: Engine must keep one Governor across links (no governor = null, no extra new Governor)")
if "governor.retune(info.freqKhz, nowMs)" not in engine_src or "governor.clearQueues()" not in engine_src or "self = null;" not in engine_src:
    raise SystemExit("F1: link up must clear queues and self; SELF_INFO must retune the bucket")
session_src = (root / "src/main/java/dev/meshcore/atak/transport/RadioSession.java").read_text()
if "table_full_retry" in session_src or "tableFullRetries < " in session_src:
    raise SystemExit("F2: sends (3 and 62) must never be retried after an error")
reasons = (root / "src/main/java/dev/meshcore/atak/engine/SendReasons.java").read_text()
if "No answer from radio: it may have been sent. Check before resending." not in reasons:
    raise SystemExit("F2: the no-answer sentence is missing")
if "Failed: radio did not answer" in reasons + text + engine_src:
    raise SystemExit("F2: a timeout must not say Failed")
layout_text = (root / "app/src/main/res/layout/meshcore_pane.xml").read_text()
import re
watch = re.search(r'<CheckBox\s+android:id="@\+id/meshcore_watch"(.*?)/>', layout_text, re.S)
if not watch:
    raise SystemExit("F3: watch checkbox not found in the layout")
if 'android:visibility="gone"' not in watch.group(1) or 'android:checked="false"' not in watch.group(1):
    raise SystemExit("F3: the GeoChat copy checkbox must be visibility gone and unchecked by default")
if "android:maxLines=\"2\"" not in layout_text[layout_text.index("meshcore_status_line"):][:400]:
    raise SystemExit("F2: the status line must allow two lines for the no-answer text")
if "GEOCHAT_COPY_AVAILABLE = false" not in text or "private boolean watchGeoChat = false;" not in text:
    raise SystemExit("F3: GeoChat copy must be off by default and not available")
if "watch.setChecked(true)" in text or "watchBox.setChecked(true)" in text or "watch.setVisibility(View.VISIBLE)" in text:
    raise SystemExit("F3: the GeoChat copy checkbox must never be shown or ticked by code")
set_watch = text[text.index("private void setWatchGeoChat("):][:260]
if "!GEOCHAT_COPY_AVAILABLE" not in set_watch:
    raise SystemExit("F3: setWatchGeoChat must refuse to turn the copy on")
manifest_text = manifest.read_text()
if "@mipmap/ic_launcher" not in manifest_text or "ic_meshcore" in manifest_text:
    raise SystemExit("manifest icon is not the png mipmap")
print("ok plugin startup source")
PY
