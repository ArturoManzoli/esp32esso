#if defined(ESP32ESSO_PROFILE_OSTER_XPERT)

#include "board/board_config.h"
#include "hal/gpio_discrete_output.h"
#if defined(ESP32ESSO_THERMOCOUPLE_MAX6675)
#include "hal/max6675_sensor.h"
#else
#include "hal/max31855_sensor.h"
#endif
#if defined(ESP32ESSO_THERMOBLOCK_NTC)
#include "hal/ntc_thermistor_sensor.h"
#endif
#if defined(ESP32ESSO_TIER2)
#include "hal/gpio_discrete_input.h"
#endif
#include "profile/machine_profile.h"

namespace esp32esso::profile {

namespace {

// Pins come from the active board (see src/board/). The Oster profile is
// board-agnostic: the same thermal/hydraulic config binds to whichever GPIO
// map the selected env compiled in.
const board::BoardPins& kPins = board::activeBoard().pins;

#if defined(ESP32ESSO_THERMOCOUPLE_MAX6675)
using ThermocoupleSensor = hal::Max6675Sensor;
#else
using ThermocoupleSensor = hal::Max31855Sensor;
#endif

#if defined(ESP32ESSO_THERMOBLOCK_NTC)
// Stock 100k NTC on the thermoblock via a 3V3 → 100k → ADC → NTC → GND divider.
// R0 trimmed on the prototype against the portafilter K-type on the same block
// @ ~18 °C (Beta left at the datasheet average; ice/boil refines further).
constexpr hal::NtcCalibration kThermoblockNtcCal{
    .r0Ohms = 105600.0f,
    .t0Celsius = 25.0f,
    .betaK = 3962.5f,
    .seriesResistorOhms = 100000.0f,
    .supplyMillivolts = 3300.0f,
    .ntcOnHighSide = false,
};
hal::NtcThermistorSensor g_brewTempSensor(kPins.thermoblockNtc, kThermoblockNtcCal);
#else
ThermocoupleSensor g_brewTempSensor(kPins.thermocoupleCs,
                                    kPins.thermocoupleSck,
                                    kPins.thermocoupleMiso);
#endif
hal::GpioDiscreteOutput g_heaterRelay(kPins.heaterSsr, /*activeHigh=*/true);

#if defined(ESP32ESSO_TIER2)
// Tier 2: thermocouple at the portafilter/group. When the thermoblock uses the
// stock NTC, the single MAX6675 amp stays on the Tier 1 CS pin (GPIO 21);
// only dual-thermocouple builds use CS2.
#if defined(ESP32ESSO_THERMOBLOCK_NTC)
ThermocoupleSensor g_groupTempSensor(kPins.thermocoupleCs,
                                     kPins.thermocoupleSck,
                                     kPins.thermocoupleMiso);
#else
ThermocoupleSensor g_groupTempSensor(kPins.thermocoupleCs2,
                                     kPins.thermocoupleSck,
                                     kPins.thermocoupleMiso);
#endif
hal::GpioDiscreteInput g_brewSwitch(kPins.brewSwitch, /*activeLow=*/true);
// Relief/3-way valve driven by a second SSR/MOSFET; pulsed open after a shot.
hal::GpioDiscreteOutput g_solenoidValve(kPins.solenoidValve, /*activeHigh=*/true);
#endif

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
            // The group sits ~20 cm downstream through PTFE + valves, so the
            // thermoblock must run hotter than the cup target. Gain 4 seeds a
            // sensible over-drive; tune from the phone (0..10). The offset is
            // bounded so a cold group at idle cannot walk the thermoblock to
            // the safety cap.
            .defaultCascadeGain = 4.0f,
            .maxCascadeOffsetC = 30.0f,
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
#if defined(ESP32ESSO_TIER2)
    .groupTempSensor = &g_groupTempSensor,
#else
    .groupTempSensor = nullptr,
#endif
#if defined(ESP32ESSO_TIER2)
    .solenoidValve = &g_solenoidValve,
#else
    .solenoidValve = nullptr,
#endif
#if defined(ESP32ESSO_TIER2)
    .brewSwitch = &g_brewSwitch,
#else
    .brewSwitch = nullptr,
#endif
    .pressureSensor = nullptr,  // Tier 3+
};

}  // namespace

const MachineProfile& activeProfile() {
    return kOsterXpertProfile;
}

hal::NtcThermistorSensor* thermoblockNtcSensor() {
#if defined(ESP32ESSO_THERMOBLOCK_NTC)
    return &g_brewTempSensor;
#else
    return nullptr;
#endif
}

void initActiveProfilePeripherals() {
    g_brewTempSensor.begin();
    g_heaterRelay.begin();
#if defined(ESP32ESSO_TIER2)
    g_groupTempSensor.begin();
    g_brewSwitch.begin();
    g_solenoidValve.begin();
#endif
}

}  // namespace esp32esso::profile

#endif  // ESP32ESSO_PROFILE_OSTER_XPERT
