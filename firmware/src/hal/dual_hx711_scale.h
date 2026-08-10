#pragma once

#include <cstdint>

namespace esp32esso::hal {

// Drip-tray scale: two load cells under the tray lid, one HX711 amp each on the
// chip's gain-128 channel A, summed so the reading holds wherever the cup sits.
//
// The two amps share the clock line and keep their own data line, so a single
// 25-pulse train shifts both out at once and the cells are sampled at the same
// instant -- tray vibration and pump ripple then cancel in the sum instead of
// beating between two free-running converters. That sharing is also why this is
// one driver rather than two instances of a single-amp one: clocking either
// chip clocks the other, so they can only be read together.
//
// The HX711 powers itself down if the clock is held high for 60 us, and this
// firmware runs the dimmer's zero-cross and gate ISRs continuously, so every
// individual high pulse is taken inside a critical section. Guarding each bit
// rather than the whole train keeps the ISR blackout at ~1 us a bit, which the
// dimmer does not notice, instead of a ~100 us hole in its gate timing.
//
// Wiring, calibration and the mechanical install the accuracy depends on are in
// hardware/oster-xpert/tier-4-wiring.md.

// Per-cell tare and gain. Corner-matching the two `countsPerGram` values is
// what buys position independence; the shared default is the nominal figure for
// a 3 kg 1 mV/V cell at gain 128, so an uncalibrated reading is at least the
// right order of magnitude.
struct ScaleCalibration {
    int32_t offsetLeft = 0;
    int32_t offsetRight = 0;
    float countsPerGramLeft = 716.0f;
    float countsPerGramRight = 716.0f;
};

class DualHx711Scale {
public:
    DualHx711Scale(uint8_t sckPin, uint8_t doutLeftPin, uint8_t doutRightPin);

    // Idles the clock low and starts a first conversion. Call once from setup.
    void begin();

    // True when both amps have a conversion waiting (both DOUT lines low).
    // Neither chip can be read until both are ready, since the clock is shared.
    bool ready() const;

    // Shifts both amps out in one clock train. Returns false if the pair did not
    // become ready within `timeoutMs` (0 polls once and gives up), leaving the
    // outputs untouched. Raw 24-bit two's-complement counts, no tare applied.
    bool readRaw(int32_t& left, int32_t& right, uint32_t timeoutMs = 0);

    // Mean of `samples` raw reads. Returns false if any read times out.
    bool readRawAveraged(int32_t& left, int32_t& right, uint8_t samples,
                         uint32_t timeoutMs = 300);

    // Captures the current reading as the zero point. Do this with the tray in
    // place and empty.
    bool tare(uint8_t samples = 8, uint32_t timeoutMs = 300);

    // Summed weight in grams, tare and per-cell gain applied. Returns false and
    // leaves `grams` untouched when no sample is available.
    bool readGrams(float& grams, uint32_t timeoutMs = 0);

    const ScaleCalibration& calibration() const { return cal_; }
    void setCalibration(const ScaleCalibration& cal) { cal_ = cal; }

private:
    uint8_t sckPin_;
    uint8_t doutLeftPin_;
    uint8_t doutRightPin_;
    ScaleCalibration cal_;
    bool ready_ = false;
};

}  // namespace esp32esso::hal
