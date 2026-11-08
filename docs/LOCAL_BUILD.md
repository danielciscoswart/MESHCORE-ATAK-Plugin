# Local ATAK-CIV 5.6.0.26 build

The SDK, its `android_keystore`, and any tak.gov password stay on the machine that downloaded them. This repository does not contain them. Cloud CI does not compile this module.

## Tools the SDK build guide asks for

Observed from the parent review of archive filename `5.6.0.26`, not from an SDK copy in git:

| Tool | Value |
| --- | --- |
| JDK | 17 |
| Gradle | 8.14.3 |
| Android Gradle Plugin | 8.13.0 |
| compileSdk | 36 |
| minSdk | 21 |
| targetSdk | 36 (see the note in `atak-plugin/app/build.gradle`) |
| Java source/target | 1.8 |
| takdev | 3+ , taken from the local jar, not from TAK maven |
| NDK `27.2.12479018` | not used; this plugin has no native code |

A published third-party 5.6 build uses `targetSdkVersion 34`. If the local template fails on 36, set 34 in that one place.

## Files the SDK directory must contain

- `main.jar`
- `atak-gradle-takdev.jar`

Do not copy those into this tree.

## Steps

1. Unpack the source zip (or use this git checkout) so `atak-plugin/settings.gradle` is at the project root of that directory.
2. Install JDK 17 and the public Android SDK with platform 36. That Android SDK is not the ATAK SDK.
3. Copy `local.properties.example` to `local.properties` beside `gradlew`.
4. Set absolute paths:
   - `sdk.dir` — Android SDK
   - `sdkPath` — unzipped ATAK-CIV 5.6.0.26 SDK directory
   - `takdev.plugin` — `atak-gradle-takdev.jar` inside that directory
5. Leave `takrepo.url` unset. Do not put a username or password in `local.properties`.
6. Do not set a keystore property for this unsigned `assembleCivRelease`. Retail or pipeline signing is a later decision and is not part of this build.
7. From the directory that contains `gradlew`, with JDK 17:

```sh
./gradlew --no-daemon assembleCivRelease
```

The flavor `civ` is real. The previous unsigned APK was `assembleRelease` without that flavor, from commit `856a81d`. This civ variant has not been assembled. The likely output, not yet confirmed, is `app/build/outputs/apk/civ/release/app-civ-release-unsigned.apk`. After it exists, from the repository root:

```sh
./scripts/check-unsigned-release.sh app/build/outputs/apk/civ/release/app-civ-release-unsigned.apk
```

The checker needs `aapt2` from Android SDK build-tools (`ANDROID_HOME` or `AAPT2`). It does not treat a missing parser as success, and it does not grep the compiled manifest as plain text.

Do not run `assembleDebug`. A debug package can pick up an automatic debug key, and a debug signature is not a retail ATAK install. Do not set `takDebugKeyFile`. Release has no `signingConfig`. `takdevLint` stays enabled. `minifyEnabled` is true and `android.bundle.storeArchive.enable` is false. JNI packaging is legacy because takdev checks it; there is no NDK version and no native code. The SDK keep file stays on the SDK machine. `isDevKitEnabled()` is false unless `takrepo.url` is set. Leave that URL unset for a local SDK build. The three paths in `local.properties` are `sdk.dir`, `sdkPath`, and `takdev.plugin`.

8. The APK, if the compile succeeds, is an unsigned-or-locally-debug-signed developer build. It has not been loaded into ATAK in this project. A retail ATAK install still needs the Product Center pipeline. Do not install it from this repository's instructions onto a phone as a finished plugin. The pane's MeshCore chat box is the supported outbound chat control. The GeoChat checkbox is experimental and default off.

## What compile success does not prove

- BLE against a radio
- A 165-byte datagram surviving an ESP32 notification
- Map markers, GeoChat, or opt-in position on a device
- The 4.6 CoT method names still matching 5.6.0.26 (`docs/ATAK_API_LOOKUP.md`)

## JDK-only tests, no SDK

From the repository root, with JDK 21:

```sh
./scripts/test.sh
```

Unset `MESHCORE_TAK_FIXTURES` and `MESHCORE_ROOT` skips those two checks. A skip is not a pass.
