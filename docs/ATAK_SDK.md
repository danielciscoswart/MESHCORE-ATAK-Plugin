# ATAK-CIV SDK and signing

Candidate baseline: **ATAK-CIV 5.6.x**. Public notes used to pick it:

- Meshtastic ATAK plugin material has been described against ATAK 5.6.0.13.
- meshcore-TAK documents its own retail loading on ATAK 5.6 and newer. That is their claim, not a test this repository ran.

The ATAK-CIV plugin SDK is not a public Maven artifact. Current public write-ups say it is downloaded from tak.gov after an account login (ATAK-CIV product, developer resources). This environment did not create that account and did not download the SDK. No `com.atakmap` classes were compiled. No plugin AndroidManifest was added, because a manifest that names a class which does not compile would look like a plugin this tree cannot build.

What is compiled here instead:

- The JDK 21 core, session, and `PluginHost` (`./scripts/test.sh`).
- `android/ble` against the public Android SDK 35 `android.jar` (`./scripts/compile-ble.sh`).

Signing, from public descriptions of ATAK plugin loading, not from a keystore in this repo:

- A developer ATAK build can load a plugin signed with a developer key. This repository does not contain that key and does not generate one.
- Retail ATAK loads plugins signed through the TAK Product Center pipeline. An SDK debug signature is not a retail install.
- CIV and MIL signatures are not interchangeable. This project is aimed at ATAK-CIV only.

Smallest step this tree cannot take by itself: a person with a tak.gov account downloads the ATAK-CIV 5.6 SDK (the version that matches the phone's ATAK build), places it where a plugin module can compile `com.atakmap` against it, and signs with whatever that SDK's developer build expects. Retail distribution is a separate Product Center signing step. No purchase is described in the public CIV SDK notes read for this file; the blocker is the account-gated download and, later, signing.
