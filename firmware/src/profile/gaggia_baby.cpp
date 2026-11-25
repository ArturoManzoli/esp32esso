#if defined(ESP32ESSO_PROFILE_GAGGIA_BABY)

#include <Arduino.h>

#include "board/board_config.h"
#include "hal/gpio_discrete_output.h"
#include "hal/ntc_thermistor_sensor.h"
#if defined(ESP32ESSO_TIER2)
#if defined(ESP32ESSO_PRESSURE_ADC)
#include "hal/analog_pressure_sensor.h"
#endif
#if defined(ESP32ESSO_THERMOCOUPLE_MAX6675)
#include "hal/max6675_sensor.h"
#else
#include "hal/max31855_sensor.h"
#endif
#endif
#include "profile/machine_profile.h"

namespace esp32esso::profile {

namespace {

// Same GPIO map as the Oster (see src/board/), so the wiring guides line up.
const board::BoardPins& kPins = board::activeBoard().pins;

// Boiler NTC on the boiler top, through the same divider as the Oster's NTC
// option but with a 10k series resistor: a boiler sits between 90 and 145 C,
// where a 100k NTC drops to a few kOhm and a 100k pull-up would squeeze the
// whole range into the bottom 0.3 V of the ADC. Part values are the common
// 100k / B3950 probe; refine with the ice/boil calibration on the real part.
constexpr hal::NtcCalibration kBoilerNtcCal{
    .r0Ohms = 100000.0f,
    .t0Celsius = 25.0f,
    .betaK = 3950.0f,
    .seriesResistorOhms = 10000.0f,
    .supplyMillivolts = 3300.0f,
    .ntcOnHighSide = false,
};
hal::NtcThermistorSensor g_brewTempSensor(kPins.thermoblockNtc, kBoilerNtcCal);
// Heater SSR in series with the stock 107 C brew thermostat, which stays in
// circuit as a hard limit along with the thermofuse.
hal::GpioDiscreteOutput g_heaterRelay(kPins.heaterSsr, /*activeHigh=*/true);

#if defined(ESP32ESSO_TIER2)
#if defined(ESP32ESSO_THERMOCOUPLE_MAX6675)
using ThermocoupleSensor = hal::Max6675Sensor;
#else
using ThermocoupleSensor = hal::Max31855Sensor;
#endif
// Cascade target thermocouple, on the primary CS.
ThermocoupleSensor g_groupTempSensor(kPins.thermocoupleCs,
                                     kPins.thermocoupleSck,
                                     kPins.thermocoupleMiso);
hal::AcPhaseDimmer g_pumpDimmer(kPins.pumpSsr, kPins.pumpZc);
// The Baby's 3-way solenoid is the brew valve: energised it feeds the group,
// released it vents the puck to the drip tray, so no separate relief output.
hal::GpioDiscreteOutput g_brewValveRelay(kPins.brewValveSsr, /*activeHigh=*/false);
#if defined(ESP32ESSO_PRESSURE_ADC)
hal::AnalogPressureSensor g_pressureSensor(kPins.pressureAdc);
#endif
#endif

// Every thermal and hydraulic value below is UNVERIFIED: written from the
// schematic and the Oster's behaviour, never run on a Baby. See
// docs/machines/gaggia-baby.md before flashing.
const MachineProfile kGaggiaBabyProfile = {
    .metadata =
        {
            .brand = "Gaggia",
            .model = "Baby (BABY GAGGIA/A, Baby 89 class)",
            .voltageRegion = "220-240V",
            .notes = "Untested profile written without the machine; "
                     "see docs/machines/gaggia-baby.md",
        },
    .thermal =
        {
            .minBrewTempC = 80.0f,
            // Above the 145 C steam thermostat, well below the thermofuse.
            .maxSafeTempC = 155.0f,
            .defaultBrewTempC = 93.0f,
            .steamTempC = 145.0f,
            // A brass/aluminium boiler full of water lags a thermoblock, so
            // softer P and I with more D than the Oster as a starting point.
            .pidKp = 0.07f,
            .pidKi = 0.002f,
            .pidKd = 3.0f,
            .pwmWindowMs = 1000,
            // The group bolts straight to the boiler, so the boiler needs far
            // less headroom over the cup target than the Oster's thermoblock.
            .defaultThermoblockOffsetC = 8.0f,
            .maxThermoblockOffsetC = 20.0f,
            .approachDutyPerC = 0.03f,
        },
    .hydraulic =
        {
            .maxPressureBar = 12.0f,
            .opvPressureBar = 9.0f,  // UNVERIFIED: the brass valve may be the boiler safety valve, not an OPV
            .hasThreeWayValve = true,
            .pumpIsVibration = true,
        },
    .brewTempSensor = &g_brewTempSensor,
    .heaterRelay = &g_heaterRelay,
#if defined(ESP32ESSO_TIER2)
    .groupTempSensor = &g_groupTempSensor,
    .solenoidValve = nullptr,
    .pumpRelay = nullptr,
    .pumpDimmer = &g_pumpDimmer,
    .brewValveRelay = &g_brewValveRelay,
#else
    .groupTempSensor = nullptr,
    .solenoidValve = nullptr,
    .pumpRelay = nullptr,
    .pumpDimmer = nullptr,
    .brewValveRelay = nullptr,
#endif
#if defined(ESP32ESSO_TIER2) && defined(ESP32ESSO_PRESSURE_ADC)
    .pressureSensor = &g_pressureSensor,
#else
    .pressureSensor = nullptr,
#endif
    .scale = nullptr,
};

}  // namespace

const MachineProfile& activeProfile() {
    return kGaggiaBabyProfile;
}

hal::NtcThermistorSensor* thermoblockNtcSensor() {
    return &g_brewTempSensor;
}

void silenceMainsOutputs() {
    g_heaterRelay.begin();
#if defined(ESP32ESSO_TIER2)
    pinMode(kPins.pumpSsr, OUTPUT);
    digitalWrite(kPins.pumpSsr, LOW);
    g_brewValveRelay.begin();
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
    g_pumpDimmer.begin();
    g_brewValveRelay.begin();
#if defined(ESP32ESSO_PRESSURE_ADC)
    g_pressureSensor.begin();
#endif
#endif
}

}  // namespace esp32esso::profile

#endif  // ESP32ESSO_PROFILE_GAGGIA_BABY
