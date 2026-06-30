#include "control/temperature_loop.h"

#include <cmath>

namespace esp32esso::control {

namespace {
constexpr uint32_t kPidPeriodMs = 200;  // 5 Hz; thermocouple settles slowly
}  // namespace

void TemperatureLoop::begin(const profile::MachineProfile& profile) {
    profile_ = &profile;
    setpoint_ = profile.thermal.defaultBrewTempC;
    pid_.setGains(profile.thermal.pidKp, profile.thermal.pidKi, profile.thermal.pidKd);
    pid_.setOutputLimits(0.0f, 1.0f);
    pid_.reset();
    lastOutput_ = 0.0f;
    lastTempC_ = NAN;
    faulted_ = false;
    faultReason_ = "";
    lastPidMs_ = 0;
    windowStartMs_ = 0;
    windowOnTimeMs_ = 0;
    if (profile.heaterRelay) {
        profile.heaterRelay->set(false);
    }
}

void TemperatureLoop::tick(uint32_t nowMs) {
    if (profile_ == nullptr || profile_->heaterRelay == nullptr ||
        profile_->brewTempSensor == nullptr) {
        return;
    }
    runPidIfDue(nowMs);
    updateSlowPwm(nowMs);
}

void TemperatureLoop::setSetpointCelsius(float c) {
    if (profile_ == nullptr) {
        return;
    }
    const float lo = profile_->thermal.minBrewTempC;
    const float hi = profile_->thermal.maxSafeTempC - 5.0f;  // never aim above safety
    if (c < lo) c = lo;
    if (c > hi) c = hi;
    setpoint_ = c;
}

bool TemperatureLoop::heaterOn() const {
    return profile_ && profile_->heaterRelay && profile_->heaterRelay->isOn();
}

void TemperatureLoop::clearFault() {
    faulted_ = false;
    faultReason_ = "";
    pid_.reset();
}

void TemperatureLoop::latchFault(const char* reason) {
    faulted_ = true;
    faultReason_ = reason;
    lastOutput_ = 0.0f;
    windowOnTimeMs_ = 0;
    if (profile_ && profile_->heaterRelay) {
        profile_->heaterRelay->set(false);
    }
}

void TemperatureLoop::runPidIfDue(uint32_t nowMs) {
    if (lastPidMs_ != 0 && (nowMs - lastPidMs_) < kPidPeriodMs) {
        return;
    }
    const float dtSec = lastPidMs_ == 0 ? 0.0f : (nowMs - lastPidMs_) / 1000.0f;
    lastPidMs_ = nowMs;

    const float temp = profile_->brewTempSensor->readCelsius();
    lastTempC_ = temp;

    if (!profile_->brewTempSensor->ok() || std::isnan(temp)) {
        latchFault("sensor-fault");
        return;
    }
    if (temp >= profile_->thermal.maxSafeTempC) {
        latchFault("overtemp");
        return;
    }
    if (faulted_) {
        return;  // latched until operator clears
    }
    lastOutput_ = pid_.update(setpoint_, temp, dtSec);
}

void TemperatureLoop::updateSlowPwm(uint32_t nowMs) {
    const uint32_t window = profile_->thermal.pwmWindowMs;
    if (window == 0) {
        return;
    }
    if (windowStartMs_ == 0 || (nowMs - windowStartMs_) >= window) {
        windowStartMs_ = nowMs;
        if (faulted_) {
            windowOnTimeMs_ = 0;
        } else {
            float frac = lastOutput_;
            if (frac < 0.0f) frac = 0.0f;
            if (frac > 1.0f) frac = 1.0f;
            windowOnTimeMs_ = static_cast<uint32_t>(frac * window);
        }
    }
    const bool shouldBeOn = !faulted_ && (nowMs - windowStartMs_) < windowOnTimeMs_;
    profile_->heaterRelay->set(shouldBeOn);
}

}  // namespace esp32esso::control
