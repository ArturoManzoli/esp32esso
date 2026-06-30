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
    if (dtSec <= 0.0f) {
        return clamp(kp_ * (setpoint - measurement) + integ_, outMin_, outMax_);
    }
    const float error = setpoint - measurement;

    // Trapezoidal integration with clamp-based anti-windup: the integral
    // term cannot push the output past the saturation limits.
    integ_ += ki_ * error * dtSec;
    integ_ = clamp(integ_, outMin_, outMax_);

    // Derivative on measurement (sign flipped) avoids the derivative kick
    // that derivative-on-error suffers when the setpoint steps.
    float deriv = 0.0f;
    if (hasLastMeas_) {
        deriv = -kd_ * (measurement - lastMeas_) / dtSec;
    }
    lastMeas_ = measurement;
    hasLastMeas_ = true;

    return clamp(kp_ * error + integ_ + deriv, outMin_, outMax_);
}

}  // namespace esp32esso::control
