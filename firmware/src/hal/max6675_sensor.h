#pragma once

#include <cstdint>

#include "hal/temperature_sensor.h"

namespace esp32esso::hal {

// Driver for the MAX6675 cold-junction-compensated thermocouple amplifier.
// Read-only SPI device (no MOSI). Typical Arduino breakouts label the data
// line SO; wire it to the board's thermocoupleMiso pin.
//
// Conversion takes up to ~220 ms; the control loop's 200 ms period is
// sufficient. If bit 2 of the 16-bit frame is set the thermocouple is open
// and readCelsius() returns NAN.
class Max6675Sensor : public TemperatureSensor {
public:
    Max6675Sensor(uint8_t csPin, uint8_t sckPin, uint8_t misoPin);

    void begin();

    float readCelsius() override;
    bool ok() const override;

private:
    uint8_t csPin_;
    uint8_t sckPin_;
    uint8_t misoPin_;
    bool ok_ = false;
    float lastTempC_ = 0.0f;
};

}  // namespace esp32esso::hal
