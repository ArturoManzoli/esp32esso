#if defined(ESP32ESSO_BOARD_S3)

#include "board/board_config.h"

namespace esp32esso::board {

// ESP32-S3-DevKitC-1. More flash/PSRAM and CPU headroom than the WROOM, so
// this is the board Tier 2-4 (BLE, pressure/flow profiling, OTA) are
// validated against. Thermocouple amps are read-only so no MOSI is wired.
// Tier 2 (dual-TC) adds a second thermocouple CS (GPIO 11) for the thermoblock
// amp -- the group amp stays on the Tier 1 CS (GPIO 10) -- plus a pump
// phase-dimmer (DIM GPIO 6, Z-C GPIO 9), a brew-valve SSR (GPIO 5), and a
// relief-valve output (GPIO 14). Tier 4 adds the drip-tray scale: one shared
// HX711 clock (GPIO 21) and a data pin per amp (GPIO 15, 16). These stay clear
// of the octal-PSRAM range (GPIO 33-37) an N16R8 module uses.
const BoardConfig& activeBoard() {
    static const BoardConfig kBoard = {
        .info =
            {
                .id = "esp32-s3-devkitc-1",
                .chip = "ESP32-S3",
                .recommendedForHigherTiers = true,
            },
        .pins =
            {
                .thermocoupleCs = 10,
                .thermocoupleSck = 12,
                .thermocoupleMiso = 13,
                .thermocoupleCs2 = 11,
                .thermoblockNtc = 7,  // ADC1_CH6
                .heaterSsr = 4,
                .pumpSsr = 6,  // pump dimmer DIM (gate)
                .pumpZc = 9,   // pump dimmer zero-cross
                .brewValveSsr = 5,  // brew valve alone, plain on/off
                .solenoidValve = 14,  // second SSR/MOSFET: relief/vent valve
                .pressureAdc = 8,  // ADC1_CH7: brew-line transducer
                .scaleSck = 21,        // HX711 clock, shared by both amps
                .scaleDoutLeft = 15,   // HX711 data, left drip-tray cell
                .scaleDoutRight = 16,  // HX711 data, right drip-tray cell
                .brewSwitchIn = 17,
                .i2cSda = 1,  // ADS1115 option
                .i2cScl = 2,
            },
    };
    return kBoard;
}

}  // namespace esp32esso::board

#endif  // ESP32ESSO_BOARD_S3
