# Source, version, and license manifest

Inspection date: 2026-11-08. Clones were shallow checkouts under `/tmp/refs` in the build environment. They are not vendored and were not modified. This continuation re-read the same pins. Nothing in those clones was edited.

## Authority

The repository owner identified <https://github.com/meshcore-dev/MeshCore> as the official MeshCore firmware and companion protocol. Payload limits, BLE framing, channel types, and link-layer permissions in this tree follow that source, not the third-party ATAK plugin.

Pinned official revision, read as `origin/main` on 2026-11-08:

`81dbae592ffad40b4212bc0b37e82b38da08f4bc` (2026-10-15, "Merge branch 'dev'")

`gridbasedotnet/meshcore-TAK` is the MCTAK interoperability reference. Its 167-byte datagram is the published application layout. It is not a statement of what current companion firmware will transmit.

## Decision

**Independent implementation of the published MCTAK v0.1 layout and the MeshCore companion frames, not a source port.**

| Candidate | License found | What was done |
| --- | --- | --- |
| `gridbasedotnet/meshcore-TAK` @ `a4d8d77fb876e185cc73ac01f481f3680abe690a` (2026-08-03) | GPL-3.0-only. `COPYRIGHT.md` says the ATAK-CIV plugin-template files are **not** under that GPL grant (`LicenseRef-ATAK-CIV-SDK`, TAK Product Center) | Read `docs/payload-spec.md`, `docs/FEATURES.md`, `docs/payload-spec-fixtures/README.md`, and `COPYRIGHT.md`. Did not copy Java, the plugin template, or fixture files into this tree. Ran this tree's decoder against the fixture bytes in `/tmp` |
| `meshtastic/ATAK-Plugin` @ `784530f8bc9271d59d33caf8afb40f464cab032f` (2026-06-04) | GPL-3.0 | Read `README.md` for the feature list. Did not copy source |
| `meshcore-dev/MeshCore` @ `81dbae592ffad40b4212bc0b37e82b38da08f4bc` (2026-10-15) | MIT, Copyright (c) 2025 Scott Powell / rippleradios.com (`license.txt`) | Read `docs/companion_protocol.md` (header says last updated 2026-03-08, companion firmware v1.12.0+) and confirmed constants in `src/MeshCore.h`, `src/helpers/BaseChatMesh.cpp`, and `examples/companion_radio/MyMesh.cpp`. Did not copy source files |
| `morluto/rea` @ `c5ed046d6e7000b27a12c01a1444f28cc1651c4a` (2026-11-08) | MIT, Copyright (c) 2026 morluto | Read the skill and README. Did not run REA. See `docs/REA_LOG.md` |
| ATAK-CIV SDK | TAK Product Center terms. Not a public source repo | Not downloaded. No `com.atakmap` sources, no keystore, no APK |

GPL-3.0 would allow a derivative of the meshcore-TAK Java **if** the ATAK template files were left behind and the GPL was kept. Those template files are the part this project cannot redistribute. The MCTAK document itself asks for a second, independent implementation. This tree takes that path. Protocol field layouts are implemented from the specification and from the MIT firmware's frame formats. No GPL source file is included.

This repository's own code is Apache-2.0. See `LICENSE`.

## Constants checked in MeshCore

Observed in the MIT tree, not invented here:

- `MAX_PACKET_PAYLOAD` 184, `CIPHER_BLOCK_SIZE` 16, so `MAX_GROUP_DATA_LENGTH` is 165 (`src/MeshCore.h`).
- `sendGroupData` returns false above that length (`BaseChatMesh.cpp`).
- Companion `MAX_FRAME_SIZE` 176 and `MAX_CHANNEL_DATA_LENGTH` 167 (`MyMesh.cpp`). Payloads of 166 or 167 pass the frame check and then fail the radio check.
- `CMD_SEND_CHANNEL_DATA` is 62. `DATA_TYPE_DEV` is `0xFFFF`.
- `SELF_INFO` writes the 32-byte public key, then frequency as kHz (`freq_mhz * 1000`) and bandwidth as Hz (`bw_khz * 1000`), then spreading factor and coding rate 5..8.
- Channel 0 is created with the name `Public` and `PUBLIC_GROUP_PSK` base64 `izOH6cXN6mrJ5e26oRXNcg==`.
- `PACKET_CHANNEL_INFO` returns the 16-byte secret. This core fingerprints it and does not keep it.
- `PACKET_DEVICE_INFO` contains a BLE PIN. This core does not store or log that field.

## MCTAK facts this code follows

From `docs/payload-spec.md` in meshcore-TAK (normative text, v0.1, dated 2026-07-27 in that file):

- Magic `TK`, version nibble 0, 10-byte header, sender as the first four public-key bytes in wire order, sequence little-endian.
- Kinds: PLI `0x01`, GeoChat `0x02`, receipt `0x03`, fragment `0x04`, transfer status `0x05`, IDENT `0x06`, WHO `0x07`.
- `data_type` `0xFFFF` until an official value exists.
- Fragment datagrams are exactly 167 bytes. Symbol length 149.
- CRC-32/ISO-HDLC. xorshift32. Robust soliton `c = 0.1`, `δ = 0.5`.
- Public-channel refusal, direct-only receipts, and the note that a channel key holder can spoof `sender`.

The spec's worked PLI vector and the checked-in datagram hex files were used as interoperability checks. They are not copied into this repository.

## What a later SDK drop would add

A tak.gov ATAK-CIV 5.6 SDK (account-gated; no account was created for this tree) and a plugin class that calls `PluginHost` and `MeshcoreGattLink`. Retail distribution still needs the TAK Product Center signing pipeline. This tree does not perform that step. The public Android SDK 35 `android.jar` used to compile the GATT client is not that SDK and does not produce an APK.

## BLE and channel facts checked in the official tree

- Service `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`, RX `…0002…`, TX `…0003…` in `src/helpers/esp32/SerialBLEInterface.cpp`, `src/helpers/rp2040/SerialBLEInterface.cpp`, and `docs/companion_protocol.md`. The nRF52 file uses Adafruit `BLEUart` and does not repeat the literals.
- One GATT write or notification is one companion frame (`docs/companion_protocol.md` frame-handling section; ESP32 `onWrite`).
- ESP32 calls `BLEDevice::setMTU(MAX_FRAME_SIZE)` (176). RP2040 drops a notify when `frame + 3 > mtu` and says the client must negotiate `MTU >= MAX_FRAME_SIZE + 3`. This tree requests 512 and will not split a frame that does not fit the grant. It does not claim the ESP32 grant rises above 176; that was not measured on a radio.
- nRF52 sets `SECMODE_ENC_WITH_MITM` and a PIN. ESP32 sets `ESP_LE_AUTH_REQ_SC_MITM_BOND` and a static PIN. This tree does not read or log the device-info PIN.
- Channel 0 is created as `Public` with `PUBLIC_GROUP_PSK` base64 `izOH6cXN6mrJ5e26oRXNcg==`, which is hex `8b3387e9c5cdea6ac9e5edbaa115cd72` in the companion document. Hashtag keys are described there as the first 16 bytes of SHA-256 of the name, with the example `#test` → `9cd8fcf22a47333b591d96a2b848b73f`. Both were checked. The document says hashtag channels should not be treated as private.
- `Packet.h` labels group datagrams unverified. Inbound CoT stays `unverified="true"`. A channel key is not a sender signature.
- `onChannelDataRecv` queues the host frame and pushes only `PUSH_CODE_MSG_WAITING` (`0x83`). The session then sends one `CMD_SYNC_NEXT_MESSAGE`.
