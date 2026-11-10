# MeshCore ATAK plugin v7.2: changes against v7.1

Base: GitHub branch `v7.1-ble-connect`, head `97ba38a`. New branch to push: `v7.2-ble-connect` (3 commits, see `/workspace/v72-branch-export/README.md`).
versionCode 9, versionName 0.7.2, on-screen tag "v7.2".
`app/proguard-rules.pro`, `app/src/main/assets/plugin.xml` and `app/src/main/AndroidManifest.xml` are byte-identical to v7.1 (SHA256 below). E3 did not need a manifest change.

Nothing was uploaded to TAK, GitHub, Claude or anywhere else. No message was sent. Nothing was run on a phone or radio.

**Review fixes F1 to F3 (after Claude's review of v7.2) are in `V7_2_F_CHANGES.md`. Where this file and that one differ, that one is newer.**

## Status of each item

| Item | Status |
|---|---|
| E1 choose radio, remember it | done (JVM logic tested; dialog and BLE not run on a phone) |
| E2 choose channel | done |
| E3 storage in ATAK's context | done (the fix itself cannot be proven without ATAK) |
| E4 step on replies | done (timing tested on a fake clock; real speedup not measured) |
| E5 real reason a link dies | done (records more; does not fix the status 8/133 cause) |
| E6 diag additions | done |
| E7 housekeeping | done |
| E8 show native text | done |
| E9 native channel text | implemented as opt-in toggle per Daniel; default plugin-only |
| E10 every refusal says why | done |

## Plugin users only switch (E9, per Daniel)

Implemented as opt-in toggle per Daniel; default plugin-only.

- A checkbox "Plugin users only" in the pane, ON by default, not saved. It goes back to ON whenever the radio or the channel changes (`Engine.clearSendOptIns`, called by `selectChannel` on a change, `onLinkUp`, `onLinkLost`, `clearChannelSelection`, and when the user picks a radio).
- ON: chat goes out as MCTAK channel data, `CMD_SEND_CHANNEL_DATA` (62). Button reads "SEND: ATAK plugin users only". Only copies of this plugin can read it; the MeshCore app does not show it.
- OFF: chat goes out as native MeshCore channel text, `CMD_SEND_CHANNEL_TXT_MSG` (3). Button reads "SEND: MeshCore channel text (anyone on channel)". Anyone on the channel who runs the MeshCore app sees it, with the radio's node name in front.
- Both modes: a ready radio and a chosen private channel are required. Public, hashtag, all-zero and empty slots are refused (E10). The 164-byte cap applies in both. Replies map to "Sent to radio" (OK) or "Failed: reason" (error or timeout). The word "delivered" is never used for a channel send (tested for every reply code).
- Native frame: `{3, 0 (TXT_TYPE_PLAIN), channel_idx, timestamp u32le, text UTF-8}`. Built in one place, `Companion.sendChannelText`, called in one place, `Engine.sendNativeChannelText`. A source check fails if any other file calls it. Opcode 3 is added to the allowlist test for this path only.
- **RF transmission is still not cleared.** Nothing here was transmitted. Do not use the OFF mode or any send until that step is done.

### Length cap (derived from firmware, not guessed)
Firmware `81dbae5` (companion-v1.18.0):
- `src/helpers/BaseChatMesh.h`: `MAX_TEXT_LEN = 10 * CIPHER_BLOCK_SIZE = 160`.
- `BaseChatMesh::sendGroupMessage` builds `temp[5 + ...]`: 4-byte timestamp, 1 type byte, then `"<node_name>: <text>"`. If `text_len + prefix_len > MAX_TEXT_LEN` it silently cuts the text to `MAX_TEXT_LEN - prefix_len`. `prefix_len = strlen(node_name) + 2`.
- The group datagram carries `5 + prefix_len + text_len` bytes. The plugin's own cap is 164 (176 - 3 - 9, MCTAK v0.2 section 4.3).
- Plugin cap in UTF-8 bytes: `min(160 - (name + 2), 164 - 5 - (name + 2)) = 159 - (name + 2)`. A 4-byte name gives 153; a 31-byte name gives 126. The plugin refuses a longer text ("Not sent: text too long (N/max bytes)"); it never lets the firmware truncate. The node name length comes from the SELF_INFO frame (`SelfInfo.nameBytes`).
- Plugin-only mode: the 164 cap is applied to the whole MCTAK datagram (header plus body) in `Engine.sendChat` and `Engine.queueDatagram`. The official 165 limit is still pinned in `RadioLimits` and `Mctak.radioWillAccept` and tested.

### Encryption check (firmware 81dbae5)
Both paths are encrypted with the channel's stored key, on the radio:
- `CMD_SEND_CHANNEL_TXT_MSG` (3) -> `BaseChatMesh::sendGroupMessage` -> `createGroupDatagram(PAYLOAD_TYPE_GRP_TXT, channel, ...)` (`BaseChatMesh.cpp` around line 531).
- `CMD_SEND_CHANNEL_DATA` (62) -> `BaseChatMesh::sendGroupData` -> `createGroupDatagram(PAYLOAD_TYPE_GRP_DATA, channel, ...)` (around line 555).
- `Mesh::createGroupDatagram` (`Mesh.cpp`) calls `Utils::encryptThenMAC(channel.secret, ...)`.
- The companion frames name the channel by index only. The plugin does not send a key, and the radio looks the key up in its own channel table (`getChannel(idx)`).
The JVM optional-tree test (run with `MESHCORE_ROOT`) asserts these strings in the firmware tree.

### Private encrypted channels
- The channel picker and the on-pane list label usable channels "Private (encrypted)", with name and fingerprint. Refused ones are listed with their reason.
- Sending works on them in both modes (tested for two private channels, both modes).
- The plugin never displays, logs, stores or exports the secret. **Honest limit:** `CMD_GET_CHANNEL` replies carry the 16-byte secret to the phone, so the plugin does receive it in that one frame. `Companion.parseChannelInfo` reads it only to compute the existing fingerprint and the Public/hashtag/zero checks, zeroes its copy, and `Engine` zeroes the frame's 16 secret bytes right after. `ChannelView` and `ChannelRow` have no secret field (checked by reflection in a test). Tests check that no log, view string or sent frame contains the secret hex.
- Public and hashtag channels stay refused.

## E1 Choose the radio explicitly and remember it
- Files: `android/ble/.../MeshcoreGattLink.java` (`start`, `finishScanWindow`, `chooseRadio`, `cancelChoose`, `candidates`), `transport/RadioChoice.java` (new), `transport/LinkMachine.java` (new CHOOSE stage, 120 s limit, button "Choose radio…"), `plugin/PluginState.java` (`saveRadio`, `savedRadioAddress`, `forgetRadio`), `host/MeshCoreAtakPlugin.java` (`showRadioDialog`, `forgetRadio`).
- Removed: the single-bonded-device shortcut, the strongest-signal pick and the early connect (`bondedMeshCore` is unused; `earlyConnect`, `bestMatch`, `firstMatchMs` are gone). A source check fails if they come back.
- First CONNECT: scan, then an AlertDialog from ATAK's Activity listing name, signal, short address ("...A2:95") and paired yes/no. Nothing is picked for the user. The chosen address and name are saved. Later CONNECTs: a bonded saved radio is connected directly; an unbonded saved radio is found by scanning for that address.
- Status line shows the radio name and short address while connecting and when ready. "Forget radio" button (scrolling area) disconnects, clears the saved radio, the channel and the opt-ins.
- Tests: `V72Checks.e1RadioChoice` (plan for none/bonded/unbonded/corrupt, short address, dialog line, sorting is display only, CHOOSE label and timeout), `LinkMachineChecks`, source checks in `scripts/test-plugin-startup.sh`.
- Not verified: dialog behaviour in ATAK; direct connect to an unbonded address can fail on the BLE address type, which is why that case scans.

## E2 Choose the channel explicitly
- Files: `engine/Engine.java` (`selectChannel`, `channelRows`, `emptyChannelSlots`, `selectedChannelRow`, `setLocationOptIn`, `setGeoChatCopy`, `clearSendOptIns`, `clearChannelSelection`, `onLinkUp`, `onLinkLost`), `host/MeshCoreAtakPlugin.java` (`showChannelDialog`, `chooseChannel`, `togglePosition`, `refreshChannelsText`).
- `selectFirstTeamChannel` is removed from the Engine and from the tick; a source check fails if it reappears.
- After READY the pane lists channels (index, name, fingerprint, "Private (encrypted)" or the refusal reason, empty-slot count). "CHOOSE CHANNEL" opens a dialog of the safe ones.
- "Send my position" is refused with no chosen channel, then asks for confirmation naming the channel and fingerprint. GeoChat copy is refused with no chosen channel too.
- Changing the radio or channel, a new link, or a lost link turns position and GeoChat copying off and the chat switch back ON. The channel choice is not saved.
- Additions beyond the E list (please review):
  - Inbound MCTAK data with no chosen channel is dropped and counted (`data_no_channel`). Without this, with auto-select gone, MCTAK from any channel including Public would have become CoT.
  - The Governor queues are cleared on link up and on link lost, so frames carrying a stale channel index cannot go out after a reconnect. (The first v7.2 build also replaced the whole Governor, which refilled the airtime bucket; fixed in F1, see `V7_2_F_CHANGES.md`.)
- Tests: `e2NoAutomaticChannel`, `e2OptInsFollowTheChannel`, updated `PluginChecks`/`TransportChecks` (they used `selectFirstTeamChannel`).

## E3 Storage
- Files: `plugin/KeyValueStore.java`, `plugin/PluginState.java` (new); `MeshCoreAtakPlugin.PrefsStore` (SharedPreferences "meshcore_atak_plugin" from `MapView.getMapView().getContext()`).
- Holds `mctak_seq`, `radio_address`, `radio_name`. These live in ATAK's own preferences (`meshcore_atak_plugin`), not in the plugin's private data, so they survive an uninstall of the plugin. They are only a sequence number, a Bluetooth address and a name; no key, PIN or message. The sequence is written only when it changes; a failure is counted once per value (`sequence_store_failed`) and the exception class and message are kept in `PluginState.lastError()` and shown in the diag.
- `pluginContext.getFilesDir()` is gone; a source check fails if it is used. `restoreSequence()` stays in `onStart`, not in the constructor (checked).
- The cause of v7.1's `sequence_store_failed` is very likely the unwritable `getFilesDir()` in ATAK's process, but that is not proven; the diag will now show the real exception.
- The manifest did not need to change (SharedPreferences needs no permission).
- Tests: `e3PluginState` (writes only on change, one failure count per value, restore, radio save/forget, corrupt address ignored, failing store reported).

## E4 Step on replies
- Files: `transport/RadioSession.java` (`nextActionDelayMs`, `lastNotificationMs`), `MeshcoreGattLink.java` (`scheduleStep`, `stepNow`, `writeInFlight`), `LinkMachine.handshakeMs`.
- After every notification and every completed write the adapter asks the session when the next frame may go (60 ms minimum gap kept) and posts it. The 1 s host tick stays as fallback. No GATT write starts while the previous `onCharacteristicWrite` is pending (5 s guard).
- The measured handshake time is in the diag. No fast retry after a refused GATT write was added on purpose (`WRITE_REFUSAL_LIMIT` is 5).
- Test: a whole handshake on a fake clock with a 40 ms radio finishes in under 8 s (`e4HandshakeIsNotOnePerSecond`); v7.1's pacing was one command per second. The real speedup on a phone is not measured.

## E5 Real reason a link dies
- Files: `transport/WriteFailureTracker.java` (new), `MeshcoreGattLink.java` (`onCharacteristicWrite`, `handleConnectionState`, `resolveWriteFailureByTimeout`).
- A failed write no longer closes the link at once. The adapter waits up to 2 s for the disconnect callback; its status becomes the final status. Otherwise the write status is final. Write status, final status, and time since the last notification are recorded in the history and the diag.
- This does not explain status 8/133. The cause cannot be decided from code; v7.2 only records more.
- Tests: `e5WriteFailureTracker`, source checks.

## E6 Diag additions
- Radio short address in the connect step and on the status line; addresses and a paired flag in the scan list; channel list with index, name, fingerprint and flags (no secrets); frame counts per type (`rx_XX`, `unhandled_XX`, `native_text_rx`, `mctak_data_rx`, `no_more_messages`); handshake ms; storage `lastError`.
- `CMD_GET_STATS` (56, core) is sent once after READY, before the first SYNC_NEXT. Reply (11 bytes) parsed into battery mV, uptime s, error flags, queue length. An uptime that goes backwards between two readings sets "radio reboot seen". An old firmware that refuses it only counts `stats_unavailable`.
- Opcode 56 is in the allowlist test. Allowed: {1, 3, 10, 22, 31, 56, 62}. Forbidden (never produced, tested): {23, 24, 51, 11, 12, 21, 37, 38, 14, 19, 65, 66}.
- Tests: `e6StatsAndCounters`, `e6NoSecretKept`, `opcodeAllowlist` (runs a whole session with both send modes and checks every opcode written).

## E7 Housekeeping
- JVM tests for E1-E6 and E8-E10; source checks for the Android wiring. Version bumped (versionCode 9 / 0.7.2 / tag v7.2). The unsigned-release checker and its fixtures expect 9 / 0.7.2.
- proguard, plugin.xml and manifest byte-identical (hashes below).
- The APP_START name "mctak-learn" is unchanged (Claude marked it not required).

## E8 Show incoming native text
- Files: `companion/Companion.java` (`parseNativeText`, frames 7, 8, 16, 17, signer prefix, stops at the first NUL), `engine/Engine.java` (`acceptNativeText`, `nativeInbox`, `takeNewNativeText`), `RadioSession.java` (forwards native text to the Engine), `MeshCoreAtakPlugin.java` (`refreshInboxText`, `announceIncomingAndResults`).
- The pane shows the last 10 messages, each shown only as "[MeshCore, unverified] channel N: text" or "direct from <6-byte prefix>: text" (`NativeMessage.display()`). A toast follows each new message, at most 3 per second plus a "+N more" toast. Control characters are replaced, display length capped at 160.
- Message text is never written to a log or the diag (tested). It is shown on screen only, as E8 asks. It never becomes CoT.
- SYNC_NEXT is still sent after MSG_WAITING. Counters are separate for native text, MCTAK data and "no more messages".
- Tests: `e8NativeText` (all four layouts, inbox cap, label, control characters, no logging), and the changed `TransportChecks.allegations`.
- Native text from the Public channel is also shown (labelled unverified); E8 asks for the last messages, not a channel filter.

## E10 Every refusal says why
- `engine/SendReasons.java` holds the sentences: "Not sent: radio not ready", "Not sent: choose a private channel first", "Not sent: type some text first", "Not sent: position sending is off", "Not sent: no GPS fix yet", "Not sent: plugin is stopped", "Not sent: text too long (N/max bytes)", and "Not sent: <reason>, choose a private channel" for Public/hashtag/zero/empty slots. `Engine.Send.why` carries it.
- The pane (`refuse`) shows each refusal as a toast and puts it on the status line for 8 s. GeoChat copy refusals use `PluginHost.lastWhy()`. The beacon refusal shows on the status line only (it repeats each tick, so no toast).
- The reasons never include message text, keys or PINs.
- Tests: `e10RefusalsInBothModes`.

## Other things to know
- The plugin never called `Engine.setNowSeconds`, so MCTAK timestamps were a constant in v7.1. The tick now sets it (`MeshCoreAtakPlugin`), which also gives native text a real timestamp. This is an addition beyond the E list.
- `.takdev/aars/takdevlint.aar` (a build-generated SDK tool file included in the v7.1 zip) is not in the v7.2 zip.
- The tick sends position only when opted in; confirmation dialog text says RF transmission has not been field tested.
- Observation: there is no SYNC_NEXT drain loop beyond one request per MSG_WAITING. The V4 radio reports 40 channel slots (diag).

## Test counts
- `scripts/test.sh` (JDK 21, `MESHCORE_ROOT` set): PASSED 46, FAILED 0, SKIPPED 1 (optional MCTAK fixtures, `MESHCORE_TAK_FIXTURES` unset). v7.1 was 27 / 0 / 2.
- Unsigned-release checker fixtures (6 rejection cases plus the pass case) and `scripts/test-plugin-startup.sh` source checks: pass.
- `scripts/check-ctor-mapped.sh` on the local APK: FAIL, as expected for a local build with an empty mapping. Run it on the APK TAK returns.

## Build
- `./gradlew --no-daemon assembleCivRelease`, JDK 17, local official ATAK-CIV 5.6.0.26 SDK (kept on the box, not in source, zip or git): BUILD SUCCESSFUL. Unsigned APK only. Logs in `evidence-v7.2/`.
