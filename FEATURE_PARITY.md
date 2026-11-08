# Feature parity

Parity target: the **Meshtastic ATAK plugin** described at
<https://meshtastic.org/docs/software/integrations/integrations-atak-plugin/>
and in `meshtastic/ATAK-Plugin` `README.md` at commit `784530f` (2026-06-04).
That plugin binds to the Meshtastic app `IMeshService`, intercepts outbound CoT,
and listens for `ATAK_PLUGIN` packets. The feature rows below follow that README's
feature list (PLI, GeoChat, receipts, file transfer, voice, external GPS, server
relay, display and channel preferences). They are not the Meshtastic 2.8 in-app TAK server.

Official MeshCore behavior cited in this table is `meshcore-dev/MeshCore` commit
`81dbae592ffad40b4212bc0b37e82b38da08f4bc`. MCTAK sizes come from the
gridbasedotnet specification and are not firmware limits.

Meshtastic Android 2.8 replaced that plugin with an in-app local TAK server and a
Mesh-to-CoT converter (protobuf TAK v1 on port 72, zstd TAK v2 on port 78, plus
shapes, routes, and other typed events). This project does **not** claim parity
with that newer path. The matrix below is the plugin feature list the docs page
still describes, plus the MeshCore behaviors this core actually has.

States:

| State | Meaning |
| --- | --- |
| implemented | Behavior exists in this tree and a named test exercises it |
| partial | A safe subset exists; the Meshtastic behavior is not fully matched |
| not-supported | Refused or absent on purpose |
| unverified | Not run against ATAK, a handset, or a MeshCore radio |

No row is field-verified. "Tests" means `./scripts/test.sh` only.

| Feature | Meshtastic plugin | This core | State | Test |
| --- | --- | --- | --- | --- |
| PLI / friendly ground CoT `a-f-G-U-C` | Yes, via protobuf or XML | Packed MCTAK PLI, 1e-7 degree, stale +600 s | implemented, unverified on air | `pliBytes`, `radios` |
| PLI opt-in | Sends when the plugin is connected | Default off. No fix, no beacon | implemented | `policy` |
| PLI rate limit | Default 5 min, 30 s–30 min | Default 300 s. Unaffordable beacons are skipped, not queued | implemented | `policy`, `radios` |
| Stale / insane clock | Receive-time display in older paths | `time = ts` when within 1 day, else `time = now` | implemented | `pliBytes` |
| GeoChat all rooms | Yes | Scope 0, short text | implemented | `geochatBytes`, `radios` |
| GeoChat direct | Yes | Scope 1 | implemented | `geochatBytes`, `radios` |
| GeoChat named room | Group chat in ATAK | Scope 2 | implemented | `geochatBytes` |
| Oversize chat | Fountain above ~231 bytes | MCTAK would use FRAG content 3 (167 bytes). Official `MAX_GROUP_DATA_LENGTH` is 165, so the send is refused | partial | `policy`, `official meshcore limits` |
| Delivered receipt | Optional, default on | Direct chat only, one delivered receipt (`b-t-f-d`) | implemented | `radios` |
| Read receipt | Same switch | Coded, default off. Broadcast and rooms never receipt | implemented | `radios` |
| Contact callsign | Node long name | IDENT callsign cache. Derived uid `MCTAK-` + 8 hex chars | partial | `radios`, `otherKinds` |
| Channel select | Meshtastic channel index | Companion channel list. Public and hashtag refused | implemented | `policy` |
| Show peer marker | Map marker from PLI | In-memory contact from the CoT string. No ATAK map item. First datagram from an unknown sender is held | partial | `radios`, `plugin lifecycle contacts chat markers` |
| Hide own node | Preference | Self-echo (own pubkey prefix) dropped | partial | `pliResync` |
| Hide 0,0 | Preference | Beacon requires a finite fix. No separate 0,0 filter | partial | `policy` |
| External GPS from the radio | Meshtastic GPS as ATAK's GPS | Not implemented. Advertisement coordinates are not copied into ATAK | not-supported | — |
| Server relay either direction | Preferences, default off | Not implemented | not-supported | — |
| File / mission package | Fountain, default off, max 56 KB | Content type 2 counted and dropped. No transfer | not-supported | `fountain` |
| Voice memo / PTT / TTS | Vosk, English | `sendVoice` returns `UNSUPPORTED` | not-supported | `policy` |
| Imagery | Not in the 1.1 plugin README as a feature; airtime makes it unattractive | `sendImage` returns `UNSUPPORTED` | not-supported | `policy` |
| Waypoints and other markers | Generic CoT, sometimes EXI | Waypoint XML can be built and is kept in memory with `transmitted=false`. TX still needs a 167-byte FRAG, which official firmware rejects | partial | `policy`, `plugin lifecycle contacts chat markers` |
| Fountain fragmentation | LT codes over Meshtastic | Systematic robust-soliton codec, duplicate and out-of-order symbols, 50% symbol loss on K=1 | implemented in memory, not transmitted | `fountain`, `fixtures` |
| Dedup | Mesh duplicates | `(sender, seq)` plus chat `(sender, msgId)` | implemented | `radios`, `fountain` |
| Reorder window for PLI | Not the same rule | Drop when the seq looks old; resync after 3 consecutive drops; forget after 1 h | implemented | `pliResync` |
| Airtime governor | Hop limit and prefs | Token bucket, 250 ms pacing, priority queues, expiry, PLI skip | implemented | `math`, `policy` |
| BLE connect / reconnect | Binds `IMeshService` and retries | `RadioSession` speaks the official companion handshake, one frame per write, sync-on-`0x83`, backoff, and the 165-byte gate. `MeshcoreGattLink` compiles against Android SDK 35. No handset, bond, or notification was run | partial, unverified on a device | `companion session loss duplicate bounds reconnect`; `scripts/compile-ble.sh` |
| Bounded queue | Not publicly specified the same way | Depth 8 per class, 30 s / 60 s lifetimes | implemented | `policy` |
| App-layer AES-GCM | Optional PSK | ENC bit with no key is dropped and counted. PBKDF2 and GCM are not implemented | partial | `malformed` |
| Channel key as authentication | Meshtastic channel PSK is also not a signature | Documented non-goal. CoT carries `__meshcore unverified="true"` | implemented as a warning, not a defense | `pliBytes`, `radios` |
| iPhone / iTAK | Separate Meshtastic apps | No | not-supported | — |
| ATAK plugin UI, tool menu, preferences screen | Yes | `PluginHost` has create/start/stop/destroy plus contact, chat, and marker lists. No view, no `com.atakmap` type, not loaded into ATAK | partial, unverified in ATAK | `plugin lifecycle contacts chat markers` |
| Retail or debug APK | Published releases | Not built | not-supported in this tree | — |

## Deliberate non-goals

- No voice, imagery, or file bytes on the air.
- No TAK server bridge.
- No claim of Meshtastic protobuf port 72 / 78 interoperability. The wire format is MCTAK inside MeshCore `DATA_TYPE_DEV` (`0xFFFF`).
- No API to choose a sender id. The sender prefix comes from `SELF_INFO`'s public key.
- Team/role nibbles are copied into CoT labels because the MCTAK table defines them. The core has no other behavior for those labels.

## Release blockers that are still true

1. ATAK-CIV SDK 5.6 is not in this tree, so there is no `com.atakmap` plugin class, plugin manifest, or APK.
2. Official MeshCore commit `81dbae5` will not transmit a 167-byte MCTAK fragment (`ERR_CODE_ILLEGAL_ARG` above 165). Markers and oversize chat stay in memory. No versioned smaller fragment was added.
3. The GATT client compiles against Android SDK 35 and has not been run on BLE, LoRa, or ATAK. Tests use an in-process frame exchanger.
4. Sequence numbers live in memory only. A process restart re-seeds; receivers resync after three rejected PLIs, which is the recovery the spec describes, not a disk store.
5. Optional ENC and the full transfer timer state machine (20 s rounds, R4, implosion jitter) are not wired. The size math and the decoder are.
