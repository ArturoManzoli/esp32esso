#pragma once

#include <cstdint>

#include "hal/discrete_input.h"
#include "hal/discrete_output.h"
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

    // Tier 1 - always required
    hal::TemperatureSensor* brewTempSensor;
    hal::DiscreteOutput* heaterRelay;

    // Tier 1 (most machines) - 3-way solenoid + brew switch
    hal::DiscreteOutput* solenoidValve;
    hal::DiscreteInput* brewSwitch;

    // Tier 2+ - optional
    hal::PressureSensor* pressureSensor;
};

// Selected at link time by the build env via the matching profile env-flag.
// Defined exactly once in src/profile/active_profile.cpp by the chosen
// profile's translation unit.
extern const MachineProfile& activeProfile();

}  // namespace esp32esso::profile
