#if defined(ESP32ESSO_BOARD_WROOM)

#include "board/board_config.h"

namespace esp32esso::board {

// Classic ESP32-WROOM DevKit (38-pin). Pins avoid the flash-backed range
// (GPIO 6-11), the input-only range (GPIO 34-39) and the boot-strapping pins
// (GPIO 0/2/5/12/15). SCK/SO follow the VSPI defaults; the MAX6675 amp
// (esp32-oster-xpert) is read-only SPI so no MOSI is wired.
const BoardConfig& activeBoard() {
    static const BoardConfig kBoard = {
        .info =
            {
                .id = "esp32-wroom-devkit",
                .chip = "ESP32",
                .recommendedForHigherTiers = false,
            },
        .pins =
            {
                .thermocoupleCs = 21,
                .thermocoupleSck = 18,
                .thermocoupleMiso = 19,
                .heaterSsr = 4,
            },
    };
    return kBoard;
}

}  // namespace esp32esso::board

#endif  // ESP32ESSO_BOARD_WROOM
