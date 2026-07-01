# Changelog

All notable changes to Esp32esso are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project
aims to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
once it reaches 1.0.

## [Unreleased]

### Added

- Tier 1 BLE GATT contract (`protocol/ble/tier1.md`) and firmware service with
  live MachineState notifications and setpoint write (NVS-persisted).
- Minimal Tier 1 Android app (`app-android/`): scan/connect, live temp and
  heater state, setpoint slider.
- GPL-3.0 license and Gaggiuino attribution in `CREDITS.md`.
- Project README with hero pitch, tiered install table, and safety warning.
- Contributing guide, code of conduct (Contributor Covenant 2.1), and
  combined security/safety policy.
- Repository tooling: `.gitignore`, `.editorconfig`, `.clang-format`.
- GitHub issue and PR templates plus the PlatformIO firmware CI workflow.
- PlatformIO ESP32 firmware skeleton with a HAL interface and a
  machine-profile config struct.
- Tier 1 closed-loop PID temperature control with SSR output, a MAX31855
  thermocouple driver, and serial telemetry.
- First machine profile: Oster Xpert (initial reverse-engineering notes).
- Board abstraction layer so the same firmware builds for classic ESP32
  (WROOM) and ESP32-S3, with per-board GPIO maps. Classic ESP32 is the
  primary Tier 1 target; ESP32-S3 is recommended for Tier 2-4. Adds the
  `esp32-dev` and `esp32-oster-xpert` PlatformIO envs (`esp32-dev` is now
  the default).
