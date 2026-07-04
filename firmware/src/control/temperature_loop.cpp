#include "control/temperature_loop.h"

#include <cmath>

namespace esp32esso::control {

namespace {
constexpr uint32_t kPidPeriodMs = 200;  // 5 Hz; thermocouple settles slowly
constexpr float kMaxBrewSetpointC = 110.0f;  // brew target never needs to exceed this
constexpr uint32_t kReliefPulseMs = 1000;  // hold the relief valve open this long after a shot

// NTC calibration bench tunables. The reference is the thermocouple (group
// sensor); the NTC is only logged. The bench holds constant duty rungs and
// only samples once each rung has thermally settled, so both probes read the
// same real temperature. A dedicated hard cut on the thermocouple guards the
// run independently of the NTC being characterised.
constexpr float kCalTcHardCutC = 200.0f;         // thermocouple hard cut during cal
constexpr float kCalMaxDutyCap = 0.80f;          // never exceed this even if asked
constexpr float kCalKpDutyPerC = 0.03f;          // proportional drive toward the target
constexpr float kCalFirstTargetC = 50.0f;        // lowest rung target
constexpr uint32_t kCalDwellMs = 8UL * 1000UL;   // hold within band this long to settle
// "Settled" when the thermocouple stays within this band of an anchor for the
// whole dwell. Measured over the dwell (not tick-to-tick) so the MAX6675's
// 0.25 C quantization does not keep resetting the timer. 0.6 C ~= within 0.06
// C/s over an 8 s dwell, which flattens the ramp-lag hysteresis.
constexpr float kCalSettleBandC = 0.6f;
constexpr uint32_t kCalStepTimeoutMs = 240UL * 1000UL;  // give up waiting per rung
}  // namespace

void TemperatureLoop::begin(const profile::MachineProfile& profile) {
    profile_ = &profile;
    setpoint_ = profile.thermal.defaultBrewTempC;
    kp_ = profile.thermal.pidKp;
    ki_ = profile.thermal.pidKi;
    kd_ = profile.thermal.pidKd;
    steamSetpoint_ = profile.thermal.steamTempC;
    gain_ = profile.thermal.defaultCascadeGain;
    pid_.setGains(kp_, ki_, kd_);
    pid_.setOutputLimits(0.0f, 1.0f);
    pid_.reset();
    lastOutput_ = 0.0f;
    lastTempC_ = NAN;
    groupTempC_ = NAN;
    groupOk_ = false;
    thermoblockSetpoint_ = setpoint_;
    faulted_ = false;
    faultReason_ = "";
    brewing_ = false;
    manualBrew_ = false;
    brewSource_ = 0;
    shotStartMs_ = 0;
    reliefValveOpen_ = false;
    reliefOpenedMs_ = 0;
    lastPidMs_ = 0;
    windowStartMs_ = 0;
    windowOnTimeMs_ = 0;
    cal_ = CalibrationState{};
    calSampleLatched_ = false;
    if (profile.heaterRelay) {
        profile.heaterRelay->set(false);
    }
    if (profile.solenoidValve) {
        profile.solenoidValve->set(false);
    }
}

void TemperatureLoop::tick(uint32_t nowMs) {
    if (profile_ == nullptr || profile_->heaterRelay == nullptr ||
        profile_->brewTempSensor == nullptr) {
        return;
    }
    updateBrewState(nowMs);
    updateReliefValve(nowMs);
    if (cal_.active) {
        runCalibrationStep(nowMs);
    } else if (tuning_.active) {
        runTuningStep(nowMs);
    } else {
        runPidIfDue(nowMs);
    }
    updateSlowPwm(nowMs);
}

void TemperatureLoop::setSetpointCelsius(float c) {
    if (profile_ == nullptr) {
        return;
    }
    const float lo = profile_->thermal.minBrewTempC;
    float hi = profile_->thermal.maxSafeTempC - 5.0f;  // never aim above safety
    if (hi > kMaxBrewSetpointC) hi = kMaxBrewSetpointC;
    if (c < lo) c = lo;
    if (c > hi) c = hi;
    setpoint_ = c;
}

void TemperatureLoop::setGain(float gain) {
    if (std::isnan(gain)) return;
    if (gain < 0.0f) gain = 0.0f;
    if (gain > 10.0f) gain = 10.0f;
    gain_ = gain;
}

void TemperatureLoop::setPidGains(float kp, float ki, float kd) {
    if (std::isnan(kp) || std::isnan(ki) || std::isnan(kd)) return;
    if (kp < 0.0f) kp = 0.0f;
    if (ki < 0.0f) ki = 0.0f;
    if (kd < 0.0f) kd = 0.0f;
    kp_ = kp;
    ki_ = ki;
    kd_ = kd;
    pid_.setGains(kp_, ki_, kd_);
}

void TemperatureLoop::setSteamSetpointCelsius(float c) {
    if (profile_ == nullptr || std::isnan(c)) {
        return;
    }
    const float hi = profile_->thermal.maxSafeTempC - 5.0f;
    if (c < profile_->thermal.minBrewTempC) c = profile_->thermal.minBrewTempC;
    if (c > hi) c = hi;
    steamSetpoint_ = c;
}

bool TemperatureLoop::hasGroupSensor() const {
    return profile_ != nullptr && profile_->groupTempSensor != nullptr;
}

void TemperatureLoop::setManualBrew(bool on) {
    manualBrew_ = on;
}

uint32_t TemperatureLoop::shotElapsedMs(uint32_t nowMs) const {
    if (!brewing_) return 0;
    return nowMs - shotStartMs_;
}

void TemperatureLoop::updateBrewState(uint32_t nowMs) {
    const bool switchActive =
        profile_->brewSwitch != nullptr && profile_->brewSwitch->read();
    const bool active = manualBrew_ || switchActive;
    if (active && !brewing_) {
        brewing_ = true;
        shotStartMs_ = nowMs;
        brewSource_ = switchActive ? 2 : 1;
        // A new shot supersedes any in-flight relief pulse.
        reliefValveOpen_ = false;
    } else if (!active && brewing_) {
        brewing_ = false;
        brewSource_ = 0;
        // Open the relief valve to dump residual pressure; updateReliefValve
        // closes it again after kReliefPulseMs.
        reliefValveOpen_ = true;
        reliefOpenedMs_ = nowMs;
    }
}

void TemperatureLoop::updateReliefValve(uint32_t nowMs) {
    if (profile_ == nullptr || profile_->solenoidValve == nullptr) {
        return;
    }
    if (reliefValveOpen_ && (nowMs - reliefOpenedMs_) >= kReliefPulseMs) {
        reliefValveOpen_ = false;
    }
    profile_->solenoidValve->set(reliefValveOpen_);
}

float TemperatureLoop::computeThermoblockSetpoint() const {
    const float hardCeil = profile_->thermal.maxSafeTempC - 5.0f;
    float target = setpoint_;
    if (hasGroupSensor() && groupOk_) {
        float comp = gain_ * (setpoint_ - groupTempC_);
        if (comp < 0.0f) comp = 0.0f;  // never drive below the cup target
        if (comp > profile_->thermal.maxCascadeOffsetC) {
            comp = profile_->thermal.maxCascadeOffsetC;
        }
        target = setpoint_ + comp;
    }
    if (target > hardCeil) target = hardCeil;
    return target;
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

    // Sample group sensor first so telemetry stays live even if the thermoblock
    // sensor faults.
    if (hasGroupSensor()) {
        const float g = profile_->groupTempSensor->readCelsius();
        groupOk_ = profile_->groupTempSensor->ok() && !std::isnan(g);
        groupTempC_ = groupOk_ ? g : NAN;
    } else {
        groupOk_ = false;
        groupTempC_ = NAN;
    }

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

    thermoblockSetpoint_ = computeThermoblockSetpoint();
    lastOutput_ = pid_.update(thermoblockSetpoint_, temp, dtSec);
}

void TemperatureLoop::startTuning(uint32_t nowMs,
                                  float duty,
                                  uint32_t durationMs,
                                  float abortTempC) {
    if (profile_ == nullptr || profile_->heaterRelay == nullptr ||
        profile_->brewTempSensor == nullptr) {
        return;
    }
    if (faulted_) {
        return;  // refuse to start while the safety latch is set
    }
    if (duty < 0.0f) duty = 0.0f;
    if (duty > 1.0f) duty = 1.0f;
    const float hardCut = profile_->thermal.maxSafeTempC;
    if (abortTempC >= hardCut) {
        abortTempC = hardCut - 1.0f;
    }
    tuning_.active = true;
    tuning_.duty = duty;
    tuning_.durationMs = durationMs;
    tuning_.abortTempC = abortTempC;
    tuning_.startMs = nowMs;
    lastPidMs_ = 0;
    windowStartMs_ = 0;
    windowOnTimeMs_ = 0;
    lastOutput_ = duty;
}

void TemperatureLoop::stopTuning() {
    tuning_.active = false;
    lastOutput_ = 0.0f;
    windowOnTimeMs_ = 0;
    if (profile_ && profile_->heaterRelay) {
        profile_->heaterRelay->set(false);
    }
    pid_.reset();
}

uint32_t TemperatureLoop::tuningElapsedMs(uint32_t nowMs) const {
    if (!tuning_.active) return 0;
    return nowMs - tuning_.startMs;
}

void TemperatureLoop::runTuningStep(uint32_t nowMs) {
    if (lastPidMs_ != 0 && (nowMs - lastPidMs_) < kPidPeriodMs) {
        return;
    }
    lastPidMs_ = nowMs;

    // Keep the group reading live so telemetry (and the precool watcher) is not
    // stale while parked in open-loop tuning.
    if (hasGroupSensor()) {
        const float g = profile_->groupTempSensor->readCelsius();
        groupOk_ = profile_->groupTempSensor->ok() && !std::isnan(g);
        groupTempC_ = groupOk_ ? g : NAN;
    }

    const float temp = profile_->brewTempSensor->readCelsius();
    lastTempC_ = temp;

    if (!profile_->brewTempSensor->ok() || std::isnan(temp)) {
        latchFault("sensor-fault");
        tuning_.active = false;
        return;
    }
    if (temp >= profile_->thermal.maxSafeTempC) {
        latchFault("overtemp");
        tuning_.active = false;
        return;
    }
    if (temp >= tuning_.abortTempC) {
        stopTuning();
        return;
    }
    if ((nowMs - tuning_.startMs) >= tuning_.durationMs) {
        stopTuning();
        return;
    }
    lastOutput_ = tuning_.duty;
}

void TemperatureLoop::startCalibration(uint32_t nowMs,
                                       float endC,
                                       float stepC,
                                       float maxDuty) {
    if (profile_ == nullptr || profile_->heaterRelay == nullptr) {
        return;
    }
    if (faulted_ || tuning_.active) {
        return;  // one bench mode at a time; clear faults first
    }
    if (profile_->groupTempSensor == nullptr) {
        return;  // no thermocouple reference -> refuse
    }
    const float tc = profile_->groupTempSensor->readCelsius();
    if (!profile_->groupTempSensor->ok() || std::isnan(tc)) {
        return;  // reference must read OK before we heat
    }
    if (tc >= kCalTcHardCutC) {
        return;
    }
    if (maxDuty < 0.0f) maxDuty = 0.0f;
    if (maxDuty > kCalMaxDutyCap) maxDuty = kCalMaxDutyCap;
    if (stepC < 1.0f) stepC = 1.0f;
    if (endC > kCalTcHardCutC - 10.0f) endC = kCalTcHardCutC - 10.0f;

    // First rung is the lowest target above the current temperature.
    float target = kCalFirstTargetC;
    while (target <= tc + 2.0f) target += stepC;
    if (target > endC) target = endC;

    cal_ = CalibrationState{};
    cal_.active = true;
    cal_.endC = endC;
    cal_.stepC = stepC;
    cal_.targetC = target;
    cal_.maxDuty = maxDuty;
    cal_.curStep = 0;
    cal_.stepStartMs = nowMs;
    cal_.stableSinceMs = nowMs;
    cal_.lastTc = tc;  // settle-band anchor
    cal_.setpointC = target;
    cal_.phase = 1;  // settling
    calSampleLatched_ = false;

    groupTempC_ = tc;
    groupOk_ = true;
    thermoblockSetpoint_ = tc;
    lastPidMs_ = 0;
    windowStartMs_ = 0;
    windowOnTimeMs_ = 0;
    lastOutput_ = 0.0f;
}

void TemperatureLoop::stopCalibration() {
    cal_.active = false;
    cal_.phase = 0;
    lastOutput_ = 0.0f;
    windowOnTimeMs_ = 0;
    if (profile_ && profile_->heaterRelay) {
        profile_->heaterRelay->set(false);
    }
    pid_.reset();
}

bool TemperatureLoop::takeCalSample() {
    if (!calSampleLatched_) return false;
    calSampleLatched_ = false;
    return true;
}

void TemperatureLoop::runCalibrationStep(uint32_t nowMs) {
    if (lastPidMs_ != 0 && (nowMs - lastPidMs_) < kPidPeriodMs) {
        return;
    }
    lastPidMs_ = nowMs;

    // The thermocouple is both the control reference and the safety guard.
    const float tc = profile_->groupTempSensor->readCelsius();
    groupOk_ = profile_->groupTempSensor->ok() && !std::isnan(tc);
    groupTempC_ = groupOk_ ? tc : NAN;

    // Log the NTC (may be faulted/NaN); never used for safety here.
    lastTempC_ = profile_->brewTempSensor->readCelsius();

    if (!groupOk_) {
        latchFault("tc-fault");
        cal_.active = false;
        cal_.phase = 0;
        return;
    }
    if (tc >= kCalTcHardCutC) {
        latchFault("tc-overtemp");
        cal_.active = false;
        cal_.phase = 0;
        return;
    }

    // Proportional drive toward the current rung target. As the thermocouple
    // approaches the target the duty tapers, so a low-mass tip settles just
    // below the target rather than overshooting hard.
    float duty = kCalKpDutyPerC * (cal_.targetC - tc);
    if (duty < 0.0f) duty = 0.0f;
    if (duty > cal_.maxDuty) duty = cal_.maxDuty;

    // Settle by band-hold: keep an anchor temperature and the time it was set;
    // whenever the thermocouple drifts more than kCalSettleBandC from it, the
    // anchor (and its timer) resets. Staying within the band for the whole
    // dwell means the rung has reached steady state.
    if (std::isnan(cal_.lastTc) ||
        std::fabs(tc - cal_.lastTc) > kCalSettleBandC) {
        cal_.lastTc = tc;
        cal_.stableSinceMs = nowMs;
    }
    const bool settled = (nowMs - cal_.stableSinceMs) >= kCalDwellMs;
    const bool timedOut = (nowMs - cal_.stepStartMs) >= kCalStepTimeoutMs;

    if (settled || timedOut) {
        calSampleDuty_ = duty;
        calSampleStep_ = cal_.curStep;
        calSampleLatched_ = true;  // drained by main once, then re-armed
        cal_.curStep++;
        cal_.stepStartMs = nowMs;
        cal_.lastTc = NAN;  // re-anchor the settle band on the new rung
        cal_.stableSinceMs = nowMs;
        const bool wasLast = cal_.targetC >= cal_.endC - 0.1f;
        cal_.targetC += cal_.stepC;
        cal_.setpointC = cal_.targetC;
        if (wasLast) {
            stopCalibration();  // ladder done; latch survives for the last point
            return;
        }
    }

    thermoblockSetpoint_ = cal_.targetC;
    lastOutput_ = duty;
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
