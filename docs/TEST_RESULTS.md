# Test results

JDK:

```text
openjdk version "21.0.12.1" 2026-08-18
OpenJDK Runtime Environment (build 21.0.12.1+1-24.04.4-Ubuntu)
```

## Default run

`MESHCORE_TAK_FIXTURES` and `MESHCORE_ROOT` were unset. The two optional rows printed `skipped` and were not included in `PASSED`.

```text
./scripts/test.sh
```

Exit code: 0

```text
ok appendix A PLI bytes
ok geochat broadcast, direct, room
ok receipt, ident, who, xfer
ok malformed and encrypted drops
ok xorshift, soliton, crc, airtime
ok fountain reorder, duplicate, loss
ok fuzz parser
ok governor, public channel, secrets, location
ok two simulated radios
ok PLI resync and self echo
ok official meshcore limits
ok companion session loss duplicate bounds reconnect
ok plugin lifecycle contacts chat markers
skipped optional mctak fixtures
MESHCORE_TAK_FIXTURES unset; byte compare was not executed
skipped optional official meshcore tree
MESHCORE_ROOT unset; official tree was not read by this test
PASSED 13 FAILED 0 SKIPPED 2
meshcore 81dbae592ffad40b4212bc0b37e82b38da08f4bc
```

## Optional checkouts, executed separately

```text
MESHCORE_TAK_FIXTURES=/tmp/refs/meshcore-TAK/docs/payload-spec-fixtures \
MESHCORE_ROOT=/tmp/refs/meshcore \
  java -cp build/classes dev.meshcore.atak.TestMain
```

Those directories are local inspection clones, not files in this repository. The fixture compare decoded `symbols_K28/datagrams.hex` (48 symbols) to `symbols_K28/object.hex` from `gridbasedotnet/meshcore-TAK` @ `a4d8d77`. The official-tree check matched `meshcore-dev/MeshCore` @ `81dbae592ffad40b4212bc0b37e82b38da08f4bc`.

```text
compared fixture symbols=48
ok optional mctak fixtures
official tree 81dbae592ffad40b4212bc0b37e82b38da08f4bc matched pinned limits and framing
ok optional official meshcore tree
PASSED 15 FAILED 0 SKIPPED 0
```

Exit code: 0

## BLE compile

`./scripts/compile-ble.sh` equivalent, using Android SDK platform 35 `android.jar` from `platform-35_r02.zip` (sha256 `0988cacad01b38a18a47bac14a0695f246bc76c1b06c0eeb8eb0dc825ab0c8e0`). `javac --release 21` produced `dev.meshcore.atak.ble.MeshcoreGattLink` class files. That is not an APK, not dex, and not an ATAK plugin. The ATAK SDK was not on the compile classpath.

Not run: instrumented Android tests, ATAK load, BLE bonding, or LoRa. No phone and no radio. Positions in tests are fixed fixtures.
