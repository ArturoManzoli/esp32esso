#include "control/pid.h"

namespace esp32esso::control {

namespace {
inline float clamp(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}
}  // namespace

void Pid::setGains(float kp, float ki, float kd) {
    kp_ = kp;
    ki_ = ki;
    kd_ = kd;
}

void Pid::setOutputLimits(float minOut, float maxOut) {
    outMin_ = minOut;
    outMax_ = maxOut;
    integ_ = clamp(integ_, outMin_, outMax_);
}

void Pid::reset() {
    integ_ = 0.0f;
    hasLastMeas_ = false;
}

float Pid::update(float setpoint, float measurement, float dtSec) {
    const float error = setpoint - measurement;
    if (dtSec <= 0.0f) {
        return clamp(kp_ * error + integ_, outMin_, outMax_);
    }

    // Derivative on measurement (sign flipped) avoids the derivative kick
    // that derivative-on-error suffers when the setpoint steps.
    float deriv = 0.0f;
    if (hasLastMeas_) {
        deriv = -kd_ * (measurement - lastMeas_) / dtSec;
    }
    lastMeas_ = measurement;
    hasLastMeas_ = true;

    // Conditional-integration anti-windup: tentatively integrate, but reject the
    // update whenever it would only push an already-saturated output further past
    // its limit. This keeps the integral near the steady-state hold duty instead
    // of parking at the clamp during a long approach from far below the setpoint
    // (which then bleeds off slowly and overshoots). When frozen the term is held
    // outright so standby does not accumulate a stale value.
    if (integEnabled_) {
        const float candidate = integ_ + ki_ * error * dtSec;
        const float out = kp_ * error + candidate + deriv;
        const bool pushingPastMax = out > outMax_ && error > 0.0f;
        const bool pushingPastMin = out < outMin_ && error < 0.0f;
        if (!pushingPastMax && !pushingPastMin) {
            integ_ = clamp(candidate, outMin_, outMax_);
        }
    }

    return clamp(kp_ * error + integ_ + deriv, outMin_, outMax_);
}

}  // namespace esp32esso::control
