#include "control/temperature_loop.h"

#include <Arduino.h>  // Serial for the flush/offset trace lines

#include <cmath>

namespace esp32esso::control {

namespace {
constexpr uint32_t kPidPeriodMs = 200;  // 5 Hz; thermocouple settles slowly
constexpr float kMaxBrewSetpointC = 110.0f;  // brew target never needs to exceed this
constexpr uint32_t kFlushDurationMs = 3000;  // grouphead flush pulse length

// Adaptive EMA for the brew-line pressure. The vibration pump injects ~50/60 Hz
// ripple (and the occasional spike); the sensor's per-read median already kills
// spikes, and this filter tames the ripple. alpha scales with the size of the
// change so steady pressure is smoothed hard (alphaMin) while a real ramp/step
// (shot start, OPV crack) tracks quickly (alphaMax), keeping lag low where it
// matters. deltaFullBar is the change that reaches full responsiveness.
constexpr float kPressAlphaMin = 0.04f;
constexpr float kPressAlphaMax = 0.55f;
constexpr float kPressDeltaFullBar = 1.0f;

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
    thermoblockOffsetC_ = profile.thermal.defaultThermoblockOffsetC;
    pid_.setGains(kp_, ki_, kd_);
    pid_.setOutputLimits(0.0f, 1.0f);
    pid_.reset();
    lastOutput_ = 0.0f;
    lastTempC_ = NAN;
    groupTempC_ = NAN;
    groupOk_ = false;
    thermoblockSetpoint_ = setpoint_;
    manualThermoblockActive_ = false;
    manualThermoblockSetpoint_ = NAN;
    faulted_ = false;
    faultReason_ = "";
    heaterEnabled_ = true;
    lastActivityMs_ = 0;
    activityPending_ = true;  // start the timeout window fresh at boot
    brewing_ = false;
    manualBrew_ = false;
    brewSource_ = 0;
    shotStartMs_ = 0;
    preinfusionPhase_ = 0;
    preinfusionPhaseStartMs_ = 0;
    flushing_ = false;
    flushUntilMs_ = 0;
    reliefValveOpen_ = false;
    pressureBar_ = NAN;
    pressureOk_ = false;
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
    if (profile.brewRelay) {
        profile.brewRelay->set(false);
    }
}

void TemperatureLoop::tick(uint32_t nowMs) {
    if (profile_ == nullptr || profile_->heaterRelay == nullptr ||
        profile_->brewTempSensor == nullptr) {
        return;
    }
    updateBrewState(nowMs);
    updatePreinfusion(nowMs);
    updateFlush(nowMs);
    updateBrewRelay();
    updateReliefValve();
    updatePressure();
    updateHeaterTimeout(nowMs);
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
    activityPending_ = true;
}

void TemperatureLoop::setThermoblockOffsetC(float offsetC) {
    if (profile_ == nullptr || std::isnan(offsetC)) return;
    const float bound = profile_->thermal.maxThermoblockOffsetC;
    if (offsetC < -bound) offsetC = -bound;
    if (offsetC > bound) offsetC = bound;
    thermoblockOffsetC_ = offsetC;
    // Touching the offset means the operator wants the relational (group +
    // offset) behaviour again, so drop any absolute manual override.
    manualThermoblockActive_ = false;
    activityPending_ = true;
    Serial.printf("# tb_offset: set to %.1f C (group_sp %.1f -> tb_sp %.1f)\n",
                  thermoblockOffsetC_,
                  setpoint_,
                  computeThermoblockSetpoint());
}

void TemperatureLoop::setManualThermoblockSetpointC(float c) {
    if (profile_ == nullptr) return;
    // Non-positive / NaN clears the override (returns to group + offset).
    if (std::isnan(c) || c <= 0.0f) {
        clearManualThermoblockSetpoint();
        return;
    }
    const float lo = profile_->thermal.minBrewTempC;
    const float hi = profile_->thermal.maxSafeTempC - 5.0f;
    if (c < lo) c = lo;
    if (c > hi) c = hi;
    manualThermoblockSetpoint_ = c;
    manualThermoblockActive_ = true;
    activityPending_ = true;
    Serial.printf("# tb_manual: hold thermoblock at %.1f C (overrides group+offset)\n",
                  manualThermoblockSetpoint_);
}

void TemperatureLoop::clearManualThermoblockSetpoint() {
    if (manualThermoblockActive_) {
        Serial.println(F("# tb_manual: cleared (back to group + offset)"));
    }
    manualThermoblockActive_ = false;
    manualThermoblockSetpoint_ = NAN;
    activityPending_ = true;
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
    activityPending_ = true;
}

void TemperatureLoop::setSteamSetpointCelsius(float c) {
    if (profile_ == nullptr || std::isnan(c)) {
        return;
    }
    const float hi = profile_->thermal.maxSafeTempC - 5.0f;
    if (c < profile_->thermal.minBrewTempC) c = profile_->thermal.minBrewTempC;
    if (c > hi) c = hi;
    steamSetpoint_ = c;
    activityPending_ = true;
}

void TemperatureLoop::setHeaterTimeoutMinutes(float minutes) {
    if (std::isnan(minutes) || minutes < 0.0f) return;
    if (minutes > 240.0f) minutes = 240.0f;  // cap at 4 h
    heaterTimeoutMin_ = minutes;
    activityPending_ = true;
}

bool TemperatureLoop::hasGroupSensor() const {
    return profile_ != nullptr && profile_->groupTempSensor != nullptr;
}

void TemperatureLoop::setManualBrew(bool on) {
    manualBrew_ = on;
    activityPending_ = true;
}

void TemperatureLoop::setPreinfusionSeconds(float seconds) {
    if (std::isnan(seconds) || seconds < 0.0f) return;
    if (seconds > 30.0f) seconds = 30.0f;
    preinfusionSec_ = seconds;
}

void TemperatureLoop::setHeaterEnabled(bool on) {
    heaterEnabled_ = on;
    activityPending_ = true;  // re-enabling restarts the inactivity window
    // Cut the SSR immediately so the phone toggle is felt this tick rather than
    // waiting for the next slow-PWM window boundary.
    if (!on && profile_ != nullptr && profile_->heaterRelay != nullptr) {
        profile_->heaterRelay->set(false);
        windowOnTimeMs_ = 0;
    }
}

uint32_t TemperatureLoop::shotElapsedMs(uint32_t nowMs) const {
    if (!brewing_) return 0;
    return nowMs - shotStartMs_;
}

void TemperatureLoop::updateBrewState(uint32_t nowMs) {
    const bool active = manualBrew_;
    if (active && !brewing_) {
        brewing_ = true;
        shotStartMs_ = nowMs;
        brewSource_ = 1;
        // Arm pre-infusion (pump-on phase) when configured; otherwise brew
        // straight away (phase 0).
        preinfusionPhase_ = (preinfusionSec_ >= 0.05f) ? 1 : 0;
        preinfusionPhaseStartMs_ = nowMs;
    } else if (!active && brewing_) {
        brewing_ = false;
        brewSource_ = 0;
        preinfusionPhase_ = 0;
    }
}

void TemperatureLoop::updatePreinfusion(uint32_t nowMs) {
    if (!brewing_ || preinfusionPhase_ == 0) {
        return;
    }
    const uint32_t durMs = static_cast<uint32_t>(preinfusionSec_ * 1000.0f);
    const uint32_t elapsed = nowMs - preinfusionPhaseStartMs_;
    if (preinfusionPhase_ == 1 && elapsed >= durMs) {
        preinfusionPhase_ = 2;  // pre-infuse done -> bloom pause (pump off)
        preinfusionPhaseStartMs_ = nowMs;
    } else if (preinfusionPhase_ == 2 && elapsed >= durMs) {
        preinfusionPhase_ = 0;  // pause done -> main brew (pump on)
        preinfusionPhaseStartMs_ = nowMs;
    }
}

void TemperatureLoop::updateBrewRelay() {
    if (profile_ == nullptr || profile_->brewRelay == nullptr) {
        return;
    }
    // Pump runs whenever brewing (except during the pre-infusion bloom pause,
    // phase 2), or during an operator-triggered grouphead flush. The relief
    // valve is held closed for both cases (see updateReliefValve) so the
    // pumped water reaches the shower screen instead of dumping to the tray.
    const bool pumpFromShot = brewing_ && preinfusionPhase_ != 2;
    const bool pumpOn = pumpFromShot || flushing_;
    profile_->brewRelay->set(pumpOn);
}

void TemperatureLoop::updateReliefValve() {
    if (profile_ == nullptr || profile_->solenoidValve == nullptr) {
        return;
    }
    // 3-way-style vent: open (relieve to the drip tray) whenever the machine is
    // idle, and closed while a shot or grouphead flush is running so the pump
    // can push water through the grouphead. Commutes closed the instant a shot
    // starts or a flush begins, reopens the instant both end.
    reliefValveOpen_ = !brewing_ && !flushing_;
    profile_->solenoidValve->set(reliefValveOpen_);
}

void TemperatureLoop::startFlush(uint32_t nowMs) {
    if (brewing_) {
        Serial.printf("# flush: refused (brewing) at t=%lu\n",
                      static_cast<unsigned long>(nowMs));
        return;  // the shot owns the pump; refuse to overlay a flush
    }
    flushing_ = true;
    flushUntilMs_ = nowMs + kFlushDurationMs;
    activityPending_ = true;
    Serial.printf("# flush: start at t=%lu until t=%lu\n",
                  static_cast<unsigned long>(nowMs),
                  static_cast<unsigned long>(flushUntilMs_));
}

void TemperatureLoop::stopFlush() {
    if (flushing_) {
        Serial.println(F("# flush: stop"));
    }
    flushing_ = false;
    flushUntilMs_ = 0;
}

void TemperatureLoop::updateFlush(uint32_t nowMs) {
    if (!flushing_) return;
    // Cancel the flush the instant a shot starts (brewing wins) or the timer
    // expires. Wrap-safe comparison via the signed difference.
    if (brewing_ || static_cast<int32_t>(flushUntilMs_ - nowMs) <= 0) {
        stopFlush();
    }
}

uint32_t TemperatureLoop::flushRemainingMs(uint32_t nowMs) const {
    if (!flushing_) return 0;
    const int32_t remaining = static_cast<int32_t>(flushUntilMs_ - nowMs);
    return remaining > 0 ? static_cast<uint32_t>(remaining) : 0;
}

void TemperatureLoop::updatePressure() {
    if (profile_ == nullptr || profile_->pressureSensor == nullptr) {
        pressureBar_ = NAN;
        pressureOk_ = false;
        return;
    }
    const float raw = profile_->pressureSensor->readBar();
    pressureOk_ = profile_->pressureSensor->ok() && !std::isnan(raw);
    if (!pressureOk_) {
        pressureBar_ = NAN;
        return;
    }
    if (std::isnan(pressureBar_)) {
        pressureBar_ = raw;  // seed the filter on the first good sample
        return;
    }
    const float delta = raw - pressureBar_;
    float responsiveness = std::fabs(delta) / kPressDeltaFullBar;
    if (responsiveness > 1.0f) responsiveness = 1.0f;
    const float alpha =
        kPressAlphaMin + (kPressAlphaMax - kPressAlphaMin) * responsiveness;
    pressureBar_ += alpha * delta;
}

void TemperatureLoop::updateHeaterTimeout(uint32_t nowMs) {
    if (lastActivityMs_ == 0) lastActivityMs_ = nowMs;
    if (activityPending_) {
        activityPending_ = false;
        lastActivityMs_ = nowMs;
    }
    // A running shot (or the tuning/cal bench) counts as continuous activity so
    // the machine is never cut mid-operation.
    if (brewing_ || tuning_.active || cal_.active) {
        lastActivityMs_ = nowMs;
    }
    if (heaterTimeoutMin_ <= 0.0f || !heaterEnabled_) {
        return;  // feature disabled, or heater already off
    }
    const uint32_t timeoutMs = static_cast<uint32_t>(heaterTimeoutMin_ * 60000.0f);
    if ((nowMs - lastActivityMs_) >= timeoutMs) {
        setHeaterEnabled(false);
        // setHeaterEnabled marks activity pending; clear it so the machine stays
        // off until the operator explicitly re-enables the heater.
        activityPending_ = false;
    }
}

float TemperatureLoop::computeThermoblockSetpoint() const {
    const float hardCeil = profile_->thermal.maxSafeTempC - 5.0f;
    // Manual override wins: hold the thermoblock at the absolute temperature the
    // operator dialed in, regardless of the group setpoint or offset.
    if (manualThermoblockActive_ && !std::isnan(manualThermoblockSetpoint_)) {
        float target = manualThermoblockSetpoint_;
        if (target > hardCeil) target = hardCeil;
        if (target < 0.0f) target = 0.0f;
        return target;
    }
    // Tier 2: the user setpoint is the group target, the thermoblock chases
    // group + offset. Tier 1 (no group sensor): the setpoint IS the thermoblock
    // target, so the offset does not apply.
    float target = setpoint_;
    if (hasGroupSensor()) {
        target += thermoblockOffsetC_;
    }
    if (target > hardCeil) target = hardCeil;
    if (target < 0.0f) target = 0.0f;  // negative offsets can drop us here
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

    // Freeze the integrator while the heater is disabled so a term is not
    // accumulated during standby and dumped the instant it is re-enabled.
    pid_.setIntegratorEnabled(heaterEnabled_);
    float output = pid_.update(thermoblockSetpoint_, temp, dtSec);

    // Soft-landing approach taper: ceiling the duty as a function of how far
    // below the setpoint we still are, so the loop eases off instead of holding
    // full power right up to the target. Without this the low-mass block coasts
    // tens of degrees past the setpoint (the cartridge stays hotter than the
    // probe reads on the way up); capping the drive within the final degrees
    // keeps that gradient -- and the overshoot -- small.
    const float slope = profile_->thermal.approachDutyPerC;
    if (slope > 0.0f) {
        float cap = slope * (thermoblockSetpoint_ - temp);
        if (cap < 0.0f) cap = 0.0f;
        if (cap > 1.0f) cap = 1.0f;
        if (output > cap) output = cap;
    }
    lastOutput_ = output;

    // Shot heater boost: during the *main brew* phase only (preinfusionPhase_
    // == 0, i.e. not the pre-infusion pulse or the bloom pause), drive the
    // heater flat-out to counter the cold-water draw that otherwise sags the
    // thermoblock through the second half of a shot. Capped at the brew target
    // so we never push past it -- the PID resumes control once the block is at
    // temperature, and the hard overtemp cut above still guards safety.
    if (brewing_ && preinfusionPhase_ == 0 && temp < thermoblockSetpoint_) {
        lastOutput_ = 1.0f;
    }
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
        if (faulted_ || !heaterEnabled_) {
            windowOnTimeMs_ = 0;
        } else {
            float frac = lastOutput_;
            if (frac < 0.0f) frac = 0.0f;
            if (frac > 1.0f) frac = 1.0f;
            windowOnTimeMs_ = static_cast<uint32_t>(frac * window);
        }
    }
    const bool shouldBeOn =
        !faulted_ && heaterEnabled_ && (nowMs - windowStartMs_) < windowOnTimeMs_;
    profile_->heaterRelay->set(shouldBeOn);
}

}  // namespace esp32esso::control
