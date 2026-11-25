#pragma once

#include <cstdint>

#include "hal/analog_pressure_sensor.h"
#include "hal/pressure_sensor.h"

namespace esp32esso::hal {

// The same ratiometric transducer and 2:1 divider as AnalogPressureSensor, but
// digitised by an external ADS1115 on I2C instead of the ESP32's own ADC, which
// is nonlinear at the ends of its range and picks up noise from the radio. Run
// the ADS1115 from 3V3 (its inputs must stay below its supply, and a 5 V supply
// would pull the ESP32's I2C lines to 5 V through the module's pull-ups), with
// the divider node on AIN0. The converter free-runs at 860 SPS; each read takes
// the latest conversion and returns the median of the last few, standing in for
// the burst median the on-chip driver uses against vibration-pump spikes.
class Ads1115PressureSensor : public PressureSensor {
public:
    Ads1115PressureSensor(uint8_t sdaPin, uint8_t sclPin, uint8_t address = 0x48,
                          const PressureCalibration& cal = PressureCalibration());

    void begin();

    float readBar() override;
    bool ok() const override;

    float lastMillivolts() const { return lastMillivolts_; }

private:
    bool configure();

    static constexpr uint8_t kMedianWindow = 5;

    uint8_t sdaPin_;
    uint8_t sclPin_;
    uint8_t address_;
    PressureCalibration cal_;
    bool configured_ = false;
    bool ok_ = false;
    float lastMillivolts_ = 0.0f;
    float window_[kMedianWindow] = {};
    uint8_t windowIndex_ = 0;
    uint8_t windowCount_ = 0;
};

}  // namespace esp32esso::hal
