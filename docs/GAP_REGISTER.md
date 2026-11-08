# Gap register

States are for this tree after the debug compile of `b4d9f5e` and the release-config change that has not been assembled yet. "Tested" means `./scripts/test.sh` only.

| Capability | State |
| --- | --- |
| MCTAK PLI, short GeoChat, direct receipt, dedup, fountain math | Implemented and tested in the JDK core |
| Send above 165 bytes | Refused. 167-byte fragments still decode. No v0.2 fragment |
| ESP32 notify of a 165-byte datagram | Arithmetic only: host frame is 174 bytes, ATT payload at MTU 176 is 173. A send frame is 170 and fits. No radio run |
| Public, hashtag, derived-hashtag, and all-zero keys | Refused for team traffic. A fingerprint is not sender identity |
| Sequence across process restart | One integer, restored by the plugin. Not a log of identities |
| Radio `PACKET_ERROR` and send timeout | Status `RADIO_ERROR_*` or `SEND_TIMEOUT`. Not a delivery receipt |
| Native MeshCore text frames | Counted as `NATIVE_TEXT_UNSUPPORTED` and not shown as chat |
| Plugin pane chat, opt-in beacon, internal CoT publish | Source compiled in the `b4d9f5e` debug javac. Not run inside ATAK |
| Experimental ATAK GeoChat copy | Default off. `CommsLogger` only. Untested at runtime |
| `PreSendProcessor` | Not used |
| Voice, imagery, files, radio GPS, server relay, iTAK | Unsupported |
| Unsigned release APK | Not built. `assembleRelease` on `b4d9f5e` failed `takdevLint` before R8 |
| Debug APK, signing, retail ATAK, phone install | Not done |
| NDK | Not installed. The plugin has no native libraries |
| Development core vs retail | Debug mapping warning means the SDK dev core, not a retail mapping |

## Claude consultation

The v0.2 spec and the 28-row gap note were written against commit `7a748a4`. They are not instructions. The debug compile of `b4d9f5e` supersedes their "no UI / no SDK integration" description. Details of the six allegations are in `docs/CLAUDE_ALLEGATIONS.md`.
