# Claude allegations checked against the current tree

The consultation was aimed at commit `7a748a4`. The code below is the current branch. None of this was taken from the consultation as an instruction.

| Claim | Result |
| --- | --- |
| Sequence restarts at 0, so a peer that still remembers recent sequence numbers drops the new process's first packets | Confirmed for the in-memory counter. `Engine.resumeAfter` keeps one integer, the last issued sequence. The plugin writes that integer to `mctak-seq.txt` and reads it back. The file is overwritten, not appended. It does not store a key, a PIN, or a callsign. Peers still resync PLI after three rejected sequences if they never see the continued numbers. |
| A named slot with an all-zero key was accepted | Confirmed. An all-zero secret is now unsafe even when the name is not empty. `selectChannel` returns `REFUSED_PUBLIC`. |
| A hashtag-derived key was accepted when the name had no `#` | Confirmed against the official example. SHA-256 of `#test` begins `9cd8fcf22a47333b591d96a2b848b73f`. A slot named `test` with that secret is now refused. A different 16-byte secret on the same name is still allowed. The fingerprint is not sender authentication. |
| Radio send errors were only counted, so the pane could still look successful | Confirmed. `PACKET_ERROR` now sets `RADIO_ERROR_<code>` after a `TABLE_FULL` retry is exhausted, and other error codes set it immediately. A send that times out sets `SEND_TIMEOUT`. Neither path creates a receipt. |
| `Companion.sendChannelData` allowed a 167-byte datagram | Confirmed as a builder hole. The radio gate already refused it. The builder now refuses anything above 165. Decode of a 167-byte fragment is unchanged. |
| Native MeshCore text was taken off the radio queue and discarded | Confirmed that sync and unexpected text frames (`0x07`, `0x08`, `0x10`, `0x11`) are not shown as GeoChat. They now increment `native_text_unsupported` and set `NATIVE_TEXT_UNSUPPORTED`. They are not parsed. Native text is not a supported feature. |
| ESP32 send cap should become 164 | Rejected. A 165-byte flood send is 170 bytes and fits a 173-byte ATT payload. The 174-byte inbound notify is an untested arithmetic concern. No new fragment size. |
| Fork the GPL plugin or copy its template | Rejected. Not done. |
