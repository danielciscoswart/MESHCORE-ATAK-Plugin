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

## What the next local command is

JDK 17, Gradle 8.14.3, same three local paths, `takrepo.url` unset, no keystore properties. Run Gradle inside `atak-plugin`, then the check from the repository root:

```sh
cd atak-plugin
gradle --no-daemon :app:assembleRelease
cd ..
./scripts/check-unsigned-release.sh atak-plugin/app/build/outputs/apk/release/app-release-unsigned.apk
```

Release sets `minifyEnabled true`, `android.bundle.storeArchive.enable=false`, and legacy JNI packaging. It still does not set `signingConfig`. No unsigned APK has been produced. After a successful assemble, the expected file name is `app-release-unsigned.apk`. From the repository root, `scripts/check-unsigned-release.sh` checks that an archive has no signature block and that the dex and manifest still contain `dev.meshcore.atak.host.MeshCoreAtakPlugin` and `dev.meshcore.atak.plugin`. takdev still applies its own core keep rules from the local SDK during `preReleaseBuild`. `proguard-release-keep.txt` is not copied into this repository. `shrinkResources` stays false. Do not disable `takdevLint`. If the next lint line requires a keystore, stop.
