# Gaggia Baby (Baby 89 class) - machine profile

> [!WARNING]
> **Untested.** This profile was written from the owner's report in #13, the
> 1989-1991 230 V wiring schematic and the Baby 89 (ER0002) parts list, without
> access to a Baby. Every value and every wiring step here is an interpretation
> of those documents and of how the Oster Xpert behaves. Check each step against
> your own machine before powering anything, and keep the stock thermostats and
> the thermofuse in circuit. Corrections from a real unit are very welcome.

## Identification

- **Brand**: Gaggia
- **Plate**: "Macchina tipo: BABY GAGGIA/A"
- **Voltage region**: 220-240 V, 50 Hz, 1200 W
- **Years**: 1989-2000
- **Closest parts list**: Baby 89 (ER0002). Internally close to early Gaggia Classics.

## Wet side

| Item | From the report | Status |
| ---- | --------------- | ------ |
| Heater | Boiler, heating element on the boiler | Reported |
| Pump | Vibration pump | Reported |
| 3-way solenoid valve | Yes | Reported |
| OPV | Large brass valve on the boiler, adjustability unknown | UNVERIFIED: may be the boiler safety valve rather than an OPV. Measure brew pressure with a blind-basket gauge |
| Group | 58 mm, Classic style, non-pressurised | Reported |

## Dry side

| Item | From the report | Status |
| ---- | --------------- | ------ |
| Mains switch | Double-pole | Reported |
| Stock thermostats | 107 C coffee, 145 C steam, plus a thermofuse | Reported |
| Existing temperature probe | None | Reported |

## Firmware build

`pio run -e esp32-gaggia-baby -t upload` (classic ESP32-WROOM). The profile is
`firmware/src/profile/gaggia_baby.cpp` and reuses the Oster's GPIO map, so the
Oster wiring guides apply pin for pin:

| Function | WROOM GPIO | Notes |
| -------- | ---------- | ----- |
| Heater SSR | 23 | Active high (Fotek-style SSR input) |
| Boiler NTC | 34 | Divider: 3V3 - **10 k** - GPIO 34 - NTC - GND |
| Group thermocouple (MAX6675) | CS 21, SCK 18, SO 19 | Tier 2 cascade target |
| Pump dimmer | DIM 32, Z-C 33 | Same AC phase dimmer as the Oster |
| 3-way valve SSR | 26 | Active low (5 V SSR board) |
| Pressure transducer | 35 | 0.5-4.5 V through a 2:1 divider, same as the Oster |

Differences from the Oster that matter:

- **Boiler NTC with a 10 k series resistor.** The Oster's NTC divider uses
  100 k, which suits a thermoblock read around 25-120 C. A boiler spends its
  time at 90-145 C, where a 100 k NTC is down to a few kOhm, so the profile
  assumes a 10 k pull-up to keep the reading in the useful part of the ADC.
  The NTC is assumed to be a 100 k / B3950 part; run the ice/boil calibration
  in [`hardware/oster-xpert/tier-2-calibration.md`](../../hardware/oster-xpert/tier-2-calibration.md)
  on the real part.
- **The 3-way valve is the brew valve.** Energised, it feeds the group;
  released, it vents the puck into the drip tray. It is driven from the brew
  valve output, and the relief output the Oster uses is left unconnected.
- **Pump curve.** The pressure-hold and profile code uses the pump curve
  measured on the Oster's pump and dimmer. A different vibration pump will sit
  at different pressures for the same duty, so expect pressure targets to be
  off until the Baby's own curve is measured with
  [`hardware/oster-xpert/pump-dimmer-calibration.md`](../../hardware/oster-xpert/pump-dimmer-calibration.md).

## Sensors and the cascade

The boiler NTC is the heater-side sensor: the safety cut (`maxSafeTempC`,
155 C) and the boiler ceiling are judged against it. The thermocouple is the
cascade target the PID closes on. The cascade does the most good when that
thermocouple is on the group head rather than lower on the boiler, since a
second probe on the same boiler mostly reads the same water.

## Heater wiring

Put the SSR in series with the heating element on the leg both thermostats
share, so the 107 C and 145 C thermostats and the thermofuse all stay in the
circuit as hard upper limits. The SSR can only cut power, never add it. Until
a steam mode lands in the firmware (#15), the controller holds brew
temperature even with the steam switch on.

## Thermal starting values

All UNVERIFIED, in `gaggia_baby.cpp`:

| Field | Value | Why |
| ----- | ----- | --- |
| `pidKp` / `pidKi` / `pidKd` | 0.07 / 0.002 / 3.0 | A water-filled boiler lags a thermoblock, so softer P and I, more D |
| `maxSafeTempC` | 155 C | Above the steam thermostat, well below the thermofuse |
| `defaultThermoblockOffsetC` / `maxThermoblockOffsetC` | 8 / 20 C | The group bolts to the boiler, so it needs much less headroom than the Oster |
| `approachDutyPerC` | 0.03 | Full duty only more than ~33 C below target |

Replace the PID gains with values from the open-loop step-response procedure in
[`oster-xpert.md`](oster-xpert.md#pid-tuning-procedure-post-tier-1-install),
using the Baby's own boiler. Do the safety checklist there first.
