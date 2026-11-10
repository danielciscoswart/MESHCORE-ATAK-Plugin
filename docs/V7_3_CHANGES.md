# v7.3: the MeshCore chat room (G1 to G7) and quiet radio pushes

Applies Claude's v7.3 plan (`CLAUDE_RESPONSE_5.md`, G1 to G7). Base: v7.2 at `857900c`, which passed its first phone and radio test on 2026-11-09.
versionCode 10, versionName 0.7.3, status line prefix `v7.3 |`. `proguard-rules.pro`, `plugin.xml`, `AndroidManifest.xml` and `scripts/check-ctor-mapped.sh` are not changed.
Nothing was built into an APK, uploaded, pushed or messaged. Nothing was run on a phone or radio.

## What the user sees

While a private channel is chosen and the link is READY, ATAK's contact list holds one contact, "MeshCore: <channel> (plugin only)" or "(MeshCore text)". The pane has an OPEN CHAT button that opens that contact's chat window. What is typed there goes to the radio, in whatever mode the "Plugin users only" switch is in. Radio messages for that channel appear in the same window. When the channel, the radio or the link changes, or the plugin stops, the contact is removed.

## G1. The room

- `Engine.roomSpec()`: the room exists only when the link is READY, the radio's SELF_INFO is in, and a private channel is chosen. Public, hashtag, zero-key and empty channels never get a room. The uid is `MESHCORE-ROOM-<slot>-<crc32 of the channel name>`: no key, no fingerprint. The title is "MeshCore: <name> (plugin only)" or "(MeshCore text)"; the name is stripped of control characters and capped at 32 characters.
- A mode switch renames the same contact (`setName` plus `dispatchChangeEvent`). A different channel gives a new uid, so the old contact is removed and a new one added.
- `AtakChatRoom` (new, app module) keeps ATAK in step with `roomSpec()` once a second. The contact is an `IndividualContact` with a `PluginConnector` and no IP or GeoChat connector. A `PluginConnector` is what lets ATAK's `openConversation` open the chat; the absence of a network connector means ATAK has no address to send to.
- Removal: the tick removes the room when `roomSpec()` is null; `onStop` calls `removeRoom()`, which removes the contact and unregisters the logger. If the comms logger cannot be registered, the room is removed again and the reason is shown ("MeshCore chat room unavailable: ATAK comms not ready"), because a room nobody listens to would drop messages silently.

## G2. Outbound

- `ExperimentalGeoChatWatch` and everything around it is deleted: the class, the hidden checkbox, `GEOCHAT_COPY_AVAILABLE`, `watchGeoChat`, `Engine.setGeoChatCopy` and `geoChatCopyOptIn`, `PluginHost.offerChat`.
- New `MeshCoreRoomLogger` (a `CommsLogger`, observe-only). `logSend(event, String[] toUids)` holds a chat only when `toUids` has exactly one entry and it starts with `MESHCORE-ROOM-`. Every other chat is counted (`chat_other_ignored`) and its text is not read. `logSend(event, String destination)` is counted only.
- `PluginHost.offerOutboundChat(xml, toUids, now)` is the only way a chat reaches the radio. The order is: stopped plugin, loop marker, event type, destination exactly the room prefix, then `Engine.sendFromRoom`, which needs the uid to be the current room's uid. A stale uid (previous channel) is ignored; no room at all is refused with the E10 sentence.
- `PluginHost.offerOutboundCot` can no longer send a chat: a `b-t-f` there returns `IGNORED`.
- The send uses the current mode: ON is `sendChat` (MCTAK scope ALL, command 62), OFF is `sendNativeChannelText` (command 3). Both keep their caps (native 159 minus name length plus 2, and the 164-byte payload cap), the Public refusal and the opt-in (a room exists only after the user chose a channel).
- The comms logger runs on ATAK's comms thread and only queues (8 at most); the plugin's tick drains it on the main thread.

## G3. Inbound

- MCTAK chat from the chosen channel (any MCTAK scope, since it is channel traffic) and native channel text on the chosen channel go to `Engine.drainRoomMessages()`. Native text from another channel, and direct native text, stay in the pane only (`native_other_channel_pane_only`, `native_direct_pane_only`).
- The sender is shown as "<name> (MeshCore, unverified)". For native text the name is whatever precedes the first ": " (the firmware writes "<node_name>: <text>"), at most 32 characters, else "unknown". For MCTAK it is the IDENT callsign, else the derived uid. Text is stripped of control characters and capped at 160.
- `Cot.roomChat` builds the event shaped like a one-to-one GeoChat message from the room contact to this device. The real sender is part of the text so the label cannot be lost whatever name the chat window prints. It carries `__meshcore unverified="true"`. `AtakChatRoom.show` publishes it only through `AtakCotBridge.publish`, which uses the internal dispatcher.
- The v7.2 inbound path that turned MCTAK chat into a general GeoChat event is replaced by the room path. Receipts, PLI and ident are unchanged.
- Room messages waiting for a channel are dropped when the channel or link changes.

## G4. Duplicates and loops

- Sent twice: `Engine.sendFromRoom` remembers the ATAK event uid for ten minutes; a second report of the same uid is dropped (`room_dup_out`). With no uid at all, the same text inside three seconds is one send. Two different ATAK messages with the same text are two sends.
- Marked event on the way out: any chat with `__meshcore` is dropped as `DROP_LOOP`, whatever its destination.
- Radio and network: the logger reports chats ATAK received from the network (`logReceive`: sender callsign and text only). The engine keeps a hash of sender and text for five minutes. A radio message with the same sender and text in that window is not shown again (`room_dup_network`). Nothing but a hash is kept.
- Radio twice: native text with the same channel, sender timestamp, sender and text is shown once; MCTAK chat keeps its (sender, message id) check. Both counted (`room_dup_in`, `dup_chat`).

## G5. Visible results

- Every refusal from the room path (not ready, no channel, too long, airtime) goes through `refuse()`: a toast and the status line. "Queued for the radio" and the radio's own answers ("Sent to radio", "No answer ...", "Failed: ...") use the existing toast and status path. The word "delivered" is never used for a channel send; a test checks every sentence.
- Ignored chats and duplicates are silent on screen but counted, and the diag shows the counters.

## G6. Phone test before any RF (not done; needs the phone)

See the test plan in `claude-review-v9/PROMPT_v7.3.md`. The first thing it must answer is whether a message typed into the room produces the plugin's own "Queued for the radio" (or a refusal) at all, and whether anything reaches the TAK server or local network.

## G7. APIs

Every API is listed in `docs/ATAK_API_LOOKUP.md` with what was confirmed in the 5.6.0.26 SDK and what is still unverified.

## Radio pushes (from the v7.2 diag)

The v7.2 diag showed `unhandled_88=21` and `unhandled_80=1`. Checked against meshcore-dev/MeshCore tag companion-v1.18.0 (81dbae5), `examples/companion_radio/MyMesh.cpp`:

- `PUSH_CODE_LOG_RX_DATA` is 0x88: `logRxRaw` writes `{0x88, snr, rssi, raw packet bytes}` for every packet the radio hears while a client is connected, then a second frame that is just `{0x88}`. The 21 unhandled frames are those.
- `PUSH_CODE_ADVERT` is 0x80: sent when an advert from an already known contact is heard (`{0x80, 32-byte public key}`). `PUSH_CODE_NEW_ADVERT` is 0x8A (a full contact frame).
- The full table is 0x80 to 0x90 (17 codes). `Companion.pushName` holds it. The engine counts each as `push_<name>` and returns; the bytes are never logged or kept. A test reads the firmware file (when `MESHCORE_ROOT` is set) and checks every code and name. A code that is not a push and not a known reply is still counted as `unhandled_<hex>`.
- The engine's text log is now capped at 500 lines (it was unbounded).

## Tests

Java tests in `src/test/java/.../V73Checks.java` (18 new, plus 3 existing tests edited, see below), and the source checks in `scripts/test-plugin-startup.sh` (the F3 checkbox checks became G1/G2/G3 checks).
Existing tests changed because the behaviour they pinned was removed on purpose: `AllTests.radios` (inbound chat is now a room message), `PluginChecks.lifecycle` (a chat offered with no destination is ignored; the room is the send path), `V72Checks.e2*` (no GeoChat copy opt-in any more). Every other v7.2 test is untouched and passes.
