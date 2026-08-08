#pragma once

#include <cstdint>

namespace esp32esso::board {

// GPIO assignment for the peripherals a machine profile binds. The concrete
// numbers differ per dev board (classic ESP32 cannot reuse the S3 pins, since
// GPIO 6-11 back the module flash and a few pins are boot-strapping).
//
// `thermocoupleCs` is the primary amp CS: the thermoblock probe in Tier 1, and
// the group/portafilter probe in a Tier 2 dual-thermocouple build. Tier 2
// (recommended) adds a second, identical thermocouple for the thermoblock on
// `thermocoupleCs2`, sharing the same SCK/SO bus (only CS differs), plus two
// independent Tier 2 outputs for the app's start/stop shot: `pumpSsr` +
// `pumpZc` drive a Standard AC phase-dimmer on the vibration pump (DIM +
// zero-cross), and `brewValveSsr` is the brew solenoid alone (plain on/off --
// never PWM'd, so the valve is not chattered/buzzed). Older builds used a
// pump SSR time-proportioned on `pumpSsr` alone; the dimmer path needs the
// extra Z-C input (see `AcPhaseDimmer` / `updatePumpAndBrewValve`).
// `thermoblockNtc` is an ADC1 channel for the alternative build that reuses a
// stock NTC thermistor on the thermoblock instead of a thermocouple (see
// ESP32ESSO_THERMOBLOCK_NTC); the dual-thermocouple setup is preferred (linear,
// cold-junction-compensated, less drift). Solenoid and pressure-transducer
// pins land with their tiers.
struct BoardPins {
    uint8_t thermocoupleCs;
    uint8_t thermocoupleSck;
    uint8_t thermocoupleMiso;
    uint8_t thermocoupleCs2;  // dual-TC thermoblock amp CS (shared bus); unused in -ntc build
    uint8_t thermoblockNtc;   // ADC1 pin for the stock NTC thermistor option
    uint8_t heaterSsr;
    uint8_t pumpSsr;  // Tier 2 pump dimmer DIM (gate) pin; legacy name kept
    uint8_t pumpZc;   // Tier 2 pump dimmer zero-cross input
    uint8_t brewValveSsr;  // Tier 2 brew solenoid valve alone (app start/stop shot, on/off)
    uint8_t solenoidValve;  // Tier 2 relief/3-way valve output (second SSR/MOSFET)
    uint8_t pressureAdc;  // Tier 2 brew-line pressure transducer (ADC1 divider node)
};

// Human-readable board identity plus the capability hints the higher tiers
// use to steer users. Tier 1 runs on any ESP32; `recommendedForHigherTiers`
// flags the boards (S3, with more flash/PSRAM/CPU headroom) that Tier 2-4
// are validated against.
struct BoardInfo {
    const char* id;    // "esp32-wroom-devkit" / "esp32-s3-devkitc-1"
    const char* chip;  // "ESP32" / "ESP32-S3"
    bool recommendedForHigherTiers;
};

struct BoardConfig {
    BoardInfo info;
    BoardPins pins;
};

// Selected at link time by the build env's ESP32ESSO_BOARD_<NAME> flag.
// Defined exactly once across src/board/*.cpp, each guarded by
// `#if defined(ESP32ESSO_BOARD_<NAME>)`.
const BoardConfig& activeBoard();

}  // namespace esp32esso::board
