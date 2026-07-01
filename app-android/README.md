# Esp32esso Tier 1 Android app

Minimal companion app for Tier 1 installs: connect over BLE, watch live brew
temperature and heater state, and set the PID setpoint.

The GATT contract lives in [`../protocol/ble/tier1.md`](../protocol/ble/tier1.md).

## Requirements

- Android 8.0+ (API 26) phone or tablet with BLE
- An Esp32esso board flashed with a Tier 1 BLE env (`esp32-oster-xpert` or
  `esp32-s3-oster-xpert`)

## Build

Open `app-android/` in Android Studio (recommended) and run on a device, or
from a shell with the Android SDK installed:

```bash
cd app-android
./gradlew assembleDebug
```

Install the debug APK:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Power the machine (or the bare dev board) and confirm the firmware advertises
   as **Esp32esso**.
2. Grant Bluetooth permissions when prompted.
3. Tap **Connect** — the app scans for the Tier 1 service UUID and connects.
4. Watch live temperature and heater state; drag the slider and tap **Apply
   setpoint** to change the brew target (persisted on the ESP32 across reboot).

## Scope (Tier 1 MVP)

- Scan / connect / disconnect
- Live `MachineState` notifications (temp, setpoint, heater, fault)
- Setpoint write

Pressure curves, shot profiles, and OTA arrive in later stages.
