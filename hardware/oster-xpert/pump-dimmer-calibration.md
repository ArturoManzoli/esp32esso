# Oster Xpert - pump dimmer characterisation

Tier 3 replaces the pump's zero-cross SSR with a **Standard AC phase-dimmer**
(Z-C + DIM, see [`tier-2-wiring.md`](tier-2-wiring.md)) so the firmware can set
the pump's conduction angle instead of just switching it. That only buys
pressure control if you know **what duty produces what pressure**, which is what
this sweep measures. The result is the lookup table the profile engine and the
manual pressure hold both invert.

Redo this whenever you change the pump, the dimmer, the OPV setting, or the
basket you normally pull with.

## Running the sweep

The firmware ships an open-loop pump bench for exactly this. Over the serial
console:

```text
pump <duty%> [seconds]   # hold the pump at a fixed duty, brew valve open
pump off                 # stop early
```

A run always expires on its own (30 s ceiling) so a lost console cannot leave the
pump running dry. Read the settled pressure off the `press` field in the JSON
telemetry, or off the app's pressure readout.

Sweep 10 % to 100 % in 10 % steps at 3 s per step, as one continuous staircase,
with a **freshly tamped puck** in the basket. Do not reuse a puck across runs:
once it has been pushed to 12 bar it channels, and every later run reads low.

## Measured response

Oster Xpert, Standard phase dimmer, fresh puck, 3 s per step:

| Duty | Settled (bar) | Mean (bar) | Peak (bar) |
| ---: | ------------: | ---------: | ---------: |
| 10 % | 0.58 | 0.62 | 0.65 |
| 20 % | 0.47 | 0.49 | 0.51 |
| 30 % | 0.49 | 0.48 | 0.49 |
| 40 % | 0.45 | 0.48 | 0.50 |
| 50 % | 0.49 | 0.41 | 0.49 |
| 60 % | 2.47 | 1.77 | 2.47 |
| 70 % | 7.10 | 6.52 | 8.62 |
| 80 % | 9.28 | 9.34 | 9.55 |
| 90 % | 10.83 | 11.34 | 11.90 |
| 100 % | 12.06 | 11.12 | 12.06 |

Three things to read off it:

- **Nothing happens below ~55 %.** The vibration pump cannot beat its own spring
  plus the puck, so the whole 0-12 bar range is squeezed into the top 45 % of the
  duty scale. A naive `duty = bar / OPV` law asks for duties that move no water
  at all: a 4 bar pre-infusion phase would command 40 %, which this table says
  produces 0.45 bar.
- **The usable region is steep.** Between 60 % and 80 % the plant gain runs
  12-46 bar per unit duty, which bounds how hard any closed loop on top can push
  before it oscillates.
- **Full duty peaks at 12 bar**, well above a 9 bar OPV. Either the OPV is not
  cracking or it is set high; worth checking on the machine itself.

> [!NOTE]
> Telemetry is 1 Hz, so each 3 s step is only ~3 samples and the pressure EMA is
> still climbing when the 60-70 % steps end. The *shape* is solid; treat the
> individual values in the steep region as approximate.

## How the firmware uses it

`kPumpCurve` in
[`temperature_loop.cpp`](../../firmware/src/control/temperature_loop.cpp) holds
the settled column as `(bar, duty)` knots. `pumpDutyForPressure()` interpolates
between them, giving a feedforward duty for any pressure target, and a small
closed-loop term trims it against the transducer. The trim gain is deliberately
near the bottom of what the measured plant gain allows (~0.02 duty/bar), because
the steep region just above the threshold is where a loop is most likely to ring.

A boot-time self-check asserts the table is monotonic and interpolates through
its knots, so a mis-typed entry fails loudly instead of quietly driving the pump
to the wrong pressure.

### Why not cosine compensation

Phase control's RMS output follows a cosine of the conduction angle, not the
firing delay, so the obvious fix is to compensate `setDuty` and spread the useful
range across the whole scale. That was tried and reverted: it linearises the
*dimmer*, but the dead zone here is dominated by the **pump and puck**, not by
the firing law, so compensation moved the knee without removing it and only
invalidated this table. Measuring the real response and inverting it handles both
non-linearities at once.
