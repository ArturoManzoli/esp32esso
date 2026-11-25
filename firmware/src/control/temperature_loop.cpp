#include "control/temperature_loop.h"

#include <Arduino.h>  // Serial for the flush/offset trace lines

#include <cassert>
#include <cmath>
#include <cstring>

namespace esp32esso::control {

namespace {
constexpr uint32_t kPidPeriodMs = 200;  // 5 Hz; thermocouple settles slowly
constexpr float kMaxBrewSetpointC = 110.0f;  // brew target never needs to exceed this
constexpr uint32_t kFlushDurationMs = 3000;  // grouphead flush pulse length
constexpr uint32_t kPostShotVentMs = 4000;   // relief-valve vent pulse after a shot

// Panel brew input. Releasing an input held at least kBrewSwitchHoldMs ends the
// shot (a latching rocker); a shorter press leaves it running (a momentary
// button, pressed again to stop). A panel shot nobody may be watching is always
// capped at kPanelShotMaxMs.
// ponytail: compile-time cap; make it a BLE setting if machines need different limits.
constexpr uint32_t kBrewSwitchHoldMs = 1000;
constexpr uint32_t kPanelShotMaxMs = 60UL * 1000UL;

// A steam session left on returns to brew temperature after this long, so a
// forgotten switch does not hold the boiler at steam temperature indefinitely.
constexpr uint32_t kSteamMaxMs = 5UL * 60UL * 1000UL;

// Steam refill hard limits. A burst is short and spaced so the boiler recovers
// between them, and a session's total pump time is capped so a pressure reading
// that never climbs (a failed sensor, an open wand) cannot flood the boiler.
constexpr uint32_t kRefillBurstMs = 1500;
constexpr uint32_t kRefillGapMs = 5000;
constexpr uint32_t kRefillMaxTotalMs = 15UL * 1000UL;

// Pump soft-start (pressure ramp). With a phase-angle dimmer the duty is
// conduction angle; the legacy zero-cross SSR path still time-proportions
// across kPumpRampWindowMs (10 Hz). kPumpRampStartDuty is the duty at the
// very start of the ramp (never 0, so the vibration pump still primes).
constexpr uint32_t kPumpRampWindowMs = 100;
constexpr float kPumpRampStartDuty = 0.30f;

// Dead-man ceiling for the open-loop pump bench: a run never outlives this,
// even if the host asks for longer or stops talking mid-sweep.
constexpr uint32_t kPumpBenchMaxMs = 30UL * 1000UL;

// Measured pump characterisation (Oster Xpert, phase dimmer, fresh puck):
// commanded duty against the brew pressure it settles at. The vibration pump
// only starts moving water near kPumpThresholdDuty -- below that it cannot beat
// its own spring plus the puck -- so the whole 0..12 bar range is squeezed into
// the top of the duty scale, and a flat "duty = bar / OPV" law would ask for
// duties that produce nothing at all. Feedforward inverts this table instead.
struct PressureDutyPoint {
    float bar;
    float duty;
};
constexpr PressureDutyPoint kPumpCurve[] = {
    {0.00f, 0.55f}, {2.47f, 0.60f}, {7.10f, 0.70f},
    {9.28f, 0.80f}, {10.83f, 0.90f}, {12.06f, 1.00f},
};
constexpr size_t kPumpCurveLen = sizeof(kPumpCurve) / sizeof(kPumpCurve[0]);
constexpr float kPumpThresholdDuty = kPumpCurve[0].duty;

// Duty expected to settle at `bar`, interpolated between measured points and
// clamped to the ends of the characterised range. Zero (or negative) asks for
// no pressure at all, which means an idle pump rather than the threshold duty.
float pumpDutyForPressure(float bar) {
    if (bar <= 0.0f) {
        return 0.0f;
    }
    for (size_t i = 1; i < kPumpCurveLen; ++i) {
        if (bar <= kPumpCurve[i].bar) {
            const PressureDutyPoint& a = kPumpCurve[i - 1];
            const PressureDutyPoint& b = kPumpCurve[i];
            const float span = b.bar - a.bar;
            const float f = (span > 0.0f) ? (bar - a.bar) / span : 0.0f;
            return a.duty + (b.duty - a.duty) * f;
        }
    }
    return kPumpCurve[kPumpCurveLen - 1].duty;
}

// Anchors the interpolation: the ends, an exact measured knot, a segment
// midpoint, and monotonicity across the range. A mis-entered table fails at
// boot instead of quietly driving the pump to the wrong pressure.
bool pumpCurveSane() {
    if (pumpDutyForPressure(-1.0f) != 0.0f) return false;
    if (pumpDutyForPressure(0.0f) != 0.0f) return false;
    if (fabsf(pumpDutyForPressure(2.47f) - 0.60f) > 0.001f) return false;
    if (fabsf(pumpDutyForPressure(8.19f) - 0.75f) > 0.01f) return false;
    if (fabsf(pumpDutyForPressure(50.0f) - 1.0f) > 0.001f) return false;
    float prev = -1.0f;
    for (int i = 0; i <= 13; ++i) {
        const float d = pumpDutyForPressure(static_cast<float>(i));
        if (d < prev) return false;
        prev = d;
    }
    return true;
}

// Closed-loop trim on top of that feedforward. The measured plant gain runs
// 12..46 bar per unit duty, so its inverse (~0.02..0.08 duty/bar) bounds what
// can be stable; sitting at the low end keeps the loop from oscillating in the
// steep region just above the threshold, where the gain is worst.
constexpr float kProfilePressKp = 0.02f;

// Flow phases run open loop (no flow sensor): the ml/s target spans the pump's
// usable duty band rather than the whole 0..1 scale, since the bottom half of
// that scale moves no water. The phase's pressure limit trims duty back by
// kProfileFlowLimitKp per bar of overshoot when a transducer exists, scaled to
// the same plant gain as the pressure trim.
constexpr float kProfileMaxFlowMlS = 8.0f;
constexpr float kProfileFlowLimitKp = 0.05f;

// Granularity of the profile's temperature leg. The override is only re-applied
// once its ramp has moved this far, so a slope does not re-log and re-arm the
// setpoint on every 10 ms tick. Finer than the thermocouple's own resolution.
constexpr float kProfileTempStepC = 0.2f;

// Ceiling for an operator-set pressure hold: the top of the characterised pump
// curve, since asking for more than the pump measurably delivers would just
// peg it at full duty while reporting a target it can never reach.
constexpr float kMaxManualPressureBar = 12.0f;

// Ceiling for a stop-on-weight target. Well past any espresso yield, but low
// enough that a stray write cannot arm a target the shot would never reach.
constexpr float kMaxTargetWeightG = 500.0f;

// Interpolated target of a profile phase at `elapsedSec` into it: ramps
// targetStart -> targetEnd across transitionSec (eased for curve 2), then holds
// targetEnd for the rest of the phase.
float profilePhaseTargetValue(uint8_t curve, float start, float end,
                              float transitionSec, float elapsedSec) {
    if (curve == 0 || transitionSec <= 0.0f || elapsedSec >= transitionSec) {
        return end;
    }
    float f = elapsedSec / transitionSec;
    if (f < 0.0f) f = 0.0f;
    if (f > 1.0f) f = 1.0f;
    const float eased = (curve == 2) ? f * f * (3.0f - 2.0f * f) : f;
    return start + (end - start) * eased;
}

// Overtemp trips are self-clearing safety cuts, not manual-clear latches: the
// heater stays off from the moment the block hits maxSafeTempC until it has
// cooled back down to this recovery point, then control resumes on its own.
// Kept well below the cut so a single trip does not immediately re-trip on
// sensor noise while coasting through the recovery band.
constexpr float kOvertempRecoveryC = 90.0f;

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
    assert(pumpCurveSane());
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
    overtempAutoRecover_ = false;
    heaterEnabled_ = true;
    lastActivityMs_ = 0;
    activityPending_ = true;  // start the timeout window fresh at boot
    brewing_ = false;
    manualBrew_ = false;
    brewSource_ = 0;
    shotStartMs_ = 0;
    brewSwitchPrimed_ = false;
    brewSwitchOn_ = false;
    panelShot_ = false;
    steamSwitchOn_ = false;
    steaming_ = false;
    refillBurstUntilMs_ = 0;
    refillGapUntilMs_ = 0;
    refillTotalMs_ = 0;
    preinfusionPhase_ = 0;
    preinfusionPhaseStartMs_ = 0;
    pumpRampSec_ = 0.0f;
    pumpWindowStartMs_ = 0;
    pumpWindowOnMs_ = 0;
    // Brew profile is loaded from NVS by the BLE layer after begin(); start
    // cleared so a Tier 1 (no BLE) build simply never has one.
    profileActive_ = false;
    profilePhaseCount_ = 0;
    profilePhaseIndex_ = 0;
    profilePhaseStartMs_ = 0;
    profileTempC_ = 0.0f;
    profileTempApplied_ = false;
    profileTempLastC_ = NAN;
    flushing_ = false;
    flushUntilMs_ = 0;
    reliefValveOpen_ = false;
    reliefVentUntilMs_ = 0;
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
    if (profile.pumpRelay) {
        profile.pumpRelay->set(false);
    }
    if (profile.pumpDimmer) {
        profile.pumpDimmer->setDuty(0.0f);
    }
    if (profile.brewValveRelay) {
        profile.brewValveRelay->set(false);
    }
}

void TemperatureLoop::tick(uint32_t nowMs) {
    if (profile_ == nullptr || profile_->heaterRelay == nullptr ||
        profile_->brewTempSensor == nullptr) {
        return;
    }
    // Ahead of the brew state so a stop-on-weight target is judged against this
    // tick's sample rather than the previous one.
    updateScale();
    updateBrewSwitch(nowMs);
    updateSteamSwitch(nowMs);
    updateBrewState(nowMs);
    updatePreinfusion(nowMs);
    updateFlush(nowMs);
    updatePumpAndBrewValve(nowMs);
    updateReliefValve(nowMs);
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

void TemperatureLoop::setManualBrew(bool on, bool useProfile) {
    manualBrew_ = on;
    panelShot_ = false;
    if (on) {
        brewUseProfile_ = useProfile;
    }
    activityPending_ = true;
}

void TemperatureLoop::setPreinfusionSeconds(float seconds) {
    if (std::isnan(seconds) || seconds < 0.0f) return;
    if (seconds > 30.0f) seconds = 30.0f;
    preinfusionSec_ = seconds;
}

void TemperatureLoop::setPumpRampSeconds(float seconds) {
    if (std::isnan(seconds) || seconds < 0.0f) return;
    if (seconds > 15.0f) seconds = 15.0f;
    pumpRampSec_ = seconds;
}

void TemperatureLoop::setBrewProfile(const uint8_t* data, size_t len) {
    // Wire layout mirrors BrewProfile.toWireBytes in the app (see tier1_gatt.h):
    // an 8-byte header then N little-endian phase records, 28 bytes each on wire
    // v1 and 36 on v2 (which appends the block-temperature ramp). Both are read
    // because a v1 blob can still be restored from NVS after an update. Anything
    // that does not carry at least one complete phase clears the profile.
    constexpr size_t kHeader = 8;
    if (data == nullptr || len < kHeader) {
        clearBrewProfile();
        return;
    }
    const uint8_t version = data[0];
    uint8_t phaseCount = data[1];
    if ((version != 1 && version != 2) || phaseCount == 0) {
        clearBrewProfile();
        return;
    }
    const size_t kPhase = (version == 2) ? 36u : 28u;
    if (len < kHeader + kPhase) {
        clearBrewProfile();
        return;
    }
    if (phaseCount > kMaxProfilePhases) phaseCount = kMaxProfilePhases;
    const size_t available = (len - kHeader) / kPhase;
    if (phaseCount > available) phaseCount = static_cast<uint8_t>(available);
    if (phaseCount == 0) {
        clearBrewProfile();
        return;
    }

    float tempC = 0.0f;
    std::memcpy(&tempC, data + 4, sizeof(float));

    uint8_t tempPhases = 0;
    for (uint8_t i = 0; i < phaseCount; ++i) {
        const uint8_t* p = data + kHeader + static_cast<size_t>(i) * kPhase;
        ProfilePhaseRt& rt = profilePhases_[i];
        rt.mode = p[0];
        rt.curve = p[1];
        rt.valveOpen = p[2] != 0;
        std::memcpy(&rt.targetStart, p + 4, sizeof(float));
        std::memcpy(&rt.targetEnd, p + 8, sizeof(float));
        std::memcpy(&rt.transitionSec, p + 12, sizeof(float));
        std::memcpy(&rt.durationSec, p + 16, sizeof(float));
        std::memcpy(&rt.restriction, p + 20, sizeof(float));
        std::memcpy(&rt.stopPressureAbove, p + 24, sizeof(float));
        rt.blockTempStartC = 0.0f;
        rt.blockTempEndC = 0.0f;
        if (version == 2) {
            std::memcpy(&rt.blockTempStartC, p + 28, sizeof(float));
            std::memcpy(&rt.blockTempEndC, p + 32, sizeof(float));
            if (std::isnan(rt.blockTempStartC)) rt.blockTempStartC = 0.0f;
            if (std::isnan(rt.blockTempEndC)) rt.blockTempEndC = 0.0f;
            if (rt.blockTempStartC > 0.0f || rt.blockTempEndC > 0.0f) tempPhases++;
        }
    }
    profilePhaseCount_ = phaseCount;
    profileTempC_ = tempC;
    profileActive_ = true;
    Serial.printf("# profile: set %u phases (v%u), temp=%.1fC, %u temp phases\n",
                  static_cast<unsigned>(phaseCount), static_cast<unsigned>(version),
                  static_cast<double>(tempC), static_cast<unsigned>(tempPhases));
}

void TemperatureLoop::clearBrewProfile() {
    if (profileActive_) {
        Serial.println(F("# profile: cleared"));
    }
    releaseProfileTemperature();
    profileActive_ = false;
    profilePhaseCount_ = 0;
    profilePhaseIndex_ = 0;
    profileTempC_ = 0.0f;
}

// Hand temperature control back to the normal group + offset regime after a
// profile has been driving the block. Only clears the override the profile
// itself installed, so an operator's manual block setpoint set after the shot
// is never stolen from under them.
void TemperatureLoop::releaseProfileTemperature() {
    if (!profileTempApplied_) {
        return;
    }
    profileTempApplied_ = false;
    profileTempLastC_ = NAN;
    clearManualThermoblockSetpoint();
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

void TemperatureLoop::updateBrewSwitch(uint32_t nowMs) {
    if (profile_->brewSwitch == nullptr) {
        return;
    }
    const bool on = profile_->brewSwitch->read();
    if (!brewSwitchPrimed_) {
        brewSwitchPrimed_ = true;
        brewSwitchOn_ = on;
        return;
    }
    if (on && !brewSwitchOn_) {
        brewSwitchSinceMs_ = nowMs;
        // Without the phone, the panel is the only way back from the
        // inactivity cut-off, so any press re-arms the heater.
        setHeaterEnabled(true);
        if (manualBrew_) {
            manualBrew_ = false;
        } else {
            setManualBrew(true, /*useProfile=*/true);
            panelShot_ = true;
        }
    } else if (!on && brewSwitchOn_ && panelShot_ &&
               (nowMs - brewSwitchSinceMs_) >= kBrewSwitchHoldMs) {
        manualBrew_ = false;
    }
    brewSwitchOn_ = on;

    if (!brewing_ || !panelShot_) {
        return;
    }
    if ((nowMs - shotStartMs_) >= kPanelShotMaxMs) {
        manualBrew_ = false;
        return;
    }
    // A profile shot from the panel ends with its last phase, since there is no
    // phone to stop it; a final phase with no duration holds until the cap.
    const float elapsed = brewProfileElapsedSec(nowMs);
    if (std::isnan(elapsed) || profilePhases_[profilePhaseCount_ - 1].durationSec <= 0.0f) {
        return;
    }
    float totalSec = 0.0f;
    for (uint8_t i = 0; i < profilePhaseCount_; ++i) {
        totalSec += profilePhases_[i].durationSec;
    }
    if (elapsed >= totalSec) {
        manualBrew_ = false;
    }
}

void TemperatureLoop::updateSteamSwitch(uint32_t nowMs) {
    if (profile_->steamSwitch == nullptr) {
        return;
    }
    const bool on = profile_->steamSwitch->read();
    if (on && !steamSwitchOn_) {
        steaming_ = true;
        steamSinceMs_ = nowMs;
        refillTotalMs_ = 0;
        setHeaterEnabled(true);
        Serial.printf("# steam: on, target %.1f C\n", static_cast<double>(steamSetpoint_));
    } else if (!on && steaming_) {
        steaming_ = false;
        Serial.println(F("# steam: off"));
    } else if (steaming_ && (nowMs - steamSinceMs_) >= kSteamMaxMs) {
        steaming_ = false;
        Serial.println(F("# steam: timed out, back to brew temperature"));
    }
    steamSwitchOn_ = on;
}

void TemperatureLoop::updateBrewState(uint32_t nowMs) {
    // Stop-on-weight drops the same flag the app's stop writes, so the shot ends
    // through one path and still gets its profile teardown and vent pulse.
    if (manualBrew_ && targetWeightG_ > 0.0f) {
        const float total = weightTotalGrams();
        if (!std::isnan(total) && total >= targetWeightG_) {
            manualBrew_ = false;
        }
    }
    // No shot at steam temperature: water through the group would flash to
    // steam at the puck.
    if (manualBrew_ && !brewing_ && steaming_) {
        manualBrew_ = false;
        panelShot_ = false;
        Serial.println(F("# brew: refused while steaming"));
    }
    const bool active = manualBrew_;
    if (active && !brewing_) {
        brewing_ = true;
        shotStartMs_ = nowMs;
        brewSource_ = panelShot_ ? 2 : 1;
        if (profileActive_ && profilePhaseCount_ > 0) {
            // A profile owns the whole pump program, so skip the built-in
            // pre-infusion state machine (phase 0) and start the profile at its
            // first phase.
            preinfusionPhase_ = 0;
            profilePhaseIndex_ = 0;
            profilePhaseStartMs_ = nowMs;
            profileTempLastC_ = NAN;  // first tick installs the ramp's start
        } else {
            // Arm pre-infusion (pump-on phase) when configured; otherwise brew
            // straight away (phase 0).
            preinfusionPhase_ = (preinfusionSec_ >= 0.05f) ? 1 : 0;
            preinfusionPhaseStartMs_ = nowMs;
        }
    } else if (!active && brewing_) {
        brewing_ = false;
        brewSource_ = 0;
        panelShot_ = false;
        preinfusionPhase_ = 0;
        // A profile that was commanding the block hands temperature back now
        // that the shot is over, so idle control returns to group + offset.
        releaseProfileTemperature();
        // Arm the post-shot vent: the relief valve opens briefly now that the
        // shot has ended, to relieve puck/line pressure (see updateReliefValve).
        reliefVentUntilMs_ = nowMs + kPostShotVentMs;
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

void TemperatureLoop::updatePumpAndBrewValve(uint32_t nowMs) {
    if (profile_ == nullptr) {
        return;
    }
    // Open-loop pump bench: a fixed duty with the brew valve open, so the pump
    // can be characterised against the transducer. A shot or flush reclaims the
    // pump, and the run always expires on its own.
    if (pumpBench_.active) {
        if (brewing_ || flushing_ ||
            (nowMs - pumpBench_.startMs) >= pumpBench_.durationMs) {
            stopPumpBench();
        } else {
            if (profile_->brewValveRelay) {
                profile_->brewValveRelay->set(true);
            }
            applyPumpDuty(nowMs, pumpBench_.duty);
            return;
        }
    }
    if (updateSteamRefill(nowMs)) {
        return;
    }
    // Pump/valve run whenever brewing (except during the pre-infusion bloom
    // pause, phase 2), or during an operator-triggered grouphead flush. The
    // relief valve is held closed for both cases (see updateReliefValve) so
    // the pumped water reaches the shower screen instead of dumping to the
    // tray.
    const bool pumpFromShot = brewing_ && preinfusionPhase_ != 2;

    // A manual pressure hold outranks everything else during a shot: it pins a
    // flat pressure for the whole shot, so it wins over the selected profile
    // exactly as the manual thermoblock setpoint wins over group + offset.
    if (manualPressureActive_ && pumpFromShot && !flushing_) {
        if (profile_->brewValveRelay) {
            profile_->brewValveRelay->set(true);
        }
        applyPumpDuty(nowMs, pressureHoldDuty(manualPressureBar_));
        return;
    }

    // A brew profile owns the entire pump program during a shot (never a
    // flush): it runs its phases, driving the brew valve and the pump duty
    // itself. Everything else -- a flush, or a shot with no profile -- falls
    // through to the built-in pre-infusion + soft-start ramp below.
    if (brewUseProfile_ && profileActive_ && profilePhaseCount_ > 0 && pumpFromShot &&
        !flushing_) {
        updateProfileExecution(nowMs);
        return;
    }

    const bool pumpOn = pumpFromShot || flushing_;

    // Brew valve: plain on/off, mirroring pumpOn exactly. It is intentionally
    // never time-proportioned -- chopping a solenoid at the ramp/PWM rate
    // would chatter and buzz it the same way the relief valve used to (see
    // updateReliefValve); the valve just needs to be open whenever the pump
    // should be pushing water, full stop.
    if (profile_->brewValveRelay) {
        profile_->brewValveRelay->set(pumpOn);
    }

    if (profile_->pumpRelay == nullptr && profile_->pumpDimmer == nullptr) {
        return;
    }
    if (!pumpOn) {
        applyPumpDuty(nowMs, 0.0f);
        return;
    }

    // Soft-start pressure ramp: only during a shot (never a flush) and only
    // while the ramp window is open. With a phase dimmer this is real
    // conduction-angle duty; with a legacy zero-cross SSR it is slow-PWM.
    // Duty climbs from kPumpRampStartDuty to 1.0 across pumpRampSec_ measured
    // from shot start (which covers the initial pre-infusion push too).
    float duty = 1.0f;
    if (pumpFromShot && !flushing_ && pumpRampSec_ > 0.0f) {
        const uint32_t rampMs = static_cast<uint32_t>(pumpRampSec_ * 1000.0f);
        const uint32_t elapsed = nowMs - shotStartMs_;
        if (rampMs > 0 && elapsed < rampMs) {
            const float frac = static_cast<float>(elapsed) / static_cast<float>(rampMs);
            duty = kPumpRampStartDuty + (1.0f - kPumpRampStartDuty) * frac;
        }
    }

    applyPumpDuty(nowMs, duty);
}

// Returns true while a refill burst owns the pump this tick.
bool TemperatureLoop::updateSteamRefill(uint32_t nowMs) {
    const float belowBar = profile_->hydraulic.steamRefillBelowBar;
    if (!steaming_ || belowBar <= 0.0f || brewing_ || flushing_) {
        refillBurstUntilMs_ = 0;
        return false;
    }
    if (refillBurstUntilMs_ != 0) {
        if (static_cast<int32_t>(refillBurstUntilMs_ - nowMs) > 0) {
            if (profile_->brewValveRelay) {
                profile_->brewValveRelay->set(false);
            }
            applyPumpDuty(nowMs, 1.0f);
            return true;
        }
        refillBurstUntilMs_ = 0;
        refillGapUntilMs_ = nowMs + kRefillGapMs;
        return false;
    }
    if (refillGapUntilMs_ != 0) {
        if (static_cast<int32_t>(refillGapUntilMs_ - nowMs) > 0) {
            return false;
        }
        refillGapUntilMs_ = 0;
    }
    if (refillTotalMs_ + kRefillBurstMs > kRefillMaxTotalMs || !pressureOk_ ||
        std::isnan(pressureBar_) || pressureBar_ >= belowBar) {
        return false;
    }
    refillBurstUntilMs_ = nowMs + kRefillBurstMs;
    if (refillBurstUntilMs_ == 0) refillBurstUntilMs_ = 1;  // 0 means idle
    refillTotalMs_ += kRefillBurstMs;
    Serial.printf("# steam: refill burst at %.2f bar (%lu ms used)\n",
                  static_cast<double>(pressureBar_),
                  static_cast<unsigned long>(refillTotalMs_));
    if (profile_->brewValveRelay) {
        profile_->brewValveRelay->set(false);
    }
    applyPumpDuty(nowMs, 1.0f);
    return true;
}

float TemperatureLoop::brewProfileElapsedSec(uint32_t nowMs) const {
    if (!brewing_ || !brewUseProfile_ || !profileActive_ || profilePhaseCount_ == 0 ||
        flushing_) {
        return NAN;
    }
    float elapsed = 0.0f;
    for (uint8_t i = 0; i < profilePhaseIndex_ && i < profilePhaseCount_; ++i) {
        elapsed += profilePhases_[i].durationSec;
    }
    const float inPhase = static_cast<float>(nowMs - profilePhaseStartMs_) / 1000.0f;
    const float phaseDur = profilePhases_[profilePhaseIndex_].durationSec;
    // The last phase holds until the operator ends the shot; clamping keeps the
    // reported position from running off the end of the profile.
    return elapsed + ((inPhase > phaseDur) ? phaseDur : inPhase);
}

void TemperatureLoop::updateProfileExecution(uint32_t nowMs) {
    // Advance through phases: a phase ends when its duration elapses, or early
    // if it names a stopPressureAbove and the transducer reports at/above it.
    // The final phase holds until the operator ends the shot.
    while (profilePhaseIndex_ < profilePhaseCount_) {
        const ProfilePhaseRt& ph = profilePhases_[profilePhaseIndex_];
        const uint32_t elapsedMs = nowMs - profilePhaseStartMs_;
        const uint32_t durMs = static_cast<uint32_t>(ph.durationSec * 1000.0f);
        const bool timeUp = durMs > 0 && elapsedMs >= durMs;
        const bool pressureStop =
            ph.stopPressureAbove > 0.0f && pressureOk_ && !std::isnan(pressureBar_) &&
            pressureBar_ >= ph.stopPressureAbove;
        if ((timeUp || pressureStop) && profilePhaseIndex_ + 1 < profilePhaseCount_) {
            profilePhaseIndex_++;
            profilePhaseStartMs_ = nowMs;
            continue;
        }
        break;
    }

    const ProfilePhaseRt& ph = profilePhases_[profilePhaseIndex_];
    const float elapsedSec =
        static_cast<float>(nowMs - profilePhaseStartMs_) / 1000.0f;
    const float target = profilePhaseTargetValue(ph.curve, ph.targetStart,
                                                 ph.targetEnd, ph.transitionSec,
                                                 elapsedSec);

    // Temperature leg of the profile: a phase carrying block temperatures pins
    // the thermoblock through the same manual-override path the phone uses, so
    // the PID chases the block while the profile owns it (the group probe is far
    // too slow to be commanded mid-shot). The ramp always runs across the whole
    // phase duration -- unlike the pressure target, which ramps over
    // transitionSec then holds -- so a vertex-to-vertex temperature slope is
    // continuous. Phases with no temperature leave control untouched.
    if (ph.blockTempStartC > 0.0f || ph.blockTempEndC > 0.0f) {
        const float from = (ph.blockTempStartC > 0.0f) ? ph.blockTempStartC : ph.blockTempEndC;
        const float to = (ph.blockTempEndC > 0.0f) ? ph.blockTempEndC : from;
        float f = (ph.durationSec > 0.0f) ? elapsedSec / ph.durationSec : 1.0f;
        if (f < 0.0f) f = 0.0f;
        if (f > 1.0f) f = 1.0f;
        const float want = from + (to - from) * f;
        if (std::isnan(profileTempLastC_) ||
            std::fabs(want - profileTempLastC_) >= kProfileTempStepC) {
            setManualThermoblockSetpointC(want);
            profileTempLastC_ = want;
            profileTempApplied_ = true;
        }
    }

    // The brew valve follows the phase intent: a closed valve (e.g. a bloom /
    // soak pause) lets the puck rest with no flow, so the pump also idles.
    if (profile_->brewValveRelay) {
        profile_->brewValveRelay->set(ph.valveOpen);
    }
    if (!ph.valveOpen) {
        applyPumpDuty(nowMs, 0.0f);
        return;
    }

    const bool haveP = pressureOk_ && !std::isnan(pressureBar_);
    float duty;
    if (ph.mode == 0) {
        duty = pressureHoldDuty(target);
    } else {
        // Flow phase: open-loop duty from the ml/s target across the pump's
        // usable band, with the phase's pressure limit (restriction) trimming
        // duty back if the puck spikes.
        float frac = (kProfileMaxFlowMlS > 0.0f) ? target / kProfileMaxFlowMlS : 0.0f;
        if (frac < 0.0f) frac = 0.0f;
        if (frac > 1.0f) frac = 1.0f;
        duty = (frac > 0.0f)
                   ? kPumpThresholdDuty + (1.0f - kPumpThresholdDuty) * frac
                   : 0.0f;
        if (haveP && ph.restriction > 0.0f && pressureBar_ > ph.restriction) {
            duty -= kProfileFlowLimitKp * (pressureBar_ - ph.restriction);
        }
    }
    if (duty < 0.0f) duty = 0.0f;
    if (duty > 1.0f) duty = 1.0f;
    applyPumpDuty(nowMs, duty);
}

// Drive the pump at `duty` (0..1). Prefers the phase-angle dimmer when the
// profile has one; otherwise falls back to slow-PWM on a zero-cross SSR.
// Shared by the built-in ramp and the profile engine.
void TemperatureLoop::applyPumpDuty(uint32_t nowMs, float duty) {
    if (profile_->pumpDimmer != nullptr) {
        profile_->pumpDimmer->setDuty(duty);
        pumpWindowStartMs_ = 0;
        pumpWindowOnMs_ = 0;
        return;
    }
    if (profile_->pumpRelay == nullptr) {
        return;
    }
    if (duty <= 0.001f) {
        profile_->pumpRelay->set(false);
        pumpWindowStartMs_ = 0;
        pumpWindowOnMs_ = 0;
        return;
    }
    // At (or effectively at) full duty, drive the SSR continuously -- no point
    // pulsing a fully-open pump, and it avoids needless SSR/pump cycling.
    if (duty >= 0.999f) {
        profile_->pumpRelay->set(true);
        pumpWindowStartMs_ = 0;
        pumpWindowOnMs_ = 0;
        return;
    }

    // Time-proportional (slow-PWM) drive over kPumpRampWindowMs at `duty`.
    if (pumpWindowStartMs_ == 0 || (nowMs - pumpWindowStartMs_) >= kPumpRampWindowMs) {
        pumpWindowStartMs_ = nowMs;
        pumpWindowOnMs_ = static_cast<uint32_t>(duty * kPumpRampWindowMs);
    }
    const bool on = (nowMs - pumpWindowStartMs_) < pumpWindowOnMs_;
    profile_->pumpRelay->set(on);
}

// Duty that should hold `targetBar` at the puck: feedforward off the measured
// pump curve, trimmed by the transducer error when one is readable. Shared by
// the manual pressure hold and the profile engine's pressure phases.
float TemperatureLoop::pressureHoldDuty(float targetBar) const {
    float duty = pumpDutyForPressure(targetBar);
    if (duty > 0.0f && pressureOk_ && !std::isnan(pressureBar_)) {
        duty += kProfilePressKp * (targetBar - pressureBar_);
    }
    if (duty < 0.0f) duty = 0.0f;
    if (duty > 1.0f) duty = 1.0f;
    return duty;
}

void TemperatureLoop::setManualPressureBar(float bar) {
    if (std::isnan(bar) || bar <= 0.0f) {
        clearManualPressure();
        return;
    }
    if (bar > kMaxManualPressureBar) {
        bar = kMaxManualPressureBar;
    }
    manualPressureActive_ = true;
    manualPressureBar_ = bar;
    activityPending_ = true;
}

void TemperatureLoop::clearManualPressure() {
    manualPressureActive_ = false;
    manualPressureBar_ = NAN;
}

void TemperatureLoop::startPumpBench(uint32_t nowMs, float duty, uint32_t durationMs) {
    if (profile_ == nullptr || brewing_ || flushing_) {
        return;
    }
    if (profile_->pumpDimmer == nullptr && profile_->pumpRelay == nullptr) {
        return;
    }
    if (duty < 0.0f) duty = 0.0f;
    if (duty > 1.0f) duty = 1.0f;
    if (durationMs == 0 || durationMs > kPumpBenchMaxMs) {
        durationMs = kPumpBenchMaxMs;
    }
    pumpBench_.active = true;
    pumpBench_.duty = duty;
    pumpBench_.durationMs = durationMs;
    pumpBench_.startMs = nowMs;
}

void TemperatureLoop::stopPumpBench() {
    if (!pumpBench_.active) {
        return;
    }
    pumpBench_.active = false;
    pumpBench_.duty = 0.0f;
    if (profile_ == nullptr) {
        return;
    }
    if (profile_->brewValveRelay) {
        profile_->brewValveRelay->set(false);
    }
    applyPumpDuty(0, 0.0f);  // nowMs is unused on the zero-duty path
}

void TemperatureLoop::updateReliefValve(uint32_t nowMs) {
    if (profile_ == nullptr || profile_->solenoidValve == nullptr) {
        return;
    }
    // 3-way-style vent, DEFAULT CLOSED (de-energised). The valve energises open
    // only for a brief post-shot vent pulse to relieve puck/line pressure; the
    // rest of the time -- idle, a hot block, a running shot or flush, and future
    // active operations (steam, hot-water) -- it stays closed. Holding it closed
    // at idle stops the solenoid humming (it used to sit energised open whenever
    // the machine was idle). A new shot or flush claims the valve and cancels any
    // pending vent so the pump can build pressure through the grouphead.
    if (brewing_ || flushing_) {
        reliefVentUntilMs_ = 0;
    }
    const bool venting =
        reliefVentUntilMs_ != 0 &&
        static_cast<int32_t>(reliefVentUntilMs_ - nowMs) > 0;
    if (!venting) {
        reliefVentUntilMs_ = 0;  // window elapsed; return to the closed default
    }
    reliefValveOpen_ = venting;
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

void TemperatureLoop::updateScale() {
    if (profile_ == nullptr || profile_->scale == nullptr) {
        weightLeftG_ = NAN;
        weightRightG_ = NAN;
        return;
    }
    // Only touch the amps when a conversion is already waiting: at 10 SPS most
    // ticks have nothing new, and a blocking wait here would stall the PID for
    // most of a conversion period.
    if (!profile_->scale->ready()) {
        return;
    }
    int32_t left = 0;
    int32_t right = 0;
    if (!profile_->scale->readRaw(left, right)) {
        return;
    }
    const auto& cal = profile_->scale->calibration();
    weightSamplesLeftG_[weightSampleIndex_] =
        static_cast<float>(left - cal.offsetLeft) / cal.countsPerGramLeft;
    weightSamplesRightG_[weightSampleIndex_] =
        static_cast<float>(right - cal.offsetRight) / cal.countsPerGramRight;
    weightSampleIndex_ = (weightSampleIndex_ + 1) % kWeightFilterSamples;
    if (weightSampleCount_ < kWeightFilterSamples) ++weightSampleCount_;

    // ponytail: a flat rolling mean, so the reported weight trails a pour by
    // half the window (~0.4 s at 10 SPS, under a gram at espresso flow rates).
    // Wiring the HX711 RATE pin for 80 SPS shrinks that window eightfold if the
    // stop ever needs to land tighter.
    float sumLeft = 0.0f;
    float sumRight = 0.0f;
    for (uint8_t i = 0; i < weightSampleCount_; ++i) {
        sumLeft += weightSamplesLeftG_[i];
        sumRight += weightSamplesRightG_[i];
    }
    const float meanLeft = sumLeft / weightSampleCount_;
    const float meanRight = sumRight / weightSampleCount_;

    trackScaleZero(meanLeft, meanRight);
    weightLeftG_ = meanLeft - zeroLeftG_;
    weightRightG_ = meanRight - zeroRightG_;
}

// Load cells creep with temperature, and this tray sits on a machine that warms
// up, so a tare goes stale by a few grams an hour. Walk the zero back toward the
// live reading while the tray is quiet and near-empty, the way any bench scale
// does. Anything heavier than the band is real load and pins the zero where it
// is; a shot or a flush suspends tracking outright so coffee landing in the cup
// is never mistaken for drift.
void TemperatureLoop::trackScaleZero(float meanLeftG, float meanRightG) {
    constexpr float kZeroTrackBandG = 2.0f;
    constexpr float kZeroTrackStepG = 0.005f;  // 0.05 g/s at 10 SPS
    if (brewing_ || flushing_) {
        return;
    }
    const float leftErr = meanLeftG - zeroLeftG_;
    const float rightErr = meanRightG - zeroRightG_;
    if (std::fabs(leftErr + rightErr) > kZeroTrackBandG) {
        return;
    }
    zeroLeftG_ += std::fmax(-kZeroTrackStepG, std::fmin(kZeroTrackStepG, leftErr));
    zeroRightG_ += std::fmax(-kZeroTrackStepG, std::fmin(kZeroTrackStepG, rightErr));
}

void TemperatureLoop::setTargetWeightGrams(float grams) {
    if (std::isnan(grams) || grams <= 0.0f) {
        targetWeightG_ = 0.0f;
        return;
    }
    if (grams > kMaxTargetWeightG) grams = kMaxTargetWeightG;
    targetWeightG_ = grams;
    activityPending_ = true;
}

bool TemperatureLoop::tareScale() {
    if (profile_ == nullptr || profile_->scale == nullptr) {
        return false;
    }
    if (!profile_->scale->tare()) {
        return false;
    }
    // Drop the filter window and the tracked zero with it: both describe the
    // load that just became the new origin, and averaging across that step would
    // drag the reading back toward the old one for a second.
    weightSampleIndex_ = 0;
    weightSampleCount_ = 0;
    zeroLeftG_ = 0.0f;
    zeroRightG_ = 0.0f;
    weightLeftG_ = 0.0f;
    weightRightG_ = 0.0f;
    activityPending_ = true;
    return true;
}

void TemperatureLoop::updateHeaterTimeout(uint32_t nowMs) {
    if (lastActivityMs_ == 0) lastActivityMs_ = nowMs;
    if (activityPending_) {
        activityPending_ = false;
        lastActivityMs_ = nowMs;
    }
    // A running shot (or the tuning/cal bench) counts as continuous activity so
    // the machine is never cut mid-operation.
    if (brewing_ || steaming_ || tuning_.active || cal_.active) {
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
    if (steaming_) {
        return (steamSetpoint_ > hardCeil) ? hardCeil : steamSetpoint_;
    }
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
    overtempAutoRecover_ = false;
    pid_.reset();
}

void TemperatureLoop::latchFault(const char* reason) {
    faulted_ = true;
    faultReason_ = reason;
    overtempAutoRecover_ = false;
    lastOutput_ = 0.0f;
    windowOnTimeMs_ = 0;
    if (profile_ && profile_->heaterRelay) {
        profile_->heaterRelay->set(false);
    }
}

void TemperatureLoop::tripOvertemp() {
    latchFault("overtemp");
    overtempAutoRecover_ = true;  // self-clears once cooled to kOvertempRecoveryC
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
        tripOvertemp();
        return;
    }
    if (faulted_) {
        if (overtempAutoRecover_ && temp <= kOvertempRecoveryC) {
            Serial.printf("# fault: overtemp cleared, cooled to %.1f C\n", temp);
            clearFault();
        } else {
            return;  // sensor-fault (manual clear), or overtemp still cooling
        }
    }

    thermoblockSetpoint_ = computeThermoblockSetpoint();

    // Pick the control variable. When the operator has fixed a GROUP target
    // (no absolute thermoblock override) and the group probe reads OK, close
    // the loop directly on the group/portafilter temperature so the cup lands
    // on the setpoint regardless of transport loss -- the thermoblock finds
    // whatever temperature that takes. Otherwise (manual thermoblock hold, or a
    // faulted/absent group sensor) fall back to driving the thermoblock against
    // its derived setpoint, which is also the graceful safety path. The block
    // sensor's fault/overtemp latch above still guards safety in every mode.
    const bool groupDirect =
        hasGroupSensor() && groupOk_ && !manualThermoblockActive_ && !steaming_;
    const float controlSetpoint = groupDirect ? setpoint_ : thermoblockSetpoint_;
    const float controlPv = groupDirect ? groupTempC_ : temp;

    // Freeze the integrator while the heater is disabled so a term is not
    // accumulated during standby and dumped the instant it is re-enabled.
    pid_.setIntegratorEnabled(heaterEnabled_);
    float output = pid_.update(controlSetpoint, controlPv, dtSec);

    // Soft-landing approach taper: ceiling the duty as a function of how far
    // below the setpoint we still are, so the loop eases off instead of holding
    // full power right up to the target. Without this the low-mass block coasts
    // tens of degrees past the setpoint (the cartridge stays hotter than the
    // probe reads on the way up); capping the drive within the final degrees
    // keeps that gradient -- and the overshoot -- small.
    const float slope = profile_->thermal.approachDutyPerC;
    if (slope > 0.0f) {
        float cap = slope * (controlSetpoint - controlPv);
        if (cap < 0.0f) cap = 0.0f;
        if (cap > 1.0f) cap = 1.0f;
        if (output > cap) output = cap;
    }

    // Shot heater boost: during the *main brew* phase only (preinfusionPhase_
    // == 0, i.e. not the pre-infusion pulse or the bloom pause), drive the
    // heater flat-out to counter the cold-water draw that otherwise sags the
    // temperature through the second half of a shot. The PID resumes control
    // once the control PV is back at the setpoint; the block ceiling and the
    // hard overtemp cut above still guard safety.
    if (brewing_ && preinfusionPhase_ == 0 && controlPv < controlSetpoint) {
        output = 1.0f;
    }

    // Thermoblock ceiling. When closing on the slow group probe the block can
    // sit far hotter than the cup while the group lags, so bound how hot it may
    // run: the block never needs to exceed the group target plus the maximum
    // operator offset. Kept strictly below the hard safety cut, and cutting the
    // duty here (rather than latching a fault) keeps control smooth. Only
    // applies in group-direct mode; block-direct mode is self-limiting because
    // its PV *is* the block.
    if (groupDirect) {
        float blockCeil = setpoint_ + profile_->thermal.maxThermoblockOffsetC;
        const float hardCeil = profile_->thermal.maxSafeTempC - 5.0f;
        if (blockCeil > hardCeil) blockCeil = hardCeil;
        if (temp >= blockCeil) {
            output = 0.0f;
        }
    }

    lastOutput_ = output;
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
        tripOvertemp();
        tuning_.active = false;  // bench run over; recovery handled by runPidIfDue()
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
