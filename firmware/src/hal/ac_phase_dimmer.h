#pragma once

#include <cstdint>

namespace esp32esso::hal {

// Standard (phase-control) AC dimmer: Z-C interrupt in, DIM gate pulse out.
// Duty is continuous conduction angle (0..1), not slow on/off PWM. Uses the
// Arduino-ESP32 hw_timer + GPIO ISR path so it builds on IDF 4.x / Arduino 2.x
// (upstream rbdimmerESP32 needs IDF 5 and will not link here).
class AcPhaseDimmer {
public:
    AcPhaseDimmer(uint8_t dimPin, uint8_t zcPin);

    // Arms the Z-C ISR and one-shot gate timer. Call once from setup.
    bool begin();

    // 0 = off, 1 = full conduction. Values in between are phase-angle duty.
    void setDuty(float duty);

    float duty() const { return duty_; }

private:
    static void onZeroCrossThunk();
    static void onGateTimerThunk();
    void onZeroCross();
    void onGateTimer();

    uint8_t dimPin_;
    uint8_t zcPin_;
    float duty_ = 0.0f;
    volatile uint32_t delayUs_ = 0;            // fire delay after Z-C; 0 = off
    volatile uint32_t halfPeriodUs_ = 10000;   // 50 Hz default until measured
    volatile uint32_t lastZcUs_ = 0;
    bool ready_ = false;

    // Single active instance: attachInterrupt / timer ISR have no user data.
    static AcPhaseDimmer* instance_;
    void* hwTimer_ = nullptr;  // hw_timer_t*
};

}  // namespace esp32esso::hal
