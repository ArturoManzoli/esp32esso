# Esp32esso roadmap

The project is split into four sequential stages. Each stage delivers a
visible user-facing outcome **and** unlocks the next stage's hardware tier.
Stages are not strict gates - a contributor can do Stage 3 work in parallel
- but the install instructions advance tier by tier.

## Stage 1 - Foundation + Tier 1 (Oster Xpert temperature)

**Goal**: hold brew temperature within ~1 C of setpoint on the Oster Xpert.

- repository bootstrap, license, docs, CI (DONE)
- PlatformIO ESP32-S3 skeleton + HAL + machine-profile struct (DONE)
- closed-loop PID temperature control with SSR + MAX31855 (DONE)
- first concrete machine profile: Oster Xpert (DONE)
- step-response data + PID tuning on real hardware (in progress on bench)
- Tier 1 install guide and BOM (`hardware/oster-xpert/`)

## Stage 2 - BLE + Android app MVP + Tier 2

**Goal**: monitor the machine and set the brew temperature from a phone,
without WiFi.

- define the BLE GATT contract in `protocol/` (one source of truth)
- firmware: BLE peripheral service on core 1, telemetry + setpoint write
- firmware: pressure-transducer driver + auto shot timer (brew-switch sense)
- Android app (Kotlin / Jetpack Compose): scan, connect, live charts,
  set target temp, start/stop
- second machine profile to validate the abstraction layer

## Stage 3 - Pressure / flow profiling + Tier 3

**Goal**: profiled shots with arbitrary pressure or flow curves, edited
from the phone.

- zero-cross detector + TRIAC dimmer pump driver on a dedicated FreeRTOS
  task pinned to core 0
- profile data model (phases, transitions, limits) shared by firmware +
  app via the GATT contract
- live shot graphing and profile editor in the Android app

## Stage 4 - Ecosystem + Tier 4

**Goal**: stop-on-weight, OTA, profile sharing, multi-machine library.

- load-cell scales (HX711) with stop-on-weight and predictive yield
- ToF water-tank level sensor
- OTA updates from the Android app, signed binaries
- profile import/export and a small profile-sharing index
- grow the machine-profile library beyond Oster, with per-tier BOM and
  install docs for each
- optional embedded display (parallel to the phone, not replacing it)
- docs site (docsify or mkdocs), release tagging, contribution tooling
