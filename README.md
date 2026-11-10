# MeshCore ATAK plugin

An ATAK-CIV plugin that connects ATAK on an Android phone to a MeshCore radio over Bluetooth, so a small team can chat over LoRa mesh with no cell service or internet.

This is an independent community project. See [Not affiliated](#not-affiliated).

## Status

- **Version 0.7.5.** Early, experimental software.
- **Tested by one developer, on one setup:** a Galaxy-class phone running Android 16, Play Store ATAK-CIV 5.6.0.12, and a Heltec V4 radio running MeshCore companion firmware v1.18.0.
- **Not field-tested.** There are no range or reliability claims. It has not been tried with a group, on other phones, on other radios, or on other ATAK versions.
- The protocol facts come from [meshcore-dev/MeshCore](https://github.com/meshcore-dev/MeshCore) at tag `companion-v1.18.0` (commit `81dbae592ffad40b4212bc0b37e82b38da08f4bc`). Other firmware versions are untested.

## Not for emergencies or life safety

Do not rely on this plugin for emergency communication, navigation, or anything where a missed or late message could hurt someone. Messages can be delayed, dropped, or never arrive. "Sent to radio" only means the radio accepted the message. It does not mean anyone received it.

## What it does

- Connects to a MeshCore companion radio over Bluetooth LE. You choose the radio. Nothing is picked for you.
- Lets you choose one private channel on the radio. You choose the channel too.
- Adds one chat room to ATAK's chat list for that channel. What you type in that room goes to the radio. Messages from that channel show up in the same room.
- Offers two send modes, with a switch in the plugin pane:
  - **Plugin users only** (the default). Sends compact binary data that only other copies of this plugin can read. The MeshCore app will not show these messages.
  - **MeshCore text.** Sends a normal MeshCore channel text message that the MeshCore app can show.
- Once a channel is chosen, reads the radio's whole waiting-message queue, and again whenever a new message arrives, so messages that came in while the phone was away show up in order. Before you choose a channel it reads nothing and leaves messages on the radio.
- Can share your position as a Cursor on Target (CoT) report, only after you tap "Send my position".
- Has a COPY DIAG button that copies a diagnostic summary for bug reports. It does not include keys, PINs, or message text.

The wire format follows the published MCTAK v0.1 datagram layout, so short messages can interoperate with other implementations of that format. `FEATURE_PARITY.md` lists what is implemented, partial, and not supported.

## Limits and things to know

- **It refuses the Public channel for sending.** Public and hashtag channels use keys anyone can derive, so the plugin will not send team traffic on them.
- **Reading a message takes it off the radio.** When the plugin reads a message from the radio's queue, the MeshCore app on that same radio cannot get it afterward. This includes messages on the Public channel, which the plugin reads and shows only in its pane. If you want to read everything in the MeshCore app, use a different radio for ATAK.
- **Messages are unverified.** A channel key shows that someone has the key. It does not prove who sent a message. Anyone with the key can pretend to be anyone else. Inbound messages are marked unverified in ATAK.
- **Backlog timestamps show display time.** A message that waited in the radio's queue is stamped with the time it appeared on the phone, not the time it was sent.
- **Messages are short.** The firmware accepts at most 165 bytes of group data, so voice, images, file transfer, and long messages are not supported.
- **Only the plugin's own chat room can transmit.** Every other ATAK chat is ignored.
- The radio holds up to 256 waiting messages on the Heltec V4. If it fills up, the radio drops its oldest channel message.

## Install

The easy way is the APK on the [GitHub Releases page](../../releases). It is signed through the TAK Product Center Third Party Pipeline, which is what the Play Store build of ATAK-CIV needs to load a plugin. It is built for ATAK-CIV 5.6.x and has only been tried on 5.6.0.12. ATAK marks plugins signed this way as third-party plugins, and that is expected.

1. Download the APK from the release for version 0.7.5 and check its SHA-256 against the value in the release notes and in `CHANGELOG.md`.
2. Install it on the phone. Android will ask you to allow installs from the app you used to open the file.
3. Open ATAK and load **MeshCore ATAK** from its plugin list (Package Management). If the status line at the top of the plugin pane starts with `v7.5 |`, you have this version.
4. Pair the radio in Android's Bluetooth settings first, and close any other app that is connected to the radio. The plugin will not connect to a radio another app already holds.
5. Open the plugin pane, tap CONNECT, choose your radio, then choose a private channel.

A plugin built any other way (an unsigned or SDK debug build) will not load in Play Store ATAK.

## Build from source

You need the ATAK-CIV SDK, and this repository does not include it. Get it yourself from [tak.gov](https://tak.gov) with your own account. The SDK license does not allow copying or redistributing the SDK, so never commit `main.jar`, `atak-gradle-takdev.jar`, `mapping.txt`, any SDK archive or sample, or `android_keystore`.

**Run the tests (no SDK, no Android, no radio).** You need JDK 21 or newer.

```bash
./scripts/test.sh
```

This compiles `src/main/java` and `src/test/java` with `javac --release 21`, runs the unit, simulated-radio, session, and plugin-lifecycle tests, then runs two shell checks. A passing run ends its test summary with `FAILED 0`. Some optional checks need a local checkout of other projects and are printed as `skipped` when you do not provide one. A skip is never counted as a pass. With no optional checkouts set, a passing run today prints `PASSED 104 FAILED 0 SKIPPED 4`, and these numbers will change as tests are added.

To run the optional checks, point them at local checkouts (neither is included here):

```bash
MESHCORE_ROOT=/path/to/MeshCore \
MESHCORE_TAK_FIXTURES=/path/to/meshcore-TAK/docs/payload-spec-fixtures \
  ./scripts/test.sh
```

`MESHCORE_ROOT` must be the MeshCore commit named above. A variable that points at a missing path fails the run.

**Compile the Bluetooth client** against the public Android SDK (no ATAK SDK needed). It makes no APK.

```bash
./scripts/compile-ble.sh
```

**Build the plugin APK locally** with the SDK you downloaded. The steps, versions, and cautions are in `docs/LOCAL_BUILD.md`. In short, copy `local.properties.example` to `local.properties`, fill in your own paths, and run `./gradlew --no-daemon assembleCivRelease`. A local build is unsigned. The script `scripts/check-unsigned-release.sh` checks that APK's manifest and entry class.

**Build a signed APK through the TAK Third Party Pipeline.** Retail ATAK only loads plugins signed by TAK. The pipeline takes one source ZIP with a single root folder, the Gradle wrapper, and the `assembleCivRelease` target. Details are on the pipeline page at tak.gov after you log in and accept its terms. Do not put `local.properties`, keys, SDK files, or build output in the ZIP. After a build, run `scripts/check-ctor-mapped.sh` on the returned APK. It must print PASS. It cannot pass on a local build, by design. `docs/TPP_SUBMISSION.md` has the notes.

## Privacy and safety defaults

- **Sharing your position is off until you tap "Send my position".** Connecting does not send position.
- **Chat sending is opt-in.** The plugin sends nothing until you choose a radio and a channel and type a message in its room. Automatic delivery receipts are off, so the radio never transmits anything you did not type.
- **The plugin never logs channel keys or message text.** It records only a channel's number, its name, and a short fingerprint of its key. It does not keep the key and does not log the radio's Bluetooth PIN.
- Chat in the MeshCore room is saved in ATAK's own chat history on the phone, like any ATAK chat. Removing the room does not delete that history.
- Messages sent on a channel can be read by everyone who has that channel's key, and by anyone who can receive the radio signal and get the key.

## Not affiliated

This is an independent project. It is not affiliated with, endorsed by, or sponsored by the TAK Product Center, the U.S. Government, or the MeshCore project. ATAK and TAK are marks of their owners (ATAK is a mark of the U.S. Air Force). MeshCore is a separate project. These names are used only to describe what this plugin works with.

## Acknowledgements

- [meshcore-dev/MeshCore](https://github.com/meshcore-dev/MeshCore) (MIT, Copyright 2025 Scott Powell / rippleradios.com). The companion protocol, frame layouts, and limits here come from its firmware at the commit above. No MeshCore source files are copied into this repository.
- [gridbasedotnet/meshcore-TAK](https://github.com/gridbasedotnet/meshcore-TAK) (MCTAK). The datagram layout here follows its published MCTAK v0.1 specification. This is an independent implementation. No source, template, or fixture files from that project are included. That project is GPL-3.0-only, so please do not paste its code into this Apache-2.0 tree. `docs/SOURCE_MANIFEST.md` records what was read and what was not copied.

## Contributing and security

See `CONTRIBUTING.md`. To report a security problem, see `SECURITY.md`. Release history is in `CHANGELOG.md`.

## License

The code in this repository is licensed under the [Apache License 2.0](LICENSE). The ATAK-CIV SDK is not part of this repository and is covered by its own license from the TAK Product Center. Third-party source was read for protocol facts and was not copied into this tree.
