# ATAK API lookup for the local 5.6.0.26 SDK

This environment did not have the ATAK-CIV SDK. Nothing under `app/` was compiled against it. The parent compile is the check. If a symbol below is missing or has a different signature in the 5.6.0.26 javadoc, stop and change only that call. Do not add a stub jar.

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
| `MarshalManager.marshal(Drawable, Drawable.class, Bitmap.class)` | same file. This tree no longer does that. It decodes `R.drawable.ic_launcher` with `BitmapFactory` and marshals `android.graphics.Bitmap`. |
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

## Play Store 5.6.0.12

No public changelog was found that lists plugin APIs added between Play Store ATAK-CIV 5.6.0.12 and the 5.6.0.26 SDK archive. The calls above are present in the 4.6.0.5 public source or in a published 5.4 plugin. `BitmapFactory.decodeResource` and `android.graphics.Bitmap.createBitmap` are platform APIs, not ATAK APIs. `Resources.getDrawable(int)` is no longer called. That does not prove a plugin built with the 5.6.0.26 SDK loads in 5.6.0.12. The published 5.5 sample still passes a PNG drawable through `MarshalManager`. This tree does not, because that conversion was given a vector drawable and is a candidate for the constructor throw. Whether `MarshalManager` accepts `android.graphics.Bitmap` was not in the javadoc notes above. A null result skips the toolbar item and does not leave the constructor.


## v7.3 chat room (G7): every call checked against the 5.6.0.26 SDK

Checked on 2026-11-09 on the box that holds the SDK, by listing the public signatures in `main.jar` with `javap -public` and by reading the bytecode of the send and receive paths with `javap -c`. No SDK file is in this repository or in any zip. The Play Store ATAK on the phone is 5.6.0.12; nothing below was run on it. A signature that exists in 5.6.0.26 is not proof that it behaves the same in 5.6.0.12. The plugin is also compiled against the 5.6.0.26 `main.jar` with `javac` (class files only, no APK) to prove the calls type-check.

### Signatures that exist (all confirmed)

| Call used by v7.3 | Signature in 5.6.0.26 |
| --- | --- |
| `Contacts.getInstance()` | `public static synchronized Contacts getInstance()` |
| `Contacts.addContact(Contact)` / `removeContact(Contact)` | `public void addContact(Contact)` (adds to the root group), `public void removeContact(Contact)` |
| `new IndividualContact(String name, String uid)` | `public IndividualContact(String, String)`. The bytecode passes the two arguments on in the order name, uid |
| `IndividualContact.addConnector(Connector)` | `public boolean addConnector(Connector)` |
| `IndividualContact.current()` | `public void current()` (marks the contact as present) |
| `Contact.setName(String)` and `Contact.dispatchChangeEvent()` | both public on `Contact` |
| `new PluginConnector(String)` | `public PluginConnector(String)`. `CONNECTOR_TYPE` is `connector.plugin` |
| `ChatManagerMapComponent.getInstance()` | `public static ChatManagerMapComponent getInstance()` |
| `ChatManagerMapComponent.openConversation(IndividualContact, boolean)` | public. It opens the chat only when the contact has an IP connect string or a `connector.plugin` connector, so the room gets a `PluginConnector` and no network connector |
| `CommsMapComponent.registerCommsLogger` / `unregisterCommsLogger` | `public void ...(CommsLogger)` |
| `CommsLogger.logSend(CotEvent, String[])`, `logSend(CotEvent, String)`, `logReceive(CotEvent, String, String)`, `dispose()` | all abstract and public |
| `CotMapComponent.getInternalDispatcher()` and `CotDispatcher.dispatch(CotEvent)` | public static / public final. The external dispatcher is never used (a source check enforces it) |
| `MapView.getDeviceUid()` (static) and `getDeviceCallsign()` | public |
| `CotEvent.getType()`, `toString()`, `findDetail(String)`; `CotDetail.getAttribute(String)`, `getInnerText()` | public |

### What the bytecode says about sending (read, not run)

1. `ChatManagerMapComponent.sendMessage(Bundle, String[] toUids)` resolves each uid with `Contacts.getContactByUuid` and calls `GeoChatService.sendMessage(Bundle, List<IndividualContact>)`.
2. That method sends to every contact through the external dispatcher's `dispatchToContacts`, except when a contact has the extra `fakeGroup` (the "All Chat Rooms" contact), which goes to `dispatchToBroadcast`. The room is not that contact.
3. `CommsMapComponent.sendCoT(List, CotEvent, String[] toUids, CoTSendMethod)` keeps a uid only when commo knows the contact, or when the comms provider has the feature `UNKNOWN_CONTACT`. Otherwise it logs "Send to unknown contact" and leaves the uid out. `DefaultCommsProvider` has these features: `MISSION_PACK_SETTINGS`, `ENDPOINT_SUPPORT`, `FILE_IO`, `INTERFACE_OPTIONS`, `CRYPTO`, `VPN`. `UNKNOWN_CONTACT` is not one of them.
4. After the send call, if no exception was thrown, every registered `CommsLogger` gets `logSend(event, toUids)` with the full uid array. This is the hook the room uses.

### What is still unverified (the phone test G6 must answer these)

- Whether commo's `sendCoT` with an empty recipient list sends nothing and does not throw. If it throws, ATAK catches it ("Invalid cot message for unicast") and the logger is never called, so a message typed into the room would be lost silently. The phone test shows this at once: a typed message must produce the plugin's "Queued for the radio" or a refusal sentence.
- Whether any copy of a room message reaches the TAK server or the local network on 5.6.0.12. The bytecode above says no for the default provider; only a packet check on the phone can prove it.
- Whether ATAK's chat window lets the user type into a contact that has only a `PluginConnector`, and whether the contact list hides it.
- Whether the injected event shape (`__chat` with `id` = this device's uid, the room contact as `chatgrp uid0` and `link` `p-p`) files the message under the room contact. This follows `ChatMessageParser`, which takes the conversation from the sender contact when `id` equals the device uid, but it was not run.
- Behaviour of all of the above on Play Store 5.6.0.12.


## v7.4 correction (J1): what ATAK does when the user types into a room whose contact has a PluginConnector

The v7.3 notes above ("What the bytecode says about sending") described `ChatManagerMapComponent.sendMessage(Bundle, String[])` and `GeoChatService`. That is the API a plugin calls to send. It is NOT what the chat window calls. The phone test on 2026-11-10 (ATAK-CIV 5.6.0.12) showed the typed message reaching nothing, and a second read of the 5.6.0.26 `main.jar` (`javap -c -p`, local only) found the real path:

1. The send button calls `ConversationFragment.sendMessage(String, Bundle)`, which calls its `SendBehavior`. `ChatManagerMapComponent$3` (the behavior set in `getOrCreateFragment`) calls the private `sendMessageToDests(Bundle, List<Contact>)`.
2. `sendMessageToDests` stores the sent line with `GeoChatService.persistMessage` (local chat database only; this is why "Me:" appears), builds the `destinations` array, and then for each destination uid other than this device:
   - `All Chat Rooms`: broadcasts `com.atakmap.android.chat.SEND_MESSAGE_ALL_CHAT_ROOMS`.
   - an `IndividualContact` that has a `connector.plugin` connector: `AtakBroadcast.getInstance().sendBroadcast(new Intent(connector.getConnectionString()).putExtra("MESSAGE", bundle))`. Nothing else happens for that contact. `GeoChatService.sendMessage` is not called, so `CommsMapComponent.sendCoT` is not called, so no `CommsLogger.logSend` ever fires.
   - an `IndividualContact` with no plugin connector: only then does it use the network (`ContactUtil.getGeoChatIpAddress`, `GeoChatService.sendMessage`).
3. `AtakBroadcast` wraps androidx `LocalBroadcastManager` (checked: `registerReceiver`/`sendBroadcast` call `LocalBroadcastManager`). The Intent never leaves the ATAK process.
4. The `MESSAGE` Bundle has: `message`, `messageId`, `conversationId` (the room uid), `conversationName`, `senderUid`, `senderCallsign`, `uid`, `sentTime`, `type`="GeoChat", `protocol`, `deviceType`, `destinations` (String[]), `paths`, `parent`, `status`, `rawCotEvent`/`cotEventUid` after persist.
5. The same code is in the public ATAK-CIV 4.6.0.5 source (`ChatManagerMapComponent.sendMessageToDests`, with the public constants `PLUGIN_SEND_MESSAGE_ALL_CHAT_ROOMS` and `PLUGIN_SEND_MESSAGE_EXTRA = "MESSAGE"`), so it is very likely the same in Play Store 5.6.0.12. Not run there.

So v7.3's comms-logger send hook could never fire for the room. v7.4 registers a `BroadcastReceiver` for the connector action (`RoomSendReceiver`). The comms logger now only counts.
