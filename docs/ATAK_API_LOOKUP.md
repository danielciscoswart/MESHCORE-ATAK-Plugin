# ATAK API lookup for the local 5.6.0.26 SDK

This environment did not have the ATAK-CIV SDK. Nothing under `atak-plugin/` was compiled against it. The parent compile is the check. If a symbol below is missing or has a different signature in the 5.6.0.26 javadoc, stop and change only that call. Do not add a stub jar.

Archive filename observed on the parent box: `5.6.0.26`. This repository does not assert a commit or hash inside that archive. A public third-party note mentions an internal changelog line `5.6.0.25` / `SDK238484e`. That was not in the SDK here, so it is not confirmed.

## Used, and where the signature was read

Published ATAK-CIV **4.6.0.5** source (`deptofdefense/AndroidTacticalAssaultKit-CIV` tag `4.6.0.5`):

| Call in this tree | Public 4.6.0.5 source |
| --- | --- |
| `CotEvent.parse(String)` | `CotEvent.java` `parse` |
| `CotEvent.isValid()` | `CotEvent.java` `isValid` |
| `CotMapComponent.getInternalDispatcher()` | `CotMapComponent.java` line 1210 |
| `CotDispatcher.dispatch(CotEvent)` | `CotDispatcher.java` line 33 |
| `MapView.getMapView()` | `MapView.java` line 260 |
| `MapView.getSelfMarker()` | `MapView.java` line 850 |
| `PointMapItem.getPoint()` | `PointMapItem.java` line 106 |
| `GeoPoint.getLatitude()` / `getLongitude()` | called on `item.getPoint()` in `Marker.java` lines 1406–1407 |

Published plugin `SyracuseUniversity/PET` `lingualinkledger` (its README says ATAK **5.4.0**, not 5.6):

| Call | Where |
| --- | --- |
| `assets/plugin.xml` extension `type="gov.tak.api.plugin.IPlugin"` | `app/src/main/assets/plugin.xml` |
| Manifest activity `com.atakmap.app.component` plus `plugin-api` metadata | `AndroidManifest.xml` |
| `IPlugin`, constructor `(IServiceController)` | `LinguaLinkLedger.java` |
| `onStart` / `onStop` | same file |
| `serviceController.getService(Class)` | same file |
| `PluginContextProvider.getPluginContext()` | same file |
| `IHostUIService.addToolbarItem` / `removeToolbarItem` / `showPane(Pane, null)` | same file |
| `ToolbarItem.Builder`, `ToolbarItemAdapter.onClick` | same file |
| `MarshalManager.marshal(Drawable, Drawable.class, Bitmap.class)` | same file |
| `PluginLayoutInflater.inflate(Context, int, ViewGroup)` | same file |
| `PaneBuilder.setMetaValue` with `Pane.RELATIVE_LOCATION`, `Pane.Location.Default`, width and height ratios | same file |

`atak-takdev-plugin` is the plugin id applied by the published `gridbasedotnet/meshcore-TAK` `app/build.gradle` (GPL project; the id was read, the build file was not copied).

## Confirm on the 5.6.0.26 javadoc before treating the compile as proof

1. `gov.tak.api.plugin.IPlugin` still has only `onStart()` and `onStop()`, and the loader still calls a constructor `(IServiceController)`.
2. `IHostUIService.showPane(Pane, …)` second parameter still accepts null.
3. `com.atakmap.android.cot.CotMapComponent.getInternalDispatcher()` still exists and still returns `com.atakmap.comms.CotDispatcher`.
4. `dispatch(CotEvent)` still injects into the local map and does not also send to a TAK server. This tree never calls `getExternalDispatcher()`.
5. `plugin-api` metadata for this SDK is `com.atakmap.app@5.6.0.CIV` and not a four-part `5.6.0.26` string.
6. `targetSdk` 36 matches the local template. The published meshcore-TAK 5.6 build uses `targetSdkVersion 34`.
7. Java source/target 1.8 is what the local template compiles. This tree's plugin sources were written to that level. `./scripts/test.sh` still runs on JDK 21.

## 5.6.0.26 javadoc, read on the parent box

Source of these lines: API Documentation 5.6.0 inside that SDK, `VERSION.txt` `47de477`. HTML only. No decompile. Not re-read in this environment.

| Fact | Decision |
| --- | --- |
| `CommsLogger.logSend(CotEvent, String)`, `logSend(CotEvent, String[])`, `logReceive(CotEvent, String, String)`, `dispose()` | Experimental checkbox, default off. Callback only queues a short GeoChat XML copy. It does not edit the event or send on that thread. |
| `CommsMapComponent.getInstance()`, `registerCommsLogger`, `unregisterCommsLogger` | Used only while that checkbox is on. |
| Javadoc: the logger slows the system and any use beyond metrics or debugging is not recommended | The checkbox text says experimental and untested. It is not the supported chat path. |
| `PreSendProcessor.processCotEvent(CotEvent, String[])`, no unregister, do not send from inside it, single plugin | Not called. |
| `ChatMessageListener.chatMessageReceived` is incoming only | Not used for outbound. |
| Destination on the hooks is an address or contact UIDs, not a room-versus-direct field | A UID is not treated as a private LoRa send. The pane sends all-chat, or a named room, on the selected private MeshCore channel. |

Still not confirmed by that javadoc, so the next compile or a device test has to answer them:

1. Does `CotEvent.getType()` still return the CoT type string, and does `CotEvent.toString()` still return the XML?
2. Does `logSend` run on the comms thread, and does a short queue handoff keep that callback from blocking the send?
3. Does `logSend` actually fire for GeoChat typed into ATAK?
4. Can `CommsMapComponent.getInstance()` be null during `onStart`?
