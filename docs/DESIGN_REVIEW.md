# Review of the 2026-11-08 design draft

The draft is a consultation. Its author marked it untested. It is not a license, a maintainer agreement, or an instruction this repository followed. Official MeshCore behavior was re-read from `meshcore-dev/MeshCore`. The tag `companion-v1.18.0` points at `81dbae592ffad40b4212bc0b37e82b38da08f4bc`. `FIRMWARE_VERSION` in that tree is `v1.18.0` and `FIRMWARE_VER_CODE` is 14.

## Accepted

| Draft claim | Check |
| --- | --- |
| Do not copy the Meshtastic plugin | Already the rule. No Meshtastic source is in this tree. |
| MCTAK is not MeshCore chat and not Meshtastic | Kept. `DATA_TYPE_DEV` (`0xFFFF`) carries MCTAK only. |
| Group payload over 165 bytes is refused on current firmware | `MyMesh.cpp` 1230–1232 at `81dbae5` returns `ERR_CODE_ILLEGAL_ARG` when `payload_len > MAX_GROUP_DATA_LENGTH`. |
| v1.16.0 still used the 167-byte frame check, then `TABLE_FULL` | Tag `companion-v1.16.0` is `07a3ca9`. That file compares `payload_len > MAX_CHANNEL_DATA_LENGTH` and, when `sendGroupData` returns false, writes `ERR_CODE_TABLE_FULL`. `FIRMWARE_VER_CODE` there is 13, version string `v1.16.0`. |
| v1.17.1 still compared against `MAX_CHANNEL_DATA_LENGTH` | Tag `companion-v1.17.1` is `d929643`. The comparison is at `MyMesh.cpp:1183`. Version string `v1.17.1`, code 13. |
| Companion frame cap 176; one write is one frame | `BaseSerialInterface.h` and the ESP32/RP2040/nRF52 companions. |
| ESP32 sets local MTU to 176 | `esp32/SerialBLEInterface.cpp` `BLEDevice::setMTU(MAX_FRAME_SIZE)`. |
| RP2040 drops a notify when `frame + 3 > mtu` | `rp2040/SerialBLEInterface.cpp`. ESP32 does not have that drop. |
| Channel-data host frame is 9 bytes plus the datagram | `MyMesh.cpp` `onChannelDataRecv`. A 165-byte datagram is a 174-byte frame. A flood send of that datagram is 170 bytes (`5 + 165`). |
| Offline queue default is 16 and can be overridden | `MyMesh.h` `OFFLINE_QUEUE_SIZE`. When full, the oldest channel text or channel datagram is removed; if none is queued, the new frame is not added (`MyMesh.cpp` `addToOfflineQueue`). |
| Device-info contains the BLE PIN at bytes 4–7 | `MyMesh.cpp` device-query response. This tree now zeros those four bytes on receipt and still does not log them. |
| Public and hashtag channels are not private | Companion document in the same commit. Refusal stays. |
| A channel key does not authenticate the sender | `Packet.h` calls group datagrams unverified. Inbound CoT stays `unverified="true"`. |
| No voice, file, imagery, iTAK, or Meshtastic radio parity | Unchanged. |
| ATAK SDK is not public and must not be committed | Unchanged. The plugin module only points at a local path. |
| Gradle 8.14.3, AGP 8.13.0, compileSdk 36, minSdk 21, takdev plugin id `atak-takdev-plugin` | The versions and plugin id are in the published meshcore-TAK Gradle files for their 5.6 build. The parent's 5.6.0.26 guide also names Gradle 8.14.3, AGP 8.13.0, compileSdk 36, minSdk 21, JDK 17, Java 1.8. |

## Rejected

| Draft prescription | Why it was not done |
| --- | --- |
| Prefer a GPL fork of `gridbasedotnet/meshcore-TAK`, or contribute under its CLA | This repository stays an independent Apache-2.0 implementation. No CLA, no rebrand, no upstream pull request, and no mail to that maintainer. That decision is Daniel's, not this draft's. |
| MCTAK v0.2 with a 164-byte fragment and symbol size 146 | No allocation and no maintainer agreement. A 164-byte kind would not be MCTAK v0.1 and would not be official MeshCore. Fragment send stays off. Decode of a 167-byte fragment remains. |
| Lower every datagram to 164 so ESP32 notifications fit | The 164 figure is ATT arithmetic, not a firmware constant. A 165-byte **send** is 170 bytes and fits a 173-byte ATT payload. A 165-byte **notification** is 174 bytes and does not. ESP32 notifies the full frame and does not document truncation in source. That behavior was not run on a radio. Short frames are dropped. The send gate stays 165. |
| Optional signatures over MCTAK | Not implemented. A channel key still does not prove who sent a datagram. No new header bit. |
| Native MeshCore text bridge (`CMD_SEND_TXT_MSG` / channel text) | Not implemented. Native text and MCTAK are different. Adding it would be a new feature, not a fix. |
| Print or log channel keys the way the third-party plugin's own channel guide describes | Their `docs/channels.md` says a newly created key is printed once into a 60-line log so it can be handed to another operator. That is their behavior. This tree fingerprints the secret and wipes the bytes. The allegation is about that project, and it matches their documentation. It is not a defect copied here. |
| Server relay, voice memo, radio GPS, TAK tracker role | Still unsupported. |
| Treat `TABLE_FULL` on v1.18 as the oversize error | On `81dbae5`, oversize is `ILLEGAL_ARG`. `TABLE_FULL` is the later branch after a payload has already passed the 165 check. |

## Unverified, and left unverified

- Whether the ESP32 BLE stack truncates, drops, or rejects a 174-byte notification when the local MTU is 176. Source does not say. No radio was attached.
- nRF52's negotiated MTU. The nRF52 companion file does not set a numeric MTU.
- v1.17.0's error branch. Its tag is `727fc05`, which matches the draft's pin, and the file was not re-read. v1.16.0 and v1.17.1 were.
- `CommsLogger.logSend` versus `PreSendProcessor` on ATAK 5.6.0.26. Neither is called.
- Every ATAK 5.6.0.26 signature listed in `docs/ATAK_API_LOOKUP.md`. The calls use public 4.6.0.5 and a published 5.4 plugin. The local SDK compile is the check.
- The draft's 42-row parity counts as a score. The feature list in `FEATURE_PARITY.md` is the one this repository maintains, against the Meshtastic plugin README, not as a promise of radio parity.
- A signed meshcore-TAK 1.0.0 release. Not checked, and not used.
- Hardware results the draft marked `[U]`.

## What this round added

Source for an ATAK host under `atak-plugin/`: manifest discovery, `plugin.xml`, a pane, opt-in self-beacon, and internal CoT publish. It was not compiled. `docs/LOCAL_BUILD.md` is the parent procedure. JDK tests still pass without the SDK, including the ESP32 size arithmetic, truncated-notification drop, offline-queue threshold, and PIN wipe.
