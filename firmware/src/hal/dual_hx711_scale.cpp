#include "hal/dual_hx711_scale.h"

#include <Arduino.h>

#include <cassert>

namespace esp32esso::hal {

namespace {

// One extra pulse after the 24 data bits selects channel A at gain 128 for the
// next conversion, which is the only channel either amp uses here.
constexpr uint8_t kPulsesPerRead = 25;

// Both DOUT lines must read low this many times, 10 us apart, before the pair
// counts as ready. Spans ~200 us, which comfortably outlasts the skew between
// the two amps' ready edges without eating into the 100 ms conversion period.
constexpr uint8_t kReadySettleProbes = 20;

portMUX_TYPE g_pulseMux = portMUX_INITIALIZER_UNLOCKED;

// The HX711 hands back 24-bit two's complement; widen it to int32 so the sign
// survives. Split out because it is the one piece of bit-fiddling here that can
// be wrong in a way the counts would not obviously show.
int32_t signExtend24(uint32_t raw) {
    if (raw & 0x800000UL) {
        raw |= 0xFF000000UL;
    }
    return static_cast<int32_t>(raw);
}

bool signExtend24Sane() {
    return signExtend24(0x000000UL) == 0 && signExtend24(0x7FFFFFUL) == 8388607 &&
           signExtend24(0x800000UL) == -8388608 && signExtend24(0xFFFFFFUL) == -1;
}

}  // namespace

DualHx711Scale::DualHx711Scale(uint8_t sckPin, uint8_t doutLeftPin, uint8_t doutRightPin)
    : sckPin_(sckPin), doutLeftPin_(doutLeftPin), doutRightPin_(doutRightPin) {}

void DualHx711Scale::begin() {
    assert(signExtend24Sane());
    pinMode(sckPin_, OUTPUT);
    pinMode(doutLeftPin_, INPUT);
    pinMode(doutRightPin_, INPUT);
    // A high pulse longer than 60 us powers the chip down; a >=60 us one here is
    // exactly how it is reset, so both amps start from a known state.
    digitalWrite(sckPin_, HIGH);
    delayMicroseconds(100);
    digitalWrite(sckPin_, LOW);
    ready_ = true;
}

bool DualHx711Scale::ready() const {
    return ready_ && digitalRead(doutLeftPin_) == LOW && digitalRead(doutRightPin_) == LOW;
}

bool DualHx711Scale::readRaw(int32_t& left, int32_t& right, uint32_t timeoutMs) {
    if (!ready_) {
        return false;
    }
    // The amps free-run and drift against each other, so the two ready edges do
    // not coincide and a single low sample proves nothing -- catching the later
    // line as it falls clocks that chip mid-flight and shifts its whole word.
    // Require both lines to hold low across a short window instead: once a
    // conversion has truly landed the chip latches it and holds DOUT low until
    // clocked, so a steady low is the only trustworthy ready signal.
    const uint32_t startMs = millis();
    for (;;) {
        bool steady = true;
        for (uint8_t probe = 0; probe < kReadySettleProbes; ++probe) {
            if (digitalRead(doutLeftPin_) == HIGH || digitalRead(doutRightPin_) == HIGH) {
                steady = false;
                break;
            }
            delayMicroseconds(10);
        }
        if (steady) {
            break;
        }
        if (millis() - startMs >= timeoutMs) {
            return false;
        }
        delay(1);
    }

    uint32_t rawLeft = 0;
    uint32_t rawRight = 0;
    for (uint8_t bit = 0; bit < kPulsesPerRead; ++bit) {
        // Each high pulse is kept short and uninterruptible: if the dimmer's
        // zero-cross ISR landed mid-pulse and stretched it past 60 us, both amps
        // would power down and the sample would be lost.
        portENTER_CRITICAL(&g_pulseMux);
        digitalWrite(sckPin_, HIGH);
        delayMicroseconds(1);
        digitalWrite(sckPin_, LOW);
        portEXIT_CRITICAL(&g_pulseMux);

        if (bit < 24) {
            rawLeft = (rawLeft << 1) | (digitalRead(doutLeftPin_) == HIGH ? 1UL : 0UL);
            rawRight = (rawRight << 1) | (digitalRead(doutRightPin_) == HIGH ? 1UL : 0UL);
        }
        delayMicroseconds(1);
    }

    left = signExtend24(rawLeft);
    right = signExtend24(rawRight);
    return true;
}

bool DualHx711Scale::readRawAveraged(int32_t& left, int32_t& right, uint8_t samples,
                                     uint32_t timeoutMs) {
    if (samples == 0) {
        return false;
    }
    int64_t sumLeft = 0;
    int64_t sumRight = 0;
    for (uint8_t i = 0; i < samples; ++i) {
        int32_t l = 0;
        int32_t r = 0;
        if (!readRaw(l, r, timeoutMs)) {
            return false;
        }
        sumLeft += l;
        sumRight += r;
    }
    left = static_cast<int32_t>(sumLeft / samples);
    right = static_cast<int32_t>(sumRight / samples);
    return true;
}

bool DualHx711Scale::tare(uint8_t samples, uint32_t timeoutMs) {
    int32_t left = 0;
    int32_t right = 0;
    if (!readRawAveraged(left, right, samples, timeoutMs)) {
        return false;
    }
    cal_.offsetLeft = left;
    cal_.offsetRight = right;
    return true;
}

bool DualHx711Scale::readGrams(float& grams, uint32_t timeoutMs) {
    int32_t left = 0;
    int32_t right = 0;
    if (!readRaw(left, right, timeoutMs)) {
        return false;
    }
    // Summed, not averaged: each cell carries part of the load, so the total is
    // what the pair reports regardless of where the cup sits on the tray.
    grams = static_cast<float>(left - cal_.offsetLeft) / cal_.countsPerGramLeft +
            static_cast<float>(right - cal_.offsetRight) / cal_.countsPerGramRight;
    return true;
}

}  // namespace esp32esso::hal
