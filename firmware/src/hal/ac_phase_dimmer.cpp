#include "hal/ac_phase_dimmer.h"

#include <Arduino.h>
#include <driver/gpio.h>

namespace esp32esso::hal {

namespace {
constexpr uint32_t kGatePulseUs = 80;
constexpr uint32_t kMinDelayUs = 120;
constexpr uint32_t kZcDebounceUs = 3000;
constexpr uint32_t kHalfPeriodMinUs = 7000;   // ~71 Hz
constexpr uint32_t kHalfPeriodMaxUs = 12000;  // ~42 Hz
}

AcPhaseDimmer* AcPhaseDimmer::instance_ = nullptr;

AcPhaseDimmer::AcPhaseDimmer(uint8_t dimPin, uint8_t zcPin)
    : dimPin_(dimPin), zcPin_(zcPin) {}

bool AcPhaseDimmer::begin() {
    if (ready_) return true;
    if (instance_ != nullptr && instance_ != this) {
        Serial.println(F("# pump_dimmer: only one instance supported"));
        return false;
    }

    pinMode(dimPin_, OUTPUT);
    digitalWrite(dimPin_, LOW);
    pinMode(zcPin_, INPUT_PULLUP);

    // 80 MHz / 80 = 1 MHz → timer ticks are microseconds.
    hw_timer_t* timer = timerBegin(0, 80, true);
    if (timer == nullptr) {
        Serial.println(F("# pump_dimmer: timerBegin failed"));
        return false;
    }
    timerAttachInterrupt(timer, &AcPhaseDimmer::onGateTimerThunk, true);
    hwTimer_ = timer;

    instance_ = this;
    // RobotDyn / RBDimmer Z-C is typically an open-collector pulse that falls
    // at each AC zero. FALLING matches the common modules; if a unit idles
    // low and pulses high, swap to RISING.
    attachInterrupt(digitalPinToInterrupt(zcPin_), onZeroCrossThunk, FALLING);

    ready_ = true;
    setDuty(0.0f);
    Serial.printf("# pump_dimmer: ready DIM=GPIO%u ZC=GPIO%u\n",
                  static_cast<unsigned>(dimPin_),
                  static_cast<unsigned>(zcPin_));
    return true;
}

void AcPhaseDimmer::setDuty(float duty) {
    if (duty < 0.0f) duty = 0.0f;
    if (duty > 1.0f) duty = 1.0f;
    duty_ = duty;

    if (duty <= 0.001f) {
        delayUs_ = 0;
        return;
    }

    // Higher duty = earlier fire = shorter delay from Z-C.
    const uint32_t half = halfPeriodUs_;
    uint32_t delay = static_cast<uint32_t>((1.0f - duty) * static_cast<float>(half));
    if (delay < kMinDelayUs) delay = kMinDelayUs;
    if (delay + kGatePulseUs > half) {
        delay = half > kGatePulseUs + kMinDelayUs
                    ? half - kGatePulseUs
                    : kMinDelayUs;
    }
    delayUs_ = delay;
}

void IRAM_ATTR AcPhaseDimmer::onZeroCrossThunk() {
    if (instance_ != nullptr) {
        instance_->onZeroCross();
    }
}

void IRAM_ATTR AcPhaseDimmer::onGateTimerThunk() {
    if (instance_ != nullptr) {
        instance_->onGateTimer();
    }
}

void IRAM_ATTR AcPhaseDimmer::onZeroCross() {
    const uint32_t now = micros();
    const uint32_t since = now - lastZcUs_;
    if (lastZcUs_ != 0 && since < kZcDebounceUs) {
        return;  // TRIAC spike / bounce
    }
    if (lastZcUs_ != 0 && since >= kHalfPeriodMinUs && since <= kHalfPeriodMaxUs) {
        halfPeriodUs_ = since;
    }
    lastZcUs_ = now;

    const uint32_t delay = delayUs_;
    if (delay == 0 || hwTimer_ == nullptr) {
        return;
    }
    auto* timer = static_cast<hw_timer_t*>(hwTimer_);
    timerAlarmDisable(timer);
    timerWrite(timer, 0);
    timerAlarmWrite(timer, delay, false);
    timerAlarmEnable(timer);
}

void IRAM_ATTR AcPhaseDimmer::onGateTimer() {
    // Short gate pulse; the TRIAC latches for the rest of the half-cycle.
    // gpio_set_level stays in IRAM; digitalWrite can fault if flash is locked
    // (BLE radio active).
    gpio_set_level(static_cast<gpio_num_t>(dimPin_), 1);
    delayMicroseconds(kGatePulseUs);
    gpio_set_level(static_cast<gpio_num_t>(dimPin_), 0);
}

}  // namespace esp32esso::hal
