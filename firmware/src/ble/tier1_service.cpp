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
constexpr const char* kPrefsGainKey = "gain";
constexpr const char* kPrefsKpKey = "kp";
constexpr const char* kPrefsKiKey = "ki";
constexpr const char* kPrefsKdKey = "kd";
constexpr const char* kPrefsSteamKey = "steam";

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
    if (g_loop != nullptr) {
        state.thermoblock_temp_c = g_loop->lastTempCelsius();
        state.group_temp_c = g_loop->groupTempCelsius();
        state.group_setpoint_c = g_loop->setpointCelsius();
        state.thermoblock_setpoint_c = g_loop->thermoblockSetpointCelsius();
        state.gain = g_loop->gain();
        state.shot_time_ms = g_loop->shotElapsedMs(nowMs);
        state.heater_duty_pct = heaterDutyPct(g_loop->lastOutput());
        state.heater_on = g_loop->heaterOn() ? 1 : 0;
        state.fault_code = static_cast<uint8_t>(faultCodeFromReason(g_loop->faultReason()));
        state.tuning = g_loop->tuningActive() ? 1 : 0;
        state.brewing = g_loop->brewing() ? 1 : 0;
        state.brew_source = g_loop->brewSource();
        state.group_sensor_ok = g_loop->groupSensorOk() ? 1 : 0;
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

class GainCallbacks : public BLECharacteristicCallbacks {
public:
    void onWrite(BLECharacteristic* characteristic) override {
        float requested = 0.0f;
        if (g_loop == nullptr || !readFloatArg(characteristic, &requested)) {
            return;
        }
        g_loop->setGain(requested);
        const float accepted = g_loop->gain();
        persistFloat(kPrefsGainKey, accepted);
        writeBackFloat(characteristic, accepted);
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
        persistFloat(kPrefsKpKey, g_loop->pidKp());
        persistFloat(kPrefsKiKey, g_loop->pidKi());
        persistFloat(kPrefsKdKey, g_loop->pidKd());
        persistFloat(kPrefsSteamKey, g_loop->steamSetpointCelsius());
        SettingsWire accepted{g_loop->pidKp(), g_loop->pidKi(), g_loop->pidKd(),
                              g_loop->steamSetpointCelsius()};
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
        g_loop->setManualBrew(value[0] != 0);
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
        loop_->setGain(loadFloat(kPrefsGainKey, thermal.defaultCascadeGain));
        loop_->setPidGains(loadFloat(kPrefsKpKey, thermal.pidKp),
                           loadFloat(kPrefsKiKey, thermal.pidKi),
                           loadFloat(kPrefsKdKey, thermal.pidKd));
        loop_->setSteamSetpointCelsius(loadFloat(kPrefsSteamKey, thermal.steamTempC));
    }

    BLEDevice::init(kDeviceName);
    // The 48-byte MachineState notification exceeds the 23-byte default ATT
    // MTU. Raise the local MTU so the client's MTU exchange can negotiate a
    // size large enough to carry the whole payload in one notification.
    BLEDevice::setMTU(kPreferredMtu);
    BLEServer* server = BLEDevice::createServer();
    server->setCallbacks(new ServerCallbacks());

    BLEService* service = server->createService(kServiceUuid);

    g_stateChar = service->createCharacteristic(
        kMachineStateUuid,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
    g_stateChar->addDescriptor(new BLE2902());

    BLECharacteristic* setpointChar = service->createCharacteristic(
        kSetpointUuid,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE);
    setpointChar->setCallbacks(new SetpointCallbacks());

    BLECharacteristic* gainChar = service->createCharacteristic(
        kGainUuid,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE);
    gainChar->setCallbacks(new GainCallbacks());

    BLECharacteristic* settingsChar = service->createCharacteristic(
        kSettingsUuid,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE);
    settingsChar->setCallbacks(new SettingsCallbacks());

    BLECharacteristic* brewChar = service->createCharacteristic(
        kBrewControlUuid, BLECharacteristic::PROPERTY_WRITE);
    brewChar->setCallbacks(new BrewControlCallbacks());

    if (loop_ != nullptr) {
        writeBackFloat(setpointChar, loop_->setpointCelsius());
        writeBackFloat(gainChar, loop_->gain());
        SettingsWire s{loop_->pidKp(), loop_->pidKi(), loop_->pidKd(),
                       loop_->steamSetpointCelsius()};
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
    const bool fast = loop_->tuningActive() || loop_->brewing();
    const uint32_t periodMs = fast ? 200u : 1000u;
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
