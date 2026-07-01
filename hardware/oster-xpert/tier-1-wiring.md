# Oster Xpert - Tier 1 wiring

> [!WARNING]
> Read this in full **before** unplugging the machine. Mains voltage is
> present on the brew thermostat's terminals even when the brew switch is
> off, depending on how the stock loom is wired. Always disconnect the
> machine from the wall, discharge any capacitors, and verify zero
> voltage with a meter before touching anything.

## Overview

```
Mains L ----+------------ stock brew thermostat (snap-disc) -----+
            |                                                    |
            |                            +-----------------------+
            |                            |
            |                            v
            |                  +---------+--------+
            |                  |  25 A SSR (load) |   <- esp32esso adds this
            |                  +---------+--------+
            |                            |
            |                            v
            +------ ESP32 power tap      heater coil
                       (via 5 V buck)        |
                                             |
Mains N --------------------------------- heater N
```

This wiring is the same on a classic ESP32-WROOM and an ESP32-S3; only the
GPIO numbers differ (see the pin table in step 4/7 below).

The stock thermostat stays in the circuit as a hard thermal cut: even if
the SSR fails closed (a common failure mode for counterfeit SSRs), the
thermostat still opens at its rated trip temperature.

## Step by step

1. Document the stock wiring with photos. Label every wire with masking
   tape **before** removing anything.
2. Open the case. Identify:
   - the brew thermostat (snap-disc closest to the brew thermoblock)
   - the steam thermostat (higher trip temperature, usually mounted on
     top of the thermoblock)
   - the heater leads
3. Disconnect the wire that runs from the brew thermostat to the heater
   (the **load** side of the brew thermostat).
   The GPIO numbers depend on your board (the firmware compiles the matching
   map from `firmware/src/board/`):

   | Signal | ESP32-WROOM (`esp32-oster-xpert`) | ESP32-S3 (`esp32-s3-oster-xpert`) |
   | ------ | --------------------------------- | --------------------------------- |
   | Heater SSR | GPIO 4 | GPIO 4 |
   | MAX31855 `CS` | GPIO 21 | GPIO 10 |
   | MAX31855 `SCK` | GPIO 18 | GPIO 12 |
   | MAX31855 `MISO` | GPIO 19 | GPIO 13 |

4. Wire the SSR in series:
   - SSR `1` (AC `+`) -> the brew thermostat load wire
   - SSR `2` (AC `-`) -> the heater terminal
   - SSR `3` (DC `+`) -> ESP32 **heater SSR** GPIO via a 220 ohm resistor
   - SSR `4` (DC `-`) -> ESP32 GND
5. Mount the SSR to its heatsink with the supplied thermal pad. Bolt the
   heatsink to a piece of empty case metal that is not part of the brew
   group (avoid heat-soak from the boiler).
6. Mount the thermocouple to the thermoblock with the stainless hose
   clamp. The bead should sit against the side of the thermoblock under
   the clamp, with the wires routed away from the heater leads.
7. Wire the MAX31855 (use the `CS`/`SCK`/`MISO` GPIOs for your board from
   the table above):
   - `VCC` -> ESP32 `3V3`
   - `GND` -> ESP32 `GND`
   - `CS`  -> ESP32 `CS` GPIO
   - `SCK` -> ESP32 `SCK` GPIO
   - `MISO` -> ESP32 `MISO` GPIO
8. Wire the 5 V buck input across the mains side **after** the machine's
   main power switch (so the controller turns off when the machine is
   switched off).
9. Buck output:
   - `5V` -> ESP32 `5V` (or `VIN` depending on board variant)
   - `GND` -> ESP32 `GND`
10. Triple-check the wiring against the diagram above.
11. Reassemble enough to power on safely. Plug in, switch on, and verify
    Serial telemetry shows the banner + a sane temperature within a few
    degrees of ambient.

## What to verify after first boot

- `temp` field in the telemetry tracks room temperature when the heater
  is off
- `heat` toggles between 0 and 1 as the SSR window cycles
- the `fault` field stays empty
- the brew thermostat still opens at its rated trip when the heater is
  run with the SSR forced ON (test with the firmware safety cut at a
  conservative 90 C before raising it to the brew setpoint)

If anything looks wrong, **stop and report it before raising the
setpoint**. A stuck SSR can boil dry a thermoblock in under a minute.
