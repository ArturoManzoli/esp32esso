# Tier 2 BLE GATT contract

Supersedes [`tier1.md`](tier1.md). Same service UUID and same connectionless,
open-write model, but the `MachineState` payload grows to carry the dual-sensor
cascade telemetry and the brew-graph lanes, and three new characteristics add
the cascade gain, the tunable settings, and the manual shot trigger.

The Android app and firmware must stay byte-compatible with this document.
Firmware structs live in `firmware/src/ble/tier1_gatt.h`; the app mirror lives
in `app-android/.../ble/Tier1Gatt.kt`.

## Device discovery

| Field | Value |
| ----- | ----- |
| Advertised name | `Esp32esso` |
| Service UUID | `a7b3c210-0001-4000-8000-000000000001` |

## Service `a7b3c210-0001-4000-8000-000000000001`

### Characteristic `MachineState` — `a7b3c210-0002-4000-8000-000000000001`

- **Properties:** READ, NOTIFY
- **CCCD:** required for NOTIFY (standard `0x2902` descriptor)
- **Payload:** 48-byte little-endian struct, pushed at 1 Hz, or 5 Hz while a
  shot is being pulled or the open-loop tuning bench is active.

| Offset | Size | Field | Type | Notes |
| ------ | ---- | ----- | ---- | ----- |
| 0 | 4 | `thermoblock_temp_c` | `float32` | Thermoblock sensor; inner PID PV, safety reference. `NaN` on fault |
| 4 | 4 | `group_temp_c` | `float32` | Portafilter/group sensor. `NaN` when absent or faulted |
| 8 | 4 | `group_setpoint_c` | `float32` | User brew target (the cup temperature) |
| 12 | 4 | `thermoblock_setpoint_c` | `float32` | Derived inner setpoint after the cascade offset |
| 16 | 4 | `pressure_bar` | `float32` | Tier 3 stub; `NaN` until a transducer is wired |
| 20 | 4 | `flow_mls` | `float32` | Tier 3 stub; `NaN` until flow sensing exists |
| 24 | 4 | `weight_g` | `float32` | Tier 4 stub; `NaN` until a scale is wired |
| 28 | 4 | `gain` | `float32` | Active cascade gain (0..10) |
| 32 | 4 | `shot_time_ms` | `uint32` | Elapsed shot time; `0` when not brewing |
| 36 | 4 | `uptime_ms` | `uint32` | Firmware `millis()` snapshot |
| 40 | 1 | `heater_duty_pct` | `uint8` | Last PID output, 0..100 |
| 41 | 1 | `heater_on` | `uint8` | `1` when the SSR output is high |
| 42 | 1 | `fault_code` | `uint8` | See fault table |
| 43 | 1 | `tuning` | `uint8` | `1` during the open-loop step bench |
| 44 | 1 | `brewing` | `uint8` | `1` while a shot is running |
| 45 | 1 | `brew_source` | `uint8` | `0` idle, `1` manual (app), `2` brew switch |
| 46 | 1 | `group_sensor_ok` | `uint8` | `1` when the group reading is trusted |
| 47 | 1 | `reserved` | `uint8` | Must be `0`; ignore on read |

#### Fault codes

| Code | Meaning |
| ---- | ------- |
| 0 | No fault |
| 1 | `sensor-fault` (thermoblock sensor) |
| 2 | `overtemp` |
| 3 | Other latched fault |

A group-sensor fault does **not** latch the heater: `group_sensor_ok` drops to
`0`, `group_temp_c` reads `NaN`, and the loop falls back to controlling the
thermoblock directly at `group_setpoint_c`.

### Characteristic `Setpoint` — `a7b3c210-0003-4000-8000-000000000001`

- **Properties:** READ, WRITE
- **Payload:** 4-byte `float32`, the **group/cup** target in °C.

Clamped to `[thermal.minBrewTempC, min(thermal.maxSafeTempC - 5, 110)]` and
persisted to NVS (`esp32esso` namespace, key `setpoint`). The 110 °C ceiling
keeps the brew target in a sane espresso band; the steam target is separate.

### Characteristic `Gain` — `a7b3c210-0004-4000-8000-000000000001`

- **Properties:** READ, WRITE
- **Payload:** 4-byte `float32`, cascade gain.

Clamped to `[0, 10]` and persisted (key `gain`). `0` disables compensation
(the thermoblock holds the cup target directly).

### Characteristic `Settings` — `a7b3c210-0005-4000-8000-000000000001`

- **Properties:** READ, WRITE
- **Payload:** 16-byte little-endian struct.

| Offset | Size | Field | Type | Notes |
| ------ | ---- | ----- | ---- | ----- |
| 0 | 4 | `pid_kp` | `float32` | Inner PID proportional gain |
| 4 | 4 | `pid_ki` | `float32` | Inner PID integral gain |
| 8 | 4 | `pid_kd` | `float32` | Inner PID derivative gain |
| 12 | 4 | `steam_setpoint_c` | `float32` | Steam target (reserved for the steam mode) |

Each field is clamped non-negative (PID) / to the safe band (steam) and
persisted (keys `kp`, `ki`, `kd`, `steam`). A write re-seeds the PID live.

### Characteristic `BrewControl` — `a7b3c210-0006-4000-8000-000000000001`

- **Properties:** WRITE
- **Payload:** 1 byte — `1` starts a manual shot, `0` stops it.

Independent of the brew switch: a wired switch and the app can each start the
timer. `brew_source` in `MachineState` reports which one is active.

## Security (Tier 2 MVP)

Writes remain open to any connected central. Bonding and authenticated writes
are planned before Tier 3 profile editing lands.
