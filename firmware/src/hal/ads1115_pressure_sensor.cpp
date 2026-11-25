#include "hal/ads1115_pressure_sensor.h"

#include <Arduino.h>
#include <Wire.h>

#include <algorithm>
#include <cmath>

namespace esp32esso::hal {

namespace {
constexpr uint8_t kRegConversion = 0x00;
constexpr uint8_t kRegConfig = 0x01;
// AIN0 single-ended, +/-4.096 V full scale, continuous conversion, 860 SPS,
// comparator disabled.
constexpr uint16_t kConfig = 0x42E3;
constexpr float kMillivoltsPerCount = 4096.0f / 32768.0f;
}  // namespace

Ads1115PressureSensor::Ads1115PressureSensor(uint8_t sdaPin, uint8_t sclPin, uint8_t address,
                                             const PressureCalibration& cal)
    : sdaPin_(sdaPin), sclPin_(sclPin), address_(address), cal_(cal) {}

void Ads1115PressureSensor::begin() {
    Wire.begin(sdaPin_, sclPin_, 400000);
    configure();
}

bool Ads1115PressureSensor::configure() {
    Wire.beginTransmission(address_);
    Wire.write(kRegConfig);
    Wire.write(static_cast<uint8_t>(kConfig >> 8));
    Wire.write(static_cast<uint8_t>(kConfig & 0xFF));
    configured_ = Wire.endTransmission() == 0;
    return configured_;
}

float Ads1115PressureSensor::readBar() {
    // A module that was missing or browned out at boot comes back unconfigured
    // (single-shot, idle), so keep trying until it acknowledges the config.
    if (!configured_ && !configure()) {
        ok_ = false;
        return NAN;
    }
    Wire.beginTransmission(address_);
    Wire.write(kRegConversion);
    if (Wire.endTransmission(false) != 0 ||
        Wire.requestFrom(address_, static_cast<uint8_t>(2)) != 2) {
        configured_ = false;
        windowIndex_ = 0;
        windowCount_ = 0;
        ok_ = false;
        return NAN;
    }
    const uint8_t hi = Wire.read();
    const uint8_t lo = Wire.read();
    const int16_t raw = static_cast<int16_t>((hi << 8) | lo);
    window_[windowIndex_] = static_cast<float>(raw) * kMillivoltsPerCount;
    windowIndex_ = (windowIndex_ + 1) % kMedianWindow;
    if (windowCount_ < kMedianWindow) ++windowCount_;

    float sorted[kMedianWindow];
    std::copy(window_, window_ + windowCount_, sorted);
    std::sort(sorted, sorted + windowCount_);
    const float pinMv = sorted[windowCount_ / 2];
    lastMillivolts_ = pinMv;
    return pressureBarFromPinMillivolts(cal_, pinMv, ok_);
}

bool Ads1115PressureSensor::ok() const {
    return ok_;
}

}  // namespace esp32esso::hal
