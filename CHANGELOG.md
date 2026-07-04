# Changelog

All notable changes to Esp32esso are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project
aims to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
once it reaches 1.0.

## [Unreleased]

### Added

- Tier 2 dual-sensor cascade control: the stock 100 kΩ NTC thermistor stays on
  the thermoblock (inner/safety loop) and a thermocouple moves to the
  portafilter, becoming the setpoint reference; an outer proportional term drives
  the thermoblock hotter to overcome the ~20 cm transport loss. Phone-tunable
  cascade gain (0–10) with a bounded offset so a cold idle group cannot walk
  the thermoblock to the safety cap.
- `NtcThermistorSensor` HAL: reads the machine's stock NTC through an ADC1
  resistor divider (Beta model, 16× oversampling, open/short fault detection).
  Calibration defaults target the Sunbeam Barista Max / Oster Xpert OEM probe
  (100 kΩ, Beta averaged to 3962.5 K); selected via `ESP32ESSO_THERMOBLOCK_NTC`
  and the new `thermoblockNtc` ADC pin on both board maps.
- NTC calibration bench: firmware mode that characterises the thermoblock NTC
  against the thermocouple (the trusted reference) with a **target-temperature
  soak** — a proportional law drives the heater to each temperature rung
  (stepping up to `endC`), held until the thermocouple settles, so a clean
  `(T_tc, R_ntc)` pair is captured only when both probes read the same real
  temperature. This eliminates the ramp-lag hysteresis that skewed earlier fits
  and spreads the settled points evenly across the brew band. A
  dedicated 200 °C thermocouple hard-cut and a duty clamp guard low-mass tips.
  The firmware streams the continuous `cal,…` telemetry plus one `sample,…` row
  per settled rung. A line-based serial protocol (`ping`/`status`/`cal`/`ntc`/
  `heat`) and NVS-persisted, runtime-settable `R0`/`Beta` back it. New
  `tools/ntc-cal/` supervisor records runs, least-squares-fits `R0`/`Beta` from
  the settled points, pushes and persists them, and plots temperature-vs-time
  and before/after NTC error.
- Tier 2 shot timer: auto-start from a wired brew switch plus manual start/stop
  over BLE.
- Tier 2 BLE GATT revision (`protocol/ble/tier2.md`): 48-byte MachineState with
  dual temps, derived thermoblock setpoint, heater duty, shot time, and
  Tier 3/4 pressure/flow/weight stub lanes; new Gain, Settings (PID + steam),
  and BrewControl characteristics (NVS-persisted).
- `Max6675`/`Max31855` group sensor binding, a debounced `GpioDiscreteInput`,
  and second-CS + brew-switch pins on both the WROOM and S3 board maps.
- Android app Tier 2 upgrade: dual-temperature readout, cascade-gain slider,
  brew-target slider (capped at 110 °C), shot timer, a live Vico brew graph
  (temperature lanes plus reserved pressure/flow/weight), and a settings screen
  for the PID gains and steam target. Restyled with a dark, orange-accented,
  frosted-glass theme.
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
