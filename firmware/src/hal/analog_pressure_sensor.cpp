#include "hal/analog_pressure_sensor.h"

#include <Arduino.h>

#include <algorithm>
#include <cmath>

namespace esp32esso::hal {

namespace {
constexpr int kBurst = 15;  // odd count so the median is a single sample
}  // namespace

AnalogPressureSensor::AnalogPressureSensor(uint8_t adcPin, const PressureCalibration& cal)
    : adcPin_(adcPin), cal_(cal) {}

void AnalogPressureSensor::begin() {
    // 11 dB attenuation covers the full divider swing; read eFuse-calibrated
    // millivolts to tame the ESP32 ADC's nonlinearity.
    analogSetPinAttenuation(adcPin_, ADC_11db);
    analogReadResolution(12);
}

float AnalogPressureSensor::readBar() {
    // Median of a fast burst rejects the vibration pump's impulse spikes without
    // the lag an average would add to a real ramp.
    float samples[kBurst];
    for (int i = 0; i < kBurst; ++i) {
        samples[i] = static_cast<float>(analogReadMilliVolts(adcPin_));
    }
    std::sort(samples, samples + kBurst);
    const float pinMv = samples[kBurst / 2];
    lastMillivolts_ = pinMv;

    // Undo the divider to recover the transducer output voltage.
    if (cal_.dividerRatio <= 0.0f) {
        ok_ = false;
        return NAN;
    }
    const float sensorMv = pinMv / cal_.dividerRatio;

    const float span = cal_.vMaxMillivolts - cal_.vMinMillivolts;
    if (!(span > 0.0f)) {
        ok_ = false;
        return NAN;
    }
    const float bar = (sensorMv - cal_.vMinMillivolts) / span * cal_.fullScaleBar;

    if (std::isnan(bar) || bar < cal_.zeroClampBar) {
        ok_ = false;
        return NAN;  // below zero => transducer unpowered / unplugged
    }
    ok_ = true;
    return bar < 0.0f ? 0.0f : bar;
}

bool AnalogPressureSensor::ok() const {
    return ok_;
}

}  // namespace esp32esso::hal
