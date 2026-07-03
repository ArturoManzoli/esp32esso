#include "hal/max6675_sensor.h"

#include <Arduino.h>
#include <SPI.h>

#include <cmath>

namespace esp32esso::hal {

namespace {
constexpr uint32_t kSpiHzMax6675 = 4'000'000;  // datasheet max ~4.3 MHz

float decodeThermocouple(uint16_t frame) {
    int16_t raw = static_cast<int16_t>(frame >> 3);
    if (raw & 0x1000) {
        raw |= 0xE000;  // sign-extend the 12-bit field
    }
    return static_cast<float>(raw) * 0.25f;
}
}  // namespace

Max6675Sensor::Max6675Sensor(uint8_t csPin, uint8_t sckPin, uint8_t misoPin)
    : csPin_(csPin), sckPin_(sckPin), misoPin_(misoPin) {}

void Max6675Sensor::begin() {
    pinMode(csPin_, OUTPUT);
    digitalWrite(csPin_, HIGH);
    SPI.begin(sckPin_, misoPin_, -1, csPin_);
}

float Max6675Sensor::readCelsius() {
    SPI.beginTransaction(SPISettings(kSpiHzMax6675, MSBFIRST, SPI_MODE0));
    digitalWrite(csPin_, LOW);
    delayMicroseconds(1);
    const uint16_t frame = SPI.transfer16(0);
    digitalWrite(csPin_, HIGH);
    SPI.endTransaction();

    if ((frame & 0x0004) != 0) {
        ok_ = false;
        return NAN;
    }
    lastTempC_ = decodeThermocouple(frame);
    ok_ = true;
    return lastTempC_;
}

bool Max6675Sensor::ok() const {
    return ok_;
}

}  // namespace esp32esso::hal
