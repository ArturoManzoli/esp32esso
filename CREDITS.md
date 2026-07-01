# Credits and Acknowledgements

Esp32esso is an independent project. Its firmware, hardware design, and
companion app are written from scratch, but the project would not exist
without the prior art and community work of the people listed below.

## Inspiration

### Gaggiuino

The single largest source of inspiration for this project is
[Gaggiuino](https://gaggiuino.github.io/) by
[Zer0-bit](https://github.com/Zer0-bit/gaggiuino) and contributors.

Gaggiuino pioneered, popularised, and battle-tested most of the ideas that
Esp32esso builds on:

- closed-loop PID temperature control on consumer espresso machines
- zero-cross TRIAC dimming of vibration pumps for pressure and flow profiling
- integrated scales with stop-on-weight and predictive output
- shot-history, profile-sharing and web-based control of a brew

Esp32esso does **not** reuse Gaggiuino source code. Gaggiuino is licensed
under Creative Commons Attribution-NonCommercial 4.0 International (CC BY-NC
4.0), which is incompatible with this project's GPL-3.0 license. Anyone who
wants the original Gaggia-Classic-focused experience should install Gaggiuino
directly.

### Differences from Gaggiuino

| Area | Gaggiuino | Esp32esso |
| ---- | --------- | -------- |
| Control MCU | STM32F411 / STM32U585 + ESP32-S3 for UI | Single ESP32 (classic ESP32 for Tier 1; ESP32-S3 recommended for Tier 2-4) |
| Target machines | Gaggia Classic family (+ Rancilio Silvia) | Any pump espresso machine, via a machine-profile abstraction; first target is the Oster Xpert |
| UI strategy | Embedded screen + web | Optional embedded screen + native Android app over BLE |
| License | CC BY-NC 4.0 | GPL-3.0-or-later |
| Install model | One build path per machine | Tiered (Tier 1 temp -> Tier 4 full bar) so users can stop at any complexity level |

## Standing on the shoulders of

- the PlatformIO and Arduino-ESP32 maintainers
- the FreeRTOS authors at Real Time Engineers ltd / Amazon
- the maintainers of every open-source library this project ends up pulling
  in (each will be acknowledged in `firmware/lib/` or `platformio.ini` as it
  is added)

## Contributors

Once contributions start landing they will be listed here, generated from the
git history. For now: this is a solo bootstrap by
[@ArturoManzoli](https://github.com/ArturoManzoli).
