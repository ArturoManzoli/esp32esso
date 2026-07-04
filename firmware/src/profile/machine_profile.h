#pragma once

#include <cstdint>

#include "hal/discrete_input.h"
#include "hal/discrete_output.h"
#include "hal/ntc_thermistor_sensor.h"
#include "hal/pressure_sensor.h"
#include "hal/temperature_sensor.h"

namespace esp32esso::profile {

// Human-readable identity for a machine. Surfaced in BLE advertising,
// telemetry headers, and the OTA build banner.
struct MachineMetadata {
    const char* brand;
    const char* model;
    const char* voltageRegion;  // "100-120V" / "220-240V"
    const char* notes;
};

// Closed-loop temperature parameters. Tuned per machine because thermal
// mass (boiler vs thermoblock) dictates the gains and the SSR window.
struct ThermalConfig {
    float minBrewTempC;
    float maxSafeTempC;       // hard cut: any reading above this disables the heater
    float defaultBrewTempC;
    float steamTempC;
    float pidKp;
    float pidKi;
    float pidKd;
    uint32_t pwmWindowMs;     // slow-PWM window for the SSR heater driver

    // Tier 2 cascade: the user setpoint targets the group/portafilter sensor,
    // and the thermoblock is driven hotter to overcome the transport loss.
    // thermoblockSetpoint = groupSetpoint + gain * (groupSetpoint - groupTemp),
    // clamped to [groupSetpoint, groupSetpoint + maxCascadeOffsetC] and the
    // hard safety cap. `defaultCascadeGain` seeds the phone knob (0..10).
    float defaultCascadeGain;
    float maxCascadeOffsetC;
};

// Wet-side properties. Mostly informational for the user, but a few fields
// (hasThreeWayValve, pumpIsVibration) gate features the firmware will or
// will not enable for this machine.
struct HydraulicConfig {
    float maxPressureBar;
    float opvPressureBar;
    bool hasThreeWayValve;
    bool pumpIsVibration;
};

// Concrete peripherals are bound by the profile at boot. Optional pointers
// are nullptr on machines / install tiers that do not have that hardware.
struct MachineProfile {
    MachineMetadata metadata;
    ThermalConfig thermal;
    HydraulicConfig hydraulic;

    // Tier 1 - always required. brewTempSensor sits on the thermoblock and is
    // the inner PID's process variable and the safety cut's reference.
    hal::TemperatureSensor* brewTempSensor;
    hal::DiscreteOutput* heaterRelay;

    // Tier 2 - group/portafilter sensor that the cascade setpoint targets.
    // nullptr on a Tier 1 install: the loop then controls the thermoblock
    // directly against the user setpoint.
    hal::TemperatureSensor* groupTempSensor;

    // Tier 1 (most machines) - 3-way solenoid + brew switch. brewSwitch, when
    // present, auto-starts the Tier 2 shot timer.
    hal::DiscreteOutput* solenoidValve;
    hal::DiscreteInput* brewSwitch;

    // Tier 2+ - optional
    hal::PressureSensor* pressureSensor;
};

// Selected at link time by the build env via the matching profile env-flag.
// Defined exactly once across the src/profile/*.cpp translation units, each
// of which is wrapped in an `#if defined(ESP32ESSO_PROFILE_<NAME>)` guard.
extern const MachineProfile& activeProfile();

// Performs any one-time hardware initialisation needed by the active
// profile (SPI bus, pinMode, etc.). Called from setup() exactly once,
// after Serial.begin().
extern void initActiveProfilePeripherals();

// Returns the thermoblock NTC when the active profile reads its thermoblock
// from a stock NTC thermistor (ESP32ESSO_THERMOBLOCK_NTC); nullptr otherwise.
// Used by the calibration workflow to push and persist fitted R0/Beta.
extern hal::NtcThermistorSensor* thermoblockNtcSensor();

}  // namespace esp32esso::profile
