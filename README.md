# MeshCore ATAK plugin

Civilian off-grid learning software that maps MeshCore companion-radio traffic to a small Cursor on Target (CoT) subset: opt-in position reports, GeoChat, and direct-message delivery receipts. The datagram layout follows published **MCTAK v0.1** so a short kind can interoperate with that format. It does not define a second payload, and it does not shrink the 167-byte MCTAK fragment.

Official companion and radio behavior is taken from [meshcore-dev/MeshCore](https://github.com/meshcore-dev/MeshCore) commit `81dbae592ffad40b4212bc0b37e82b38da08f4bc`. The gridbasedotnet plugin is an interoperability reference, not the firmware authority. This repository does not modify that upstream.

This tree is **not** an installable ATAK plugin and it has **not** been fielded on a radio or a phone. No APK is produced. The Android BLE client compiles against the public Android SDK only. The ATAK-CIV SDK was not available here.

## What you can run here

JDK 21 or newer. No Android SDK, ATAK SDK, Gradle, or radio is required.

```bash
./scripts/test.sh
```

The script deletes `build/classes`, compiles `src/main/java` and `src/test/java` with `javac --release 21`, and runs `dev.meshcore.atak.TestMain`. With neither optional checkout set, a passing run prints `PASSED 13 FAILED 0 SKIPPED 2`. The two skipped rows are not counted as executed.

Optional checks, only when you point them at local checkouts (neither tree is vendored):

```bash
MESHCORE_TAK_FIXTURES=/path/to/meshcore-TAK/docs/payload-spec-fixtures \
MESHCORE_ROOT=/path/to/MeshCore \
  ./scripts/test.sh
```

`MESHCORE_ROOT` must be commit `81dbae592ffad40b4212bc0b37e82b38da08f4bc`. When that variable is unset, the official-tree read is skipped. When `MESHCORE_TAK_FIXTURES` is unset, the MCTAK byte compare is skipped. Setting either variable to a missing path fails the run.

The BLE adapter is compiled separately. It needs the public Android 35 platform `android.jar` and does not produce an APK:

```bash
./scripts/compile-ble.sh
```

## Layout

| Path | Role |
| --- | --- |
| `src/main/java/dev/meshcore/atak/mctak` | MCTAK codecs, fountain math, reassembly, airtime |
| `src/main/java/dev/meshcore/atak/companion` | Companion-radio frames. Channel secrets are not retained |
| `src/main/java/dev/meshcore/atak/engine` | Link state, governor, queue, CoT bridge |
| `src/main/java/dev/meshcore/atak/transport` | Companion session: one write is one frame, 165-byte gate, sync-on-tickle |
| `src/main/java/dev/meshcore/atak/plugin` | Lifecycle plus in-memory contacts, chat, and markers. No ATAK types |
| `android/ble` | GATT client. Compiles against the public Android SDK. Not an APK |
| `src/main/java/dev/meshcore/atak/cot` | PLI, GeoChat, receipt, and waypoint XML |
| `src/main/java/dev/meshcore/atak/atak` | Named ATAK integration points. No SDK types |
| `src/test/java` | Unit, fuzz, simulated-radio, session, and lifecycle tests |
| `FEATURE_PARITY.md` | Implemented, partial, unsupported, unverified |
| `docs/SOURCE_MANIFEST.md` | Repos, commits, licenses, and the reuse decision |
| `docs/REA_LOG.md` | Which REA steps actually ran |

## ATAK baseline

**ATAK-CIV 5.6.x** remains the candidate, from public notes: Meshtastic plugin material tested with 5.6.0.13, and meshcore-TAK's own retail-5.6 claim. The exact phone and ATAK build for this project are unknown, and nothing here has been loaded into ATAK.

The ATAK-CIV SDK is distributed from tak.gov after an account login. It was not downloaded, and no account was created. Retail ATAK loads plugins that the TAK Product Center pipeline has signed. A debug keystore from the SDK does not make a plugin acceptable to retail ATAK. This repository does not contain a keystore and does not ask for one. See `docs/ATAK_SDK.md`.

## Safety defaults

- Position sending is off until `setLocationOptIn(true)`. Tests use the published spec example (51.5074, -0.1278) or other fixed fixtures, never a live GPS fix.
- The firmware Public channel and hashtag channels are refused for team traffic. Hashtag keys are derivable from the channel name.
- Channel possession is not sender authentication. Inbound CoT is marked `unverified="true"`.
- Voice, imagery, and file transfer are refused.
- Logs record channel index, name, and a SHA-256 fingerprint prefix. They do not record the 16-byte channel secret or the BLE PIN.
- Datagrams longer than 165 bytes are not handed to the companion send frame. See the radio-limit note below.

## Radio-limit blocker

MCTAK fixes a fragment symbol at **167** bytes (`MAX_DATAGRAM`, derived from the companion host constant `MAX_CHANNEL_DATA_LENGTH`). Official MeshCore commit `81dbae5` rejects group data above **165** bytes (`MAX_GROUP_DATA_LENGTH` in `src/MeshCore.h`, enforced in `MyMesh.cpp` with `ERR_CODE_ILLEGAL_ARG`). Short kinds fit and are what the simulated radios exchange: PLI (28), chat, receipts (19), IDENT, WHO, and transfer status. A 167-byte fragment is encoded and tested in memory, then refused before a write. This project does not shrink the symbol, advertise FRAG-rx, or invent a versioned header. The companion document in that same commit still describes 166 and 167 as `ERR_CODE_TABLE_FULL`. The firmware source is the authority. See `docs/RADIO_PAYLOAD_LIMIT.md`.

## License

Original code in this repository is Apache-2.0. See `LICENSE` and `docs/SOURCE_MANIFEST.md`. Third-party source was inspected for protocol facts and was not copied into this tree.
