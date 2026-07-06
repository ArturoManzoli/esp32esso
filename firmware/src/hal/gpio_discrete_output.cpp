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
    // Reclaim the pin on every write — SPI.begin() on the shared thermocouple
    // bus can leave VSPI MOSI (GPIO 23) attached to the driver on some cores.
    pinMode(pin_, OUTPUT);
    digitalWrite(pin_, level ? HIGH : LOW);
}

}  // namespace esp32esso::hal
