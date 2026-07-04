#include "hal/ntc_thermistor_sensor.h"

#include <Arduino.h>

#include <cmath>

namespace esp32esso::hal {

namespace {
constexpr int kSamples = 16;              // oversample to fight ADC noise
constexpr float kMinValidMv = 25.0f;      // below this: short / at-rail -> fault
constexpr float kMinPlausibleC = -10.0f;  // reject nonsense (open/short) readings
constexpr float kMaxPlausibleC = 200.0f;
constexpr float kZeroCelsiusK = 273.15f;
}  // namespace

NtcThermistorSensor::NtcThermistorSensor(uint8_t adcPin, const NtcCalibration& cal)
    : adcPin_(adcPin), cal_(cal) {}

void NtcThermistorSensor::begin() {
    // 11 dB attenuation covers the full ~0-3.3 V divider swing; read calibrated
    // millivolts (uses the per-chip eFuse ADC calibration) rather than raw
    // counts to tame the ESP32 ADC's nonlinearity.
    analogSetPinAttenuation(adcPin_, ADC_11db);
    analogReadResolution(12);
}

float NtcThermistorSensor::readCelsius() {
    float mvSum = 0.0f;
    for (int i = 0; i < kSamples; ++i) {
        mvSum += static_cast<float>(analogReadMilliVolts(adcPin_));
    }
    const float mv = mvSum / kSamples;
    lastMillivolts_ = mv;

    const float supply = cal_.supplyMillivolts;
    if (mv < kMinValidMv || mv > supply - kMinValidMv) {
        ok_ = false;
        return NAN;  // rail-pinned: NTC open or shorted
    }

    // Convert the divider node voltage to the thermistor resistance.
    float rNtc;
    if (cal_.ntcOnHighSide) {
        rNtc = cal_.seriesResistorOhms * (supply - mv) / mv;
    } else {
        rNtc = cal_.seriesResistorOhms * mv / (supply - mv);
    }
    if (!(rNtc > 0.0f) || std::isnan(rNtc)) {
        ok_ = false;
        return NAN;
    }
    lastResistanceOhms_ = rNtc;

    const float t0K = cal_.t0Celsius + kZeroCelsiusK;
    const float invT = 1.0f / t0K + (1.0f / cal_.betaK) * std::log(rNtc / cal_.r0Ohms);
    const float tempC = 1.0f / invT - kZeroCelsiusK;

    if (std::isnan(tempC) || tempC < kMinPlausibleC || tempC > kMaxPlausibleC) {
        ok_ = false;
        return NAN;
    }
    lastTempC_ = tempC;
    ok_ = true;
    return tempC;
}

void NtcThermistorSensor::setCalibration(float r0Ohms, float betaK) {
    if (r0Ohms > 0.0f && !std::isnan(r0Ohms)) {
        cal_.r0Ohms = r0Ohms;
    }
    if (betaK > 0.0f && !std::isnan(betaK)) {
        cal_.betaK = betaK;
    }
}

bool NtcThermistorSensor::ok() const {
    return ok_;
}

}  // namespace esp32esso::hal
