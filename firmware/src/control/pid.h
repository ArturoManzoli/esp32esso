#pragma once

namespace esp32esso::control {

// Textbook PID with conditional-integration anti-windup and derivative-on-
// measurement to avoid derivative kick when the setpoint moves. Designed
// for slow processes (thermal control), so dt is supplied by the caller in
// seconds rather than read from a hardware timer.
class Pid {
public:
    Pid() = default;

    void setGains(float kp, float ki, float kd);
    void setOutputLimits(float minOut, float maxOut);
    void reset();

    // Freeze/unfreeze the integrator without discarding its accumulated value.
    // Frozen, the integral term holds steady (used while the heater is disabled
    // so a stale term is not accumulated during standby and dumped on re-enable).
    void setIntegratorEnabled(bool on) { integEnabled_ = on; }

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
    bool integEnabled_ = true;
};

}  // namespace esp32esso::control
