#if defined(ESP32ESSO_PROFILE_OSTER_XPERT)

#include "board/board_config.h"
#include "hal/gpio_discrete_output.h"
#include "hal/max31855_sensor.h"
#include "profile/machine_profile.h"

namespace esp32esso::profile {

namespace {

// Pins come from the active board (see src/board/). The Oster profile is
// board-agnostic: the same thermal/hydraulic config binds to whichever GPIO
// map the selected env compiled in. Tier 2+ peripherals (solenoid, brew
// switch, pressure transducer) are left unconnected here.
const board::BoardPins& kPins = board::activeBoard().pins;

hal::Max31855Sensor g_brewTempSensor(kPins.thermocoupleCs,
                                     kPins.thermocoupleSck,
                                     kPins.thermocoupleMiso);
hal::GpioDiscreteOutput g_heaterRelay(kPins.heaterSsr, /*activeHigh=*/true);

const MachineProfile kOsterXpertProfile = {
    .metadata =
        {
            .brand = "Oster",
            .model = "Xpert (BVSTEM6601/6701 class)",
            .voltageRegion = "220-240V",
            .notes = "First-cut profile, several fields still UNVERIFIED; "
                     "see docs/machines/oster-xpert.md",
        },
    .thermal =
        {
            // Conservative defaults. These will be re-tuned once the
            // thermoblock's step response has been captured on a real
            // unit; see docs/machines/oster-xpert.md for the procedure.
            .minBrewTempC = 80.0f,
            .maxSafeTempC = 160.0f,
            .defaultBrewTempC = 93.0f,
            .steamTempC = 145.0f,
            .pidKp = 0.10f,
            .pidKi = 0.005f,
            .pidKd = 1.50f,
            .pwmWindowMs = 1000,
        },
    .hydraulic =
        {
            // Most Oster Xpert variants are advertised as "15 bar" but
            // the mechanical OPV typically cracks around 10-11 bar.
            // Verify with a portafilter gauge before relying on these.
            .maxPressureBar = 12.0f,
            .opvPressureBar = 10.0f,
            .hasThreeWayValve = false,  // UNVERIFIED - inspect during teardown
            .pumpIsVibration = true,
        },
    .brewTempSensor = &g_brewTempSensor,
    .heaterRelay = &g_heaterRelay,
    .solenoidValve = nullptr,    // Tier 2+
    .brewSwitch = nullptr,       // Tier 2+
    .pressureSensor = nullptr,   // Tier 2+
};

}  // namespace

const MachineProfile& activeProfile() {
    return kOsterXpertProfile;
}

void initActiveProfilePeripherals() {
    g_brewTempSensor.begin();
    g_heaterRelay.begin();
}

}  // namespace esp32esso::profile

#endif  // ESP32ESSO_PROFILE_OSTER_XPERT
