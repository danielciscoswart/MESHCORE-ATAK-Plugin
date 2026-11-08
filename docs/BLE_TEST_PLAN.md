# BLE, background, and locked-screen test plan

This plan is not a test that has been run. No phone, radio, location provider, or transmitter was used. `./scripts/test.sh` drives an in-process session only.

Official behavior the plan has to respect (`meshcore-dev/MeshCore` `81dbae5`):

- One GATT write and one notification are one companion frame. Do not split a frame when the negotiated MTU is short. Expect the write to be refused by this client.
- ESP32 companion code sets the local MTU to 176. A 165-byte datagram send frame is 170 bytes (fits an ATT payload of 173). A full channel-data notification is 9 + datagram bytes, so a 165-byte datagram notification is 174 bytes and does not fit under MTU 176. RP2040 drops an outbound notify rather than truncating when `frame + 3 > mtu`. Confirm on hardware which grant the phone actually gets. Do not "fix" a short notification by inventing a second framing layer.
- Pairing is MITM with a PIN (nRF52 `SECMODE_ENC_WITH_MITM`, ESP32 secure-connections bond). The tester types the PIN into the system dialog. Logs must not contain the PIN or a channel secret. Device-info bytes 4–7 are the PIN and must not be recorded.
- Inbound mesh datagrams arrive as `0x83` plus `CMD_SYNC_NEXT_MESSAGE`, not as an immediate full push.

## Permissions

On API 31 and newer, deny `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` separately. `MeshcoreGattLink.start` must return false and must not scan. Grant only connect, then only scan, then both. On older Android, deny fine location and confirm scan results are not delivered. Toggle Bluetooth off. None of these steps may throw into ATAK or write a companion frame.

## Background and locked screen

Do not install a foreground service in this test. With the screen locked and the app backgrounded for longer than the radio's supervision timeout:

- Record whether the GATT callback reports disconnect.
- Confirm `RadioSession` stops writing and that `reconnectDue` stays false while the host is stopped (`PluginHost.onStop`).
- Unlock, call `onStart`, and confirm a new handshake (app start, device query, channel list) rather than a stale in-flight command.
- Repeat with the process killed. Sequence numbers are memory-only; a restart is allowed to resync PLI after three rejected sequences. That is not a persistence bug to "fix" in the field test.

## Loss, duplication, reorder, corrupt, bounds

Already covered in process, and still required on a bench with two radios before any field claim:

- Drop the notification for one command. The session times out once, retries a handshake command once, and does not retry a channel-data send (the frame may already be on the air).
- Deliver the same synced datagram twice. The engine counts a duplicate and emits one CoT.
- Deliver `0x83` during the app-start wait. It must not complete app start. One later sync is enough for a burst of tickles.
- Notify 200-byte values and truncated values. They are counted and dropped.
- Fill the write queue and the contact list. Both are capped (32 writes, 256 contacts).

## What must not happen in the test

- No live GPS fix. Position fixtures stay the published sample or another constant.
- No transmission of a datagram longer than 165 bytes, voice, imagery, or a file.
- No public or hashtag channel selected for team traffic.
- No claim that a simulation or a compile equals field readiness.
