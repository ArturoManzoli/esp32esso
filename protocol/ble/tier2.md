# Tier 2 BLE GATT contract

Supersedes [`tier1.md`](tier1.md). Same service UUID and same connectionless,
open-write model, but the `MachineState` payload grows to carry the dual-probe
telemetry and the brew-graph lanes, and further characteristics add the derived
thermoblock target, the tunable settings, the manual shot trigger, and the
Tier 3 pressure controls (pump ramp, brew profile, pressure hold).

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
- **Payload:** 72-byte little-endian struct, pushed at 4 Hz, or 20 Hz while a
  shot is being pulled or the open-loop tuning bench is active (for a smooth
  brew graph; still well within the negotiated 7.5-22.5 ms connection interval).

Every version appends its fields after the previous ones: v4 the manual pressure
hold (offsets 50-55), v5 `profile_elapsed_sec`, v6 the per-cell drip-tray
weights, v7 `weight_target_g`. A reader that only consumes the first 52, 56, 60
or 68 bytes still parses correctly and can treat the later fields as absent.

| Offset | Size | Field | Type | Notes |
| ------ | ---- | ----- | ---- | ----- |
| 0 | 4 | `thermoblock_temp_c` | `float32` | Thermoblock sensor; inner PID PV, safety reference. `NaN` on fault |
| 4 | 4 | `group_temp_c` | `float32` | Portafilter/group sensor. `NaN` when absent or faulted |
| 8 | 4 | `group_setpoint_c` | `float32` | User brew target (the cup temperature) |
| 12 | 4 | `thermoblock_setpoint_c` | `float32` | Effective inner setpoint the PID chases: `group_setpoint_c + thermoblock_offset_c`, or the absolute manual setpoint when `thermoblock_manual` is `1`; clamped to safety |
| 16 | 4 | `pressure_bar` | `float32` | Brew-line pressure, spike/ripple-filtered. `NaN` when no transducer is bound or the reading is out of range |
| 20 | 4 | `flow_mls` | `float32` | Tier 3 stub; `NaN` until flow sensing exists |
| 24 | 4 | `weight_g` | `float32` | Drip-tray total (`weight_left_g + weight_right_g`); `NaN` until a scale is wired |
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
| 50 | 1 | `pressure_manual` | `uint8` | `1` when an operator-set pressure hold overrides the selected profile (see `PressureSetpoint`) |
| 51 | 1 | `steaming` | `uint8` | `1` while steam mode holds the steam setpoint (panel steam input); was padding, so older firmware reports `0` |
| 52 | 4 | `pressure_setpoint_bar` | `float32` | The held pressure while `pressure_manual` is `1`; `NaN` otherwise |
| 56 | 4 | `profile_elapsed_sec` | `float32` | Position along the running brew profile's own timeline; `NaN` when no profile is driving the pump |
| 60 | 4 | `weight_left_g` | `float32` | Drip-tray left load cell alone; `NaN` when no scale is bound |
| 64 | 4 | `weight_right_g` | `float32` | Drip-tray right load cell alone; `NaN` when no scale is bound |
| 68 | 4 | `weight_target_g` | `float32` | Stop-on-weight target in grams; `0` when disabled (see `WeightTarget`) |

`profile_elapsed_sec` is not the shot clock: a phase that ends early on its
`stopPressureAbove` jumps the position straight to the next phase's start, so the
value tracks where the machine actually sits on the profile's published curve.
It is clamped to the profile's total duration, since the final phase holds until
the operator ends the shot.

The cells are reported separately as well as summed because only the total is
independent of where the cup sits on the tray, while the per-cell figures are
what make a miswired or dead corner visible instead of silently halving the
reading.

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

Signed value added to the group setpoint to produce the derived thermoblock
target: `thermoblockSetpoint = groupSetpoint + offset`. With a healthy group
probe the PID closes directly on the group and this target is only reported;
it becomes the actual control target when the loop falls back to block-direct
control (group probe missing or faulted). Clamped to
`[-maxThermoblockOffsetC, +maxThermoblockOffsetC]` (±50 °C on Oster) on top
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
- **Payload:** 1 byte — `0` stops the shot, `1` starts a manual shot, `2` starts
  a profile shot.

A manual shot always runs the plain pre-infusion + pressure-ramp program, even
when a brew profile is loaded; a profile shot runs the loaded profile's phase
program (and falls back to the plain program when none is loaded). The choice is
latched at shot start. Any other non-zero value behaves like `2`.

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

### Characteristic `PumpRamp` — `a7b3c210-000b-4000-8000-000000000001`

- **Properties:** READ, WRITE
- **Payload:** 4-byte `float32`, pump pressure-ramp (soft-start) time in seconds.

Clamped to `[0, 15]` and persisted (key `pumpRamp`, default `0`). A positive
value gives the pump a softer pressure start to reduce puck channeling: the
firmware raises the AC phase dimmer's duty from ~30 % up to 100 % across this
many seconds measured from shot start (which also gentles the initial
pre-infusion push). A grouphead `Flush` always runs the pump at full duty
regardless of this value.
`0` disables the ramp (full pressure immediately). Echoed back on the same
characteristic.

### Characteristic `BrewProfile` — `a7b3c210-000c-4000-8000-000000000001`

- **Properties:** READ, WRITE
- **Payload:** variable-length little-endian blob (a pressure/flow profile).

Selects the pressure/flow profile the firmware executes by duty-cycling the
pump — either one of the bundled ones (curated in the app from the
[gaggimate](https://github.com/jniebuhr/gaggimate) and
[gaggiuino](https://github.com/Zer0-bit/gaggiuino) projects) or one the operator
drew in the app's profile editor. When a profile is active it replaces the
built-in pre-infusion + `PumpRamp` behaviour for the whole shot.

Layout — an 8-byte header followed by `phaseCount` phase records:

| Offset | Type       | Field         | Notes                                           |
| ------ | ---------- | ------------- | ----------------------------------------------- |
| 0      | `u8`       | `version`     | `1` (28-byte records) or `2` (36-byte records)   |
| 1      | `u8`       | `phaseCount`  | number of phase records that follow (max 12)    |
| 2      | `u8[2]`    | reserved      | `0`                                             |
| 4      | `float32`  | `temperature` | profile's recommended brew temp °C (advisory)   |

Each phase record (28 bytes on v1, 36 on v2):

| Offset | Type      | Field               | Notes                                          |
| ------ | --------- | ------------------- | ---------------------------------------------- |
| 0      | `u8`      | `mode`              | `0` pressure (bar), `1` flow (ml/s)            |
| 1      | `u8`      | `curve`             | `0` instant, `1` linear, `2` ease              |
| 2      | `u8`      | `valveOpen`         | `1` pump/valve run, `0` closed pause (bloom)   |
| 3      | `u8`      | reserved            | `0`                                            |
| 4      | `float32` | `targetStart`       | target at phase start                          |
| 8      | `float32` | `targetEnd`         | target after the transition                    |
| 12     | `float32` | `transitionSec`     | ramp time from start → end                     |
| 16     | `float32` | `durationSec`       | total phase length                             |
| 20     | `float32` | `restriction`       | limit on the other variable (0 = none)         |
| 24     | `float32` | `stopPressureAbove` | early-exit pressure in bar (0 = time only)     |
| 28     | `float32` | `blockTempStartC`   | v2 only: thermoblock target at phase start     |
| 32     | `float32` | `blockTempEndC`     | v2 only: thermoblock target at phase end       |

Phases run in order; a phase ends after `durationSec` or early when
`stopPressureAbove` is met on the transducer, and the last phase holds until the
operator ends the shot. Pressure phases close a coarse loop on the transducer
(feedforward + P trim); flow phases run open-loop (no flow sensor) with
`restriction` as a soft pressure cap.

A v2 phase whose block temperatures are both `0` leaves temperature control
alone. Otherwise the profile owns the thermoblock for the shot: the target ramps
linearly across the *whole* phase duration (not `transitionSec`, so a
vertex-to-vertex slope stays continuous) through the same override path as
`ThermoblockSetpoint`, which also means `thermoblock_manual` reads `1` while it
runs. Ending the shot — or clearing the profile — hands temperature back to
`group + offset`. Values are clamped to the machine's brew band exactly as a
manual block setpoint is.

A payload with `phaseCount == 0`, an unknown `version`, or shorter than one phase
clears the profile. Persisted to NVS (key `brewProf`) so the selection survives a
reboot — which is why v1 stays readable, since that stored blob may predate a
firmware update. The app also re-sends the profile on connect.

### Characteristic `PressureSetpoint` — `a7b3c210-000d-4000-8000-000000000001`

- **Properties:** READ, WRITE
- **Payload:** `float32` bar

Pins a shot at a fixed brew pressure instead of running the selected
`BrewProfile`, the same way `ThermoblockSetpoint` pins the block instead of
following `group + offset`. Writing a positive value holds that pressure for the
whole shot and outranks any active profile; writing `0` (or `NaN`) clears the
hold and hands the shot back to the profile / `PumpRamp` behaviour. A grouphead
`Flush` always runs at full duty and ignores the hold.

Duty comes from the firmware's measured pump curve (feedforward) plus the same
closed-loop trim the profile engine's pressure phases use, so the achievable
range is bounded by what the pump was characterised to deliver; the value is
clamped to 12 bar. Echoed back on the same characteristic, persisted to NVS (key
`pManual`), and reflected by `pressure_manual` / `pressure_setpoint_bar` in
`MachineState`.

### Characteristic `ScaleTare` — `a7b3c210-000e-4000-8000-000000000001`

- **Properties:** WRITE
- **Payload:** 1 byte, any non-zero value tares

Zeroes the drip-tray scale at its current load, storing a fresh offset per cell.
The tare is held in RAM only, so it does not survive a reboot; the reading it
produces comes back through `weight_g` / `weight_left_g` / `weight_right_g` in
`MachineState`. A write with `0` or an empty payload is ignored.

Each reading is a rolling mean of the last 8 samples per cell. While the tray is
idle and within 2 g of zero, the zero walks slowly toward the live reading to
cancel load-cell drift as the machine warms up; a shot or flush suspends that.
A tare resets both the mean and the tracked zero.

### Characteristic `WeightTarget` — `a7b3c210-000f-4000-8000-000000000001`

- **Properties:** READ, WRITE
- **Payload:** 4-byte float, grams

A positive value arms stop-on-weight: the firmware ends the running shot, through
the same path as a `BrewControl` stop, once `weight_g` reaches the target. `0`
or `NaN` disables it, and values above 500 g are clamped. The accepted value is
echoed back, persisted to NVS (key `wTarget`) and reported in
`MachineState.weight_target_g`. The comparison is against the tray total, so tare
with the empty cup in place.

## Security (Tier 2 MVP)

Writes remain open to any connected central. Bonding and authenticated writes
are planned before Tier 3 profile editing lands.
