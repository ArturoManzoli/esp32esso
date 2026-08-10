#include "ble/tier1_gatt.h"

#include <cstring>

namespace esp32esso::ble {

FaultCode faultCodeFromReason(const char* reason) {
    if (reason == nullptr || reason[0] == '\0') {
        return FaultCode::kNone;
    }
    if (std::strcmp(reason, "sensor-fault") == 0) {
        return FaultCode::kSensorFault;
    }
    if (std::strcmp(reason, "overtemp") == 0) {
        return FaultCode::kOvertemp;
    }
    return FaultCode::kOther;
}

}  // namespace esp32esso::ble

#if defined(ESP32ESSO_TIER1_BLE)

#include "ble/tier1_service.h"

#include <Arduino.h>
#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <Preferences.h>

#include <cmath>

#include "control/temperature_loop.h"
#include "profile/machine_profile.h"

namespace esp32esso::ble {

namespace {

constexpr uint16_t kPreferredMtu = 517;

constexpr const char* kPrefsNamespace = "esp32esso";
constexpr const char* kPrefsSetpointKey = "setpoint";
constexpr const char* kPrefsThermoblockOffsetKey = "tbOffset";
constexpr const char* kPrefsKpKey = "kp";
constexpr const char* kPrefsKiKey = "ki";
constexpr const char* kPrefsKdKey = "kd";
constexpr const char* kPrefsSteamKey = "steam";
constexpr const char* kPrefsHeaterTimeoutKey = "heat_to";
constexpr const char* kPrefsPreinfusionKey = "preinf";
constexpr const char* kPrefsPumpRampKey = "pumpRamp";
constexpr const char* kPrefsThermoblockManualKey = "tbManual";
constexpr const char* kPrefsPressureManualKey = "pManual";
constexpr const char* kPrefsBrewProfileKey = "brewProf";

Tier1Service g_service;
BLECharacteristic* g_stateChar = nullptr;
control::TemperatureLoop* g_loop = nullptr;
volatile bool g_clientConnected = false;

uint8_t heaterDutyPct(float output) {
    if (std::isnan(output) || output <= 0.0f) return 0;
    if (output >= 1.0f) return 100;
    return static_cast<uint8_t>(output * 100.0f + 0.5f);
}

MachineStateWire packState(uint32_t nowMs) {
    MachineStateWire state{};
    state.group_temp_c = NAN;
    state.pressure_bar = NAN;
    state.flow_mls = NAN;
    state.weight_g = NAN;
    state.weight_left_g = NAN;
    state.weight_right_g = NAN;
    if (g_loop != nullptr) {
        state.thermoblock_temp_c = g_loop->lastTempCelsius();
        state.group_temp_c = g_loop->groupTempCelsius();
        state.group_setpoint_c = g_loop->setpointCelsius();
        state.thermoblock_setpoint_c = g_loop->thermoblockSetpointCelsius();
        state.pressure_bar = g_loop->pressureOk() ? g_loop->pressureBar() : NAN;
        state.thermoblock_offset_c = g_loop->thermoblockOffsetC();
        state.shot_time_ms = g_loop->shotElapsedMs(nowMs);
        state.heater_duty_pct = heaterDutyPct(g_loop->lastOutput());
        state.heater_on = g_loop->heaterOn() ? 1 : 0;
        state.fault_code = static_cast<uint8_t>(faultCodeFromReason(g_loop->faultReason()));
        state.tuning = g_loop->tuningActive() ? 1 : 0;
        state.brewing = g_loop->brewing() ? 1 : 0;
        state.brew_source = g_loop->brewSource();
        state.group_sensor_ok = g_loop->groupSensorOk() ? 1 : 0;
        state.heater_enabled = g_loop->heaterEnabled() ? 1 : 0;
        state.flushing = g_loop->flushing() ? 1 : 0;
        state.thermoblock_manual = g_loop->thermoblockManualActive() ? 1 : 0;
        state.pressure_manual = g_loop->pressureManualActive() ? 1 : 0;
        state.pressure_setpoint_bar = g_loop->pressureManualActive()
                                          ? g_loop->manualPressureBar()
                                          : NAN;
        state.profile_elapsed_sec = g_loop->brewProfileElapsedSec(nowMs);
        state.weight_left_g = g_loop->weightLeftGrams();
        state.weight_right_g = g_loop->weightRightGrams();
        state.weight_g = g_loop->weightTotalGrams();
    }
    state.uptime_ms = nowMs;
    return state;
}

void persistFloat(const char* key, float value) {
    Preferences prefs;
    if (!prefs.begin(kPrefsNamespace, /*readOnly=*/false)) {
        return;
    }
    prefs.putFloat(key, value);
    prefs.end();
}

float loadFloat(const char* key, float fallback) {
    Preferences prefs;
    if (!prefs.begin(kPrefsNamespace, /*readOnly=*/true)) {
        return fallback;
    }
    const float v = prefs.getFloat(key, fallback);
    prefs.end();
    return v;
}

void persistBytes(const char* key, const uint8_t* data, size_t len) {
    Preferences prefs;
    if (!prefs.begin(kPrefsNamespace, /*readOnly=*/false)) {
        return;
    }
    if (data == nullptr || len == 0) {
        prefs.remove(key);
    } else {
        prefs.putBytes(key, data, len);
    }
    prefs.end();
}

// Reads a persisted blob into `buf` (capacity `cap`); returns the byte count
// (0 if absent or too large to fit).
size_t loadBytes(const char* key, uint8_t* buf, size_t cap) {
    Preferences prefs;
    if (!prefs.begin(kPrefsNamespace, /*readOnly=*/true)) {
        return 0;
    }
    const size_t len = prefs.getBytesLength(key);
    size_t read = 0;
    if (len > 0 && len <= cap) {
        read = prefs.getBytes(key, buf, len);
    }
    prefs.end();
    return read;
}

bool readFloatArg(BLECharacteristic* characteristic, float* out) {
    if (characteristic == nullptr) return false;
    const std::string& value = characteristic->getValue();
    if (value.size() < sizeof(float)) return false;
    std::memcpy(out, value.data(), sizeof(float));
    return !std::isnan(*out);
}

void writeBackFloat(BLECharacteristic* characteristic, float v) {
    uint8_t bytes[sizeof(float)];
    std::memcpy(bytes, &v, sizeof(v));
    characteristic->setValue(bytes, sizeof(bytes));
}

class SetpointCallbacks : public BLECharacteristicCallbacks {
public:
    void onWrite(BLECharacteristic* characteristic) override {
        float requested = 0.0f;
        if (g_loop == nullptr || !readFloatArg(characteristic, &requested)) {
            return;
        }
        g_loop->setSetpointCelsius(requested);
        const float accepted = g_loop->setpointCelsius();
        persistFloat(kPrefsSetpointKey, accepted);
        writeBackFloat(characteristic, accepted);
    }
};

class ThermoblockOffsetCallbacks : public BLECharacteristicCallbacks {
public:
    void onWrite(BLECharacteristic* characteristic) override {
        float requested = 0.0f;
        if (g_loop == nullptr || !readFloatArg(characteristic, &requested)) {
            return;
        }
        g_loop->setThermoblockOffsetC(requested);
        const float accepted = g_loop->thermoblockOffsetC();
        persistFloat(kPrefsThermoblockOffsetKey, accepted);
        writeBackFloat(characteristic, accepted);
    }
};

class ThermoblockSetpointCallbacks : public BLECharacteristicCallbacks {
public:
    void onWrite(BLECharacteristic* characteristic) override {
        float requested = 0.0f;
        if (g_loop == nullptr || characteristic == nullptr) {
            return;
        }
        // Read the raw float without the NaN guard readFloatArg() applies, so a
        // NaN sentinel can also clear the override.
        const std::string& value = characteristic->getValue();
        if (value.size() < sizeof(float)) {
            return;
        }
        std::memcpy(&requested, value.data(), sizeof(float));
        g_loop->setManualThermoblockSetpointC(requested);
        const float accepted = g_loop->thermoblockManualActive()
                                   ? g_loop->manualThermoblockSetpointC()
                                   : 0.0f;
        persistFloat(kPrefsThermoblockManualKey, accepted);
        writeBackFloat(characteristic, accepted);
    }
};

class PressureSetpointCallbacks : public BLECharacteristicCallbacks {
public:
    void onWrite(BLECharacteristic* characteristic) override {
        float requested = 0.0f;
        if (g_loop == nullptr || characteristic == nullptr) {
            return;
        }
        // Raw read (no NaN guard) so a NaN sentinel can also clear the hold.
        const std::string& value = characteristic->getValue();
        if (value.size() < sizeof(float)) {
            return;
        }
        std::memcpy(&requested, value.data(), sizeof(float));
        g_loop->setManualPressureBar(requested);
        const float accepted =
            g_loop->pressureManualActive() ? g_loop->manualPressureBar() : 0.0f;
        persistFloat(kPrefsPressureManualKey, accepted);
        writeBackFloat(characteristic, accepted);
    }
};

// Any non-zero byte written arms a fixed-length grouphead flush. Writing 0
// cancels the flush early.
class FlushCallbacks : public BLECharacteristicCallbacks {
public:
    void onWrite(BLECharacteristic* characteristic) override {
        if (g_loop == nullptr || characteristic == nullptr) return;
        const std::string& value = characteristic->getValue();
        if (value.empty()) {
            Serial.println(F("# flush_char: write with empty payload -- ignoring"));
            return;
        }
        Serial.printf("# flush_char: write=0x%02X (len=%u)\n",
                      static_cast<unsigned>(value[0]) & 0xFFu,
                      static_cast<unsigned>(value.size()));
        if (value[0] != 0) {
            g_loop->startFlush(millis());
        } else {
            g_loop->stopFlush();
        }
    }
};

class ScaleTareCallbacks : public BLECharacteristicCallbacks {
public:
    void onWrite(BLECharacteristic* characteristic) override {
        if (g_loop == nullptr || characteristic == nullptr) return;
        const std::string& value = characteristic->getValue();
        if (value.empty() || value[0] == 0) {
            return;
        }
        Serial.printf("# scale_char: tare %s\n", g_loop->tareScale() ? "ok" : "failed");
    }
};

class SettingsCallbacks : public BLECharacteristicCallbacks {
public:
    void onWrite(BLECharacteristic* characteristic) override {
        if (g_loop == nullptr || characteristic == nullptr) {
            return;
        }
        const std::string& value = characteristic->getValue();
        if (value.size() < sizeof(SettingsWire)) {
            return;
        }
        SettingsWire s{};
        std::memcpy(&s, value.data(), sizeof(s));
        g_loop->setPidGains(s.pid_kp, s.pid_ki, s.pid_kd);
        g_loop->setSteamSetpointCelsius(s.steam_setpoint_c);
        g_loop->setHeaterTimeoutMinutes(s.heater_timeout_min);
        persistFloat(kPrefsKpKey, g_loop->pidKp());
        persistFloat(kPrefsKiKey, g_loop->pidKi());
        persistFloat(kPrefsKdKey, g_loop->pidKd());
        persistFloat(kPrefsSteamKey, g_loop->steamSetpointCelsius());
        persistFloat(kPrefsHeaterTimeoutKey, g_loop->heaterTimeoutMinutes());
        SettingsWire accepted{g_loop->pidKp(), g_loop->pidKi(), g_loop->pidKd(),
                              g_loop->steamSetpointCelsius(),
                              g_loop->heaterTimeoutMinutes()};
        uint8_t bytes[sizeof(SettingsWire)];
        std::memcpy(bytes, &accepted, sizeof(accepted));
        characteristic->setValue(bytes, sizeof(bytes));
    }
};

class BrewControlCallbacks : public BLECharacteristicCallbacks {
public:
    void onWrite(BLECharacteristic* characteristic) override {
        if (g_loop == nullptr || characteristic == nullptr) {
            return;
        }
        const std::string& value = characteristic->getValue();
        if (value.empty()) {
            return;
        }
        // 0 stops. 1 pulls a plain shot (pre-infusion + ramp, ignoring any
        // loaded profile); 2 runs the loaded profile. Any other non-zero value
        // starts a shot with the profile, matching the pre-v4 single-button
        // behaviour.
        const uint8_t cmd = static_cast<uint8_t>(value[0]);
        g_loop->setManualBrew(cmd != 0, /*useProfile=*/cmd != 1);
    }
};

class PreinfusionCallbacks : public BLECharacteristicCallbacks {
public:
    void onWrite(BLECharacteristic* characteristic) override {
        float requested = 0.0f;
        if (g_loop == nullptr || !readFloatArg(characteristic, &requested)) {
            return;
        }
        g_loop->setPreinfusionSeconds(requested);
        const float accepted = g_loop->preinfusionSeconds();
        persistFloat(kPrefsPreinfusionKey, accepted);
        writeBackFloat(characteristic, accepted);
    }
};

class PumpRampCallbacks : public BLECharacteristicCallbacks {
public:
    void onWrite(BLECharacteristic* characteristic) override {
        float requested = 0.0f;
        if (g_loop == nullptr || !readFloatArg(characteristic, &requested)) {
            return;
        }
        g_loop->setPumpRampSeconds(requested);
        const float accepted = g_loop->pumpRampSeconds();
        persistFloat(kPrefsPumpRampKey, accepted);
        writeBackFloat(characteristic, accepted);
    }
};

// Brew pressure/flow profile blob (see tier1_gatt.h / TemperatureLoop::
// setBrewProfile). The whole payload is handed to the loop, then persisted so
// the selected profile survives a reboot. A short/zero-phase payload clears it.
class BrewProfileCallbacks : public BLECharacteristicCallbacks {
public:
    void onWrite(BLECharacteristic* characteristic) override {
        if (g_loop == nullptr || characteristic == nullptr) {
            return;
        }
        const std::string& value = characteristic->getValue();
        const auto* data = reinterpret_cast<const uint8_t*>(value.data());
        g_loop->setBrewProfile(data, value.size());
        if (g_loop->brewProfileActive()) {
            persistBytes(kPrefsBrewProfileKey, data, value.size());
        } else {
            persistBytes(kPrefsBrewProfileKey, nullptr, 0);
        }
    }
};

class HeaterEnableCallbacks : public BLECharacteristicCallbacks {
public:
    void onWrite(BLECharacteristic* characteristic) override {
        if (g_loop == nullptr || characteristic == nullptr) {
            return;
        }
        const std::string& value = characteristic->getValue();
        if (value.empty()) {
            return;
        }
        g_loop->setHeaterEnabled(value[0] != 0);
    }
};

class ServerCallbacks : public BLEServerCallbacks {
public:
    void onConnect(BLEServer*) override { g_clientConnected = true; }
    void onDisconnect(BLEServer*) override {
        g_clientConnected = false;
        // The ESP32 BLE stack stops advertising on disconnect. Without
        // re-arming it the device is invisible and cannot be reconnected until
        // a hard reset -- so restart advertising for the next client.
        BLEDevice::startAdvertising();
    }
};

void pushStateNotify(uint32_t nowMs) {
    if (g_stateChar == nullptr || !g_clientConnected) {
        return;
    }
    const MachineStateWire state = packState(nowMs);
    uint8_t bytes[sizeof(MachineStateWire)];
    std::memcpy(bytes, &state, sizeof(state));
    g_stateChar->setValue(bytes, sizeof(bytes));
    g_stateChar->notify();
}

}  // namespace

Tier1Service& tier1Service() {
    return g_service;
}

void Tier1Service::begin(control::TemperatureLoop* loop,
                         const profile::MachineProfile* profile) {
    loop_ = loop;
    profile_ = profile;
    g_loop = loop;
    lastNotifyMs_ = 0;

    if (loop_ != nullptr && profile_ != nullptr) {
        const auto& thermal = profile_->thermal;
        loop_->setSetpointCelsius(loadFloat(kPrefsSetpointKey, thermal.defaultBrewTempC));
        loop_->setThermoblockOffsetC(
            loadFloat(kPrefsThermoblockOffsetKey, thermal.defaultThermoblockOffsetC));
        // Restore an absolute manual override if one was persisted (0 = none).
        // Loaded after the offset because setThermoblockOffsetC clears it.
        loop_->setManualThermoblockSetpointC(
            loadFloat(kPrefsThermoblockManualKey, 0.0f));
        loop_->setPidGains(loadFloat(kPrefsKpKey, thermal.pidKp),
                           loadFloat(kPrefsKiKey, thermal.pidKi),
                           loadFloat(kPrefsKdKey, thermal.pidKd));
        loop_->setSteamSetpointCelsius(loadFloat(kPrefsSteamKey, thermal.steamTempC));
        loop_->setHeaterTimeoutMinutes(
            loadFloat(kPrefsHeaterTimeoutKey, loop_->heaterTimeoutMinutes()));
        loop_->setPreinfusionSeconds(
            loadFloat(kPrefsPreinfusionKey, loop_->preinfusionSeconds()));
        loop_->setPumpRampSeconds(
            loadFloat(kPrefsPumpRampKey, loop_->pumpRampSeconds()));
        loop_->setManualPressureBar(loadFloat(kPrefsPressureManualKey, 0.0f));
        // Restore the selected brew profile (if any) from NVS.
        uint8_t profileBlob[kBrewProfileHeaderSize +
                            kBrewProfileMaxPhases * kBrewProfilePhaseSize];
        const size_t profileLen =
            loadBytes(kPrefsBrewProfileKey, profileBlob, sizeof(profileBlob));
        if (profileLen > 0) {
            loop_->setBrewProfile(profileBlob, profileLen);
        }
    }

    BLEDevice::init(kDeviceName);
    // The 48-byte MachineState notification exceeds the 23-byte default ATT
    // MTU. Raise the local MTU so the client's MTU exchange can negotiate a
    // size large enough to carry the whole payload in one notification.
    BLEDevice::setMTU(kPreferredMtu);
    BLEServer* server = BLEDevice::createServer();
    server->setCallbacks(new ServerCallbacks());

    // The default BLEService handle budget is 15, which counts declaration and
    // value handles per characteristic (2 each) plus any descriptor handles
    // (1 each) plus the service declaration itself. With ten characteristics
    // and one Client Characteristic Configuration Descriptor on machine-state,
    // that lands at 22 handles -- overflow silently drops the tail of the
    // GATT table (e.g. flush and the thermoblock setpoint), which then makes
    // writes from the app be treated as "characteristic not found" and vanish.
    // Bumping the budget with plenty of margin future-proofs adding another
    // handful of characteristics without having to remember to bump this again.
    BLEService* service = server->createService(BLEUUID(kServiceUuid), 32, 0);

    g_stateChar = service->createCharacteristic(
        kMachineStateUuid,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
    g_stateChar->addDescriptor(new BLE2902());

    BLECharacteristic* setpointChar = service->createCharacteristic(
        kSetpointUuid,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE);
    setpointChar->setCallbacks(new SetpointCallbacks());

    BLECharacteristic* thermoblockOffsetChar = service->createCharacteristic(
        kThermoblockOffsetUuid,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE);
    thermoblockOffsetChar->setCallbacks(new ThermoblockOffsetCallbacks());

    BLECharacteristic* settingsChar = service->createCharacteristic(
        kSettingsUuid,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE);
    settingsChar->setCallbacks(new SettingsCallbacks());

    BLECharacteristic* brewChar = service->createCharacteristic(
        kBrewControlUuid, BLECharacteristic::PROPERTY_WRITE);
    brewChar->setCallbacks(new BrewControlCallbacks());

    BLECharacteristic* heaterChar = service->createCharacteristic(
        kHeaterEnableUuid, BLECharacteristic::PROPERTY_WRITE);
    heaterChar->setCallbacks(new HeaterEnableCallbacks());

    BLECharacteristic* preinfusionChar = service->createCharacteristic(
        kPreinfusionUuid,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE);
    preinfusionChar->setCallbacks(new PreinfusionCallbacks());

    BLECharacteristic* flushChar = service->createCharacteristic(
        kFlushUuid, BLECharacteristic::PROPERTY_WRITE);
    flushChar->setCallbacks(new FlushCallbacks());

    BLECharacteristic* thermoblockSetpointChar = service->createCharacteristic(
        kThermoblockSetpointUuid,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE);
    thermoblockSetpointChar->setCallbacks(new ThermoblockSetpointCallbacks());

    BLECharacteristic* pumpRampChar = service->createCharacteristic(
        kPumpRampUuid,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE);
    pumpRampChar->setCallbacks(new PumpRampCallbacks());

    BLECharacteristic* brewProfileChar = service->createCharacteristic(
        kBrewProfileUuid,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE);
    brewProfileChar->setCallbacks(new BrewProfileCallbacks());

    BLECharacteristic* pressureSetpointChar = service->createCharacteristic(
        kPressureSetpointUuid,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE);
    pressureSetpointChar->setCallbacks(new PressureSetpointCallbacks());

    BLECharacteristic* scaleTareChar = service->createCharacteristic(
        kScaleTareUuid, BLECharacteristic::PROPERTY_WRITE);
    scaleTareChar->setCallbacks(new ScaleTareCallbacks());

    if (loop_ != nullptr) {
        writeBackFloat(setpointChar, loop_->setpointCelsius());
        writeBackFloat(thermoblockOffsetChar, loop_->thermoblockOffsetC());
        writeBackFloat(preinfusionChar, loop_->preinfusionSeconds());
        writeBackFloat(pumpRampChar, loop_->pumpRampSeconds());
        writeBackFloat(thermoblockSetpointChar,
                       loop_->thermoblockManualActive()
                           ? loop_->manualThermoblockSetpointC()
                           : 0.0f);
        writeBackFloat(pressureSetpointChar,
                       loop_->pressureManualActive() ? loop_->manualPressureBar()
                                                     : 0.0f);
        SettingsWire s{loop_->pidKp(), loop_->pidKi(), loop_->pidKd(),
                       loop_->steamSetpointCelsius(), loop_->heaterTimeoutMinutes()};
        uint8_t bytes[sizeof(SettingsWire)];
        std::memcpy(bytes, &s, sizeof(s));
        settingsChar->setValue(bytes, sizeof(bytes));
    }

    service->start();

    BLEAdvertising* advertising = BLEDevice::getAdvertising();
    advertising->addServiceUUID(kServiceUuid);
    advertising->setScanResponse(true);
    advertising->setMinPreferred(0x06);
    advertising->setMaxPreferred(0x12);
    BLEDevice::startAdvertising();
}

void Tier1Service::tick(uint32_t nowMs) {
    if (loop_ == nullptr || g_stateChar == nullptr) {
        return;
    }
    // Push fast enough for a smooth brew graph. 20 Hz while a shot or the tuning
    // bench is live is well within the BLE connection interval headroom (7.5-
    // 22.5 ms negotiated); idle stays slower to save power and radio airtime.
    const bool fast = loop_->tuningActive() || loop_->brewing();
    const uint32_t periodMs = fast ? 50u : 250u;
    if (lastNotifyMs_ != 0 && (nowMs - lastNotifyMs_) < periodMs) {
        return;
    }
    lastNotifyMs_ = nowMs;
    pushStateNotify(nowMs);
}

}  // namespace esp32esso::ble

#else  // !ESP32ESSO_TIER1_BLE

#include "ble/tier1_service.h"

namespace esp32esso::ble {

Tier1Service& tier1Service() {
    static Tier1Service stub;
    return stub;
}

void Tier1Service::begin(control::TemperatureLoop*, const profile::MachineProfile*) {}

void Tier1Service::tick(uint32_t) {}

}  // namespace esp32esso::ble

#endif  // ESP32ESSO_TIER1_BLE
