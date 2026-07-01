#pragma once

#include <cstdint>

namespace esp32esso::board {

// GPIO assignment for the peripherals a machine profile binds. The concrete
// numbers differ per dev board (classic ESP32 cannot reuse the S3 pins, since
// GPIO 6-11 back the module flash and a few pins are boot-strapping).
//
// Only Tier 1 lines exist today; solenoid, brew-switch, pressure-transducer
// and pump-dimmer pins are added here as their tiers land.
struct BoardPins {
    uint8_t thermocoupleCs;
    uint8_t thermocoupleSck;
    uint8_t thermocoupleMiso;
    uint8_t heaterSsr;
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
