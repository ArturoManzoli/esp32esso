#pragma once

namespace esp32esso::hal {

// Abstract source of a single brew-side temperature reading in degrees
// Celsius. Implementations include MAX31855 (thermocouple), MAX31865 (PT100
// RTD), and a software simulator used in unit tests.
class TemperatureSensor {
public:
    virtual ~TemperatureSensor() = default;

    // Latest reading. Returns NAN when the sensor is in a fault state.
    virtual float readCelsius() = 0;

    // True when the most recent transaction succeeded and the reading can
    // be trusted by the control loop.
    virtual bool ok() const = 0;
};

}  // namespace esp32esso::hal
