# Build provenance

This file records commands that were actually run. A successful Java compile is not ATAK load, retail signing, BLE, or a radio test.

## Debug compile of commit `b4d9f5e`

Parent machine, SDK kept local. `VERSION.txt` in that SDK tree: `ATAK CIV SDK 47de477`. Archive filename `5.6.0.26`. JDK 17.0.21 for Gradle, JDK 21.0.13 for `./scripts/test.sh`, Gradle 8.14.3, Android Gradle Plugin 8.13.0, public Android platform 36, build-tools 36.0.0. `local.properties` had `sdk.dir`, `sdkPath`, and `takdev.plugin` only.

`:app:tasks --all` exited 0. The variants are `debug` and `release`. There is no `civ` flavor.

`:app:compileDebugJavaWithJavac` exited 0 in 14s, 19 tasks. Resources and the manifest tasks ran, including `processDebugResources`. The log notes a deprecated API in `MeshCoreAtakPlugin.java`. The parent counted 55 compiled classes, including the host and `ExperimentalGeoChatWatch`. No ATAK symbol errors. JDK tests: `PASSED 13 FAILED 0 SKIPPED 2`.

`:app:installRequiredNdk` printed `CI variable not set. Skipping NDK installation.` No NDK was installed. Native merge tasks were `NO-SOURCE`. This plugin has no native code. The SDK guide's NDK pin is unused here.

`preDebugBuild` printed `no mapping file could be established, obfuscating just the plugin to work with the development core`, then `augment the proguard file with the atak core rules`. That warning means the debug plugin is lined up with the SDK's development ATAK core, not with a retail ProGuard mapping. Retail ATAK still requires the TAK Product Center pipeline. A missing mapping file is not a retail signature.

## Release package of the same commit

`:app:assembleRelease` reached `preReleaseBuild` (`augment the proguard file with the atak core rules`) and failed at `:app:takdevLint` with `Obfuscation disabled. Please set minifyEnabled to true.` Release had `minifyEnabled false`. No APK was produced. No keystore was opened. Debug assemble was not run, because a debug package can be signed with the automatic debug key.

## Release package of commit `8b92d89`

Same machine and SDK. `:app:compileDebugJavaWithJavac` exited 0 again. JDK tests were 14 passed and 2 optional skips. `:app:assembleRelease` exited 1 at `:app:takdevLint` before R8 and before any APK existed. The message was `android.bundle.storeArchive.enable is enabled. Please set storeArchive to false for proper release signing.` No keystore was opened and nothing was signed. The wording mentions signing; the failed check is the archive flag, not a missing key. This repository still does not declare a signingConfig.

## Source after `856a81d`

Commits after `856a81d` change the checker and then the Gradle layout. They have not been assembled. The 32003-byte APK remains the only measured package, and it is the no-flavor `assembleRelease` output. It is not `assembleCivRelease`.

## What the next local command is

JDK 17, Gradle 8.14.3, same three local paths, `takrepo.url` unset, no keystore properties. Run Gradle inside `atak-plugin`, then the check from the repository root:

```sh
cd atak-plugin
gradle --no-daemon :app:assembleRelease
cd ..
./scripts/check-unsigned-release.sh atak-plugin/app/build/outputs/apk/release/app-release-unsigned.apk
```

Release sets `minifyEnabled true`, `android.bundle.storeArchive.enable=false`, and legacy JNI packaging. It does not set `signingConfig`. takdev still applies its own core keep rules from the local SDK during `preReleaseBuild`. `proguard-release-keep.txt` is not copied into this repository. `shrinkResources` stays false. Do not disable `takdevLint`. If a later lint line requires a keystore, stop.

## Unsigned release of commit `856a81d`

Same local SDK and tools. `:app:compileDebugJavaWithJavac` exited 0. `:app:assembleRelease` exited 0 with R8 enabled and no `signingConfig`. JDK tests were 14 passed and 2 optional skips.

Measured artifact, not stored in git:

| Field | Value |
| --- | --- |
| File | `app-release-unsigned.apk` |
| Size | 32003 bytes |
| SHA-256 | `3f0a968225f6c4723eb2632ee60f93d291b29dab8bc190f8b511dab6d3cb1cdc` |
| `aapt2 dump badging` package | `dev.meshcore.atak.plugin` |
| versionCode / versionName | 1 / `0.2-source` |
| minSdk / targetSdk / compileSdk | 21 / 36 / 36 |
| `plugin-api` | `com.atakmap.app@5.6.0.CIV` |
| `assets/plugin.xml` impl | `dev.meshcore.atak.host.MeshCoreAtakPlugin` |
| R8 | that class name is still in the dex |

`apksigner verify` rejected it with `Missing META-INF/MANIFEST.MF`. That is the unsigned result. No signature block was present. No key was used. It was not installed. A debug signature is not a retail ATAK load. The development-core mapping warning still means this is not a Product Center build.

`scripts/check-unsigned-release.sh` on that APK exited 1 with `manifest package string dev.meshcore.atak.plugin not found`. It grepped the compiled binary manifest as text. `aapt2` already showed the package. The script now requires `aapt2 dump badging` and `aapt2 dump xmltree`, and exits 1 when `aapt2` is missing. `scripts/test-check-unsigned.sh` rejects a wrong package, a signature block, a missing dex name, a wrong `plugin.xml`, and a missing `aapt2`. Those fixtures are not the measured APK. The Java sources that produce the payload were not changed for this verifier fix.
