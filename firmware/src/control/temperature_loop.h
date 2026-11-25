#pragma once

#include <cmath>
#include <cstddef>
#include <cstdint>

#include "control/pid.h"
#include "profile/machine_profile.h"

namespace esp32esso::control {

// Tier 1/2 closed-loop temperature controller.
//
// Tier 1: holds a single setpoint against the thermoblock sensor, runs the
// PID, and drives the heater relay through slow-PWM.
//
// Tier 2: when a group/portafilter sensor is bound and the operator has fixed a
// group target (no absolute thermoblock override), the PID closes *directly on
// the group temperature* -- the user setpoint is the cup target and the heater
// is driven so the group probe reaches it, letting the thermoblock settle at
// whatever temperature that takes. The block sensor is kept purely as the
// safety reference (fault latch + self-clearing overtemp trip) plus a soft
// ceiling that bounds how hot the block may run (groupSetpoint +
// maxThermoblockOffsetC, below the hard cut) so the slow group probe cannot
// cook the block toward the safety cut.
//
// The thermoblockOffsetC still derives the reported thermoblock setpoint
// (groupSetpoint + offset) and, as above, sets the block ceiling. It is clamped
// to [-maxThermoblockOffsetC, +maxThermoblockOffsetC]. Positive offsets are the
// "thermoblock" side of the phone slider, negative the "grouphead" side.
//
// When the operator pins an absolute thermoblock temperature, or the group
// sensor faults / is absent, the loop falls back to controlling the thermoblock
// against its derived setpoint directly (Tier 1 behaviour), since the block
// sensor still guards safety.
//
// Safety contract (must hold at every tick):
//   - the heater is OFF whenever the thermoblock sensor is in a fault state
//     (latched until the operator calls clearFault())
//   - the heater is OFF whenever the thermoblock temperature is at or above
//     `profile.thermal.maxSafeTempC`; this trip clears itself automatically
//     once the thermoblock cools back to kOvertempRecoveryC (see .cpp) --
//     no operator action required
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

    // Steam mode, driven by the profile's panel steam input: while it is on the
    // heater holds the boiler/block at the steam setpoint (group control is
    // suspended and shots are refused), and when it is switched off, or after
    // kSteamMaxMs, temperature returns to the brew target. A timed-out session
    // stays off until the input is cycled.
    bool steaming() const { return steaming_; }

    // Shot timer: brewing is true while a shot is active. brewSource: 0 idle,
    // 1 manual (app), 2 panel brew input (see updateBrewSwitch).
    //
    // `useProfile` picks what shapes the pump for this shot: true runs the
    // loaded brew profile, false runs the plain pre-infusion + soft-start ramp
    // even when a profile is loaded, so the app can offer a manual pull and a
    // profile pull as separate buttons without rewriting the stored profile.
    // Latched at shot start and ignored when stopping.
    void setManualBrew(bool on, bool useProfile = true);
    bool brewing() const { return brewing_; }
    uint8_t brewSource() const { return brewSource_; }
    uint32_t shotElapsedMs(uint32_t nowMs) const;

    // Grouphead flush: pulses the pump (and opens the brew valve) for
    // `kFlushDurationMs` (see .cpp) with the relief valve held closed so water
    // actually flows through the shower screen instead of dumping to the drip
    // tray. Independent of `brewing_`, does not run the shot timer, does not
    // honour pre-infusion. Calling while already flushing restarts the
    // countdown; calling during a shot is a no-op (the shot owns the pump).
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

    // Pump pressure-ramp (soft start): time-proportions the pump SSR (driven
    // independently of the brew valve, see `pumpRelay`/`brewValveRelay` on
    // MachineProfile) so a vibration pump builds pressure gradually over the
    // first `seconds` of a shot instead of slamming to full flow, reducing
    // puck channeling. Duty rises from a low floor to 100 % across the ramp;
    // 0 disables it (the pump runs at full flow from the start). Never
    // applies to a grouphead flush.
    void setPumpRampSeconds(float seconds);
    float pumpRampSeconds() const { return pumpRampSec_; }

    // Brew pressure/flow profile ---------------------------------------------
    // A profile is a sequence of phases (transcribed in the app from the
    // gaggimate/gaggiuino projects) that shape the pump over a shot. When one is
    // active it replaces the pre-infusion + pump-ramp behaviour: during a shot
    // the phases run in order, each ramping its target (bar for a PRESSURE
    // phase, ml/s for a FLOW phase) over its transition then holding it, while
    // the pump is duty-cycled to chase it -- pressure phases close a coarse loop
    // on the transducer, flow phases run open-loop (no flow sensor) with the
    // phase's pressure limit as a soft cap. A phase may also carry a thermoblock
    // temperature ramp (wire v2), in which case the profile pins the block for
    // the duration of the shot exactly as a manual block setpoint would, and
    // temperature control returns to group + offset when the shot ends.
    // `data`/`len` is the BLE blob (see tier1_gatt.h); an empty / zero-phase
    // blob clears the profile.
    void setBrewProfile(const uint8_t* data, size_t len);
    void clearBrewProfile();
    bool brewProfileActive() const { return profileActive_; }
    uint8_t brewProfilePhaseCount() const { return profilePhaseCount_; }
    uint8_t brewProfilePhaseIndex() const { return profilePhaseIndex_; }

    // Position along the profile's own timeline, in seconds: the durations of
    // the finished phases plus the time spent in the current one. It is not the
    // shot clock, because a phase that hits its stopPressureAbove ends early and
    // jumps the position to the next phase's start. NaN whenever a profile is
    // not currently driving the pump.
    float brewProfileElapsedSec(uint32_t nowMs) const;

    // Relief/3-way vent valve. Default CLOSED (de-energised): it energises open
    // only for a brief vent pulse (kPostShotVentMs) right after a shot ends, to
    // relieve puck/line pressure. It stays closed at idle, while the block is
    // hot, during a shot or flush, and during future active operations (steam,
    // hot-water) -- so the solenoid is not held energised humming at idle. The
    // de-energised state (boot/reset) is closed, matching an NC valve's
    // fail-safe. Only driven when the profile binds a solenoidValve output.
    bool reliefValveOpen() const { return reliefValveOpen_; }

    // Brew-line pressure (bar), spike- and ripple-filtered. NaN when no
    // transducer is bound or the reading is out of range.
    float pressureBar() const { return pressureBar_; }
    bool pressureOk() const { return pressureOk_; }

    // Drip-tray scale, per cell and summed. NaN when no scale is bound or no
    // sample has landed yet. The total is what holds regardless of where the cup
    // sits; the per-cell figures are what make a bad corner or a dead cell
    // visible instead of silently halving the reading.
    float weightLeftGrams() const { return weightLeftG_; }
    float weightRightGrams() const { return weightRightG_; }
    float weightTotalGrams() const { return weightLeftG_ + weightRightG_; }

    // Zeroes the tray. Returns false when there is no scale or no sample.
    bool tareScale();

    // Stop-on-weight. A positive target ends the shot as soon as the tray total
    // reaches it; 0 (or NaN) disables it. The comparison is against the absolute
    // total, the same figure the app displays, so the tray is expected to be
    // tared with the empty cup already on it.
    void setTargetWeightGrams(float grams);
    float targetWeightGrams() const { return targetWeightG_; }

    // Manual brew-pressure hold. When set, a shot chases this pressure for its
    // whole duration instead of running the selected brew profile (the same
    // relationship the manual thermoblock setpoint has with group + offset), so
    // the app can pin a flat pressure without editing profiles. Duty comes from
    // the measured pump curve plus the same closed-loop trim the profile engine
    // uses. Passing a non-positive value (or NaN) clears the hold. Never applies
    // to a grouphead flush, which always runs at full flow.
    void setManualPressureBar(float bar);
    void clearManualPressure();
    bool pressureManualActive() const { return manualPressureActive_; }
    float manualPressureBar() const { return manualPressureBar_; }

    // Open-loop pump bench. Holds the pump at a fixed duty (0..1) with the brew
    // valve open, so the phase dimmer's conduction angle can be characterised
    // against the pressure transducer. Refuses while a shot or flush owns the
    // pump, and either of those starting later reclaims it. Always expires after
    // `durationMs` (capped) so a lost host cannot leave the pump running dry.
    void startPumpBench(uint32_t nowMs, float duty, uint32_t durationMs);
    void stopPumpBench();
    bool pumpBenchActive() const { return pumpBench_.active; }
    float pumpBenchDuty() const { return pumpBench_.duty; }

    // Clears a latched fault (e.g. after the operator inspects the sensor
    // wiring). Does not modify the setpoint. Note: an overtemp trip clears
    // itself automatically once the block cools down, so this is normally
    // only needed for a sensor-fault latch.
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
    void updateBrewSwitch(uint32_t nowMs);
    void updateSteamSwitch(uint32_t nowMs);
    void updateBrewState(uint32_t nowMs);
    void updatePreinfusion(uint32_t nowMs);
    void updateFlush(uint32_t nowMs);
    void updatePumpAndBrewValve(uint32_t nowMs);
    bool updateSteamRefill(uint32_t nowMs);
    void updateProfileExecution(uint32_t nowMs);
    void releaseProfileTemperature();
    void applyPumpDuty(uint32_t nowMs, float duty);
    float pressureHoldDuty(float targetBar) const;
    void updateReliefValve(uint32_t nowMs);
    void updatePressure();
    void updateScale();
    void trackScaleZero(float meanLeftG, float meanRightG);
    void updateHeaterTimeout(uint32_t nowMs);
    float computeThermoblockSetpoint() const;
    void latchFault(const char* reason);
    void tripOvertemp();

    const profile::MachineProfile* profile_ = nullptr;
    Pid pid_;

    float setpoint_ = 0.0f;  // group target (Tier 2) or thermoblock target (Tier 1)
    float lastTempC_ = 0.0f;  // thermoblock reading (inner PV, safety reference)
    float lastOutput_ = 0.0f;
    bool faulted_ = false;
    const char* faultReason_ = "";
    // True while the current latch is an overtemp trip (as opposed to e.g. a
    // sensor fault), so runPidIfDue() knows it may clear itself once the
    // thermoblock cools back down instead of waiting for clearFault().
    bool overtempAutoRecover_ = false;

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
    bool brewUseProfile_ = true;  // latched at shot start (see setManualBrew)
    uint8_t brewSource_ = 0;
    uint32_t shotStartMs_ = 0;

    // Panel brew input (see updateBrewSwitch). The first read only records the
    // level, so a rocker left on does not start a shot at power-up.
    bool brewSwitchPrimed_ = false;
    bool brewSwitchOn_ = false;
    uint32_t brewSwitchSinceMs_ = 0;
    bool panelShot_ = false;  // the running shot was started from the panel

    // Steam mode (see steaming())
    bool steamSwitchOn_ = false;
    bool steaming_ = false;
    uint32_t steamSinceMs_ = 0;

    // Steam refill (profile.hydraulic.steamRefillBelowBar, off by default; see
    // updateSteamRefill). 0 deadlines mean none pending.
    uint32_t refillBurstUntilMs_ = 0;
    uint32_t refillGapUntilMs_ = 0;
    uint32_t refillTotalMs_ = 0;

    // Pre-infusion (see setPreinfusionSeconds)
    float preinfusionSec_ = 2.0f;
    uint8_t preinfusionPhase_ = 0;  // 0 none/brewing, 1 pre-infuse, 2 bloom pause

    // Pump pressure-ramp / soft start (see setPumpRampSeconds). 0 disables it.
    // The window fields drive the time-proportional (slow-PWM) pump SSR while
    // the ramp duty is below 100 %.
    float pumpRampSec_ = 0.0f;
    uint32_t pumpWindowStartMs_ = 0;
    uint32_t pumpWindowOnMs_ = 0;
    uint32_t preinfusionPhaseStartMs_ = 0;

    // Brew profile (see setBrewProfile). A shot with profileActive_ runs the
    // phase program instead of the pre-infusion/pump-ramp path.
    static constexpr uint8_t kMaxProfilePhases = 12;
    struct ProfilePhaseRt {
        uint8_t mode = 0;      // 0 pressure (bar), 1 flow (ml/s)
        uint8_t curve = 0;     // 0 instant, 1 linear, 2 ease
        bool valveOpen = true;
        float targetStart = 0.0f;
        float targetEnd = 0.0f;
        float transitionSec = 0.0f;
        float durationSec = 0.0f;
        float restriction = 0.0f;       // limit on the other variable (0 = none)
        float stopPressureAbove = 0.0f;  // early-exit pressure (0 = time only)
        // Thermoblock temperature ramped across the phase (wire v2). Both 0
        // means the phase does not touch temperature control.
        float blockTempStartC = 0.0f;
        float blockTempEndC = 0.0f;
    };
    ProfilePhaseRt profilePhases_[kMaxProfilePhases];
    uint8_t profilePhaseCount_ = 0;
    bool profileActive_ = false;
    float profileTempC_ = 0.0f;         // profile's recommended brew temp (informational)
    uint8_t profilePhaseIndex_ = 0;     // phase currently executing during a shot
    uint32_t profilePhaseStartMs_ = 0;  // start of the current phase
    // True while the running profile is the one commanding the thermoblock
    // setpoint, so the end of the shot knows to hand temperature control back
    // to the normal group + offset regime (see releaseProfileTemperature).
    // profileTempLastC_ is the last value pushed through the override, so a
    // ramp only re-applies on a real change instead of every 10 ms tick.
    bool profileTempApplied_ = false;
    float profileTempLastC_ = NAN;

    // Grouphead flush window (see startFlush)
    bool flushing_ = false;
    uint32_t flushUntilMs_ = 0;

    // Relief/3-way vent valve state (see reliefValveOpen()). reliefVentUntilMs_
    // is the wrap-safe deadline of the post-shot vent pulse; 0 means closed.
    bool reliefValveOpen_ = false;
    uint32_t reliefVentUntilMs_ = 0;

    // Brew-line pressure, filtered (see pressureBar())
    float pressureBar_ = NAN;
    bool pressureOk_ = false;

    // Drip-tray scale, per cell (see weightLeftGrams())
    float weightLeftG_ = NAN;
    float weightRightG_ = NAN;
    float targetWeightG_ = 0.0f;  // 0 = stop-on-weight disabled

    // Rolling mean over the last samples of each cell, and the software zero the
    // drift tracker walks (see trackScaleZero). Both are reset by a tare.
    static constexpr uint8_t kWeightFilterSamples = 8;
    float weightSamplesLeftG_[kWeightFilterSamples] = {};
    float weightSamplesRightG_[kWeightFilterSamples] = {};
    uint8_t weightSampleIndex_ = 0;
    uint8_t weightSampleCount_ = 0;
    float zeroLeftG_ = 0.0f;
    float zeroRightG_ = 0.0f;

    // Manual brew-pressure hold (see setManualPressureBar)
    bool manualPressureActive_ = false;
    float manualPressureBar_ = NAN;

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

    // Open-loop pump bench (see startPumpBench)
    struct PumpBenchState {
        bool active = false;
        float duty = 0.0f;
        uint32_t durationMs = 0;
        uint32_t startMs = 0;
    } pumpBench_;

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
