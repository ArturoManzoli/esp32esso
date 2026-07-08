# Oster Xpert - hardware

Reverse-engineering and install notes for the Oster Xpert (Brazilian
220-240 V variant). See [`docs/machines/oster-xpert.md`](../../docs/machines/oster-xpert.md)
for what is known about the machine itself and the tuning procedure.

## Tier 1 - Temperature brain

Drives the existing thermoblock with closed-loop PID via an SSR, reading
the thermoblock temperature with a K-type thermocouple and a MAX6675 amp
(WROOM build) or MAX31855 (S3 build).

### BOM (machine-specific additions)

| Qty | Part | Notes |
| --- | ---- | ----- |
| 1 | Stainless steel hose clamp, ~25 mm | Secures the thermocouple bead to the thermoblock |
| 1 | High-temp double-shrink heatshrink | Insulates the thermocouple junction at the contact point |
| 1 | Spade connector kit (6.3 mm and 4.8 mm female) | Mates the SSR into the stock thermostat-load wiring without cutting it |
| ~ | 18 AWG silicone wire (red / black) | Heater-side wiring, rated for the ambient temperature inside the machine |

The shared Tier 1 BOM (ESP32 board, thermocouple amp, SSR, buck, etc.) lives in the
hardware index at [`../README.md`](../README.md).

### Wiring

The detailed wiring change is documented in
[`tier-1-wiring.md`](tier-1-wiring.md).

## Tier 2 - Group-referenced temperature

Adds a **thermocouple at the portafilter/group** so the setpoint targets the cup
temperature while the thermoblock is driven hotter to overcome the transport
loss. The thermoblock keeps its own inner/safety probe, provided two ways — the
recommended one being a second thermocouple. Optionally taps the brew switch for
the auto shot timer.

- **Recommended (default `esp32-oster-xpert` / `esp32-s3-oster-xpert`):** a
  **second thermocouple identical to the group one** on the thermoblock. The
  group amp stays on the primary `CS` (GPIO 21 WROOM / GPIO 10 S3) and the new
  thermoblock amp shares the same `SCK`/`SO` bus on its own `CS2` (GPIO 5 WROOM /
  GPIO 11 S3). Linear, cold-junction-compensated, no calibration soak, less drift.
- **Alternative (`-ntc` build):** reuse the machine's **stock NTC thermistor** on
  the thermoblock via an ADC divider, keeping the single group thermocouple on the
  primary `CS`. Saves an amp/probe at the cost of calibration and drift.

### BOM (machine-specific additions)

| Qty | Part | Notes |
| --- | ---- | ----- |
| 1 | Thermocouple amp at the group (MAX6675 on WROOM / MAX31855 on S3) | On the shared SPI bus, primary `CS` (GPIO 21 WROOM / GPIO 10 S3) in both builds |
| 1 | K-type thermocouple, mineral-insulated | Mounted at the portafilter adapter / group outlet |
| 1 | Stainless hose clamp / bracket | Secures the group probe |
| 1 | Second thermocouple amp + K-type probe (**recommended dual-TC build**) | Same type as the group amp; reads the thermoblock on `CS2` (GPIO 5 WROOM / GPIO 11 S3), shared bus |
| 1 | 100 kΩ 1 % resistor (**`-ntc` build only**) | Series pull-up for the stock-NTC ADC divider (`3V3 → R → ADC → NTC → GND`) |
| 1 | 10-100 nF ceramic cap (**`-ntc` build**, optional) | ADC-node-to-GND noise filter for the NTC line |
| ~ | Optocoupler or low-current relay (optional) | Isolates the brew-switch sense from mains for the auto shot timer |
| 1 | Ratiometric pressure transducer, 0–12 bar, G1/8" (optional) | Brew-line pressure; needs a 10 kΩ/20 kΩ divider into an ADC pin (Tier 3-ready) |
| 2 | 10 kΩ + 20 kΩ 1 % resistors (optional) | 2:1 divider scaling the transducer's 0.5–4.5 V output to the 3.3 V ADC |

The recommended dual-thermocouple build needs no sensor calibration. For the
`-ntc` build, the stock 100 kΩ NTC (Beta averaged to 3962.5 K) is reused as-is;
how to calibrate it (fixed-point and common-heater methods, with results) is in
[`tier-2-calibration.md`](tier-2-calibration.md).

### Wiring

Documented in [`tier-2-wiring.md`](tier-2-wiring.md); sensor calibration is in
[`tier-2-calibration.md`](tier-2-calibration.md).

## Tier 3 / 4

Not started. Documented as those tiers come online for this machine.
