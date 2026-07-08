#if defined(ESP32ESSO_PROFILE_OSTER_XPERT)

#include "board/board_config.h"
#include "hal/gpio_discrete_output.h"
#if defined(ESP32ESSO_PRESSURE_ADC)
#include "hal/analog_pressure_sensor.h"
#endif
#if defined(ESP32ESSO_THERMOCOUPLE_MAX6675)
#include "hal/max6675_sensor.h"
#else
#include "hal/max31855_sensor.h"
#endif
#if defined(ESP32ESSO_THERMOBLOCK_NTC)
#include "hal/ntc_thermistor_sensor.h"
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

// Thermoblock sensor. The default (and recommended) build puts a thermocouple
// here -- identical to the group probe -- on its own CS (thermocoupleCs2),
// sharing the group amp's SCK/SO bus. Two matched thermocouples give a linear,
// cold-junction-compensated reading with no per-unit resistance fit and far
// less drift than the stock thermistor, so prefer this unless you specifically
// want to reuse the machine's NTC. Defining ESP32ESSO_THERMOBLOCK_NTC (the
// `-ntc` build) swaps in the stock 100k NTC instead; that path is kept so
// people can opt for either sensor.
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
ThermocoupleSensor g_brewTempSensor(kPins.thermocoupleCs2,
                                    kPins.thermocoupleSck,
                                    kPins.thermocoupleMiso);
#endif
// Heater Fotek SSR (3–32 V input): HIGH = on. Brew/relief use active-low 5 V boards.
hal::GpioDiscreteOutput g_heaterRelay(kPins.heaterSsr, /*activeHigh=*/true);

#if defined(ESP32ESSO_TIER2)
// Tier 2: thermocouple at the portafilter/group on the primary CS
// (thermocoupleCs, GPIO 21 on WROOM), in both the dual-TC and NTC builds. In
// the recommended dual-TC build the thermoblock amp shares this same SCK/SO bus
// on its own CS (thermocoupleCs2, GPIO 5 on WROOM); in the NTC build there is
// no thermoblock amp and thermocoupleCs2 is unused.
ThermocoupleSensor g_groupTempSensor(kPins.thermocoupleCs,
                                     kPins.thermocoupleSck,
                                     kPins.thermocoupleMiso);
// Brew pump/valve SSR; activeLow matches common 5 V dual-channel SSR boards.
hal::GpioDiscreteOutput g_brewRelay(kPins.brewSsr, /*activeHigh=*/false);
// Relief valve SSR; activeLow matches common 5 V dual-channel SSR boards.
hal::GpioDiscreteOutput g_solenoidValve(kPins.solenoidValve, /*activeHigh=*/false);
#if defined(ESP32ESSO_PRESSURE_ADC)
// Ratiometric 0.5-4.5 V / 0-12 bar transducer through a 2:1 divider (see
// hardware/oster-xpert/tier-2-wiring.md). Defaults match the documented front
// end; refine the calibration once a reference gauge is available.
hal::AnalogPressureSensor g_pressureSensor(kPins.pressureAdc);
#endif
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
            // thermoblock must run hotter than the cup target. Seed the slider
            // at +8 °C above the cup target on first boot; the phone tunes it
            // live in ±20 °C. The bound keeps the safety cap out of reach even
            // when the user pushes the offset to the extreme.
            .defaultThermoblockOffsetC = 8.0f,
            .maxThermoblockOffsetC = 20.0f,
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
    .brewRelay = &g_brewRelay,
#else
    .brewRelay = nullptr,
#endif
#if defined(ESP32ESSO_TIER2) && defined(ESP32ESSO_PRESSURE_ADC)
    .pressureSensor = &g_pressureSensor,
#else
    .pressureSensor = nullptr,
#endif
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
#if defined(ESP32ESSO_TIER2)
    g_groupTempSensor.begin();
#endif
    // GPIO outputs after SPI sensor init so GPIO 23 (heater, VSPI MOSI default)
    // is not left claimed by the thermocouple bus driver.
    g_heaterRelay.begin();
#if defined(ESP32ESSO_TIER2)
    g_brewRelay.begin();
    g_solenoidValve.begin();
#if defined(ESP32ESSO_PRESSURE_ADC)
    g_pressureSensor.begin();
#endif
#endif
}

}  // namespace esp32esso::profile

#endif  // ESP32ESSO_PROFILE_OSTER_XPERT
