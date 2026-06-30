# Changelog

All notable changes to Esp32esso are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project
aims to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
once it reaches 1.0.

## [Unreleased]

### Added

- GPL-3.0 license and Gaggiuino attribution in `CREDITS.md`.
- Project README with hero pitch, tiered install table, and safety warning.
- Contributing guide, code of conduct (Contributor Covenant 2.1), and
  combined security/safety policy.
- Repository tooling: `.gitignore`, `.editorconfig`, `.clang-format`.
- GitHub issue and PR templates plus the PlatformIO firmware CI workflow.
- PlatformIO ESP32-S3 firmware skeleton with a HAL interface and a
  machine-profile config struct.
- Tier 1 closed-loop PID temperature control with SSR output, a MAX31855
  thermocouple driver, and serial telemetry.
- First machine profile: Oster Xpert (initial reverse-engineering notes).
