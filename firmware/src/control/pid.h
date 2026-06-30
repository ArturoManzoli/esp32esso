#pragma once

namespace esp32esso::control {

// Textbook PID with anti-windup via output clamping and derivative-on-
// measurement to avoid derivative kick when the setpoint moves. Designed
// for slow processes (thermal control), so dt is supplied by the caller in
// seconds rather than read from a hardware timer.
class Pid {
public:
    Pid() = default;

    void setGains(float kp, float ki, float kd);
    void setOutputLimits(float minOut, float maxOut);
    void reset();

    // Compute the new control output given the current setpoint and
    // measurement, and the time elapsed since the previous call.
    float update(float setpoint, float measurement, float dtSec);

private:
    float kp_ = 0.0f;
    float ki_ = 0.0f;
    float kd_ = 0.0f;
    float outMin_ = 0.0f;
    float outMax_ = 1.0f;
    float integ_ = 0.0f;
    float lastMeas_ = 0.0f;
    bool hasLastMeas_ = false;
};

}  // namespace esp32esso::control
