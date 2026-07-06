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
inline constexpr const char* kDeviceName = "Esp32esso";

inline constexpr size_t kMachineStateSize = 52;
inline constexpr size_t kSettingsSize = 20;

// 52-byte MachineState v3. Tier 3/4 lanes (pressure/flow/weight) are carried
// as NaN stubs so the brew graph can allocate them before the hardware lands.
// `thermoblock_offset_c` reports the current thermoblock setpoint offset (was
// "gain" in v2); `flushing` reports whether a grouphead-flush pulse is live.
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
    uint8_t _pad[2];           // keep the struct at 52 bytes / 4-byte aligned
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
