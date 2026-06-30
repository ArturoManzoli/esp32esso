#pragma once

#include <cstdint>

#include "hal/temperature_sensor.h"

namespace esp32esso::hal {

// Driver for the MAX31855 cold-junction-compensated thermocouple amplifier.
// Read-only SPI device (no MOSI). Up to ~10 Hz sample rate.
//
// The driver decodes the 32-bit frame inline; if any of the three fault
// flags (OPEN, SHORT-TO-GND, SHORT-TO-VCC) is asserted the reading is
// rejected, NAN is returned, and ok() is false until the next successful
// read.
class Max31855Sensor : public TemperatureSensor {
public:
    Max31855Sensor(uint8_t csPin, uint8_t sckPin, uint8_t misoPin);

    void begin();

    float readCelsius() override;
    bool ok() const override;

    // Fault flags from the last frame, exposed for telemetry. Bit 0 = OC
    // (open circuit), bit 1 = SCG (short to GND), bit 2 = SCV (short to
    // VCC). Zero when the last read was clean.
    uint8_t lastFaultMask() const { return faultMask_; }

private:
    uint8_t csPin_;
    uint8_t sckPin_;
    uint8_t misoPin_;
    bool ok_ = false;
    uint8_t faultMask_ = 0;
    float lastTempC_ = 0.0f;
};

}  // namespace esp32esso::hal
