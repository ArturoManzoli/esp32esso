#pragma once

#include <cstdint>

#include "hal/discrete_output.h"

namespace esp32esso::hal {

// Plain digital-output driver for a GPIO. Suitable for SSR control lines
// and low-side MOSFETs; not suitable for direct mains switching without an
// opto-isolated SSR in between.
class GpioDiscreteOutput : public DiscreteOutput {
public:
    GpioDiscreteOutput(uint8_t pin, bool activeHigh = true);

    void begin();
    void set(bool on) override;
    bool isOn() const override { return on_; }

private:
    uint8_t pin_;
    bool activeHigh_;
    bool on_ = false;
};

}  // namespace esp32esso::hal
