# Tier 1 BLE GATT contract

Single source of truth for the Tier 1 phone link: live machine state and
brew setpoint. The Android app and firmware must stay byte-compatible with
this document.

## Device discovery

| Field | Value |
| ----- | ----- |
| Advertised name | `Esp32esso` |
| Service UUID | `a7b3c210-0001-4000-8000-000000000001` |

Scan filters should match the service UUID. The advertised name is a hint
only (some stacks truncate it).

## Service `a7b3c210-0001-4000-8000-000000000001`

### Characteristic `MachineState` — `a7b3c210-0002-4000-8000-000000000001`

- **Properties:** READ, NOTIFY
- **Permissions:** READ
- **CCCD:** required for NOTIFY (standard `0x2902` descriptor)
- **Payload:** 16-byte little-endian struct, pushed at 1 Hz (5 Hz while the
  open-loop tuning bench is active)

| Offset | Size | Field | Type | Notes |
| ------ | ---- | ----- | ---- | ----- |
| 0 | 4 | `temp_c` | `float32` LE | Last brew-side reading; `NaN` encoded as IEEE754 NaN |
| 4 | 4 | `setpoint_c` | `float32` LE | Active PID setpoint |
| 8 | 1 | `heater_on` | `uint8` | `1` when the SSR output is high |
| 9 | 1 | `fault_code` | `uint8` | See fault table |
| 10 | 1 | `tuning` | `uint8` | `1` during open-loop step bench |
| 11 | 1 | `reserved` | `uint8` | Must be `0`; ignore on read |
| 12 | 4 | `uptime_ms` | `uint32` LE | Firmware `millis()` snapshot |

#### Fault codes

| Code | Meaning |
| ---- | ------- |
| 0 | No fault |
| 1 | `sensor-fault` |
| 2 | `overtemp` |
| 3 | Other latched fault |

### Characteristic `Setpoint` — `a7b3c210-0003-4000-8000-000000000001`

- **Properties:** READ, WRITE
- **Permissions:** READ, WRITE
- **Payload:** 4-byte `float32` LE, brew target in °C

The firmware clamps to `[profile.thermal.minBrewTempC,
profile.thermal.maxSafeTempC - 5]` and persists the accepted value to NVS
(`esp32esso` namespace, key `setpoint`). A write during an active fault
still updates the stored setpoint but the heater remains off until the
operator clears the fault (serial `c` today; a future app button).

## Security (Tier 1 MVP)

Writes are open to any connected central. Bonding and authenticated writes
are planned before Tier 3 profile editing lands. Do not expose this service
on machines you would not trust on your LAN-equivalent BLE range.
