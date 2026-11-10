# v7.5 changes against v7.4 (K1 to K6)

versionCode 12, versionName 0.7.5, on-screen tag "v7.5 |". Source only: nothing here has been uploaded to the TAK pipeline or run on a phone. A local `assembleCivRelease` against the 5.6.0.26 SDK was run for compile proof only.

## Why

Phone test 2026-11-10 (v7.4, build 50928, ATAK-CIV 5.6.0.12, radio Ciscokid-ATAK, slot 1 CombatClubMesh):

- Every `rx_83` was followed by exactly one `write 10`, and each returned one 0x11 frame (`rx_83=13`, `rx_11=13`, `no_more_messages=0`). The plugin never read the queue to the end, so the ATAK room lagged several messages behind. One message arrived about 15 minutes late; "Hello?", "Hello 2" and "Hello 3" stayed queued on the radio.
- "You read this?" showed up three times. CiscoKid sent it once.
- Sends worked (`room_send_queued=4`, `write 3` each time). Inbound slot 1 shows in the room as unverified; Public (slot 0) stays in the pane. Both correct.

## K1 (top priority): drain the radio's offline queue

Firmware (companion-v1.18.0 = 81dbae5, `examples/companion_radio/MyMesh.cpp`): `CMD_SYNC_NEXT_MESSAGE` = 10 (line 18). The handler (lines 1415-1423) pops one frame from the offline queue and writes it, or writes the one-byte `RESP_CODE_NO_MORE_MESSAGES` = 10 (line 85). `PUSH_CODE_MSG_WAITING` = 0x83 (line 120) is sent for every queued message, only while a host is connected (lines 469-472, 585-588, 629-632). Messages that arrive while no host is connected get no tickle at all.

`RadioSession` (v7.4 answered a tickle with one cmd 10) now runs a drain pass:

- `requestDrain()` starts a pass on a 0x83 and when the session becomes READY (so on every connect and reconnect, after the stats read). A pass sends cmd 10 after every message frame (0x07, 0x08, 0x10, 0x11, 0x1B) and ends on 0x0A.
- One pass at a time. A 0x83 that arrives during a pass sets `drainAgain` (counter `sync_coalesced`); when the pass ends on NO_MORE one extra pass runs (`drain_extra_pass`), however many tickles came in.
- Safety: cap `RadioLimits.SYNC_DRAIN_CAP` = 300 cmd 10 per pass (`drain_cap_hit`; `OFFLINE_QUEUE_SIZE` is 256 for the Heltec V4 companion builds and 256 or 128 in every firmware variant setting; the 16 in `MyMesh.h` line 62 is only the fallback). An error or an unknown code in reply ends the pass (`drain_stopped_unexpected`). A cmd 10 with no answer ends it too (`drain_timeout`). A disconnect resets it. The next tickle or the next READY starts a fresh pass.
- Counters: `drain_started`, `drain_frames`, `drain_complete`, `drain_extra_pass`, `drain_cap_hit`, `drain_timeout`, `drain_stopped_unexpected`, `sync_coalesced`, and the existing `no_more_messages` (the Engine counts the 0x0A frame). The diag has a `drain:` line.
- Sends wait while a pass runs (the sync command is picked before a queued send, as before). At the 60 ms write gap a pass costs about 100 ms per frame including the radio's answer, so a few messages take well under a second, and a full 256-frame queue (the Heltec V4 size) takes about 25 s, during which a typed message waits.

Consequence to know about: the READY drain runs before the user has chosen a channel (E2: a new session starts with no channel chosen, and nothing from the air becomes room content without one). Backlog read at READY therefore goes to the pane only (`native_other_channel_pane_only`, last 10 kept) and not to the ATAK room. In v7.4 that backlog stayed on the radio and trickled into the room, late, one message per later tickle once a channel was chosen. If the room should get it, the alternative is to start the READY drain when a channel is selected instead; that was not done because the task said "after session_ready".

## K2: inbound duplicate drop

`Engine.acceptNativeText` drops a native text whose key (channel slot or direct sender prefix, sender timestamp, hash of the radio text, which includes the sender name) was seen in the last 10 minutes. It is a bounded access-ordered LRU (`SeenLru`, 128 keys; a repeat counts as a sighting; the window runs from the first sighting). The drop is before the pane, the toast and the room, and counted as `room_msg_dup_dropped`; the frame still counts in `native_text_rx` and `native_slot_<n>`. Public repeats are dropped from the pane too.

Note: v7.3 (G3) already had a 10-minute duplicate check in `offerRoomMessage` on the same fields (counter `room_dup_in`). The 2026-11-10 diag has no `room_dup_in`, so the three "You read this?" copies were NOT identical on timestamp, sender and text, and this K2 filter will not hide them either if they carry different timestamps. New diag evidence settles it: the diag now lists the last 8 native frames as `ch<n> ts=<seconds> path=<p> bytes=<n> txt16=<4 hex> [DUP]` (no text; txt16 is 16 bits of a hash).

## K3: a chat window left open after a mode switch or channel change

Claude's v7.4 review (ChatManagerMapComponent.sendMessageToDests, line 812): ATAK looks each destination up with `Contacts.getContactByUuid`. After a switch the old contact was removed, so no Intent was sent, H1's refusal could never run, and the window showed "Me:" and lost the text.

Chosen: keep the replaced room as a "(closed) MeshCore: ..." contact (same IndividualContact, renamed, same PluginConnector), up to 6, removed when the same uid comes back, on link loss (null room spec), on channel unselect and when the plugin stops. The typed text reaches `RoomSendReceiver`; the engine refuses it with the existing H1 sentence and the plugin toasts it. Why this and not closing the window: the 5.6.0.26 `ChatManagerMapComponent` has no public close for a conversation (only openConversation; the fragment map is private), so closing means reflection into ATAK internals. The closed contact uses only API the room already uses. Cost: a "(closed)" entry in the contact list after a switch.

Not covered: a window left open across a LINK LOSS still shows "Me:" with no toast (the room is removed so it disappears from contacts, which is what the phone test expected). Keeping a closed contact there would leave a stale entry while disconnected.

## K4: the unused H3 hold is gone

`BoundedHold`, the logger's `pending` queue, `pollOutbound`, `takeOverflow`, `MAX_PENDING` and `MAX_XML_CHARS` are deleted, with the H3 unit test and the startup-script rule that required it. The startup script now checks it stays removed.

## K5: the room is offered on the send receiver alone

`setRoomListeners(true)` registers the send receiver first; if that fails, the room is removed and not offered (counter `room_receiver_unavailable`). The comms logger is best effort afterwards: no comms component or a failing registration only turns off the network-chat duplicate check (G4), counted once as `room_logger_unavailable`, `logger=false` in the diag, retried every 10 s. `reconcileRoom` no longer needs the logger object.

## K6: a message id is remembered only when it was queued

`Engine.sendFromRoom` records the id (or the text fallback key) only after the send returned QUEUED. A refused send followed by a retry with the same id now goes through. A real repeat of a queued id returns DUPLICATE with a sentence ("Not sent again: that message was already queued"), so the plugin shows a toast instead of dropping it silently.

## Out of scope

The read-only Public view is not part of v7.5. Nobody has asked for it.

## Tests

`V75Checks` (16 tests). The drain tests run a `RadioSession` against a model of the firmware queue written from `MyMesh.cpp`: all of five queued messages after one tickle (6 cmd 10, never two outstanding), one message per tickle, backlog queued before connect drained at READY, drain again after reconnect, cap on an endless radio and on a 350-message backlog, three tickles mid-drain coalesced into one extra pass, error reply and timeout end a pass and the next tickle restarts it, a typed send goes out during a backlog. With the v7.4 `RadioSession` the drain tests fail (9 failures). Dedup: three copies show once, key fields, window, LRU bound, diag lines without text. Also the K3 to K6 checks and the firmware opcode check (needs `MESHCORE_ROOT`). `./scripts/test.sh`: PASSED 97, FAILED 0, SKIPPED 1 with `MESHCORE_ROOT` set (the skipped one needs `MESHCORE_TAK_FIXTURES`); PASSED 94, SKIPPED 4 without. v7.4 had 82 with `MESHCORE_ROOT`; 1 removed (H3), 16 added, existing tests adjusted for the READY drain.
