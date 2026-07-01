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
4. Drive the SSR opto input from the ESP32's heater-SSR GPIO (GPIO 4 on
   both the WROOM and S3 maps) through a 220 ohm current-limiting resistor.
5. Connect the MAX31855 amplifier to the ESP32 (`VCC=3V3`, `GND=GND`, and
   `CS`/`SCK`/`MISO` per your board: WROOM `CS=21`, `SCK=18`, `MISO=19`;
   S3 `CS=10`, `SCK=12`, `MISO=13`).
6. Power the ESP32 from a small 5 V buck behind the IEC inlet's switch
   (so the controller turns off with the machine).

This project works on any ESP32; a classic ESP32-WROOM is the primary Tier 1
target, and an ESP32-S3 is recommended if you plan to grow into Tier 2-4.
See the pin table in
[`hardware/oster-xpert/tier-1-wiring.md`](../../hardware/oster-xpert/tier-1-wiring.md).

Wiring diagram lives at `hardware/oster-xpert/tier-1-wiring.md`.

## PID tuning procedure (post-Tier-1 install)

The defaults in `firmware/src/profile/oster_xpert.cpp` (`Kp=0.10`,
`Ki=0.005`, `Kd=1.5`, window 1000 ms) are conservative first-cut values
chosen before the first unit was benched. Replace them with values fitted
to your specific machine using the open-loop step-response procedure
below.

### Safety checklist (before the bench)

> [!CAUTION]
> The bench drives the heater at 50 % duty open-loop for up to 120
> seconds. If the SSR fails closed, the safety-cut at `maxSafeTempC =
> 160 C` is the only thing standing between your thermoblock and a dry
> boil. Confirm every item below **before** typing the start command.

- [ ] the stock brew thermostat is still wired in series with the SSR
  (see `hardware/oster-xpert/tier-1-wiring.md`) so it still trips on
  runaway temperature
- [ ] the thermocouple bead is firmly clamped to the thermoblock and
  readings track room temperature when the heater is off
- [ ] the firmware reports `fault=""` and a sane temperature on the
  serial monitor at idle
- [ ] the water reservoir is **full** so the thermoblock does not boil
  dry mid-bench
- [ ] a fire extinguisher rated for electrical fires is within reach
- [ ] you can physically yank the IEC power lead in under one second

### Bench protocol

The bench tooling lives in [`tools/tuning/`](../../tools/tuning/). Install
the host requirements once: `pip install -r tools/requirements.txt`.

1. Start the machine cold (let it sit unpowered for at least 30 minutes
   after any prior shot so the thermoblock is at room temperature).
2. Connect USB-serial to the ESP32 and confirm the firmware banner +
   one steady telemetry line in a serial monitor. Close the monitor.
3. From the repo root, launch the capture script with the run timestamped
   under the gitignored data folder:

   ```bash
   mkdir -p tools/tuning/data
   python3 tools/tuning/capture.py \
       --port /dev/ttyACM0 \
       --out tools/tuning/data/step-$(date +%Y%m%dT%H%M%S).csv
   ```

   The script claims the port, settles for ~1.5 s, sends the `s` command
   to the firmware, and records every telemetry line. The firmware emits
   at 5 Hz while tuning, then drops back to 1 Hz when the bench ends.
4. Watch the live output. The bench self-terminates when any of these
   happen and the script exits after a short cooldown:
   - 120 s elapses
   - brew temperature reaches the 140 C bench-abort threshold
   - the safety latch fires (sensor fault or 160 C hard cut)
   - you press `Ctrl-C` (the script sends `q` to abort before closing)
5. Fit the FOPDT model and derive PID gain suggestions:

   ```bash
   python3 tools/tuning/analyze.py \
       --in tools/tuning/data/step-<timestamp>.csv \
       --duty 0.50 \
       --out-json tools/tuning/data/step-<timestamp>.json \
       --out-plot tools/tuning/data/step-<timestamp>.png
   ```

   The script prints the fitted process gain K (deg C per duty fraction),
   time constant tau, and dead time L, then the Ziegler-Nichols and
   Cohen-Coon PID gain suggestions.
6. Inspect the PNG plot. A clean fit should hug the measured curve with
   no systematic drift; a poor fit usually means the bench was aborted
   too early to resolve the time constant (rerun with a higher abort
   temperature or a longer duration, **never** with the safety cut
   removed).
7. Update `firmware/src/profile/oster_xpert.cpp` with the chosen gains
   (Ziegler-Nichols is the conservative starting point; Cohen-Coon is
   more aggressive but tracks setpoints faster). Re-flash and verify in
   closed-loop with a setpoint near 93 C: temperature should approach
   setpoint with bounded overshoot and settle within a couple of cycles.
8. Once you've picked a winning run, copy the plot and the derived JSON
   into the committed tree (e.g. under `hardware/oster-xpert/`) so the
   tuning provenance is preserved. The raw CSVs stay gitignored.

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
