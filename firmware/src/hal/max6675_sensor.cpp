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
    // Discard the first conversions after power-up (datasheet: ~220 ms each).
    for (int i = 0; i < 2; ++i) {
        SPI.beginTransaction(SPISettings(kSpiHzMax6675, MSBFIRST, SPI_MODE0));
        digitalWrite(csPin_, LOW);
        delayMicroseconds(1);
        (void)SPI.transfer16(0);
        digitalWrite(csPin_, HIGH);
        SPI.endTransaction();
        delay(220);
    }
}

float Max6675Sensor::readCelsius() {
    // Each read completes the previous conversion; discard one frame first.
    auto readFrame = [this]() -> uint16_t {
        SPI.beginTransaction(SPISettings(kSpiHzMax6675, MSBFIRST, SPI_MODE0));
        digitalWrite(csPin_, LOW);
        delayMicroseconds(1);
        const uint16_t frame = SPI.transfer16(0);
        digitalWrite(csPin_, HIGH);
        SPI.endTransaction();
        return frame;
    };

    (void)readFrame();
    const uint16_t frame = readFrame();

    // A floating MISO line often reads 0x0000, which decodes as 0 C with no
    // fault bit — treat empty-bus patterns as disconnected.
    if (frame == 0 || frame == 0xFFFF) {
        ok_ = false;
        return NAN;
    }
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
