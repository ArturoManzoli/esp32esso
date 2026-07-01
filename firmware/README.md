# Esp32esso firmware

ESP32 firmware built with PlatformIO and the Arduino-ESP32 framework. It
targets both the classic ESP32 (WROOM) and the ESP32-S3 from the same source;
the board only sets the chip and GPIO map.

## Layout

```
src/
  main.cpp                Entry point: banner + scheduler hand-off
  board/                  Board config: chip + GPIO map, selected per env
  hal/                    Hardware abstraction layer (interfaces only here)
  profile/                Machine profile struct + per-machine bindings
```

## Build environments

Each env is a `(board, machine-profile)` pair. The board sets the chip +
GPIO map (`ESP32ESSO_BOARD_*`); the profile sets the machine config
(`ESP32ESSO_PROFILE_*`). Naming: `esp32-*` = classic ESP32, `esp32-s3-*` =
ESP32-S3.

| Env | Board | Purpose |
| --- | ----- | --- |
| `esp32-dev` | ESP32-WROOM | Stub profile, no hardware. Default env; CI + board bring-up. |
| `esp32-oster-xpert` | ESP32-WROOM | Oster Xpert on a classic ESP32 (primary target). |
| `esp32-s3-dev` | ESP32-S3 | Stub profile on the S3. |
| `esp32-s3-oster-xpert` | ESP32-S3 | Oster Xpert on the S3 (recommended for Tier 2-4). |

## Building

```bash
pio run                        # build the default env (esp32-dev)
pio run -e esp32-oster-xpert   # classic ESP32-WROOM + Oster profile
pio run -e esp32-s3-oster-xpert # ESP32-S3 + Oster profile
pio run -t upload              # flash a connected board (default env)
pio device monitor             # serial telemetry @ 115200
```

## Adding a new board

1. Add `src/board/<board>.cpp` that defines `activeBoard()` guarded by
   `#if defined(ESP32ESSO_BOARD_<NAME>)`, returning a `BoardConfig` with the
   chip id and a GPIO map safe for that chip.
2. Add env block(s) to `platformio.ini` that set `ESP32ESSO_BOARD_<NAME>`.

## Adding a new machine profile

1. Pick a slug (`<brand>-<model>` in kebab-case).
2. Add `src/profile/<slug>.cpp` that defines `activeProfile()` guarded by
   `#if defined(ESP32ESSO_PROFILE_<SLUG_UPPER>)` and reads its pins from
   `board::activeBoard()` rather than hard-coding them.
3. Add env block(s) to `platformio.ini` (one per supported board) that
   define that macro.
4. Document the wiring in `hardware/<slug>/` and `docs/machines/<slug>.md`.
