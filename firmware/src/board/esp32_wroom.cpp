#if defined(ESP32ESSO_BOARD_WROOM)

#include "board/board_config.h"

namespace esp32esso::board {

// Classic ESP32-WROOM DevKit (38-pin). Pins avoid the flash-backed range
// (GPIO 6-11) and the input-only range (GPIO 34-39). SCK/SO follow the VSPI
// defaults; the MAX6675 amps (esp32-oster-xpert) are read-only SPI so no MOSI
// is wired. Tier 2 (dual-TC) adds a second thermocouple CS (GPIO 5) for the
// thermoblock amp -- the group amp stays on the Tier 1 CS (GPIO 21). GPIO 5 is
// a boot-strapping pin, but it only has to idle HIGH (its reset default, which
// is exactly how an unselected CS sits), so it is safe as a CS here. Tier 2
// also adds a brew-switch brew SSR (GPIO 27) and a relief-valve output
// (GPIO 26); all unused until the Tier 2 build.
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
                .thermocoupleCs = 21,   // group/portafilter amp (both builds)
                .thermocoupleSck = 18,
                .thermocoupleMiso = 19,
                .thermocoupleCs2 = 5,   // dual-TC thermoblock amp (shared bus)
                .thermoblockNtc = 34,  // ADC1_CH6, input-only (no pull needed)
                .heaterSsr = 23,  // D23 on the 38-pin WROOM DevKit
                .brewSsr = 27,
                .solenoidValve = 26,  // second SSR/MOSFET: relief/vent valve
                .pressureAdc = 35,  // ADC1_CH7, input-only: brew-line transducer
            },
    };
    return kBoard;
}

}  // namespace esp32esso::board

#endif  // ESP32ESSO_BOARD_WROOM
