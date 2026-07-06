#if defined(ESP32ESSO_BOARD_S3)

#include "board/board_config.h"

namespace esp32esso::board {

// ESP32-S3-DevKitC-1. More flash/PSRAM and CPU headroom than the WROOM, so
// this is the board Tier 2-4 (BLE, pressure/flow profiling, OTA) are
// validated against. Thermocouple amps are read-only so no MOSI is wired.
// Tier 2 adds a second CS (GPIO 11) for the group sensor, a brew-switch
// brew SSR (GPIO 5), and a relief-valve output (GPIO 14).
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
                .brewSsr = 5,
                .solenoidValve = 14,  // second SSR/MOSFET: relief/vent valve
                .pressureAdc = 8,  // ADC1_CH7: brew-line transducer
            },
    };
    return kBoard;
}

}  // namespace esp32esso::board

#endif  // ESP32ESSO_BOARD_S3
