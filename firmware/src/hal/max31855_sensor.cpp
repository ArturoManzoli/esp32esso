#include "hal/max31855_sensor.h"

#include <Arduino.h>
#include <SPI.h>

#include <cmath>

namespace esp32esso::hal {

namespace {
constexpr uint32_t kSpiHzMax31855 = 4'000'000;  // datasheet allows up to 5 MHz

// Decode the 14-bit signed thermocouple field at bits [31:18].
float decodeThermocouple(uint32_t frame) {
    uint16_t bits = static_cast<uint16_t>((frame >> 18) & 0x3FFF);
    if (bits & 0x2000) {
        // sign-extend the 14-bit two's complement value to 16 bits
        bits |= 0xC000;
    }
    const int16_t raw = static_cast<int16_t>(bits);
    return static_cast<float>(raw) * 0.25f;
}
}  // namespace

Max31855Sensor::Max31855Sensor(uint8_t csPin, uint8_t sckPin, uint8_t misoPin)
    : csPin_(csPin), sckPin_(sckPin), misoPin_(misoPin) {}

void Max31855Sensor::begin() {
    pinMode(csPin_, OUTPUT);
    digitalWrite(csPin_, HIGH);
    // MOSI is unused; pass -1 so the bus driver does not allocate it.
    SPI.begin(sckPin_, misoPin_, -1, csPin_);
}

float Max31855Sensor::readCelsius() {
    SPI.beginTransaction(SPISettings(kSpiHzMax31855, MSBFIRST, SPI_MODE0));
    digitalWrite(csPin_, LOW);
    delayMicroseconds(1);
    const uint32_t frame = SPI.transfer32(0);
    digitalWrite(csPin_, HIGH);
    SPI.endTransaction();

    const bool generalFault = (frame & (1u << 16)) != 0;
    faultMask_ = static_cast<uint8_t>(frame & 0x07);
    if (generalFault || faultMask_ != 0) {
        ok_ = false;
        return NAN;
    }
    lastTempC_ = decodeThermocouple(frame);
    ok_ = true;
    return lastTempC_;
}

bool Max31855Sensor::ok() const {
    return ok_;
}

}  // namespace esp32esso::hal
