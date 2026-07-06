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
- **Payload:** 52-byte little-endian struct, pushed at 4 Hz, or 20 Hz while a
  shot is being pulled or the open-loop tuning bench is active (for a smooth
  brew graph; still well within the negotiated 7.5-22.5 ms connection interval).

| Offset | Size | Field | Type | Notes |
| ------ | ---- | ----- | ---- | ----- |
| 0 | 4 | `thermoblock_temp_c` | `float32` | Thermoblock sensor; inner PID PV, safety reference. `NaN` on fault |
| 4 | 4 | `group_temp_c` | `float32` | Portafilter/group sensor. `NaN` when absent or faulted |
| 8 | 4 | `group_setpoint_c` | `float32` | User brew target (the cup temperature) |
| 12 | 4 | `thermoblock_setpoint_c` | `float32` | Effective inner setpoint the PID chases: `group_setpoint_c + thermoblock_offset_c`, or the absolute manual setpoint when `thermoblock_manual` is `1`; clamped to safety |
| 16 | 4 | `pressure_bar` | `float32` | Brew-line pressure, spike/ripple-filtered. `NaN` when no transducer is bound or the reading is out of range |
| 20 | 4 | `flow_mls` | `float32` | Tier 3 stub; `NaN` until flow sensing exists |
| 24 | 4 | `weight_g` | `float32` | Tier 4 stub; `NaN` until a scale is wired |
| 28 | 4 | `thermoblock_offset_c` | `float32` | Signed °C offset added to the group setpoint; range clamped to the profile's `maxThermoblockOffsetC` (±20 °C on Oster) |
| 32 | 4 | `shot_time_ms` | `uint32` | Elapsed shot time; `0` when not brewing |
| 36 | 4 | `uptime_ms` | `uint32` | Firmware `millis()` snapshot |
| 40 | 1 | `heater_duty_pct` | `uint8` | Last PID output, 0..100 |
| 41 | 1 | `heater_on` | `uint8` | `1` when the SSR output is high |
| 42 | 1 | `fault_code` | `uint8` | See fault table |
| 43 | 1 | `tuning` | `uint8` | `1` during the open-loop step bench |
| 44 | 1 | `brewing` | `uint8` | `1` while a shot is running |
| 45 | 1 | `brew_source` | `uint8` | `0` idle, `1` manual (app), `2` brew switch |
| 46 | 1 | `group_sensor_ok` | `uint8` | `1` when the group reading is trusted |
| 47 | 1 | `heater_enabled` | `uint8` | `1` when the master heater enable is on (see `HeaterEnable`) |
| 48 | 1 | `flushing` | `uint8` | `1` while a grouphead flush is in progress (see `Flush`) |
| 49 | 1 | `thermoblock_manual` | `uint8` | `1` when an absolute manual thermoblock setpoint overrides `group + offset` (see `ThermoblockSetpoint`) |
| 50 | 2 | `_pad` | `uint8[2]` | Reserved for future single-byte flags; readers must ignore |

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

### Characteristic `ThermoblockOffset` — `a7b3c210-0004-4000-8000-000000000001`

- **Properties:** READ, WRITE
- **Payload:** 4-byte `float32`, thermoblock setpoint offset in °C.

Signed value added to the group setpoint to produce the inner PID target:
`thermoblockSetpoint = groupSetpoint + offset`. Positive offsets over-drive
the thermoblock to compensate transport loss to the puck; negative offsets
pull it below the cup target (rarely useful, but not forbidden). Clamped to
`[-maxThermoblockOffsetC, +maxThermoblockOffsetC]` (±20 °C on Oster) on top
of the hard safety cap, and persisted (key `tbOffset`, default per-profile).
UUID inherited from the v2 `Gain` characteristic; v3 clients must not treat
the value as a unitless gain.

### Characteristic `Settings` — `a7b3c210-0005-4000-8000-000000000001`

- **Properties:** READ, WRITE
- **Payload:** 20-byte little-endian struct.

| Offset | Size | Field | Type | Notes |
| ------ | ---- | ----- | ---- | ----- |
| 0 | 4 | `pid_kp` | `float32` | Inner PID proportional gain |
| 4 | 4 | `pid_ki` | `float32` | Inner PID integral gain |
| 8 | 4 | `pid_kd` | `float32` | Inner PID derivative gain |
| 12 | 4 | `steam_setpoint_c` | `float32` | Steam target (reserved for the steam mode) |
| 16 | 4 | `heater_timeout_min` | `float32` | Inactivity heater cut-off in minutes; `0` disables (default 10) |

Each field is clamped non-negative (PID) / to the safe band (steam) / to
`[0, 240]` (heater timeout) and persisted (keys `kp`, `ki`, `kd`, `steam`,
`heat_to`). A write re-seeds the PID live. The heater timeout auto-disables the
heater after that many minutes without any user activity (setpoint/gain/settings
write, brew, or heater re-enable); brewing continuously refreshes it.

### Characteristic `BrewControl` — `a7b3c210-0006-4000-8000-000000000001`

- **Properties:** WRITE
- **Payload:** 1 byte — `1` starts a manual shot, `0` stops it.

Independent of the brew switch: a wired switch and the app can each start the
timer. `brew_source` in `MachineState` reports which one is active.

### Characteristic `HeaterEnable` — `a7b3c210-0007-4000-8000-000000000001`

- **Properties:** WRITE
- **Payload:** 1 byte — `1` enables the heater, `0` forces it to a cold standby.

Master heater cut-off. When `0`, the SSR is held off regardless of the PID or
bench output (the loop keeps running and the BLE link stays up), so the phone can
park the machine cold without disconnecting. Defaults to enabled at boot and is
not persisted. `heater_enabled` in `MachineState` reflects the current value.

### Characteristic `Preinfusion` — `a7b3c210-0008-4000-8000-000000000001`

- **Properties:** READ, WRITE
- **Payload:** 4-byte `float32`, pre-infusion time in seconds.

Clamped to `[0, 30]` and persisted (key `preinf`, default `2`). When a shot
starts with a non-zero value, the pump runs for that many seconds, pauses for the
same time to let the puck bloom, then the main brew begins. The relief valve
stays closed for the whole sequence (`brewing` is `1` throughout), so the pause
holds pressure instead of venting. `0` disables pre-infusion.

### Characteristic `Flush` — `a7b3c210-0009-4000-8000-000000000001`

- **Properties:** WRITE
- **Payload:** 1 byte — non-zero starts a fixed-length grouphead flush; `0`
  cancels an in-flight flush.

Pulses the pump for 3 seconds with the relief valve held closed, so water
actually flows through the shower screen (useful for warming or rinsing the
head between shots). Does not run the shot timer, does not honour pre-infusion,
and is refused while `brewing` is `1` — the shot always owns the pump. The
`flushing` flag in `MachineState` reports the current state. Not persisted.

### Characteristic `ThermoblockSetpoint` — `a7b3c210-000a-4000-8000-000000000001`

- **Properties:** READ, WRITE
- **Payload:** 4-byte `float32` — thermoblock temperature in °C.

Writing a positive temperature pins the thermoblock at that absolute value,
overriding the `group_setpoint_c + thermoblock_offset_c` relationship entirely
(the app's thermoblock temperature picker uses this). Writing `0` (or `NaN`)
clears the override and returns to `group + offset`. Writing the
`ThermoblockOffset` characteristic also clears the override. The value is
clamped to `[minBrewTempC, maxSafeTempC - 5]`, echoed back on the same
characteristic, persisted to NVS, and reflected by the `thermoblock_manual`
flag in `MachineState`.

## Security (Tier 2 MVP)

Writes remain open to any connected central. Bonding and authenticated writes
are planned before Tier 3 profile editing lands.
