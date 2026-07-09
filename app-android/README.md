# Esp32esso Android app

Companion app for the ESP32 espresso controller. Connects over BLE to watch
live telemetry, tune the brew, time shots, and edit machine settings — no WiFi.

The GATT contract lives in [`../protocol/ble/tier2.md`](../protocol/ble/tier2.md).

<img width="2560" height="1600" alt="Screenshot_20260709-150936" src="https://github.com/user-attachments/assets/5eb403be-af7c-4668-9949-58a8f6d9fedf" />


## Requirements

- Android 8.0+ (API 26) phone or tablet with BLE
- An Esp32esso board flashed with a Tier 1/2 BLE env (`esp32-oster-xpert` or
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
3. Tap **Connect** — the app scans for the service UUID and connects.
4. Read the **group** (portafilter) and **thermoblock** temperatures, set the
   **brew target** (the cup temperature), and trim the **cascade gain**.
5. Start/stop a shot from the app (or wire a brew switch), and watch the live
   **brew graph**.
6. Open **Settings** (gear icon) to edit the PID gains and steam target; changes
   persist on the ESP32.

## Scope (Tier 2)

- Dual-sensor readout: group/portafilter target + thermoblock, with the derived
  thermoblock setpoint and heater duty
- Cascade-gain slider (0–10, 0.1 steps) and brew-target slider (80–110 °C)
- Shot timer: manual start/stop plus auto from a wired brew switch
- Live brew graph (thermoblock / group / target), with pressure, flow, and
  weight lanes reserved for Tier 3/4 hardware
- Machine settings: inner PID gains and steam target

Dark theme with an orange accent and frosted-glass surfaces. Pressure/flow
profiling and OTA arrive in later stages.

## Screenshots

<img width="2560" height="1600" alt="Screenshot_20260709-151016" src="https://github.com/user-attachments/assets/733e8b61-f37a-4108-93d1-0085038971f8" />
<img width="2560" height="1600" alt="Screenshot_20260709-151003" src="https://github.com/user-attachments/assets/8a141e5b-6124-4494-83d9-8a003ac948a8" />

