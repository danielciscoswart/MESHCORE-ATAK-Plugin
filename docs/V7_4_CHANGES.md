# v7.4 changes against v7.3 (J1, J2)

versionCode 11, versionName 0.7.4, on-screen tag "v7.4". Source only: nothing here has been built into an APK, uploaded or run on a phone.

## Why

Phone test 2026-11-10 06:39-06:44 CST (ATAK-CIV 5.6.0.12, radio Ciscokid-ATAK, channel slot 1 CombatClubMesh):

- BUG A: two messages typed in the room ("testing", "not working") appeared as "Me:" but the radio got no send command, and there was no toast.
- BUG B: "Hello?" sent from the main radio reached the plugin (rx_83, rx_11, native_text_rx=1) but was counted `native_other_channel_pane_only`.

## J1 (BUG A): the room is wired to the path ATAK really uses

Root cause: v7.3 assumed ATAK would hand a room message to the comms layer, where a `CommsLogger.logSend` hook would see it. ATAK 5.6's chat window does something else for a contact that has a `connector.plugin` connector: it broadcasts an Intent (action = the connector's connection string, extra `MESSAGE` = the message Bundle) and stops. The room had a connector whose action nobody listened for (`ROOM_NOT_AN_ENDPOINT`, "No receiver is registered for it"). Details and bytecode evidence in `docs/ATAK_API_LOOKUP.md` ("v7.4 correction").

Changes:

- `AtakChatRoom.CONNECTOR_ACTION` is now `dev.meshcore.atak.plugin.ROOM_SEND_MESSAGE` and package-visible.
- New `RoomSendReceiver` (BroadcastReceiver). Registered through `AtakBroadcast` for that action when the room is offered, unregistered when it is removed. Copies out `conversationId`, `destinations`, `senderUid`, `messageId`, `message` and passes them on.
- `PluginHost.offerRoomChat(...)`: refuses an unreadable Bundle, a conversation that is not a MeshCore room, empty or over-long text, and any Bundle whose destinations include someone other than the room or this device (ATAK's own chat service would send that to them). Everything else goes to the unchanged `Engine.sendFromRoom` (stale room refusal, duplicate id check, byte caps, governor, plugin-only versus native text).
- `MeshCoreAtakPlugin.handleRoomSend` shows the toast and status line for queued and refused. `setRoomLogger(true)` fails, and the room is not offered, if the receiver cannot be registered.
- `MeshCoreRoomLogger.logSend` no longer queues anything. A room uid seen there is counted as `room_logsend_unexpected` and is never sent, so no message can go out twice.
- The v7.3H hold (H3) is no longer used for sends; the engine's own queue limit refuses with a sentence.
- No external dispatcher and no `GeoChatService` call anywhere in the plugin (source check).

Leak analysis: the Intent goes through `LocalBroadcastManager` and `GeoChatService.sendMessage` is skipped for that contact, so the TAK server does not get the message from this path. The typed message is stored in ATAK's local chat database (as in v7.3).

## J2 (BUG B): evidence, not a guess

The parser matches the firmware (`MyMesh.cpp` `onChannelMessageRecv`, companion-v1.18.0): v3 frame `{0x11, snr*4, 0, 0, channel_idx, path_len, txt_type, ts u32le, text}`, channel_idx at byte 4. No offset bug was found. The slot in the diag therefore came from the radio. The diag did not say which slot, so v7.4 adds:

- `native_slot_<n>` counters (and `native_slot_direct`).
- A diag line `last native text: frame=0x11 channel=<n> selected=<s> path=<p> bytes=<len>` (no text).

The pane's inbox line already shows `channel <n>` for every received message, so the slot of "Hello?" is on Daniel's screen already.

## Tests

`V74Checks` (6 tests, in `AllTests`): connector-Intent room send in both modes, unreadable and foreign Bundles, mixed destinations, stale room, lost link, stopped host, the Android wiring (source check), the firmware-byte frames, the slot counters. `scripts/test-plugin-startup.sh` has the J1 source rules. `./scripts/test.sh`: PASSED 80, FAILED 0, SKIPPED 3 (the three skips need MESHCORE_ROOT or fixtures); with `MESHCORE_ROOT` set, PASSED 82, SKIPPED 1.
