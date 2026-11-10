# 167-byte MCTAK datagram versus 165-byte MeshCore payload

The official firmware repository is <https://github.com/meshcore-dev/MeshCore>. The pin below is commit `81dbae592ffad40b4212bc0b37e82b38da08f4bc`. The MCTAK 167-byte figure is from the gridbasedotnet specification, which is an interoperability document and not the firmware.

This tree keeps both numbers. It does not pick a third size.

## Re-read in this continuation

The lines cited below were read again from that official checkout on 2026-11-08. They still match this note.

- `src/MeshCore.h` lines 20–21: `MAX_PACKET_PAYLOAD` 184, `MAX_GROUP_DATA_LENGTH` = 184 − 16 − 3 = **165**.
- `src/helpers/BaseChatMesh.cpp` lines 544–546: `sendGroupData` returns false above 165.
- `src/helpers/BaseSerialInterface.h` line 5: `MAX_FRAME_SIZE` 176.
- `examples/companion_radio/MyMesh.cpp` line 64: `CMD_SEND_CHANNEL_DATA` is 62. Line 106: `MAX_CHANNEL_DATA_LENGTH` is 167. Lines 1230–1232: a payload longer than 165 is `ERR_CODE_ILLEGAL_ARG` before `sendGroupData`. Lines 1235–1236: `ERR_CODE_TABLE_FULL` is the later branch, when a payload that already fit 165 still fails `sendGroupData`. Lines 603–606: receive drops only above 167.
- `docs/companion_protocol.md` lines 322–339 in the same commit still say 166 and 167 pass a frame check and then return `ERR_CODE_TABLE_FULL`, and they cite `MyMesh.cpp:1265`. That citation does not match the source. The source is the authority. This bridge refuses locally and does not wait for either error code.

MCTAK v0.1 (`gridbasedotnet/meshcore-TAK` `docs/payload-spec.md` at `a4d8d77`, status date 2026-07-27) says there is one size, 167, derived from `MAX_CHANNEL_DATA_LENGTH`, and that a fragment of any other length is malformed. It also says not to add a second size that silently drops traffic. This tree still decodes a 167-byte fragment and still refuses to hand one to `CMD_SEND_CHANNEL_DATA`. The refusal is reported (`BLOCKED_RADIO_LIMIT` / `illegal_arg`). The fragment layout is not changed. IDENT capability bit 0 (FRAG-rx) is advertised clear, because this bridge cannot receive a 167-byte fragment through current group data. That is not a new wire version. The header version nibble stays 0.

No radio was attached. The limit is from the firmware source above, not from a transmission.

## Earlier note, still consistent with the re-read

Commit `9185ad5` kept both numbers. It did not pick a third size.

## What each number is

MCTAK v0.1 defines one application datagram size, and the fragment kind is that size exactly.

- Spec: `gridbasedotnet/meshcore-TAK` `docs/payload-spec.md` (repo commit `a4d8d77`, spec status date 2026-07-27 in that file).
- Line 39: `MAX_DATAGRAM` is **167**, derived from MeshCore `MAX_CHANNEL_DATA_LENGTH`.
- Lines 40–43: header 10, fragment subheader 8, symbol **149**. `10 + 8 + 149 = 167`.
- Lines 346–360: kind `0x04` FRAG is exactly 167 bytes. Any other length is malformed and must be dropped.

MeshCore's radio path does not accept a 167-byte group payload.

- `meshcore-dev/MeshCore` commit `81dbae592ffad40b4212bc0b37e82b38da08f4bc`.
- `src/MeshCore.h` line 14: `CIPHER_BLOCK_SIZE` is 16.
- `src/MeshCore.h` lines 20–21: `MAX_PACKET_PAYLOAD` is 184, and `MAX_GROUP_DATA_LENGTH` is `MAX_PACKET_PAYLOAD - CIPHER_BLOCK_SIZE - 3`, which is **165**.
- `src/helpers/BaseChatMesh.cpp` lines 544–546: `sendGroupData` returns false when `data_len > MAX_GROUP_DATA_LENGTH`.
- `src/helpers/BaseSerialInterface.h` line 5: `MAX_FRAME_SIZE` is 176.
- `examples/companion_radio/MyMesh.cpp` line 106: `MAX_CHANNEL_DATA_LENGTH` is `MAX_FRAME_SIZE - 9`, which is **167**.
- `examples/companion_radio/MyMesh.cpp` lines 1230–1232: `CMD_SEND_CHANNEL_DATA` (command byte 62, line 64) rejects `payload_len > MAX_GROUP_DATA_LENGTH` with `ERR_CODE_ILLEGAL_ARG` before it calls `sendGroupData`. A 166- or 167-byte payload is refused on send.
- `examples/companion_radio/MyMesh.cpp` lines 603–606: `onChannelDataRecv` drops a received payload only when `data_len > MAX_CHANNEL_DATA_LENGTH` (167). The receive frame limit is the larger of the two.

## Where the companion doc does not match this firmware

`docs/companion_protocol.md` in that same MeshCore commit, lines 322–334, says the host-frame check allows 167 and that 166 or 167 then fail later with `ERR_CODE_TABLE_FULL`. It points at `MyMesh.cpp:1265`.

In commit `81dbae5` the send check is at line 1230, it compares against `MAX_GROUP_DATA_LENGTH` (165), and the error is `ERR_CODE_ILLEGAL_ARG`, not `TABLE_FULL`. `TABLE_FULL` on this command is the branch at lines 1235–1236, used when `sendGroupData` fails for a payload that already passed the 165 check. The prose doc and the source disagree. This core followed the source.

## What this tree does

`src/main/java/dev/meshcore/atak/mctak/Mctak.java` keeps `MAX_DATAGRAM = 167` so a FRAG still decodes and still encodes to the spec length. `RADIO_MAX_PAYLOAD = 165` is the send gate. `Engine` will not put a longer datagram into `CMD_SEND_CHANNEL_DATA`.

Short kinds fit under 165 and are what the simulated radios exchange: PLI is 28 bytes, a receipt is 19. A fragment does not. Shrinking the symbol, or defining a second header, would be a different protocol. That was not done.

No radio was attached. The limit is from the firmware source above, not from a transmission.

## v7.2: the 164-byte plugin cap and native channel text

The plugin refuses any chat payload above **164** bytes (`Companion.SAFE_PAYLOAD`, 176 - 3 - 9 from MCTAK v0.2 section 4.3). The 165 limit above is the firmware's; `Mctak.radioWillAccept` still pins it.

Native channel text (`CMD_SEND_CHANNEL_TXT_MSG`, opcode 3, opt-in switch) is built as `{3, 0, channel_idx, timestamp u32le, text}`. `BaseChatMesh::sendGroupMessage` (`BaseChatMesh.cpp`) writes `"<node_name>: <text>"` into a buffer limited by `MAX_TEXT_LEN` (160, `BaseChatMesh.h`) and silently truncates. With the 5-byte header (timestamp and type) the plugin allows `min(160 - (name + 2), 164 - 5 - (name + 2))` UTF-8 bytes, which is `159 - (name + 2)`. A text above that is refused, not truncated. A 4-byte node name allows 153 bytes.

Both opcode 3 and opcode 62 end in `Mesh::createGroupDatagram`, which encrypts with the channel's stored secret (`Utils::encryptThenMAC(channel.secret, ...)`). The companion frame names the channel by index only.

No radio was attached. These numbers come from source, not a transmission.
