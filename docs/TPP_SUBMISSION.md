# TAK third-party pipeline source package

Nothing in this repository has been uploaded or submitted. Signing is not configured. The last APK that was actually built is the unsigned release of commit `856a81d`, recorded in `docs/BUILD_PROVENANCE.md`. Later commits change the Gradle project and have not been assembled.

## Which versions this tree follows

The ATAK 5.6.0 build-environment page and the general pipeline page disagree. This tree follows the 5.6 page for the toolchain, because that is the SDK the local build used:

| Item | This tree | 5.6 page | General pipeline page |
| --- | --- | --- | --- |
| Gradle | 8.14.3, wrapper, distribution SHA-256 pinned | 8.14.3 | 6.9.1 |
| Android Gradle Plugin | 8.13.0 | 8.13.0 | not the 5.6 value |
| takdev | `3.+` when `takrepo.url` is set; local file otherwise | `3.+`, or 3.5.3 or newer if pinned | `2.+` for ATAK 4.2 and newer |
| JDK | 17, Java source/target 1.8 | 17, source/target 1.8 | JDK 17 |
| compile / min / target SDK | 36 / 21 / 36 | 36 / 21 / 36 | not stated as 36 |
| NDK | not declared; no native code | 27.3.13750724 if native libraries are built | older preinstalled NDKs; cannot install one at build time |

No page says which row wins inside the pipeline image. If the service runs `./gradlew`, the wrapper asks for Gradle 8.14.3 from `services.gradle.org` and checks `distributionSha256Sum`. If the service ignores the wrapper and runs Gradle 6.9.1, this project will not configure. That has not been tried.

`artifacts.tak.gov` is not used from this environment. Public users were told that repository is for USG federal and military personnel. The build does not embed a user, a password, or that URL. Maven mode starts only when the caller passes `takrepo.url`. An empty user is a hard failure, not a stand-in login.

## Layout the pipeline asked for

`meshcore-atak-tpp.zip` has one root folder, `meshcore-atak-plugin`. That name is what the service says it uses for APK names. Inside it:

- `gradlew` and `gradle/wrapper/gradle-wrapper.jar` (official Gradle 8.14.3 wrapper, not an SDK jar)
- `app/` with the manifest, `plugin.xml`, resources, and ProGuard rules
- `src/main/java` and `android/ble/src`, which `app/build.gradle` compiles
- no `local.properties`, keystore, `main.jar`, APK, or `.git`

`productFlavors.civ` is a real flavor. There is no `assembleCivRelease` task that only renames `assembleRelease`. The command is `./gradlew assembleCivRelease`. The output path under `app/build/outputs/apk/civ/release/` has not been confirmed, because this commit has not been assembled.

## Discovery and ProGuard

`AndroidManifest.xml` has the `com.atakmap.app.component` activity, `android:exported="true"`, and the action `com.atakmap.app.component` with label `@string/app_name`. The 5.6 page includes `exported`. The general pipeline snippet does not. This tree keeps `exported` because the 5.6 page and current Android target both expect it.

`-repackageclasses atakplugin.MeshCoreAtak` is the identifying name from the 5.6 example shape. The general page's template token `atakplugin.PluginTemplate PluginTemplate` is not used. `-keep` holds `dev.meshcore.atak.host.MeshCoreAtakPlugin` at the name in `assets/plugin.xml`. Whether 5.6 lint accepts that pair has not been re-run.

## Local check, then a separate upload decision

On the machine that already has the SDK, unpack `meshcore-atak-tpp.zip`, put `local.properties` in `meshcore-atak-plugin/` with only `sdk.dir`, `sdkPath`, and `takdev.plugin`, and run:

```sh
cd meshcore-atak-plugin
./gradlew --no-daemon assembleCivRelease
cd ..
./scripts/check-unsigned-release.sh meshcore-atak-plugin/app/build/outputs/apk/civ/release/app-civ-release-unsigned.apk
```

Do not set `takrepo.url`. Do not add a keystore. `aapt2` must be available to the checker. If `assembleCivRelease` stops because a signing key is required, stop. Do not generate one.

The phone reported in the field notes is Play Store ATAK-CIV 5.6.0.12 on Android 16, Samsung S24 Ultra. The SDK used for the earlier unsigned APK is 5.6.0.26. The compatibility page says the plugin API, the obfuscation mapping, and the signing key have to match the installed ATAK. None of that has been tried. A debug signature is not a retail load. Pipeline signing, if a later upload is approved, marks the artifact as third-party signed rather than an official TAK Product Center signature. This document does not claim the service will accept the zip or that the result will run on 5.6.0.12.
