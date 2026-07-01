#if defined(ESP32ESSO_BOARD_S3)

#include "board/board_config.h"

namespace esp32esso::board {

// ESP32-S3-DevKitC-1. More flash/PSRAM and CPU headroom than the WROOM, so
// this is the board Tier 2-4 (BLE, pressure/flow profiling, OTA) are
// validated against. MAX31855 is read-only so no MOSI is wired.
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
                .heaterSsr = 4,
            },
    };
    return kBoard;
}

}  // namespace esp32esso::board

#endif  // ESP32ESSO_BOARD_S3
