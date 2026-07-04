#pragma once

#include <cstdint>

#include "hal/temperature_sensor.h"

namespace esp32esso::hal {

// Driver for the machine's stock NTC thermistor on the thermoblock, read
// through a resistor divider on an ESP32 ADC1 channel (ADC2 is unusable while
// the BLE radio is on).
//
// Divider (default, NTC on the low side, series resistor as the pull-up):
//
//   Vsupply --[ Rseries ]--+--[ NTC ]-- GND
//                          |
//                        ADC pin
//
//   Vnode = Vsupply * Rntc / (Rseries + Rntc)
//   Rntc  = Rseries * Vnode / (Vsupply - Vnode)
//
// Temperature uses the Beta (B-parameter) equation:
//
//   1/T = 1/T0 + (1/Beta) * ln(Rntc / R0)
//
// Calibration defaults target the Sunbeam Barista Max / Oster Xpert stock
// probe: a 100 kOhm-at-25C M5 brass-tip NTC, Beta averaged across the OEM-
// equivalent 100k parts (see NtcCalibration below). Refine with a real 2-point
// (ice-bath / boiling-water) measurement and adjust r0/beta accordingly.
struct NtcCalibration {
    float r0Ohms = 100000.0f;      // nominal resistance at t0
    float t0Celsius = 25.0f;       // nominal temperature
    float betaK = 3962.5f;         // B25/85, averaged (3950..3975 -> 3962.5)
    float seriesResistorOhms = 100000.0f;
    float supplyMillivolts = 3300.0f;
    bool ntcOnHighSide = false;    // false: NTC to GND; true: NTC to Vsupply
};

class NtcThermistorSensor : public TemperatureSensor {
public:
    NtcThermistorSensor(uint8_t adcPin, const NtcCalibration& cal = NtcCalibration());

    void begin();

    float readCelsius() override;
    bool ok() const override;

    // Runtime override for the fitted parameters (from the calibration
    // workflow). T0 stays at the compiled value; only R0 and Beta move.
    void setCalibration(float r0Ohms, float betaK);
    float r0Ohms() const { return cal_.r0Ohms; }
    float betaK() const { return cal_.betaK; }

    // Last raw values, exposed for bring-up/telemetry and calibration.
    float lastResistanceOhms() const { return lastResistanceOhms_; }
    float lastMillivolts() const { return lastMillivolts_; }

private:
    uint8_t adcPin_;
    NtcCalibration cal_;
    bool ok_ = false;
    float lastTempC_ = 0.0f;
    float lastResistanceOhms_ = 0.0f;
    float lastMillivolts_ = 0.0f;
};

}  // namespace esp32esso::hal
