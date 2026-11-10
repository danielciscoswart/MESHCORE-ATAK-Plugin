# v7.2 review fixes F1 to F3

Applies Claude's second review of v7.2 (`CLAUDE_RESPONSE_5.md`). Previously reviewed v7.2 head: `f78096f`. This is one new commit on top.
Still versionCode 9, versionName 0.7.2, tag "v7.2". `proguard-rules.pro`, `plugin.xml` and `AndroidManifest.xml` are byte-identical to v7.1 (hashes in `evidence-v7.2-F/SUMMARY.md` and in V7_2_CHANGES.md).
Nothing was uploaded, pushed or messaged. Nothing was run on a phone or radio.

## F1. One airtime bucket across links
Problem: `Engine.onLinkUp` built a new `Governor` (a full bucket) on every link and `onLinkLost` dropped it, so each reconnect handed back the whole allowance. The duty also came from the previous radio's frequency, and a SELF_INFO no longer replaced it.

Fix:
- `engine/Governor.java`: `duty` and `capacityMs` are no longer final. New `retune(freqKhz, nowMs)` (credits the refill earned so far, takes the duty from the new frequency, recomputes the capacity, keeps the token level and caps it at the new capacity), `clearQueues()` (empties P0, P1, P2, leaves the tokens alone) and `capacityMs()`.
- `engine/Engine.java`:
  - `onLinkUp`: `self = null` (nothing from the previous radio, such as sender id or node name length, is used before the new SELF_INFO); creates the Governor only the first time (910.525 MHz default), otherwise `clearQueues()`.
  - `onLinkLost`: `clearQueues()` only. The governor is kept (it used to be set to null).
  - SELF_INFO: creates the Governor if none, otherwise `retune(info.freqKhz, nowMs)`.
  - `refusedNotReady` also says "radio not ready" when the link is not READY (after a lost link `self` is still set, so without this a send would have said "choose a private channel").
  - New read-only `airtimeTokensMs()` and `airtimeCapacityMs()` for tests and diag.
- Not changed: `onLinkLost` keeps `self` (as asked: only link up clears it).

Tests (`V72Checks`):
- `f1BucketSurvivesReconnect`: spend tokens with 40 chats, lose the link, reconnect to the same radio 2 s later: tokens are at most about 400 ms above the spent level (the 10 % refill for 2 s) and far from the 60 000 ms capacity. Same for a new link with no loss event.
- `f1DutyFromNewRadioKeepsTokens`: reconnect to an 868.1 MHz radio: capacity 6 000, tokens capped to 6 000. Reconnect to 910.525 MHz: capacity 60 000, tokens do not jump to full. Refill follows the 10 % duty.
- `f1QueuesAndSelfClearedOnLink`: queued frames are gone after a lost link; the bucket is still there; after link up, native cap is unknown (-1), native and plugin sends and JOIN say "radio not ready" until the new SELF_INFO; the cap then follows the new node name; channels start empty.
- Source check: no `governor = null`, no second `new Governor(`, link up clears queues and self, SELF_INFO retunes.
- I checked that the first two tests fail if `onLinkUp` builds a new Governor again.

## F2. "No answer" is not "Failed"
- `engine/SendReasons.java`: `NO_ANSWER = "No answer from radio: it may have been sent. Check before resending."` (68 characters, so the 70-character toast shows it whole). `radioFailure(-1)`, the code a timeout gives, returns it. Real error codes still say "Failed: ...".
- It reaches the toast and the status line through the same path as every chat result (`announceIncomingAndResults`: toast plus an 8 s status-line message). The status line allowed one line, which would have cut it; the layout now allows two (`maxLines` 2).
- The v7.2 head retried a rejected `CMD_SEND_CHANNEL_DATA` (62) once on `ERR_TABLE_FULL`. That contradicts "never retried after an error", so I removed the retry from `RadioSession`. A table-full error now ends as "Failed: radio queue is full" and the user decides. (The earlier review text said the code never retries 3 or 62; for 62 with TABLE_FULL it did.)
- Left alone on purpose: when Android refuses a GATT write (`writeRefused`), the frame goes back to be written later. Nothing reached the radio in that case, so it is not a resend after an error or a timeout. Handshake commands (1, 22, 31) keep their own retry.
- Tests: `f2NoAnswerWording`; `f2SendsAreNeverRetried` runs a READY session for both opcodes: after a timeout, 60 s of ticks produce no second 3 or 62, the result is `NO_ANSWER`, the queue is empty; after each error code 1 to 6 (including 3), 10 s of ticks produce no second 3 or 62, the result is "Failed: ...", the queue is empty and the failure is counted once. `E9 reply mapping` was updated for the new wording. Source check: no `table_full_retry`, the sentence exists, "Failed: radio did not answer" is gone, the status line allows two lines.

## F3. GeoChat copy off
- Layout: the `meshcore_watch` checkbox has `android:visibility="gone"` and `android:checked="false"`. The plugin also calls `watch.setVisibility(View.GONE)` after inflating.
- `MeshCoreAtakPlugin`: `watchGeoChat` starts false, and a new `GEOCHAT_COPY_AVAILABLE = false` makes `setWatchGeoChat` refuse to turn it on, so nothing can register the comms logger. The rest of the code stays for v7.3.
- Source check (`scripts/test-plugin-startup.sh`) fails if the checkbox is not GONE, is checked by default, if the plugin shows it or ticks it, if `watchGeoChat` or `GEOCHAT_COPY_AVAILABLE` default to on, or if `setWatchGeoChat` lacks the guard. I tried it with the layout set to visible and to checked: both fail the check.
- The pane no longer offers to copy GeoChat, so the engine-side copy opt-in (`setGeoChatCopy`) can no longer be switched on from the pane.

## Saved radio storage (documentation)
The saved radio address and name (and the MCTAK sequence) are kept in ATAK's own SharedPreferences (`meshcore_atak_plugin`), not in the plugin's private data. They survive an uninstall of the plugin. They are an address, a name and a number; no key, PIN or message. "Forget radio" removes the address and name. Added to `docs/V7_2_CHANGES.md` (E3).

## Not changed, noted by Claude
- Node renamed during a session: the native cap keeps using the old name length until the next SELF_INFO.
- The reboot check compares in-memory uptime readings, so it does not work across an ATAK restart.
