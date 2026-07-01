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

constexpr const char* kPrefsNamespace = "esp32esso";
constexpr const char* kPrefsSetpointKey = "setpoint";

Tier1Service g_service;
BLECharacteristic* g_stateChar = nullptr;
control::TemperatureLoop* g_loop = nullptr;

MachineStateWire packState(uint32_t nowMs) {
    MachineStateWire state{};
    if (g_loop != nullptr) {
        state.temp_c = g_loop->lastTempCelsius();
        state.setpoint_c = g_loop->setpointCelsius();
        state.heater_on = g_loop->heaterOn() ? 1 : 0;
        state.fault_code = static_cast<uint8_t>(faultCodeFromReason(g_loop->faultReason()));
        state.tuning = g_loop->tuningActive() ? 1 : 0;
    }
    state.uptime_ms = nowMs;
    return state;
}

void persistSetpoint(float c) {
    Preferences prefs;
    if (!prefs.begin(kPrefsNamespace, /*readOnly=*/false)) {
        return;
    }
    prefs.putFloat(kPrefsSetpointKey, c);
    prefs.end();
}

float loadStoredSetpoint(float fallbackC) {
    Preferences prefs;
    if (!prefs.begin(kPrefsNamespace, /*readOnly=*/true)) {
        return fallbackC;
    }
    const float sp = prefs.getFloat(kPrefsSetpointKey, fallbackC);
    prefs.end();
    return sp;
}

class SetpointCallbacks : public BLECharacteristicCallbacks {
public:
    void onWrite(BLECharacteristic* characteristic) override {
        if (g_loop == nullptr || characteristic == nullptr) {
            return;
        }
        const std::string& value = characteristic->getValue();
        if (value.size() < sizeof(float)) {
            return;
        }
        float requested = 0.0f;
        std::memcpy(&requested, value.data(), sizeof(float));
        if (std::isnan(requested)) {
            return;
        }
        g_loop->setSetpointCelsius(requested);
        const float accepted = g_loop->setpointCelsius();
        persistSetpoint(accepted);
        uint8_t bytes[sizeof(float)];
        std::memcpy(bytes, &accepted, sizeof(accepted));
        characteristic->setValue(bytes, sizeof(bytes));
    }
};

class ServerCallbacks : public BLEServerCallbacks {};

void pushStateNotify(uint32_t nowMs) {
    if (g_stateChar == nullptr) {
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
        loop_->setSetpointCelsius(loadStoredSetpoint(profile_->thermal.defaultBrewTempC));
    }

    BLEDevice::init(kDeviceName);
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
    if (loop_ != nullptr) {
        const float sp = loop_->setpointCelsius();
        uint8_t bytes[sizeof(float)];
        std::memcpy(bytes, &sp, sizeof(sp));
        setpointChar->setValue(bytes, sizeof(bytes));
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
    const uint32_t periodMs = loop_->tuningActive() ? 200u : 1000u;
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
