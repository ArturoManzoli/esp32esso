#pragma once

#include <cstddef>
#include <cstdint>

namespace esp32esso::ble {

// UUIDs and wire format shared with protocol/ble/tier2.md and app-android.

inline constexpr const char* kServiceUuid = "a7b3c210-0001-4000-8000-000000000001";
inline constexpr const char* kMachineStateUuid = "a7b3c210-0002-4000-8000-000000000001";
inline constexpr const char* kSetpointUuid = "a7b3c210-0003-4000-8000-000000000001";
inline constexpr const char* kGainUuid = "a7b3c210-0004-4000-8000-000000000001";
inline constexpr const char* kSettingsUuid = "a7b3c210-0005-4000-8000-000000000001";
inline constexpr const char* kBrewControlUuid = "a7b3c210-0006-4000-8000-000000000001";
inline constexpr const char* kDeviceName = "Esp32esso";

inline constexpr size_t kMachineStateSize = 48;
inline constexpr size_t kSettingsSize = 16;

// 48-byte MachineState v2. Tier 3/4 lanes (pressure/flow/weight) are carried
// as NaN stubs so the brew graph can allocate them before the hardware lands.
struct MachineStateWire {
    float thermoblock_temp_c;
    float group_temp_c;
    float group_setpoint_c;
    float thermoblock_setpoint_c;
    float pressure_bar;
    float flow_mls;
    float weight_g;
    float gain;
    uint32_t shot_time_ms;
    uint32_t uptime_ms;
    uint8_t heater_duty_pct;
    uint8_t heater_on;
    uint8_t fault_code;
    uint8_t tuning;
    uint8_t brewing;
    uint8_t brew_source;
    uint8_t group_sensor_ok;
    uint8_t reserved;
};

static_assert(sizeof(MachineStateWire) == kMachineStateSize);

struct SettingsWire {
    float pid_kp;
    float pid_ki;
    float pid_kd;
    float steam_setpoint_c;
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
