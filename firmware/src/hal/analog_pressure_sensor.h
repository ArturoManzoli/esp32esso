#pragma once

#include <cstdint>

#include "hal/pressure_sensor.h"

namespace esp32esso::hal {

// Ratiometric brew-line pressure transducer (the ubiquitous 5 V, 0.5-4.5 V,
// 0-1.2 MPa "XDB401" type) read through a resistor divider on an ESP32 ADC1
// channel. The transducer's 0.5-4.5 V span is scaled down to the 3.3 V ADC by a
// divider, so the sensor voltage at the pin is:
//
//   Vpin = Vsensor * dividerRatio          (e.g. 20k/(10k+20k) = 0.667)
//
// and the gauge pressure is linear in the sensor voltage:
//
//   bar = (Vsensor - Vmin) / (Vmax - Vmin) * fullScaleBar
//
// Each read takes a short median-of-burst to reject the vibration pump's
// spikes; the caller (TemperatureLoop) applies the ripple-smoothing filter.
struct PressureCalibration {
    float vMinMillivolts = 500.0f;     // transducer output at 0 bar (0.5 V)
    float vMaxMillivolts = 4500.0f;    // transducer output at full scale (4.5 V)
    float fullScaleBar = 12.0f;        // pressure at vMax (0-1.2 MPa)
    float dividerRatio = 0.6667f;      // Vpin / Vsensor (20k/(10k+20k))
    float zeroClampBar = -0.5f;        // readings below this are treated as fault
};

class AnalogPressureSensor : public PressureSensor {
public:
    AnalogPressureSensor(uint8_t adcPin, const PressureCalibration& cal = PressureCalibration());

    void begin();

    float readBar() override;
    bool ok() const override;

    float lastMillivolts() const { return lastMillivolts_; }

private:
    uint8_t adcPin_;
    PressureCalibration cal_;
    bool ok_ = false;
    float lastMillivolts_ = 0.0f;
};

}  // namespace esp32esso::hal
