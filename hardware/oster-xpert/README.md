# Oster Xpert - hardware

Reverse-engineering and install notes for the Oster Xpert (Brazilian
220-240 V variant). See [`docs/machines/oster-xpert.md`](../../docs/machines/oster-xpert.md)
for what is known about the machine itself and the tuning procedure.

## Tier 1 - Temperature brain

Drives the existing thermoblock with closed-loop PID via an SSR, reading
the thermoblock temperature with a K-type thermocouple and MAX31855.

### BOM (machine-specific additions)

| Qty | Part | Notes |
| --- | ---- | ----- |
| 1 | Stainless steel hose clamp, ~25 mm | Secures the thermocouple bead to the thermoblock |
| 1 | High-temp double-shrink heatshrink | Insulates the thermocouple junction at the contact point |
| 1 | Spade connector kit (6.3 mm and 4.8 mm female) | Mates the SSR into the stock thermostat-load wiring without cutting it |
| ~ | 18 AWG silicone wire (red / black) | Heater-side wiring, rated for the ambient temperature inside the machine |

The shared Tier 1 BOM (ESP32 board, MAX31855, SSR, buck, etc.) lives in the
hardware index at [`../README.md`](../README.md).

### Wiring

The detailed wiring change is documented in
[`tier-1-wiring.md`](tier-1-wiring.md).

## Tier 2 / 3 / 4

Not started. Documented as those tiers come online for this machine.
