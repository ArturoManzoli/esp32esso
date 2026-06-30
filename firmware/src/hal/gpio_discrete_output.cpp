#include "hal/gpio_discrete_output.h"

#include <Arduino.h>

namespace esp32esso::hal {

GpioDiscreteOutput::GpioDiscreteOutput(uint8_t pin, bool activeHigh)
    : pin_(pin), activeHigh_(activeHigh) {}

void GpioDiscreteOutput::begin() {
    pinMode(pin_, OUTPUT);
    set(false);
}

void GpioDiscreteOutput::set(bool on) {
    on_ = on;
    const bool level = activeHigh_ ? on : !on;
    digitalWrite(pin_, level ? HIGH : LOW);
}

}  // namespace esp32esso::hal
