# Oster Xpert - machine profile

> Status: first-cut. Most fields below are tagged either **VERIFIED** or
> **UNVERIFIED**. Unverified ones are starting assumptions written from
> publicly available product information and similar Chinese-OEM
> pump-espresso platforms. They must be confirmed on a real unit before
> anyone flashes Tier 2 or higher.

The Oster Xpert is a sub-$100 pump espresso machine sold in Latin America
(notably Brazil) and other markets. It is widely understood to be a rebrand
of a Chinese OEM design (the user reports the upstream brand has "Sun" in
its name). Several other entry-level brands in the same price band share
this platform.

## Identification

- **Brand**: Oster
- **Model name on the box**: "Xpert" / "Xpert Pro"
- **Likely model codes**: `BVSTEM6601`, `BVSTEM6701`, or a regional
  equivalent (UNVERIFIED)
- **Voltage region**: 220-240 V (Brazil) - 100-120 V variants may exist
- **Original Chinese OEM**: UNVERIFIED, suspected Sunpentown / Sunbeam-style
  Yueli platform

## Wet side (UNVERIFIED until teardown)

| Item | Expected | Verification step |
| ---- | -------- | ----------------- |
| Heater type | Aluminium thermoblock | Visual inspection during teardown |
| Heater power | ~850 W (220-240 V) | Read from the rating plate |
| Pump | Ulka EX5 or generic 15-bar vibration pump | Read pump label |
| 3-way solenoid valve | Probably absent (entry tier) | Inspect group-head solenoid |
| OPV crack pressure | ~10-11 bar | Measure with a blank portafilter gauge |
| Group-head valve | Spring-loaded check valve | Visual inspection |

## Dry side (UNVERIFIED until teardown)

| Item | Expected | Verification step |
| ---- | -------- | ----------------- |
| Power switch | Single-pole, latching | Trace stock wiring |
| Brew switch | Momentary push or rocker | Trace stock wiring |
| Steam switch | Rocker, swaps thermostat reference | Trace stock wiring |
| Stock thermostats | Two bimetallic snap-discs (~95 C brew, ~145 C steam) | Probe with a meter, note part numbers |
| Stock control board | Simple relay or none (thermostats inline) | Open and photograph |
| Ground bonding | UNKNOWN - many cheap machines float chassis ground | Continuity check before any mains work |

> [!CAUTION]
> Before touching any mains-side wiring, **measure ground continuity**
> between the IEC ground pin and the chassis. If the chassis is not bonded
> to ground, do **not** install Esp32esso on this machine until the
> bonding is fixed.

## Tier 1 install plan (temperature control only)

1. Document the stock wiring with photos before touching anything.
2. Tap the brew-side thermostat with a K-type thermocouple secured to the
   thermoblock with a stainless hose clamp, routed away from the steam
   thermostat.
3. Wire a 25 A opto-isolated SSR in series with the brew thermostat's
   load side (the stock thermostat stays in place as a thermal-fuse-style
   backup; the SSR can only cut, never boost).
4. Drive the SSR opto input from ESP32-S3 GPIO 4 through a 220 ohm
   current-limiting resistor.
5. Connect the MAX31855 amplifier to the ESP32-S3 (`CS=10`, `SCK=12`,
   `MISO=13`, `VCC=3V3`, `GND=GND`).
6. Power the ESP32-S3 from a small 5 V buck behind the IEC inlet's switch
   (so the controller turns off with the machine).

Wiring diagram lives at `hardware/oster-xpert/tier-1-wiring.md`.

## PID tuning procedure (post-Tier-1 install)

The defaults in `firmware/src/profile/oster_xpert.cpp` are conservative
guesses. To tune for a specific unit:

1. Power on with the existing defaults (`Kp=0.10`, `Ki=0.005`, `Kd=1.5`,
   window 1000 ms).
2. Capture the open-loop step response by setting the heater to 100% for
   a short, fixed time (e.g. 5 s) from a cold start and recording the
   thermocouple curve over Serial telemetry.
3. Apply Ziegler-Nichols or relay-feedback tuning offline.
4. Update the gains in the machine profile and re-flash.

## Compatibility caveats specific to this machine

- The thermoblock has very low thermal mass relative to a Gaggia boiler,
  so PID overshoot is the main risk. The hard cut at `maxSafeTempC` (160 C)
  is intentionally tight.
- If the chassis-ground bonding is found to be missing, see the warning
  above. This is more common on entry-tier Chinese-OEM machines than on
  the Gaggia family.
- Without a 3-way solenoid valve, "stop on weight" (Tier 4) will dribble
  at the end of the shot because brew-line pressure has no fast bleed
  path. This is a known trade-off, not a bug.
