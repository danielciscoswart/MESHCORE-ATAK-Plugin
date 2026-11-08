# Android BLE client

`MeshcoreGattLink` is a GATT client for the official MeshCore companion service (Nordic UART UUIDs, one frame per write, MTU requested at 512, write-with-response). It calls `RadioSession`. It does not scan or connect during unit tests.

Compile it with the public Android SDK. This directory is not a Gradle application and does not build an APK:

```bash
./scripts/compile-ble.sh
```

The script downloads Android platform 35, checks the pinned zip hash, and runs `javac` against `android.jar`. The class files are Java 21 bytecode. They are not dex and they are not loadable as an ATAK plugin. The ATAK-CIV SDK is not on this classpath.

Permissions, bonding, background, and the locked screen are described in `docs/BLE_TEST_PLAN.md`. They have not been executed on a phone.
