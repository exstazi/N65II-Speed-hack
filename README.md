# N65II v1.1.7 — Broad 4/6/7 telemetry + reboot logger

This build fixes the v1.1.6 diagnostic assumption that `0x70 DATA[26]` must echo the requested mode. On the tested 6001 / Meter 2058 / BLDC0005, normal `0x58=5` also read back as driveMode 0, so v1.1.7 treats that field as non-decisive.

The logger now captures raw B003 notification chunks and every parsed NAVEE frame, explicitly requests 0x70/0x90/0x91/0x92 at baseline and after modes 4/6/7, restores mode 5 between candidates, selects the candidate with the strongest observable telemetry delta (fallback = 4), leaves it active, and **always enters WAIT_POWER_CYCLE**. After the physical reboot, CHECK AFTER REBOOT captures the same telemetry again and exports the full evidence. No DFU/firmware write is performed by the logger.

# Historical v1.1.6 — Multi-mode 4/6/7 reboot logger (superseded by v1.1.7)

Before any release firmware is flashed, TOOLS now performs one stationary diagnostic sweep on stock BLDC0005: baseline 0x70 → 0x58 values 4, 6 and 7 with a fresh 0x70 readback after each and a baseline restore between probes. The first value that is actually forwarded is re-applied, then one physical power cycle is used to test whether that state is volatile. The exported log contains every raw 0x70 frame and byte diff. No DFU is started by the logger.

# Historical v1.1.5 — MODE 4 reboot logger (superseded by v1.1.7)

Dedicated Android app for **NAVEE N65i II 6001 / Meter 2.0.5.8 / BLDC 0.0.0.5 only**.

## Why v1.1.5 exists

Before flashing the hidden MODE 4 release profile, this build proves two things on the stock scooter:

1. Meter 2058 actually forwards `0x58 = 4` and `0x70 DATA[26]` reports `4`.
2. MODE 4 does **not** survive a physical scooter power cycle.

The logger itself never starts DFU and never modifies firmware. It only performs the existing NAVEE drive-mode write plus readbacks. Release-firmware preflight remains blocked until both checks pass.

## Test sequence

1. Connect the exact `PID 2509 / Meter 2058 / BLDC0005` scooter.
2. Keep the scooter completely stationary.
3. Open **TOOLS → MODE 4 / REBOOT LOGGER**.
4. Press **START LOG**.
5. The app records a fresh baseline `0x70`, sends `0x58 = 4`, then records another fresh `0x70`.
6. If live `driveMode[26]` becomes `4`, the logger stops at `WAIT_POWER_CYCLE`.
7. Physically switch the scooter OFF, then ON.
8. Reconnect the app.
9. Press **CHECK AFTER REBOOT**.
10. The app captures a fresh `0x70` and reports whether mode 4 disappeared.
11. Press **SHARE LOG** to export a text log containing all three raw `0x70` snapshots and the conclusion.

A normal BLE reconnect is deliberately **not** treated as proof of a scooter reboot. The final check only runs after the user presses `CHECK AFTER REBOOT`.

Expected ideal result:

```text
Baseline:       5 (or another normal mode)
After 0x58=4:  4
After reboot:   5/3/2 — anything except 4
Result: VOLATILE CONFIRMED
```

If MODE 4 is not forwarded, or if it remains `4` after reboot, the release firmware stays blocked.

## Flash gating

The release patch still exists in this source tree, but `CONNECTION PREFLIGHT + ARM` now requires both:

- `mode4Verified = true`
- `mode4VolatileVerified = true`

This prevents using the release profile until the stock-behaviour test has been completed successfully.

---

Dedicated Android app for **NAVEE N65i II 6001 only**.

## What changed in v1.1.4

The BLDC0005 selector already contains a distinct **mode 4** path. v1.1.4 keeps normal SPORT independent and turns the existing mode-4 path into a fourth, hidden runtime profile:

- ECO: 1–60 km/h
- DRIVE: 1–60 km/h
- SPORT NORMAL: 1–60 km/h
- SPEED RELEASE / hidden MODE 4: 1–60 km/h

The default fresh-install profile is `E6 / D15 / S20 / RELEASE40`. Existing installs keep their saved E/D/S values and get RELEASE=40 by default.

The important difference from the earlier live-mode prototype is that **SPORT no longer has to be flashed at the release speed**. Example:

`OFF -> ECO 6 / DRIVE 15 / SPORT 20`

`ACTIVATE -> hidden MODE 4 -> 40`

`LOCK -> SPORT mode 5 -> 20`

No firmware flash is performed when ACTIVATE/LOCK is pressed. The BLDC firmware is flashed once with all four caps; the button only sends the normal NAVEE drive-mode write (`0x58`).

## Fail-closed Meter 2058 verification

The old project history did not contain a hardware result proving that **Meter 2.0.5.8 forwards mode 4** on the exact 6001/0005 pair. v1.1.4 therefore does not assume it.

Before the release firmware can be armed for DFU:

1. Connect the exact N65i II 6001 / Meter 2058 / BLDC0005.
2. Keep the scooter stationary.
3. Open TOOLS -> SPEED RELEASE.
4. Press `TEST / ACTIVATE`.
5. The app sends `0x58 = 4`, refreshes `0x70`, and requires live `driveMode[26] == 4`.
6. When the card says mode-4 passthrough is **VERIFIED**, press `LOCK` to return to mode 5.
7. Only then does FLASH preflight allow the new firmware to be armed.

If live mode never becomes 4, **do not flash this release profile**. That means Meter 2058 is filtering/clamping the value and the next job is a meter-side passthrough patch instead.

## Exact flash target

- Model: N65i II 6001
- PID: `2509`
- pid5: `25092`
- Meter: `2.0.5.8` / `2058`
- BLDC: `0.0.0.5` / `0005`
- DFU component: **2 / BLDC only**
- Stock BLDC SHA-256: `03c8c60163eeea4e49367edd1b54235d9f9fd0776a1eee84f93a4248d8561b02`

FLASH and TOOLS remain locked until the live scooter reports the exact PID + Meter + BLDC combination above.

## BLDC0005 mapping used by this build

The selector uses:

- mode `0x0B` -> ECO
- mode `2` -> DRIVE
- modes `3/5` -> normal SPORT/Turbo
- mode `4` -> hidden RELEASE

Main verified locations in `Navee6001SpeedProfiles.kt`:

- selector: `0x36A4..0x36BA`
- ECO load: `0x36C6`
- DRIVE load: `0x36CA..0x36CC`
- SPORT load: `0x36D0`
- hidden mode-4 entry: `0x36D4`
- hidden mode-4 release literal load: `0x36EC`
- SPORT literal: `0x3964`
- DRIVE literal: `0x3968`
- ECO literal (reused stock mode-4 flag literal): `0x396C`
- RELEASE literal: `0x3978`

The patch changes the mode-4 entry at `0x36D4` to branch directly to the existing `0x36EC` literal-load path. No new code cave is introduced. The compressed initialized-data stream must remain byte-equivalent after decompression, and both CRC-16/XMODEM seals are regenerated and verified before DFU can be armed.

For the validation profile `E6 / D15 / S20 / R40`, the capZ values are:

- ECO = `118`
- DRIVE = `298`
- SPORT = `397`
- RELEASE = `795`

Offline validation candidate SHA-256 from this source tree:

`245ab03a388bea4ad48e50937eb3432feceb7cdb12ff495ccb189ce00ecc8511`

This hash applies specifically to E6/D15/S20/R40; changing any slider intentionally changes the candidate hash.

## TOOLS UI

The former km/h <-> mph card is replaced by **SPEED RELEASE**. The actual NAVEE `0x55` display-unit command is not repurposed or patched; only its UI slot is used.

The release card shows the live `0x70` drive-mode value. RELEASE is shown active only when the scooter reports mode 4.

## Build on Windows

Run:

```bat
BUILD_N65II.bat
```

The helper reuses an existing Android SDK (`ANDROID_SDK_ROOT`, `ANDROID_HOME`, `%LOCALAPPDATA%\Android\Sdk`, or `.android-sdk`) and downloads Gradle into `.tools` only if no suitable `gradle` command is available.

Output:

`app\build\outputs\apk\debug\app-debug.apk`
