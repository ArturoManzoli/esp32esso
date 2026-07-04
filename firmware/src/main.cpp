#include <Arduino.h>
#include <Preferences.h>

#include <cmath>
#include <cstdlib>
#include <cstring>

#include "board/board_config.h"
#include "ble/tier1_service.h"
#include "control/temperature_loop.h"
#include "hal/ntc_thermistor_sensor.h"
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

// NTC calibration bench defaults (overridable from
// `cal start [endC] [stepC] [maxDuty%]`). The bench walks temperature targets
// from the first rung above the current temperature, stepping by `stepC` up to
// `endC`, holding each until the thermocouple settles before sampling.
constexpr float kCalEndDefaultC = 110.0f;
constexpr float kCalStepDefaultC = 20.0f;
constexpr float kCalDutyDefault = 0.60f;

// NVS store for the fitted NTC calibration (shared namespace with the BLE
// layer; distinct keys).
constexpr const char* kPrefsNamespace = "esp32esso";
constexpr const char* kPrefsNtcR0Key = "ntc_r0";
constexpr const char* kPrefsNtcBetaKey = "ntc_beta";

esp32esso::control::TemperatureLoop g_tempLoop;
uint32_t g_lastTelemetryMs = 0;
bool g_calWasActive = false;
char g_lineBuf[96];
size_t g_lineLen = 0;

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
#if defined(ESP32ESSO_TIER2) && defined(ESP32ESSO_THERMOBLOCK_NTC)
    const auto& pins = esp32esso::board::activeBoard().pins;
    Serial.printf("sensors: thermoblock=NTC GPIO %u, portafilter=MAX6675 CS GPIO %u "
                  "(SCK %u SO %u)\n",
                  pins.thermoblockNtc,
                  pins.thermocoupleCs,
                  pins.thermocoupleSck,
                  pins.thermocoupleMiso);
#elif defined(ESP32ESSO_TIER2)
    const auto& pins = esp32esso::board::activeBoard().pins;
    Serial.printf("sensors: thermoblock=TC CS GPIO %u, portafilter=TC CS GPIO %u "
                  "(SCK %u SO %u)\n",
                  pins.thermocoupleCs,
                  pins.thermocoupleCs2,
                  pins.thermocoupleSck,
                  pins.thermocoupleMiso);
#endif
}

void emitTelemetry(uint32_t nowMs) {
    const float tb = g_tempLoop.lastTempCelsius();
    const float grp = g_tempLoop.groupTempCelsius();
    const float sp = g_tempLoop.setpointCelsius();
    const float tbSp = g_tempLoop.thermoblockSetpointCelsius();
    const float out = g_tempLoop.lastOutput();
    const int heat = g_tempLoop.heaterOn() ? 1 : 0;
    const char* fault = g_tempLoop.faultReason();
    const int tune = g_tempLoop.tuningActive() ? 1 : 0;
    const int brew = g_tempLoop.brewing() ? 1 : 0;

    Serial.print(F("thermoblock: "));
    if (std::isnan(tb)) {
        Serial.print(F("--"));
    } else {
        Serial.printf("%.1f C", tb);
    }
    Serial.print(F("  portafilter: "));
    if (!g_tempLoop.groupSensorOk() || std::isnan(grp)) {
        Serial.print(F("-- (offline)"));
    } else {
        Serial.printf("%.1f C", grp);
    }
    const float delta = grp - tb;
    if (g_tempLoop.groupSensorOk() && !std::isnan(grp) && !std::isnan(tb)) {
        Serial.printf("  (delta %.1f C)", delta);
    }
    Serial.println();

    Serial.printf(
        "{\"t\":%lu,\"tb\":%.2f,\"grp\":%.2f,\"sp\":%.2f,\"tb_sp\":%.2f,\"gain\":%.1f,"
        "\"out\":%.3f,\"heat\":%d,\"brew\":%d,\"shot_ms\":%lu,\"relief\":%d,"
        "\"fault\":\"%s\",\"tune\":%d}\n",
        static_cast<unsigned long>(nowMs),
        std::isnan(tb) ? 0.0f : tb,
        std::isnan(grp) ? 0.0f : grp,
        sp,
        tbSp,
        g_tempLoop.gain(),
        out,
        heat,
        brew,
        static_cast<unsigned long>(g_tempLoop.shotElapsedMs(nowMs)),
        g_tempLoop.reliefValveOpen() ? 1 : 0,
        fault ? fault : "",
        tune);
}

void printHelp() {
    Serial.println(F("# commands (line-based, newline-terminated):"));
    Serial.println(F("#   ping                      -> pong (liveness)"));
    Serial.println(F("#   status                    -> one JSON status line"));
    Serial.println(F("#   help | h | ?              -> this help"));
    Serial.println(F("#   clear | c                 -> clear latched fault"));
    Serial.println(F("#   stop | q                  -> stop calibration/tuning"));
    Serial.println(F("#   heat <duty%> <s> <abortC> -> open-loop step (alias: s = 50/120/140)"));
    Serial.println(F("#   cal start [endC] [stepC] [maxDuty%] -> NTC soak calibration vs TC"));
    Serial.println(F("#   cal stop                  -> abort calibration"));
    Serial.println(F("#   ntc show                  -> print R0/Beta"));
    Serial.println(F("#   ntc set <r0> <beta>       -> apply fitted values live"));
    Serial.println(F("#   ntc save                  -> persist R0/Beta to NVS"));
}

bool loadNtcCalibration() {
    auto* ntc = esp32esso::profile::thermoblockNtcSensor();
    if (ntc == nullptr) {
        return false;
    }
    Preferences prefs;
    if (!prefs.begin(kPrefsNamespace, /*readOnly=*/true)) {
        return false;
    }
    const float r0 = prefs.getFloat(kPrefsNtcR0Key, NAN);
    const float beta = prefs.getFloat(kPrefsNtcBetaKey, NAN);
    prefs.end();
    if (std::isnan(r0) && std::isnan(beta)) {
        return false;
    }
    ntc->setCalibration(std::isnan(r0) ? ntc->r0Ohms() : r0,
                        std::isnan(beta) ? ntc->betaK() : beta);
    return true;
}

bool saveNtcCalibration() {
    auto* ntc = esp32esso::profile::thermoblockNtcSensor();
    if (ntc == nullptr) {
        return false;
    }
    Preferences prefs;
    if (!prefs.begin(kPrefsNamespace, /*readOnly=*/false)) {
        return false;
    }
    prefs.putFloat(kPrefsNtcR0Key, ntc->r0Ohms());
    prefs.putFloat(kPrefsNtcBetaKey, ntc->betaK());
    prefs.end();
    return true;
}

void printNtcCalibration() {
    auto* ntc = esp32esso::profile::thermoblockNtcSensor();
    if (ntc == nullptr) {
        Serial.println(F("# ntc: no NTC on this build"));
        return;
    }
    Serial.printf("# ntc r0=%.1f beta=%.1f\n", ntc->r0Ohms(), ntc->betaK());
}

void printStatus(uint32_t nowMs) {
    auto* ntc = esp32esso::profile::thermoblockNtcSensor();
    Serial.printf(
        "{\"t\":%lu,\"tb\":%.2f,\"grp\":%.2f,\"grp_ok\":%d,\"cal\":%d,\"cal_phase\":%u,"
        "\"tune\":%d,\"fault\":\"%s\",\"ntc_r0\":%.1f,\"ntc_beta\":%.1f}\n",
        static_cast<unsigned long>(nowMs),
        g_tempLoop.lastTempCelsius(),
        g_tempLoop.groupTempCelsius(),
        g_tempLoop.groupSensorOk() ? 1 : 0,
        g_tempLoop.calibrating() ? 1 : 0,
        g_tempLoop.calPhase(),
        g_tempLoop.tuningActive() ? 1 : 0,
        g_tempLoop.faultReason() ? g_tempLoop.faultReason() : "",
        ntc ? ntc->r0Ohms() : 0.0f,
        ntc ? ntc->betaK() : 0.0f);
}

void processLine(char* line, uint32_t nowMs) {
    char* tok = std::strtok(line, " \t");
    if (tok == nullptr) {
        return;
    }

    if (!std::strcmp(tok, "h") || !std::strcmp(tok, "help") || !std::strcmp(tok, "?")) {
        printHelp();
    } else if (!std::strcmp(tok, "ping")) {
        Serial.println(F("# pong"));
    } else if (!std::strcmp(tok, "status")) {
        printStatus(nowMs);
    } else if (!std::strcmp(tok, "c") || !std::strcmp(tok, "clear")) {
        g_tempLoop.clearFault();
        Serial.println(F("# fault: cleared"));
    } else if (!std::strcmp(tok, "q") || !std::strcmp(tok, "stop")) {
        if (g_tempLoop.calibrating()) {
            g_tempLoop.stopCalibration();
            Serial.println(F("# cal: stopped by operator"));
        } else if (g_tempLoop.tuningActive()) {
            g_tempLoop.stopTuning();
            Serial.println(F("# tuning: stopped by operator"));
        }
    } else if (!std::strcmp(tok, "s")) {
        g_tempLoop.startTuning(nowMs, kBenchDuty, kBenchDurationMs, kBenchAbortTempC);
        Serial.println(g_tempLoop.tuningActive()
                           ? F("# tuning: started 50% / 120s / abort 140C")
                           : F("# tuning: refused (faulted or profile incomplete)"));
    } else if (!std::strcmp(tok, "heat")) {
        const char* a = std::strtok(nullptr, " \t");
        const char* b = std::strtok(nullptr, " \t");
        const char* c = std::strtok(nullptr, " \t");
        const float dutyPct = a ? std::atof(a) : kBenchDuty * 100.0f;
        const uint32_t secs = b ? static_cast<uint32_t>(std::atol(b)) : kBenchDurationMs / 1000UL;
        const float abortC = c ? std::atof(c) : kBenchAbortTempC;
        g_tempLoop.startTuning(nowMs, dutyPct / 100.0f, secs * 1000UL, abortC);
        if (g_tempLoop.tuningActive()) {
            Serial.printf("# tuning: started duty=%.0f%% duration=%lus abort=%.1fC\n",
                          dutyPct, static_cast<unsigned long>(secs), abortC);
        } else {
            Serial.println(F("# tuning: refused (faulted or profile incomplete)"));
        }
    } else if (!std::strcmp(tok, "cal")) {
        const char* sub = std::strtok(nullptr, " \t");
        if (sub && !std::strcmp(sub, "start")) {
            const char* a = std::strtok(nullptr, " \t");
            const char* b = std::strtok(nullptr, " \t");
            const char* c = std::strtok(nullptr, " \t");
            const float endC = a ? std::atof(a) : kCalEndDefaultC;
            const float stepC = b ? std::atof(b) : kCalStepDefaultC;
            const float duty = c ? std::atof(c) / 100.0f : kCalDutyDefault;
            g_tempLoop.startCalibration(nowMs, endC, stepC, duty);
            if (g_tempLoop.calibrating()) {
                auto* ntc = esp32esso::profile::thermoblockNtcSensor();
                Serial.printf(
                    "# cal_begin soak endC=%.0f stepC=%.0f maxDuty=%.0f%% r0=%.1f beta=%.1f\n",
                    endC, stepC, duty * 100.0f,
                    ntc ? ntc->r0Ohms() : 0.0f, ntc ? ntc->betaK() : 0.0f);
                Serial.println(F("# cal_cols: t_ms,phase,tc_c,ntc_c,r_ntc_ohm,mv,duty,sp_c"));
                Serial.println(F("# cal_sample_cols: t_ms,step,duty,tc_c,ntc_c,r_ntc_ohm,mv"));
            } else {
                Serial.println(F("# cal: refused (need TC ok, no fault, not tuning)"));
            }
        } else if (sub && !std::strcmp(sub, "stop")) {
            g_tempLoop.stopCalibration();
            Serial.println(F("# cal: stopped by operator"));
        } else {
            Serial.println(F("# cal: expected 'start' or 'stop'"));
        }
    } else if (!std::strcmp(tok, "ntc")) {
        const char* sub = std::strtok(nullptr, " \t");
        if (sub && !std::strcmp(sub, "show")) {
            printNtcCalibration();
        } else if (sub && !std::strcmp(sub, "set")) {
            const char* a = std::strtok(nullptr, " \t");
            const char* b = std::strtok(nullptr, " \t");
            auto* ntc = esp32esso::profile::thermoblockNtcSensor();
            if (ntc == nullptr) {
                Serial.println(F("# ntc: no NTC on this build"));
            } else if (a == nullptr || b == nullptr) {
                Serial.println(F("# ntc set: expected <r0> <beta>"));
            } else {
                ntc->setCalibration(std::atof(a), std::atof(b));
                printNtcCalibration();
            }
        } else if (sub && !std::strcmp(sub, "save")) {
            Serial.println(saveNtcCalibration() ? F("# ntc: saved to NVS")
                                                 : F("# ntc: save failed"));
        } else {
            Serial.println(F("# ntc: expected 'show', 'set <r0> <beta>' or 'save'"));
        }
    } else {
        Serial.printf("# unknown command '%s' (try 'help')\n", tok);
    }
}

void handleSerial(uint32_t nowMs) {
    while (Serial.available() > 0) {
        const int c = Serial.read();
        if (c == '\r' || c == '\n') {
            if (g_lineLen > 0) {
                g_lineBuf[g_lineLen] = '\0';
                processLine(g_lineBuf, nowMs);
                g_lineLen = 0;
            }
        } else if (g_lineLen < sizeof(g_lineBuf) - 1) {
            g_lineBuf[g_lineLen++] = static_cast<char>(c);
        } else {
            // Overflow: reset the buffer and drop the garbled line.
            g_lineLen = 0;
        }
    }
}

void emitCalSample(uint32_t nowMs) {
    auto* ntc = esp32esso::profile::thermoblockNtcSensor();
    const float tc = g_tempLoop.groupSensorOk() ? g_tempLoop.groupTempCelsius() : NAN;
    const float ntcC = g_tempLoop.lastTempCelsius();
    const float r = ntc ? ntc->lastResistanceOhms() : NAN;
    const float mv = ntc ? ntc->lastMillivolts() : NAN;
    const char* phase = g_tempLoop.calPhase() == 1 ? "soak" : "done";
    Serial.printf("cal,%lu,%s,%.2f,%.2f,%.1f,%.1f,%.3f,%.2f\n",
                  static_cast<unsigned long>(nowMs),
                  phase,
                  tc,
                  ntcC,
                  r,
                  mv,
                  g_tempLoop.lastOutput(),
                  g_tempLoop.calSetpointCelsius());
}

// One clean settled point per rung -- this is what the supervisor fits on.
void emitCalSettledSample(uint32_t nowMs) {
    auto* ntc = esp32esso::profile::thermoblockNtcSensor();
    const float tc = g_tempLoop.groupSensorOk() ? g_tempLoop.groupTempCelsius() : NAN;
    const float ntcC = g_tempLoop.lastTempCelsius();
    const float r = ntc ? ntc->lastResistanceOhms() : NAN;
    const float mv = ntc ? ntc->lastMillivolts() : NAN;
    Serial.printf("sample,%lu,%u,%.3f,%.2f,%.2f,%.1f,%.1f\n",
                  static_cast<unsigned long>(nowMs),
                  static_cast<unsigned>(g_tempLoop.calSampleStep()),
                  g_tempLoop.calSampleDuty(),
                  tc,
                  ntcC,
                  r,
                  mv);
}

}  // namespace

void setup() {
    Serial.begin(115200);
    delay(100);

    const auto& profile = esp32esso::profile::activeProfile();
    printBanner(profile);
    esp32esso::profile::initActiveProfilePeripherals();
    if (loadNtcCalibration()) {
        Serial.print(F("ntc: loaded calibration from NVS -> "));
        printNtcCalibration();
    }
    g_tempLoop.begin(profile);
    esp32esso::ble::tier1Service().begin(&g_tempLoop, &profile);
}

void loop() {
    const uint32_t now = millis();
    handleSerial(now);
    g_tempLoop.tick(now);
    esp32esso::ble::tier1Service().tick(now);

    // Drain the settled-sample latch before the cal_end check so the final
    // rung's point is emitted even though stopCalibration() already fired.
    if (g_tempLoop.takeCalSample()) {
        emitCalSettledSample(now);
    }

    const bool calActive = g_tempLoop.calibrating();
    if (g_calWasActive && !calActive) {
        // Calibration just ended (completed, aborted, or faulted).
        const char* reason = g_tempLoop.faulted() ? g_tempLoop.faultReason() : "done";
        Serial.printf("# cal_end reason=%s\n", reason ? reason : "done");
    }
    g_calWasActive = calActive;

    const bool fast = calActive || g_tempLoop.tuningActive();
    const uint32_t period = fast ? kTelemetryPeriodTuningMs : kTelemetryPeriodIdleMs;
    if (now - g_lastTelemetryMs >= period) {
        g_lastTelemetryMs = now;
        if (calActive) {
            emitCalSample(now);  // clean CSV stream for the supervisor
        } else {
            emitTelemetry(now);
        }
    }
    delay(10);
}
