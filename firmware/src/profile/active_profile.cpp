#include "profile/machine_profile.h"

namespace esp32esso::profile {

#if defined(ESP32ESSO_PROFILE_STUB)

// Compile-only stub profile. No peripherals are wired; the firmware boots,
// prints a banner, and idles. Used for the CI build and bring-up of new
// boards before a real machine profile exists.
namespace {
const MachineProfile kStubProfile = {
    .metadata =
        {
            .brand = "esp32esso",
            .model = "dev stub",
            .voltageRegion = "n/a",
            .notes = "compile-only profile; do not flash on a real machine",
        },
    .thermal =
        {
            .minBrewTempC = 80.0f,
            .maxSafeTempC = 165.0f,
            .defaultBrewTempC = 93.0f,
            .steamTempC = 145.0f,
            .pidKp = 0.0f,
            .pidKi = 0.0f,
            .pidKd = 0.0f,
            .pwmWindowMs = 1000,
        },
    .hydraulic =
        {
            .maxPressureBar = 12.0f,
            .opvPressureBar = 10.0f,
            .hasThreeWayValve = false,
            .pumpIsVibration = true,
        },
    .brewTempSensor = nullptr,
    .heaterRelay = nullptr,
    .solenoidValve = nullptr,
    .brewSwitch = nullptr,
    .pressureSensor = nullptr,
};
}  // namespace

const MachineProfile& activeProfile() {
    return kStubProfile;
}

void initActiveProfilePeripherals() {
    // Stub profile has no peripherals; intentionally empty.
}

#endif  // ESP32ESSO_PROFILE_STUB

}  // namespace esp32esso::profile
