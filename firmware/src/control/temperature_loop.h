#pragma once

#include <cstdint>

#include "control/pid.h"
#include "profile/machine_profile.h"

namespace esp32esso::control {

// Tier 1 closed-loop temperature controller.
//
// Holds a single setpoint, reads the brew-side temperature sensor, runs the
// PID, and drives the heater relay through slow-PWM with the window length
// configured by the active machine profile.
//
// Safety contract (must hold at every tick):
//   - the heater is OFF whenever the sensor is in a fault state
//   - the heater is OFF whenever the measured temperature is at or above
//     `profile.thermal.maxSafeTempC` (latched until the loop is reset)
//   - the heater is OFF whenever no profile/relay was provided
class TemperatureLoop {
public:
    void begin(const profile::MachineProfile& profile);
    void tick(uint32_t nowMs);

    void setSetpointCelsius(float c);
    float setpointCelsius() const { return setpoint_; }
    float lastTempCelsius() const { return lastTempC_; }
    float lastOutput() const { return lastOutput_; }
    bool faulted() const { return faulted_; }
    const char* faultReason() const { return faultReason_; }
    bool heaterOn() const;

    // Clears the latched fault (e.g. after the operator inspects the
    // sensor wiring). Does not modify the setpoint.
    void clearFault();

    // Open-loop step-response bench. Bypasses the PID and drives the heater
    // at the given duty fraction (0..1) for up to `durationMs`, aborting
    // early on any of: temperature reaches `abortTempC`, sensor fault, the
    // hard cut at `profile.thermal.maxSafeTempC` (which still applies), or
    // an explicit `stopTuning()` call. Refuses to start when the loop is
    // already faulted; `abortTempC` is clamped strictly below the hard cut.
    void startTuning(uint32_t nowMs, float duty, uint32_t durationMs, float abortTempC);
    void stopTuning();
    bool tuningActive() const { return tuning_.active; }
    float tuningDuty() const { return tuning_.duty; }
    uint32_t tuningElapsedMs(uint32_t nowMs) const;
    uint32_t tuningDurationMs() const { return tuning_.durationMs; }
    float tuningAbortTempC() const { return tuning_.abortTempC; }

private:
    void runPidIfDue(uint32_t nowMs);
    void runTuningStep(uint32_t nowMs);
    void updateSlowPwm(uint32_t nowMs);
    void latchFault(const char* reason);

    const profile::MachineProfile* profile_ = nullptr;
    Pid pid_;

    float setpoint_ = 0.0f;
    float lastTempC_ = 0.0f;
    float lastOutput_ = 0.0f;
    bool faulted_ = false;
    const char* faultReason_ = "";

    uint32_t lastPidMs_ = 0;
    uint32_t windowStartMs_ = 0;
    uint32_t windowOnTimeMs_ = 0;

    struct TuningState {
        bool active = false;
        float duty = 0.0f;
        uint32_t durationMs = 0;
        float abortTempC = 0.0f;
        uint32_t startMs = 0;
    } tuning_;
};

}  // namespace esp32esso::control
