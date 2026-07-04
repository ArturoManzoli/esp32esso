# Oster Xpert - Tier 2 wiring

Tier 2 turns the single-sensor Tier 1 rig into a **two-sensor cascade**, with an
optional brew-line **pressure transducer** and a **brew-switch tap** for the
auto shot timer:

- **Thermoblock:** the machine's **stock NTC thermistor** (already bolted to the
  thermoblock), read through a resistor divider on an ADC pin.
- **Group/portafilter:** a **thermocouple** at the water exit over the
  portafilter adapter.
- **Pressure (optional):** a ratiometric transducer on the brew line, scaled
  into an ADC pin. The firmware carries the field as a stub until the Tier 3
  driver lands, but you can wire the sensor now.
- **Relief valve (optional):** a solenoid on a second SSR that the firmware
  pulses open for 1 s at the end of a shot to dump brew-line pressure.

The heater/SSR from Tier 1 is unchanged. Sensor **calibration** (fixed-point and
common-heater methods, with results) lives in its own doc:
[`tier-2-calibration.md`](tier-2-calibration.md).

> [!NOTE]
> The `esp32-oster-xpert` / `esp32-s3-oster-xpert` envs build with
> `ESP32ESSO_THERMOBLOCK_NTC=1`, so the **thermoblock is read from the stock
> NTC** and the thermocouple amp moves to the group. If you'd rather keep a
> thermocouple on the thermoblock, drop that flag and wire per
> [Tier 1](tier-1-wiring.md) instead (thermoblock TC on `CS`, group TC on `CS2`).

> [!WARNING]
> Same mains-safety rules as Tier 1 apply. Disconnect from the wall and verify
> zero voltage before touching anything. The sensor taps are low voltage, but
> they run next to mains wiring inside the case. A pressure transducer is
> plumbed into the pressurised brew line — fit it only on the **cold/low side**
> you can reach safely and torque its thread to spec.

## Why two sensors

The stock NTC sits on the thermoblock. Water then travels ~20 cm through PTFE
tubing and valves to the portafilter, losing heat along the way. Tier 2
measures the water temperature **at the group** and makes that the target: the
firmware runs the thermoblock hotter by a tunable amount so the cup lands at
your setpoint. See the cascade math in
[`../../protocol/ble/tier2.md`](../../protocol/ble/tier2.md) and
`firmware/src/control/temperature_loop.cpp`.

## Pin map at a glance

| Signal | ESP32-WROOM (`esp32-oster-xpert`) | ESP32-S3 (`esp32-s3-oster-xpert`) |
| ------ | --------------------------------- | --------------------------------- |
| NTC divider node (ADC) | GPIO 34 (ADC1_CH6, input-only) | GPIO 7 (ADC1_CH6) |
| Group amp `CS` | GPIO 21 (Tier 1 pin) | GPIO 10 (Tier 1 pin) |
| Group amp `SCK` | GPIO 18 | GPIO 12 |
| Group amp `SO` / `MISO` | GPIO 19 | GPIO 13 |
| Group amp `CS2` (dual-TC only) | GPIO 22 | GPIO 11 |
| Heater SSR | GPIO 4 | GPIO 4 |
| Brew switch (optional) | GPIO 27 | GPIO 5 |
| Relief-valve SSR (optional) | GPIO 26 | GPIO 14 |
| Pressure divider node (ADC, optional) | GPIO 35 (ADC1_CH7, input-only) | GPIO 8 (ADC1_CH7) |

Use **ADC1** channels only for the analog sensors — ADC2 is unavailable while
the BLE radio is on. Tie all sensor grounds to the ESP32 `GND`.

## Thermoblock NTC (stock sensor)

The Sunbeam Barista Max / Oster Xpert ships a **100 kΩ-at-25 °C NTC thermistor**
(M5 brass-tip probe) on the thermoblock. Reuse it as the inner/safety sensor via
a resistor divider into an ESP32 **ADC1** channel.

Wire a divider with a **100 kΩ 1 % series resistor** as the pull-up:

```text
3V3 ──[ 100 kΩ 1% ]──┬── ADC pin (GPIO 34 / GPIO 7)
                     │
                    NTC (stock probe)
                     │
                    GND
```

- At 25 °C the node sits at ~1.65 V (mid-scale); it falls as the thermoblock
  heats and the NTC resistance drops.
- Keep the divider leads short and away from mains/heater wiring — the ADC is
  high-impedance and picks up EMI. A **10–100 nF cap** from the ADC node to GND
  helps filter noise (the firmware also oversamples 16×).
- One probe leg is often the chassis/GND (brass body); wire that leg to `GND`
  and the isolated leg to the divider node.

**Alternatives / equivalents**

- **Different stock NTC.** Any machine's stock thermistor works — match the
  **series resistor to the NTC's nominal R₂₅** (e.g. a 50 kΩ NTC → 50 kΩ series)
  so the node lands mid-scale at room temperature, then calibrate. The firmware
  fit (`R0`/`Beta`) absorbs the exact device.
- **No stock NTC / adding one.** A generic **100 kΩ 3950 glass-bead or
  screw-probe NTC** is a drop-in; screw-probe (M4/M5/M6) types clamp to the
  block better than glass beads.
- **Skip the NTC entirely** by dropping `ESP32ESSO_THERMOBLOCK_NTC` and running
  a thermocouple on the thermoblock (Tier 1 wiring) — you lose the "free" stock
  sensor but gain a linear, cold-junction-compensated reading.

See [`tier-2-calibration.md`](tier-2-calibration.md) for turning the raw divider
reading into accurate °C, and for the open/short fault behaviour.

## Group thermocouple

With the NTC on the thermoblock, the **same MAX6675 amp from Tier 1** reads the
**group** — only the probe moves to the portafilter. Keep `CS` on the **Tier 1
pin** (GPIO 21 on WROOM); you do not need a second amp or CS2 unless you run
dual-thermocouple builds without the NTC.

> GPIO 22 (WROOM) / GPIO 11 (S3) is **CS2**, used only when *both* thermoblock
> and group are thermocouples (no NTC). With `ESP32ESSO_THERMOBLOCK_NTC`, the
> firmware reads the group amp on the Tier 1 `CS` pin above.

1. Mount the K-type probe against the **portafilter adapter / group outlet**, as
   close to where the water exits over the puck as you can clamp it. Insulate the
   bead from ambient airflow so it reads water temperature, not case air.
2. Wire the amp's `VCC`/`GND` to the ESP32 `3V3`/`GND` (**use `3V3`, not `5V`**),
   `SCK`/`SO` to the GPIOs above, and `CS` to **GPIO 21** (same as Tier 1).
3. If the group amp is missing or faults, the firmware degrades to single-sensor
   behaviour (controls the thermoblock NTC at the setpoint directly) and the app
   shows "group sensor offline".

**Alternatives / equivalents**

- **Amplifier.** MAX6675 (K-type, 0–1024 °C, 0.25 °C steps) is the cheapest.
  **MAX31855** is a pin-similar SPI upgrade with wider range and open/short fault
  flags; **MAX31865 + PT100/PT1000 RTD** trades the thermocouple for a more
  accurate, drift-free RTD (needs a code change to the HAL driver).
- **Probe.** A **K-type washer/ring-lug probe** bolts under an existing screw at
  the group; a **screw-tip M4 probe** threads into a tapped boss. Mineral-
  insulated (MI) probes handle the wet/steam environment best.
- **Bus sharing.** All SPI amps share `SCK`/`SO`; only `CS` is per-device, so a
  second amp just needs its own CS (the `CS2` pin above).

## Pressure transducer (optional, Tier 3-ready)

A brew-line pressure reading lets the app graph pressure alongside temperature
and is the foundation for Tier 3 pressure profiling. The firmware exposes the
`pressure_bar` field as a `NaN` stub until the Tier 3 driver ships
([`../../protocol/ble/tier2.md`](../../protocol/ble/tier2.md)), but the sensor
and its analog front-end can be wired now.

**Recommended sensor.** A **ratiometric automotive/industrial transducer**,
0–1.2 MPa (**0–12 bar**), **G1/8" or 1/8" NPT** stainless thread, 5 V supply,
**0.5 V–4.5 V** output (the ubiquitous "XDB401 / 5 V 1.2 MPa" type used in most
DIY espresso builds). Output is **ratiometric** (proportional to the 5 V
supply), so:

```text
bar ≈ (V_out - 0.5) / (4.5 - 0.5) * 12
```

**Scale 0.5–4.5 V down to the 3.3 V ADC.** The ESP32 ADC tops out at ~3.3 V, so
divide the 4.5 V full-scale output with a **2:1 divider** (10 kΩ / 20 kΩ, 1 %):

```text
sensor OUT ──[ 10 kΩ 1% ]──┬── ADC pin (GPIO 35 / GPIO 8)
                           │
                        [ 20 kΩ 1% ]
                           │
                          GND
```

- Ratio `20k / (10k + 20k) = 0.667`, so 0.5–4.5 V → **0.33–3.0 V** at the pin,
  safely inside the ADC range with headroom. Recompute `bar` in firmware for the
  divider ratio, or divide the constant `12` accordingly.
- Add a **10–100 nF cap** from the ADC node to GND to filter pump ripple; the
  vibration pump makes the raw signal noisy at ~50/60 Hz.
- **Power the sensor from 5 V**, not 3V3 — its ratiometric span is specified
  against a 5 V rail. Share the ESP32 `GND`.

**Plumbing / placement.**

1. Tee the transducer into the **brew line after the pump**, before or at the
   group — a **brass T-fitting** on the pump outlet or the group inlet is the
   usual tap. Match the thread (G1/8" is common on these transducers; adapt to
   the machine's line with a brass fitting).
2. Mount it **thread-down or sideways** so trapped water can drain and it is not
   sitting in a steam pocket. Keep the body away from the boiler's radiant heat;
   these sensors are typically rated to ~85–125 °C at the port.
3. Use PTFE tape / a bonded washer on the thread and torque to the fitting spec —
   this line sees 9–12 bar.

**Alternatives / equivalents**

- **Range.** 0–1.6 MPa (16 bar) or 0–2.0 MPa versions exist; a higher range
  loses a little resolution but survives OPV spikes. Scale the firmware constant
  to the sensor's full-scale bar.
- **Thread.** 1/8" NPT vs G1/8" (BSPP) — pick to match your fittings; adapters
  are cheap. Stainless diaphragm preferred over brass for the wet line.
- **Digital sensors.** An I²C pressure sensor (e.g. a board around an
  Honeywell/TE part) skips the divider and ADC noise entirely, at higher cost
  and a different driver — wire it to the I²C pins instead of an ADC channel.

## Brew switch (optional, for the auto shot timer)

The shot timer can start automatically when the brew switch closes. If you
don't wire it, use the **Start shot** button in the app instead.

1. Find a dry-contact signal that closes when brewing starts (a spare pole on
   the brew rocker, or a low-current relay/optocoupler across the pump-switch
   contacts — **never** tap mains directly into the GPIO).
2. Wire one side to the brew-switch GPIO above and the other to ESP32 `GND`.
   The input uses the internal pull-up and reads active-low (closed = brewing),
   debounced in firmware.

## Pressure-relief valve (optional, dumps pressure after a shot)

Machines without a stock 3-way valve (the Oster Xpert is one — `hasThreeWayValve`
is `false`) trap brew pressure in the puck when the shot ends, giving a wet puck
and drips. A solenoid relief valve teed into the brew line fixes this: the
firmware **pulses it open for 1 second when a shot ends** (`kReliefPulseMs` in
`firmware/src/control/temperature_loop.cpp`) to dump residual pressure, then
closes it. The valve is closed at all other times, and a new shot starting
cancels any in-flight pulse.

The valve is driven from a **second SSR** on its own GPIO, wired exactly like the
heater SSR — same brick, same drive caveats (see
[Tier 1](tier-1-wiring.md); a `3–32 VDC` input SSR is marginal at 3.3 V, so use a
5 V transistor stage if it does not switch reliably).

| Signal | ESP32-WROOM (`esp32-oster-xpert`) | ESP32-S3 (`esp32-s3-oster-xpert`) |
| ------ | --------------------------------- | --------------------------------- |
| Relief-valve SSR (DC control) | GPIO **26** | GPIO **14** |

```text
Mains L ──[ 2nd SSR (load) ]── solenoid valve ── Mains N

SSR DC + ── ESP32 GPIO 26 / GPIO 14   (5 V transistor stage if 3.3 V is marginal)
SSR DC - ── ESP32 GND
```

1. Fit a **normally-closed mains solenoid valve** teed into the brew line (a
   brass T on the pump outlet / group inlet), with its outlet routed to the
   **drip tray**. Energising the valve opens the path and relieves pressure.
2. Switch the valve's mains leg through the **second SSR**, mounted on a
   heatsink like the heater SSR. Keep it on the low/return side you can reach
   safely and torque all fittings — the line sees 9–12 bar.
3. Drive the SSR's DC input from the relief-valve GPIO above. The output is
   active-high; the firmware forces it closed at boot and whenever no valve is
   bound.

**Alternatives / equivalents**

- **Low-voltage solenoid (12/24 V DC).** Skip the second SSR and switch a
  low-side **logic-level MOSFET** (with a flyback diode across the coil) from the
  same GPIO; power the coil from a 12/24 V rail.
- **3-way vs 2-way.** A 3-way valve vents the group side directly; a 2-way valve
  teed to the drip tray works too. Either way the firmware just pulses the one
  output.
- **Pulse length.** Adjust `kReliefPulseMs` if 1 s under- or over-vents your
  line.

> [!WARNING]
> This is a **second mains-switched load**. All the Tier 1 mains-safety rules
> apply: opto-isolated SSR only, never tap a GPIO into mains, and verify the
> valve fails **closed** (de-energised) so a controller reset cannot leave the
> brew line venting.

## After first boot

- The app shows two temperatures: **group** (the target) and **thermoblock**.
- Raise the **cascade gain** until the group holds your setpoint during a shot;
  `0` disables compensation.
- Pull a shot (button or switch) and confirm the **shot timer** runs and the
  **brew graph** traces both temperatures.
- Before trusting the thermoblock reading, calibrate the NTC — see
  [`tier-2-calibration.md`](tier-2-calibration.md).
