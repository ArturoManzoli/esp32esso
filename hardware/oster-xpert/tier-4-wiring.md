# Oster Xpert - Tier 4 wiring (drip-tray scale)

Tier 4 weighs the cup by turning the **drip-tray lid** into a scale platform,
carried on **two load cells** at its edges with one **HX711** amplifier each.
Summing the two cells gives the cup weight wherever it sits on the tray, which
is what stop-on-weight and predictive yield are built on.

Unlike every other tier here, none of this touches mains. The whole job is low
voltage and mechanical. What makes or breaks it is the mechanical install, not
the wiring, so read [Mechanical install](#mechanical-install) before drilling
anything.

## Parts

| Qty | Part | Notes |
| --- | ---- | ----- |
| 2 | Parallel-beam (single-point) load cell, 3 kg, 4-wire | 1 mV/V, ~1 kΩ bridge. One per tray edge |
| 2 | HX711 24-bit ADC breakout | The common 33 × 20 mm green module. Channel A only |
| ~ | M4/M5 fasteners + spacers | Each cell needs a clearance gap at both ends so the beam can flex |
| ~ | 4-core shielded or twisted cable (if the cells sit far from the amps) | Keep it short; see [Noise](#noise-and-routing) |

## Pin map at a glance

| Signal | ESP32-WROOM (`esp32-oster-xpert`) | ESP32-S3 (`esp32-s3-oster-xpert`) |
| ------ | --------------------------------- | --------------------------------- |
| HX711 `SCK` (shared by both amps) | GPIO 22 | GPIO 21 |
| Left amp `DT` / `DOUT` | GPIO 16 | GPIO 15 |
| Right amp `DT` / `DOUT` | GPIO 17 | GPIO 16 |
| Both amps `VCC` | **3V3** | **3V3** |
| Both amps `GND` | GND | GND |

> [!IMPORTANT]
> Power the amps from **3.3 V, not 5 V**. On these modules `DOUT` swings to
> whatever `VCC` is, so a 5 V supply drives 5 V into a 3.3 V-only GPIO.

GPIO 16 and 17 are free on a **WROOM** module but back the PSRAM on a
**WROVER**. If your DevKit carries a WROVER, move the two data pins to GPIO 25
and GPIO 13 and update `firmware/src/board/esp32_wroom.cpp` to match.

Two bridges at 3.3 V draw about 7 mA (or ~22 mA if your cells are the 350 Ω
variant), plus ~1.5 mA per HX711. The dev board's 3.3 V regulator covers that.

## Load cell to HX711

Each cell is a **full Wheatstone bridge**, so it uses channel A only — the one
with gain 128. Leave `B+` / `B-` unconnected on both modules.

| Cell wire (typical) | HX711 pin | What it is |
| ------------------- | --------- | ---------- |
| Red | `E+` | Bridge excitation + |
| Black | `E-` | Bridge excitation − |
| Green | `A+` | Signal + |
| White | `A-` | Signal − |
| Bare / shield, if present | `GND` | Cable shield, grounded at the amp end **only** |

### Verify the colour code before trusting it

These cells ship with several colour conventions. Check with a multimeter
instead of assuming:

- The **excitation pair** and the **signal pair** each read the bridge's
  nominal resistance (~1 kΩ here, sometimes 350 Ω).
- Any **excitation-to-signal** pair reads about three quarters of that (~750 Ω).

That identifies the two pairs. To tell which pair is which, use the cell's
datasheet colours, then confirm by pressing on the beam once it is powered: if
the reading moves the wrong way, swap the two **signal** wires. If pressing
produces nothing sensible at all, a signal wire is crossed with an excitation
wire.

## HX711 to the ESP32

Both amps share one clock line and keep their own data line:

```
ESP32 GPIO 22 (S3: 21) ──┬── HX711 #1 SCK
                         └── HX711 #2 SCK
ESP32 GPIO 16 (S3: 15) ───── HX711 #1 DT
ESP32 GPIO 17 (S3: 16) ───── HX711 #2 DT
ESP32 3V3 ───────────────┬── HX711 #1 VCC
                         └── HX711 #2 VCC
ESP32 GND ───────────────┬── HX711 #1 GND
                         └── HX711 #2 GND
```

Sharing the clock is not just pin thrift: both chips are clocked on the same
edges, so the cells are **sampled at the same instant** and tray vibration and
pump ripple cancel in the sum instead of beating between two free-running
converters.

It does constrain the driver. Two independent HX711 library instances on a
shared clock do not work, because clocking one chip also clocks the other and
makes it discard a conversion. The firmware instead waits for **both** `DT`
lines to go low, then shifts both chips out in a single 25-pulse loop, sampling
both data pins on each pulse.

### Sample rate

The modules ship strapped to **10 SPS** (`RATE`, chip pin 15, tied low). That is
the starting point here. For stop-on-weight the sampling lag costs real
overshoot at the end of the shot, so if predictive yield proves too coarse, lift
pin 15 off ground and tie it to `DVDD` (or bridge the `RATE` pad, if your board
breaks one out) for **80 SPS**.

## Mechanical install

This matters more than the wiring, and it is where the "does the cup's position
change the reading?" question is actually settled.

**The sum of the two cells reads the true total wherever the cup sits — but only
if the two cells are the entire load path for the lid.** Anything else touching
the lid (a wire, a corner resting on the chassis, the tray rim binding in its
recess) carries part of the load, and that part silently disappears from the
reading by an amount that depends on where the cup is. A scale that is accurate
in the centre and wrong at the edges is almost always this.

So:

- Nothing but the two cells may support the lid. Check by hand, with the tray in
  place, that it is free to move down slightly everywhere.
- Each cell's **load-direction arrow points down**, in the direction of the
  applied force.
- Each cell is bolted **at one end to the chassis and at the other end to the
  lid**, with a **clearance gap** at both ends so the beam can flex. Bolting
  both ends flat to a surface is the classic mistake and yields a scale that
  barely reads.
- Two single-point cells rigidly joined by one rigid lid are over-constrained
  and will fight each other a little, showing up as hysteresis. If the lid has
  some natural flex you may never notice; if you do, slotting the mounting holes
  on one cell gives it enough compliance to fix it.

## Noise and routing

- Keep the **cell-to-amp** wiring short and mount each HX711 near its cell. The
  digital run from the amps to the ESP32 can be long; the analog run should not.
- Route the cell cable **away from the pump dimmer and the heater SSR**. A phase
  dimmer chopping mains is the loudest thing in the case, and the bridge signal
  is a few millivolts.
- A 100 nF ceramic across `E+` / `E-` at the module helps.
- Ground the cable shield at the **amp end only**, so it cannot form a ground
  loop.

## Calibration

Do it in place, with the tray installed:

1. **Tare** with the tray empty.
2. Put a known mass (a 500 g calibration weight, or anything you can weigh on a
   kitchen scale) **directly over one cell**, note the total, then move it
   **directly over the other** and note the total again.
3. Trim the two per-cell gains until both positions report the same total. This
   is the corner match, and it is what buys you position independence.
4. Only then set the overall scale factor with the mass in the **centre**.

Verify by walking the mass around all four corners; a good install lands within
a few tenths of a gram everywhere.

## What to expect

Each 3 kg cell at 1 mV/V gives roughly **716 counts per gram** at gain 128, so a
500 g cup sits comfortably inside range on both cells. Realistic noise on these
modules is about **±0.2 g peak to peak**, settling near **0.1 g** with light
median-and-EMA filtering, which is where it needs to be for espresso.

Excitation voltage does not change any of that. The HX711 derives both the
bridge excitation and its own ADC reference from the same rail, so the reading
is ratiometric and counts per gram are identical at 3.3 V and 5 V. The only cost
of the lower rail is a slightly worse signal-to-noise ratio against the chip's
fixed input noise, which is not the limiting factor here.
