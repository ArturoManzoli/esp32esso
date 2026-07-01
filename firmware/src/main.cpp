#include <Arduino.h>

#include <cmath>

#include "board/board_config.h"
#include "control/temperature_loop.h"
#include "profile/machine_profile.h"

namespace {

constexpr uint32_t kTelemetryPeriodIdleMs = 1000;
constexpr uint32_t kTelemetryPeriodTuningMs = 200;

// Defaults for the open-loop step-response bench, balanced for a low-mass
// thermoblock: enough signal to fit FOPDT, ~20 C headroom before the hard
// safety cut at the profile's maxSafeTempC.
constexpr float kBenchDuty = 0.50f;
constexpr uint32_t kBenchDurationMs = 120UL * 1000UL;
constexpr float kBenchAbortTempC = 140.0f;

esp32esso::control::TemperatureLoop g_tempLoop;
uint32_t g_lastTelemetryMs = 0;

void printBanner(const esp32esso::profile::MachineProfile& profile) {
    const auto& board = esp32esso::board::activeBoard();
    Serial.println();
    Serial.println(F("esp32esso starting"));
    Serial.printf("build: %s %s\n", __DATE__, __TIME__);
    Serial.printf("board: %s (%s)%s\n",
                  board.info.id,
                  board.info.chip,
                  board.info.recommendedForHigherTiers ? ""
                                                       : " [Tier 1 target]");
    Serial.printf("profile: %s %s (%s)\n",
                  profile.metadata.brand,
                  profile.metadata.model,
                  profile.metadata.voltageRegion);
    if (profile.metadata.notes && profile.metadata.notes[0] != '\0') {
        Serial.printf("notes: %s\n", profile.metadata.notes);
    }
    Serial.printf("thermal: default=%.1fC max=%.1fC kp=%.3f ki=%.3f kd=%.3f window=%lums\n",
                  profile.thermal.defaultBrewTempC,
                  profile.thermal.maxSafeTempC,
                  profile.thermal.pidKp,
                  profile.thermal.pidKi,
                  profile.thermal.pidKd,
                  static_cast<unsigned long>(profile.thermal.pwmWindowMs));
}

void emitTelemetry(uint32_t nowMs) {
    const float t = g_tempLoop.lastTempCelsius();
    const float sp = g_tempLoop.setpointCelsius();
    const float out = g_tempLoop.lastOutput();
    const int heat = g_tempLoop.heaterOn() ? 1 : 0;
    const char* fault = g_tempLoop.faultReason();
    const int tune = g_tempLoop.tuningActive() ? 1 : 0;
    Serial.printf(
        "{\"t\":%lu,\"temp\":%.2f,\"sp\":%.2f,\"out\":%.3f,\"heat\":%d,\"fault\":\"%s\",\"tune\":%d}\n",
        static_cast<unsigned long>(nowMs),
        std::isnan(t) ? 0.0f : t,
        sp,
        out,
        heat,
        fault ? fault : "",
        tune);
}

void printHelp() {
    Serial.println(F("# commands:"));
    Serial.println(F("#   s -> start open-loop step bench (50% / 120s / abort 140C)"));
    Serial.println(F("#   q -> stop tuning"));
    Serial.println(F("#   c -> clear latched fault"));
    Serial.println(F("#   h -> this help"));
}

void handleSerial(uint32_t nowMs) {
    while (Serial.available() > 0) {
        const int c = Serial.read();
        switch (c) {
            case 's':
                g_tempLoop.startTuning(nowMs, kBenchDuty, kBenchDurationMs, kBenchAbortTempC);
                if (g_tempLoop.tuningActive()) {
                    Serial.printf(
                        "# tuning: started duty=%.0f%% duration=%lus abort=%.1fC\n",
                        kBenchDuty * 100.0f,
                        static_cast<unsigned long>(kBenchDurationMs / 1000UL),
                        kBenchAbortTempC);
                } else {
                    Serial.println(F("# tuning: refused (faulted or profile incomplete)"));
                }
                break;
            case 'q':
                if (g_tempLoop.tuningActive()) {
                    g_tempLoop.stopTuning();
                    Serial.println(F("# tuning: stopped by operator"));
                }
                break;
            case 'c':
                g_tempLoop.clearFault();
                Serial.println(F("# fault: cleared"));
                break;
            case 'h':
            case '?':
                printHelp();
                break;
            case '\r':
            case '\n':
            case ' ':
                break;
            default:
                Serial.printf("# unknown command '%c' (try 'h')\n", c);
                break;
        }
    }
}

}  // namespace

void setup() {
    Serial.begin(115200);
    delay(100);

    const auto& profile = esp32esso::profile::activeProfile();
    printBanner(profile);
    esp32esso::profile::initActiveProfilePeripherals();
    g_tempLoop.begin(profile);
}

void loop() {
    const uint32_t now = millis();
    handleSerial(now);
    g_tempLoop.tick(now);

    const uint32_t period =
        g_tempLoop.tuningActive() ? kTelemetryPeriodTuningMs : kTelemetryPeriodIdleMs;
    if (now - g_lastTelemetryMs >= period) {
        g_lastTelemetryMs = now;
        emitTelemetry(now);
    }
    delay(10);
}
