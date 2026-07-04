#include "hal/gpio_discrete_input.h"

#include <Arduino.h>

namespace esp32esso::hal {

GpioDiscreteInput::GpioDiscreteInput(uint8_t pin, bool activeLow, uint32_t debounceMs)
    : pin_(pin), activeLow_(activeLow), debounceMs_(debounceMs) {}

void GpioDiscreteInput::begin() {
    // Input-only pins (GPIO 34-39 on the classic ESP32) have no internal
    // pulls; fall back to a plain INPUT there and rely on an external pull.
    if (activeLow_ && pin_ < 34) {
        pinMode(pin_, INPUT_PULLUP);
    } else {
        pinMode(pin_, INPUT);
    }
    const bool level = digitalRead(pin_) == HIGH;
    stable_ = activeLow_ ? !level : level;
    candidate_ = stable_;
    lastChangeMs_ = millis();
}

bool GpioDiscreteInput::read() {
    const bool level = digitalRead(pin_) == HIGH;
    const bool active = activeLow_ ? !level : level;
    const uint32_t now = millis();
    if (active != candidate_) {
        candidate_ = active;
        lastChangeMs_ = now;
    } else if (candidate_ != stable_ && (now - lastChangeMs_) >= debounceMs_) {
        stable_ = candidate_;
    }
    return stable_;
}

}  // namespace esp32esso::hal
