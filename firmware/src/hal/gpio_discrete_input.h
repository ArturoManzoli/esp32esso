#pragma once

#include <cstdint>

#include "hal/discrete_input.h"

namespace esp32esso::hal {

// Polled, debounced GPIO input for panel switches (e.g. the brew switch used
// by the Tier 2 shot timer). Configured active-low with the internal pullup
// by default so an unconnected pin reads "off" and a switch to GND reads
// "on". read() returns the debounced level; call it at loop frequency.
class GpioDiscreteInput : public DiscreteInput {
public:
    GpioDiscreteInput(uint8_t pin, bool activeLow = true, uint32_t debounceMs = 30);

    void begin();
    bool read() override;

private:
    uint8_t pin_;
    bool activeLow_;
    uint32_t debounceMs_;
    bool stable_ = false;
    bool candidate_ = false;
    uint32_t lastChangeMs_ = 0;
};

}  // namespace esp32esso::hal
