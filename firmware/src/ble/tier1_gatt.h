#pragma once

#include <cstddef>
#include <cstdint>

namespace esp32esso::ble {

// UUIDs and wire format shared with protocol/ble/tier1.md and app-android.

inline constexpr const char* kServiceUuid = "a7b3c210-0001-4000-8000-000000000001";
inline constexpr const char* kMachineStateUuid = "a7b3c210-0002-4000-8000-000000000001";
inline constexpr const char* kSetpointUuid = "a7b3c210-0003-4000-8000-000000000001";
inline constexpr const char* kDeviceName = "Esp32esso";

inline constexpr size_t kMachineStateSize = 16;

struct MachineStateWire {
    float temp_c;
    float setpoint_c;
    uint8_t heater_on;
    uint8_t fault_code;
    uint8_t tuning;
    uint8_t reserved;
    uint32_t uptime_ms;
};

static_assert(sizeof(MachineStateWire) == kMachineStateSize);

enum class FaultCode : uint8_t {
    kNone = 0,
    kSensorFault = 1,
    kOvertemp = 2,
    kOther = 3,
};

FaultCode faultCodeFromReason(const char* reason);

}  // namespace esp32esso::ble
