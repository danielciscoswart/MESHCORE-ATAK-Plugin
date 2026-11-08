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
3. Copy `atak-plugin/local.properties.example` to `atak-plugin/local.properties`.
4. Set absolute paths:
   - `sdk.dir` — Android SDK
   - `sdkPath` — unzipped ATAK-CIV 5.6.0.26 SDK directory
   - `takdev.plugin` — `atak-gradle-takdev.jar` inside that directory
5. Leave `takrepo.url` unset. Do not put a username or password in `local.properties`.
6. Point `takDebugKeyFile` at a debug keystore that stays on this machine. Do not use a path inside the git tree, and do not commit the SDK's bundled `android_keystore`. Retail TAK Product Center signing is a separate decision and is not configured here.
7. From `atak-plugin`, with Gradle 8.14.3 and JDK 17:

```sh
gradle --no-daemon :app:assembleRelease
../scripts/check-unsigned-release.sh app/build/outputs/apk/release/app-release-unsigned.apk
```

There is no `civ` flavor. The variants are `debug` and `release`. Do not run `:app:assembleDebug` for this package: a debug assemble can be signed with the automatic debug key. Do not set `takDebugKeyFile`. Release has no `signingConfig`. `takdevLint` requires `minifyEnabled true` and `android.bundle.storeArchive.enable=false`. Both are set. JNI packaging uses the legacy extractor because that is another takdev 3.x check; this project has no native libraries. Do not skip `takdevLint`. The SDK keep file stays on the SDK machine and is applied by takdev's `preReleaseBuild` step. Commit `8b92d89` compiled in debug and failed release at the archive flag. No unsigned APK has been produced.

`atak-plugin/app/build.gradle` defines `isDevKitEnabled()` before `atak-takdev-plugin` is applied. It returns false unless `takrepo.url` is set, and `takrepoUser` / `takrepoPassword` default to empty strings. Do not set `takrepo.url`. The local `takdev.plugin` jar stays on the classpath from `atak-plugin/build.gradle`. A configuration run of commit `90503e6` failed with `Could not find method isDevKitEnabled()` and then reported a missing `compileSdk` because the `android` block never finished. This change has not been compiled against the SDK in this environment.

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
