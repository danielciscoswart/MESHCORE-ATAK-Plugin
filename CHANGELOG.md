# Changelog

All notable changes to the MeshCore ATAK plugin. Version names come from `versionName` in `app/build.gradle`. This history was written from the git log. Versions 0.3 to 0.6 were never named: the builds between 0.2 and 0.7.0 are listed together under 0.2.x.

This project is early, experimental software. It has been tried by one developer on one phone and one radio, and it has not been field-tested.

## 0.7.5 (versionCode 12), 2026-11-10

Signed by the TAK Third Party Pipeline (build 50936). SHA-256 of the signed APK: `b914981366364fd68b0287a9d3ead067d5f99f554aab3c2ba9b8840630fcab67`.

- The plugin now reads the radio's whole waiting-message queue instead of one message per notification. Before this, the chat room could lag many messages behind.
- Reading starts only after you choose a private channel. Before that, messages stay on the radio, because a message the plugin reads cannot be read by the MeshCore app afterward.
- A bounded duplicate filter drops repeated copies of the same inbound text.
- A typed message goes out after every 8 queue reads, so a large backlog does not block sending.
- Messages the chat room cannot show yet are retried on the next tick instead of being dropped.
- After a mode switch or a channel change, a chat window left open becomes a "(closed)" contact. Typing in it sends nothing and shows a notice.
- A message id is remembered only after the send was queued, so a refused send can be retried. A true repeat gets an explanation instead of silence.
- The unused outbound hold code was removed. Diagnostics gained drain counters and a list of recent inbound message timestamps (no text).

Repository cleanup in the tagged source, no code change (in `app/` and `src/main/` only two code comments differ from the built tree):

- New README with status, limits, install, build, privacy, what COPY DIAG includes, and not-affiliated notes.
- CI no longer pins an exact skip count for the optional tests. It requires the four known optional checks to print their `skipped` lines, and it fails if the test script fails.
- Operator-specific names in test fixtures and comments were replaced with neutral placeholders.
- Added `CHANGELOG.md`, `SECURITY.md`, `CONTRIBUTING.md`, and issue and pull request templates. Added `docs/DESIGN.md` with the reasons behind four behaviors. Removed internal review notes from `docs/`. They stay in the git history. Marked three older notes as historical.
- Tightened `.gitignore` for SDK files, keys, and APKs.

## 0.7.4 (versionCode 11), 2026-11-10

- Room sends go through ATAK's plugin connector Intent, so what you type in the MeshCore room reaches the radio. Before this, text typed in the room showed in the chat window but was never sent, and there was no notice.
- Diagnostics show which channel slot the radio reported for each received message (per-slot counters and a last-received line, no text). This settled where a message that looked like it came from the wrong channel really came from. No change to how messages are parsed.

## 0.7.3 (versionCode 10), 2026-11-09

- One dedicated MeshCore chat room in ATAK's chat list for the chosen private channel, with the pane's OPEN CHAT button.
- Only a message addressed to that room is sent. Every other ATAK chat is ignored. The v7.2 GeoChat copy was removed.
- Inbound channel messages from the chosen channel show in the room, marked as MeshCore and unverified.
- Duplicate and loop protection for room messages, and a clear on-screen reason for every refusal.
- Radio push messages are counted quietly instead of being treated as unknown. The engine text log is capped.
- Automatic delivery receipts are off. A room's mode (plugin only or MeshCore text) is part of its identity.

## 0.7.2 (versionCode 9), 2026-11-09

- You choose the radio and the channel. Nothing is picked for you. The chosen radio is remembered and can be forgotten.
- Settings are stored in ATAK's own context.
- Native MeshCore channel text can be read and (with the "Plugin users only" switch turned off) sent. The switch is on by default and goes back to on whenever the radio or channel changes.
- Every refusal says why. The diagnostic summary gained more fields.
- One airtime budget is shared across links, "no answer" wording is honest, and failed sends are not retried automatically.

## 0.7.1 (versionCode 8), 2026-11-09

- Refuses to connect when another app already holds the radio.
- A failed Bluetooth write ends the attempt with its status code. Short hints for common Android Bluetooth errors. Visible MTU and handshake progress.

## 0.7.0 (versionCode 7), 2026-11-09

- A connect state machine with a fixed header (CONNECT, DISCONNECT, COPY DIAG, one status line).
- Bonded radios are tried first. Every stage has a time limit and records its status code.
- COPY DIAG puts a diagnostic summary on the clipboard. It has no PIN, keys, or frame bytes.

## 0.2.x (versionCode 1, versionName `0.2-source`), 2026-11-08 to 2026-11-09

- Plugin construction was moved out of the constructor so a setup failure cannot stop the plugin from loading.
- ProGuard rules now apply ATAK's class mapping. This fixed a load failure in Play Store ATAK-CIV 5.6.0.12. `scripts/check-ctor-mapped.sh` checks the pipeline's APK for it.
- Bluetooth connect fixes: scan timeout, permission request through ATAK's activity, an unfiltered scan with a name fallback, and a pane layout fix.
- Release obfuscation, a public Gradle 8.14.3 wrapper, `assembleCivRelease`, and an unsigned-release checker, so the source can go through the TAK Third Party Pipeline.
- The ATAK plugin module: lifecycle, pane, and an explicit MeshCore chat control.
- Channel safety: all-zero and hashtag-derived keys are refused, and the outbound sequence number can resume.

## 0.1 (source only, no version name), 2026-11-08

- The core in plain Java: MCTAK v0.1 codecs, fountain code math, reassembly, airtime governor, MeshCore companion frames, a companion session, and a Bluetooth LE client that compiles against the public Android SDK.
- MeshCore firmware at commit `81dbae5` is the authority for sizes and framing. A 167-byte MCTAK fragment is refused at the radio's 165-byte limit.
- Optional checks against the firmware tree and the MCTAK fixtures are skipped, not counted as passes, unless you supply those checkouts.
