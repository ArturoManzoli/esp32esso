# Esp32esso firmware

ESP32-S3 firmware built with PlatformIO and the Arduino-ESP32 framework.

## Layout

```
src/
  main.cpp                Entry point: banner + scheduler hand-off
  hal/                    Hardware abstraction layer (interfaces only here)
  profile/                Machine profile struct + per-machine bindings
```

## Build environments

| Env | Purpose |
| --- | --- |
| `esp32-s3-dev` | Stub profile, no hardware. Used for CI builds and board bring-up. |

Per-machine environments (e.g. `esp32-s3-oster-xpert`) are added alongside
their machine profile under `src/profile/`.

## Building

```bash
pio run                      # build the default env (esp32-s3-dev)
pio run -e esp32-s3-dev      # explicit env
pio run -t upload            # flash a connected board
pio device monitor           # serial telemetry @ 115200
```

## Adding a new machine profile

1. Pick a slug (`<brand>-<model>` in kebab-case).
2. Add `src/profile/<slug>.cpp` that defines `activeProfile()` guarded by
   `#if defined(ESP32ESSO_PROFILE_<SLUG_UPPER>)`.
3. Add an env block to `platformio.ini` that defines that macro.
4. Document the wiring in `hardware/<slug>/` and `docs/machines/<slug>.md`.
