#pragma once

#include <cstdint>

#include "hal/ac_phase_dimmer.h"
#include "hal/discrete_input.h"
#include "hal/discrete_output.h"
#include "hal/dual_hx711_scale.h"
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

    // Tier 2 offset: the user setpoint is the group/portafilter target, and
    // the thermoblock setpoint is derived as groupSetpoint + offset. Positive
    // offsets push the thermoblock hotter (compensating transport loss down to
    // the puck); negative offsets pull it colder (e.g. for pre-warmed heads
    // that overshoot). Clamped to [-maxThermoblockOffsetC, +maxThermoblockOffsetC]
    // on top of the hard safety cap. `defaultThermoblockOffsetC` seeds the
    // phone slider on first boot; the value persists in NVS thereafter.
    float defaultThermoblockOffsetC;
    float maxThermoblockOffsetC;

    // Soft-landing approach taper. The heater duty is ceilinged at
    // approachDutyPerC * (setpoint - measured) so the loop eases off well before
    // the setpoint instead of holding full power right up to it. The thermoblock
    // stores enough heat that even a tapered approach coasts several degrees past
    // the target (the aluminium block + cartridge stay hotter than the probe
    // reads on the way up, and that gradient only bleeds off passively -- there
    // is no active cooling), so the taper needs to start reining in duty well
    // before the final few degrees, not just within them. A smaller value here
    // means a wider, gentler taper band. 0 disables the taper.
    float approachDutyPerC;
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

    // Tier 2 - relief/3-way solenoid (post-shot vent pulse) plus the pump and
    // brew-valve actuators for a shot. Prefer pumpDimmer (Standard AC
    // phase-angle module on DIM + Z-C) when present; otherwise pumpRelay is
    // the legacy zero-cross SSR path (time-proportioned). brewValveRelay is
    // always plain on/off -- never PWM'd.
    hal::DiscreteOutput* solenoidValve;
    hal::DiscreteOutput* pumpRelay;
    hal::AcPhaseDimmer* pumpDimmer;
    hal::DiscreteOutput* brewValveRelay;

    // Tier 2+ - optional
    hal::PressureSensor* pressureSensor;

    // Tier 4 - drip-tray scale (two load cells summed). nullptr until the
    // hardware is installed and the build enables ESP32ESSO_SCALE_HX711.
    hal::DualHx711Scale* scale;

    // Tier 2 - optional panel inputs, so a shot or steam can start without the
    // phone (ESP32ESSO_PANEL_INPUTS). nullptr when not wired.
    hal::DiscreteInput* brewSwitch;
    hal::DiscreteInput* steamSwitch;
};

// Selected at link time by the build env via the matching profile env-flag.
// Defined exactly once across the src/profile/*.cpp translation units, each
// of which is wrapped in an `#if defined(ESP32ESSO_PROFILE_<NAME>)` guard.
extern const MachineProfile& activeProfile();

// Drives every mains-switching output to its idle level. Called as the first
// statement of setup(), before Serial and the sensors, since these pins leave
// reset floating and a floating SSR/TRIAC gate can conduct.
extern void silenceMainsOutputs();

// Performs any one-time hardware initialisation needed by the active
// profile (SPI bus, pinMode, etc.). Called from setup() exactly once,
// after Serial.begin().
extern void initActiveProfilePeripherals();

// Returns the thermoblock NTC when the active profile reads its thermoblock
// from a stock NTC thermistor (ESP32ESSO_THERMOBLOCK_NTC); nullptr otherwise.
// Used by the calibration workflow to push and persist fitted R0/Beta.
extern hal::NtcThermistorSensor* thermoblockNtcSensor();

}  // namespace esp32esso::profile
