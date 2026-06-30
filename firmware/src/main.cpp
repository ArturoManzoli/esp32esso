#include <Arduino.h>

#include <cmath>

#include "control/temperature_loop.h"
#include "profile/machine_profile.h"

namespace {

constexpr uint32_t kTelemetryPeriodMs = 1000;

esp32esso::control::TemperatureLoop g_tempLoop;
uint32_t g_lastTelemetryMs = 0;

void printBanner(const esp32esso::profile::MachineProfile& profile) {
    Serial.println();
    Serial.println(F("esp32esso starting"));
    Serial.printf("build: %s %s\n", __DATE__, __TIME__);
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
    Serial.printf(
        "{\"t\":%lu,\"temp\":%.2f,\"sp\":%.2f,\"out\":%.3f,\"heat\":%d,\"fault\":\"%s\"}\n",
        static_cast<unsigned long>(nowMs),
        std::isnan(t) ? 0.0f : t,
        sp,
        out,
        heat,
        fault ? fault : "");
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
    g_tempLoop.tick(now);

    if (now - g_lastTelemetryMs >= kTelemetryPeriodMs) {
        g_lastTelemetryMs = now;
        emitTelemetry(now);
    }
    delay(10);
}
