#pragma once

#include <cstdint>

namespace esp32esso::control {
class TemperatureLoop;
}
namespace esp32esso::profile {
struct MachineProfile;
}

namespace esp32esso::ble {

// Tier 1 BLE peripheral: MachineState NOTIFY + Setpoint READ/WRITE.
// Compiled only when ESP32ESSO_TIER1_BLE is defined by the PlatformIO env.
class Tier1Service {
public:
    void begin(control::TemperatureLoop* loop, const profile::MachineProfile* profile);
    void tick(uint32_t nowMs);

private:
    control::TemperatureLoop* loop_ = nullptr;
    const profile::MachineProfile* profile_ = nullptr;
    uint32_t lastNotifyMs_ = 0;
};

Tier1Service& tier1Service();

}  // namespace esp32esso::ble
