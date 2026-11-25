#pragma once

#include <cstddef>
#include <cstdint>

namespace esp32esso::ble {

// UUIDs and wire format shared with protocol/ble/tier2.md and app-android.

inline constexpr const char* kServiceUuid = "a7b3c210-0001-4000-8000-000000000001";
inline constexpr const char* kMachineStateUuid = "a7b3c210-0002-4000-8000-000000000001";
inline constexpr const char* kSetpointUuid = "a7b3c210-0003-4000-8000-000000000001";
// UUID inherited from the previous cascade-gain characteristic. Wire format is
// still 4-byte float; semantics changed from "0..10 cascade gain" to
// "-20..+20 °C thermoblock setpoint offset". Kept on the same slot so the app
// does not need a new discovery table.
inline constexpr const char* kThermoblockOffsetUuid = "a7b3c210-0004-4000-8000-000000000001";
inline constexpr const char* kSettingsUuid = "a7b3c210-0005-4000-8000-000000000001";
inline constexpr const char* kBrewControlUuid = "a7b3c210-0006-4000-8000-000000000001";
inline constexpr const char* kHeaterEnableUuid = "a7b3c210-0007-4000-8000-000000000001";
inline constexpr const char* kPreinfusionUuid = "a7b3c210-0008-4000-8000-000000000001";
inline constexpr const char* kFlushUuid = "a7b3c210-0009-4000-8000-000000000001";
// Absolute manual thermoblock setpoint (4-byte float °C). Writing a positive
// value pins the thermoblock at that temperature, overriding group + offset;
// writing 0 (or NaN) clears the override.
inline constexpr const char* kThermoblockSetpointUuid = "a7b3c210-000a-4000-8000-000000000001";
// Pump pressure-ramp / soft-start duration (4-byte float seconds). 0 disables
// the ramp; a positive value time-proportions the pump SSR so pressure builds
// gradually over that many seconds at shot start.
inline constexpr const char* kPumpRampUuid = "a7b3c210-000b-4000-8000-000000000001";
// Brew pressure/flow profile program (variable-length blob). Little-endian:
// an 8-byte header { u8 version, u8 phaseCount, u8[2] reserved, f32 temperature }
// followed by phaseCount phase records.
// v1 records are 28 bytes:
// { u8 mode(0=pressure,1=flow), u8 curve(0=instant,1=linear,2=ease),
//   u8 valveOpen, u8 reserved, f32 targetStart, f32 targetEnd, f32 transitionSec,
//   f32 durationSec, f32 restriction, f32 stopPressureAbove }.
// v2 records are 36 bytes: the v1 fields plus
// { f32 blockTempStartC, f32 blockTempEndC }, a thermoblock temperature ramped
// across the phase (0 on both = that phase leaves temperature control alone).
// Both versions are accepted, because a v1 blob can still be sitting in NVS
// from before an update. A header with phaseCount 0 (or a payload shorter than
// one phase) clears the profile. See BrewProfile.toWireBytes in the app and
// protocol/ble/tier2.md.
inline constexpr const char* kBrewProfileUuid = "a7b3c210-000c-4000-8000-000000000001";
// Manual brew-pressure hold (4-byte float bar). A positive value pins the shot
// at that pressure, overriding the selected brew profile; 0 (or NaN) clears the
// hold and returns the shot to the profile / ramp behaviour.
inline constexpr const char* kPressureSetpointUuid = "a7b3c210-000d-4000-8000-000000000001";
// Drip-tray scale tare (1-byte trigger). Any non-zero value zeroes the tray at
// its current load; the reading is reported back in MachineState.
inline constexpr const char* kScaleTareUuid = "a7b3c210-000e-4000-8000-000000000001";
// Stop-on-weight target (4-byte float grams). A positive value ends the shot
// once the drip-tray total reaches it; 0 (or NaN) disables the stop.
inline constexpr const char* kWeightTargetUuid = "a7b3c210-000f-4000-8000-000000000001";
inline constexpr const char* kDeviceName = "Esp32esso";

inline constexpr size_t kMachineStateSize = 72;
inline constexpr size_t kSettingsSize = 20;
inline constexpr size_t kBrewProfileHeaderSize = 8;
inline constexpr size_t kBrewProfilePhaseSize = 28;    // wire v1
inline constexpr size_t kBrewProfilePhaseSizeV2 = 36;  // wire v2 (adds block temps)
inline constexpr size_t kBrewProfileMaxPhases = 12;

// 72-byte MachineState v7. `flow_mls` is still a NaN stub so the brew graph can
// allocate the lane before the hardware lands; `weight_g` now carries the real
// drip-tray total once a scale is bound. `thermoblock_offset_c` reports the
// current thermoblock setpoint offset (was "gain" in v2); `flushing` reports
// whether a grouphead-flush pulse is live. Each version appends its fields after
// the previous ones -- v4 the manual pressure hold, v5 `profile_elapsed_sec`,
// v6 the per-cell weights, v7 the stop-on-weight target -- so an older reader
// that consumes only the first 52, 56, 60 or 68 bytes still parses correctly.
struct MachineStateWire {
    float thermoblock_temp_c;
    float group_temp_c;
    float group_setpoint_c;
    float thermoblock_setpoint_c;
    float pressure_bar;
    float flow_mls;
    float weight_g;
    float thermoblock_offset_c;  // signed °C added to the group setpoint
    uint32_t shot_time_ms;
    uint32_t uptime_ms;
    uint8_t heater_duty_pct;
    uint8_t heater_on;
    uint8_t fault_code;
    uint8_t tuning;
    uint8_t brewing;
    uint8_t brew_source;
    uint8_t group_sensor_ok;
    uint8_t heater_enabled;    // 1 when the master heater enable is on
    uint8_t flushing;          // 1 while a grouphead flush pulse is active
    uint8_t thermoblock_manual;  // 1 when an absolute manual setpoint overrides group+offset
    uint8_t pressure_manual;   // 1 when an operator-set pressure hold overrides the profile
    uint8_t steaming;          // 1 while steam mode holds the steam setpoint (was padding)
    float pressure_setpoint_bar;  // operator-set hold target; NaN when not held
    float profile_elapsed_sec;    // position on the running profile; NaN when idle
    float weight_left_g;          // drip-tray left cell alone; NaN when no scale
    float weight_right_g;         // drip-tray right cell alone; NaN when no scale
    float weight_target_g;        // stop-on-weight target; 0 when disabled
};

static_assert(sizeof(MachineStateWire) == kMachineStateSize);

struct SettingsWire {
    float pid_kp;
    float pid_ki;
    float pid_kd;
    float steam_setpoint_c;
    float heater_timeout_min;  // inactivity heater cut-off; 0 disables
};

static_assert(sizeof(SettingsWire) == kSettingsSize);

enum class FaultCode : uint8_t {
    kNone = 0,
    kSensorFault = 1,
    kOvertemp = 2,
    kOther = 3,
};

FaultCode faultCodeFromReason(const char* reason);

}  // namespace esp32esso::ble
