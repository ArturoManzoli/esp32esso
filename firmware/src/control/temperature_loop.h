#pragma once

#include <cmath>
#include <cstdint>

#include "control/pid.h"
#include "profile/machine_profile.h"

namespace esp32esso::control {

// Tier 1/2 closed-loop temperature controller.
//
// Tier 1: holds a single setpoint against the thermoblock sensor, runs the
// PID, and drives the heater relay through slow-PWM.
//
// Tier 2 offset: when a group/portafilter sensor is bound, the user setpoint
// targets the *group* temperature and the inner PID chases:
//
//   thermoblockSetpoint = groupSetpoint + thermoblockOffsetC
//
// Positive offsets over-drive the thermoblock to compensate transport loss
// down to the puck (right side of the phone slider, labelled "thermoblock").
// Negative offsets pull the thermoblock cooler than the cup target ("grouphead"
// side of the slider). The offset is clamped to
// [-maxThermoblockOffsetC, +maxThermoblockOffsetC] and the hard safety cap
// still applies. A group-sensor fault degrades gracefully to Tier 1 behaviour
// (control the thermoblock at the setpoint directly) rather than latching,
// since the thermoblock sensor still guards safety.
//
// Safety contract (must hold at every tick):
//   - the heater is OFF whenever the thermoblock sensor is in a fault state
//   - the heater is OFF whenever the thermoblock temperature is at or above
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

    // Master heater enable. When disabled the SSR is forced off regardless of
    // the PID/bench output, so the phone can park the machine in a cold standby
    // without dropping the BLE link or the setpoint. Defaults to enabled.
    void setHeaterEnabled(bool on);
    bool heaterEnabled() const { return heaterEnabled_; }

    // Inactivity safety cut-off. If no user activity (setpoint/gain/settings
    // write, brew, or heater re-enable) happens for this many minutes, the
    // heater is auto-disabled so a forgotten machine cannot idle hot forever.
    // Brewing continuously refreshes the timer. 0 disables the feature.
    void setHeaterTimeoutMinutes(float minutes);
    float heaterTimeoutMinutes() const { return heaterTimeoutMin_; }

    // Tier 2 thermoblock offset --------------------------------------------
    void setThermoblockOffsetC(float offsetC);
    float thermoblockOffsetC() const { return thermoblockOffsetC_; }
    float groupTempCelsius() const { return groupTempC_; }
    bool groupSensorOk() const { return groupOk_; }
    float thermoblockSetpointCelsius() const { return thermoblockSetpoint_; }
    bool hasGroupSensor() const;

    // Manual thermoblock setpoint override. When set, the inner PID chases this
    // absolute thermoblock temperature directly, ignoring the group-setpoint +
    // offset relationship entirely (so tapping a thermoblock temperature in the
    // app holds that exact value). Passing a non-positive value (or NaN) clears
    // the override and returns to the group + offset behaviour; writing the
    // offset also clears it. Clamped to [minBrew, maxSafe-5].
    void setManualThermoblockSetpointC(float c);
    void clearManualThermoblockSetpoint();
    bool thermoblockManualActive() const { return manualThermoblockActive_; }
    float manualThermoblockSetpointC() const { return manualThermoblockSetpoint_; }

    // Runtime-tunable settings (edited from the phone, persisted by the BLE
    // layer). setPidGains re-seeds the inner PID immediately.
    void setPidGains(float kp, float ki, float kd);
    float pidKp() const { return kp_; }
    float pidKi() const { return ki_; }
    float pidKd() const { return kd_; }
    void setSteamSetpointCelsius(float c);
    float steamSetpointCelsius() const { return steamSetpoint_; }

    // Shot timer: brewing is true while a manual shot is active over BLE.
    // brewSource: 0 idle, 1 manual (app).
    void setManualBrew(bool on);
    bool brewing() const { return brewing_; }
    uint8_t brewSource() const { return brewSource_; }
    uint32_t shotElapsedMs(uint32_t nowMs) const;

    // Grouphead flush: pulses the pump for `kFlushDurationMs` (see .cpp) with
    // the relief valve held closed so water actually flows through the shower
    // screen instead of dumping to the drip tray. Independent of `brewing_`,
    // does not run the shot timer, does not honour pre-infusion. Calling while
    // already flushing restarts the countdown; calling during a shot is a
    // no-op (the shot owns the pump).
    void startFlush(uint32_t nowMs);
    void stopFlush();
    bool flushing() const { return flushing_; }
    uint32_t flushRemainingMs(uint32_t nowMs) const;

    // Pre-infusion: when a shot starts with a non-zero pre-infusion time, the
    // pump runs for that many seconds, then pauses for the same time (letting
    // the puck bloom), then the main brew begins. The relief valve stays closed
    // for the whole sequence (brewing() is true throughout), so the pause does
    // not vent the puck. 0 disables pre-infusion. phase: 0 none/brewing, 1
    // pre-infuse (pump on), 2 bloom pause (pump off).
    void setPreinfusionSeconds(float seconds);
    float preinfusionSeconds() const { return preinfusionSec_; }
    uint8_t preinfusionPhase() const { return preinfusionPhase_; }

    // Relief/3-way vent valve. Held open (vent to drip tray) whenever a shot is
    // NOT running, and closed at the instant a shot starts so the pump can build
    // pressure; it reopens when the shot ends and stays open through idle. The
    // de-energised state (boot/reset) is closed, matching an NC valve's
    // fail-safe. Only driven when the profile binds a solenoidValve output.
    bool reliefValveOpen() const { return reliefValveOpen_; }

    // Brew-line pressure (bar), spike- and ripple-filtered. NaN when no
    // transducer is bound or the reading is out of range.
    float pressureBar() const { return pressureBar_; }
    bool pressureOk() const { return pressureOk_; }

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

    // NTC calibration bench (target-temperature soak). The thermocouple (group
    // sensor) is the trusted reference; the NTC is only logged. The bench walks
    // a ladder of temperature targets (from the first rung above the current
    // temperature, stepping by `stepC` up to `endC`). For each target a
    // proportional law drives the heater toward it, then the rung is held until
    // the thermocouple stops changing (stays within a band for a dwell), and a
    // clean settled (T_tc, R_ntc) sample is latched before stepping up. Aiming
    // at temperatures (rather than fixed duties) spreads the settled points
    // evenly across the operating band, which a constant-duty ladder does not.
    // Settled points are the only ones both probes agree on, so the fit is
    // hysteresis-free. Duty is clamped to `maxDuty` (and an internal cap). A
    // hard cut trips the heater if the thermocouple reaches 200 C, independent
    // of the (unreliable) NTC. Requires a group sensor that reads OK at start.
    void startCalibration(uint32_t nowMs, float endC, float stepC, float maxDuty);
    void stopCalibration();
    bool calibrating() const { return cal_.active; }
    float calSetpointCelsius() const { return cal_.setpointC; }
    uint8_t calPhase() const { return cal_.phase; }  // 0 idle, 1 settling, 2 done
    uint8_t calStep() const { return cal_.curStep; }
    float calTargetCelsius() const { return cal_.targetC; }

    // Settled-sample latch: set true once each time a rung settles. Drained by
    // takeCalSample() (returns true once, then clears). The latch survives
    // stopCalibration() so the final rung's point is not lost when the ladder
    // completes. calSampleDuty/Step describe the latched rung.
    bool takeCalSample();
    float calSampleDuty() const { return calSampleDuty_; }
    uint8_t calSampleStep() const { return calSampleStep_; }

private:
    void runPidIfDue(uint32_t nowMs);
    void runTuningStep(uint32_t nowMs);
    void runCalibrationStep(uint32_t nowMs);
    void updateSlowPwm(uint32_t nowMs);
    void updateBrewState(uint32_t nowMs);
    void updatePreinfusion(uint32_t nowMs);
    void updateFlush(uint32_t nowMs);
    void updateBrewRelay();
    void updateReliefValve();
    void updatePressure();
    void updateHeaterTimeout(uint32_t nowMs);
    float computeThermoblockSetpoint() const;
    void latchFault(const char* reason);

    const profile::MachineProfile* profile_ = nullptr;
    Pid pid_;

    float setpoint_ = 0.0f;  // group target (Tier 2) or thermoblock target (Tier 1)
    float lastTempC_ = 0.0f;  // thermoblock reading (inner PV, safety reference)
    float lastOutput_ = 0.0f;
    bool faulted_ = false;
    const char* faultReason_ = "";

    // Tier 2 thermoblock offset state
    float thermoblockOffsetC_ = 0.0f;
    float groupTempC_ = NAN;
    bool groupOk_ = false;
    float thermoblockSetpoint_ = 0.0f;

    // Manual thermoblock setpoint override (see setManualThermoblockSetpointC).
    bool manualThermoblockActive_ = false;
    float manualThermoblockSetpoint_ = NAN;

    // Runtime-tunable settings
    float kp_ = 0.0f;
    float ki_ = 0.0f;
    float kd_ = 0.0f;
    float steamSetpoint_ = 0.0f;

    // Master heater enable (see setHeaterEnabled)
    bool heaterEnabled_ = true;

    // Inactivity safety cut-off (see setHeaterTimeoutMinutes). activityPending_
    // is set by the setters (which have no clock) and drained on the next tick.
    float heaterTimeoutMin_ = 10.0f;
    uint32_t lastActivityMs_ = 0;
    bool activityPending_ = false;

    // Shot timer
    bool brewing_ = false;
    bool manualBrew_ = false;
    uint8_t brewSource_ = 0;
    uint32_t shotStartMs_ = 0;

    // Pre-infusion (see setPreinfusionSeconds)
    float preinfusionSec_ = 2.0f;
    uint8_t preinfusionPhase_ = 0;  // 0 none/brewing, 1 pre-infuse, 2 bloom pause
    uint32_t preinfusionPhaseStartMs_ = 0;

    // Grouphead flush window (see startFlush)
    bool flushing_ = false;
    uint32_t flushUntilMs_ = 0;

    // Relief/3-way vent valve state (see reliefValveOpen())
    bool reliefValveOpen_ = false;

    // Brew-line pressure, filtered (see pressureBar())
    float pressureBar_ = NAN;
    bool pressureOk_ = false;

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

    struct CalibrationState {
        bool active = false;
        float endC = 0.0f;       // last rung target
        float stepC = 0.0f;      // target increment between rungs
        float targetC = 0.0f;    // current rung target
        float maxDuty = 0.0f;
        uint8_t curStep = 0;     // rung index (for sample labelling)
        uint32_t stepStartMs = 0;
        uint32_t stableSinceMs = 0;  // when the settle-band anchor was last set
        float lastTc = NAN;          // settle-band anchor temperature
        float setpointC = 0.0f;      // informational: current target
        uint8_t phase = 0;       // 0 idle, 1 settling, 2 done
    } cal_;

    // Settled-sample latch (see takeCalSample). Kept outside cal_ so it is not
    // wiped when the ladder finishes and stopCalibration() resets cal_.
    bool calSampleLatched_ = false;
    float calSampleDuty_ = 0.0f;
    uint8_t calSampleStep_ = 0;
};

}  // namespace esp32esso::control
