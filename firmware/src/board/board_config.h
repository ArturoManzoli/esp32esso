#pragma once

#include <cstdint>

namespace esp32esso::board {

// GPIO assignment for the peripherals a machine profile binds. The concrete
// numbers differ per dev board (classic ESP32 cannot reuse the S3 pins, since
// GPIO 6-11 back the module flash and a few pins are boot-strapping).
//
// Tier 1 uses a single thermocouple on the thermoblock. Tier 2 (recommended)
// adds a second, identical thermocouple at the portafilter/group
// (`thermocoupleCs2`, sharing the same SCK/SO bus) plus an optional brew SSR
// output (`brewSsr`) for pump/valve control from the app. `thermoblockNtc` is
// an ADC1 channel for the alternative build that reuses a stock NTC thermistor
// on the thermoblock instead of a thermocouple (see ESP32ESSO_THERMOBLOCK_NTC);
// the dual-thermocouple setup is preferred (linear, cold-junction-compensated,
// less drift). Solenoid, pressure-transducer and pump-dimmer pins land with
// their tiers.
struct BoardPins {
    uint8_t thermocoupleCs;
    uint8_t thermocoupleSck;
    uint8_t thermocoupleMiso;
    uint8_t thermocoupleCs2;  // Tier 2 group/portafilter sensor (shared bus)
    uint8_t thermoblockNtc;   // ADC1 pin for the stock NTC thermistor option
    uint8_t heaterSsr;
    uint8_t brewSsr;  // Tier 2 brew pump/valve SSR (app start/stop shot)
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
