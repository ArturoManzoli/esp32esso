# tools/ntc-cal

Calibrates the thermoblock NTC thermistor against the thermocouple, which is
the trusted reference. Both probes must be clamped to the **same** thermal mass
(on the bench: the tip of a soldering iron heated by the SSR, or a lighter for
a no-mains shakedown). The firmware bench and its 200 C thermocouple hard-cut
live in
[`firmware/src/control/temperature_loop.h`](../../firmware/src/control/temperature_loop.h);
the serial command protocol is in
[`firmware/src/main.cpp`](../../firmware/src/main.cpp).

## How it works

The firmware runs a **target-temperature soak**: it walks a ladder of TC
targets (from the first rung above the current temperature, stepping by
`stepC` up to `endC`), drives the heater toward each with a proportional law,
and waits for the **thermocouple** to stop changing (stays within a 0.6 C band
for a dwell) before capturing one clean point. Two reasons: (1) during a
*continuous ramp* the NTC bead and the thermocouple sit at different real
temperatures because of their different thermal time constants, which shows up
as a wide **hysteresis loop** (NTC hot on the way up, cold on the way down) and
poisons the Beta fit — only settled points, where both probes actually agree,
are trustworthy; (2) aiming at temperatures (rather than fixed duties) spreads
the settled points evenly across the operating band, so the fit is not
dominated by a cluster of near-identical low-temperature points.

Every 200 ms the firmware streams the continuous CSV row (used for temp-vs-time
plots):

```
cal,<t_ms>,<phase>,<tc_c>,<ntc_c>,<r_ntc_ohm>,<mv>,<duty>,<sp_c>
```

and, once per settled rung, one clean sample row (used for the fit):

```
sample,<t_ms>,<step>,<duty>,<tc_c>,<ntc_c>,<r_ntc_ohm>,<mv>
```

`r_ntc_ohm` is the measured thermistor resistance, independent of the current
calibration, so the fit is valid no matter what R0/Beta are flashed. The tool
fits the Beta (B-parameter) model

```
ln(R) = ln(R0) + Beta * (1/T - 1/T0)      (T0 = 25 C, T from the thermocouple)
```

pushes the fitted `R0`/`Beta` to the board (`ntc set`), and persists them to NVS
(`ntc save`) so they survive reboots.

> **Safety**
> - A dedicated hard-cut trips the heater if the **thermocouple** reaches
>   **200 C**, independent of the NTC being characterised.
> - Duty is clamped (default 30 %, capped at 60 % in firmware) for low-mass tips.
> - `Ctrl-C` in any command sends `cal stop`.
> - Do the first run with `monitor` / a lighter and **watch the SSR LED cycle**
>   before connecting the heater to mains.

## Serial command protocol

Line-based, newline-terminated, 115200 baud:

| Command | Effect |
| ------- | ------ |
| `ping` | `# pong` liveness check |
| `status` | one JSON status line (temps, cal/tune state, R0/Beta) |
| `cal start [endC] [stepC] [maxDuty%]` | start the soak ladder (defaults 110 / 20 / 60) |
| `cal stop` | abort calibration |
| `ntc show` | print current R0/Beta |
| `ntc set <r0> <beta>` | apply fitted values live |
| `ntc save` | persist R0/Beta to NVS |
| `clear` / `c` | clear a latched fault |
| `stop` / `q` | stop calibration or tuning |
| `help` / `h` | full command list |

## Shakedown (no mains)

1. Flash: `pio run -e esp32-oster-xpert -t upload`.
2. Clamp both probes to the iron tip.
3. `python3 tools/ntc-cal/ntc_cal.py monitor --port /dev/ttyUSB0` and confirm
   both temperatures read room temperature and the thermocouple is not offline.
4. Start a low, short run and heat the tip with a lighter while watching the
   **SSR LED** — it chases each target and shows the commanded duty:
   ```bash
   python3 tools/ntc-cal/ntc_cal.py run --port /dev/ttyUSB0 \
       --end 70 --step 20 --duty 40 --out shakedown.csv --plot shakedown.png
   ```
   (No electrical heat without mains; the lighter provides the heat and the LED
   shows the commanded duty.)

## Full calibration (heater on mains via the SSR)

One shot — records a before run, fits, applies, records an after run, and writes
all plots:

```bash
python3 tools/ntc-cal/ntc_cal.py auto --port /dev/ttyUSB0 \
    --end 110 --step 20 --duty 60 --prefix ntc_cal
```

Each rung chases its target and then holds until the thermocouple settles, so a
full run (e.g. targets 50/70/90/110) takes roughly 8–15 min depending on how
fast the mass settles.

> **Precool.** Opening the port resets the ESP32, which boots into normal
> control and heats toward the brew setpoint, so a run started on a warm machine
> would only capture high-temperature points. By default the tool first holds
> the heater **off** (`heat 0`, overriding the PID) until the thermocouple drops
> below `--precool` (40 °C) so the ladder spans a useful range. Set `--precool 0`
> to skip it. Keep a single instance on the port — two processes on the same
> `/dev/ttyUSB0` corrupt the stream and crash.

Outputs:

- `ntc_cal_before.csv` / `ntc_cal_after.csv` — continuous CSV streams
- `ntc_cal_before.samples.csv` / `ntc_cal_after.samples.csv` — settled points (what the fit uses)
- `ntc_cal_before.png` / `ntc_cal_after.png` — temperature vs time (both probes + duty)
- `ntc_cal_before_after.png` — NTC error `(NTC - TC)` vs temperature, before vs after

### Or step by step

```bash
# 1. record a run (writes before.csv + before.samples.csv)
python3 tools/ntc-cal/ntc_cal.py run --port /dev/ttyUSB0 --out before.csv --plot before.png

# 2. fit the settled points and push to the board
python3 tools/ntc-cal/ntc_cal.py fit --csv before.samples.csv --apply --port /dev/ttyUSB0

# 3. record an after run and overlay the error against the before run
python3 tools/ntc-cal/ntc_cal.py verify --port /dev/ttyUSB0 --before before.csv \
    --out after.csv --plot before_after.png
```

`fit` without `--apply` is fully offline (prints R0/Beta, a per-point residual
table, and RMSE only). Point `--csv` at the `.samples.csv` (settled points); if
given a raw `cal` stream it falls back to slope-filtering the flat sections.

## Persisting to the firmware default

NVS values override the compiled default on boot. To bake the fit into source
so a fresh flash starts calibrated, edit `kThermoblockNtcCal` in
[`firmware/src/profile/oster_xpert.cpp`](../../firmware/src/profile/oster_xpert.cpp)
with the reported `R0`/`Beta`.

## Scripts

### `ntc_cal.py`

Subcommands: `run`, `fit`, `verify`, `auto`, `monitor`. See `--help` on each.
Shared options: `--port` (default `/dev/ttyUSB0`), `--baud` (default 115200).
Run options: `--end` (highest target C), `--step` (target increment C), `--duty`
(max % while chasing a target), `--precool` (cool-below C before the ladder; 0
disables), `--precool-timeout`.
Fit options: `--tmin` / `--tmax` (fit window C). Depends on `pyserial`, `numpy`,
`matplotlib` (see
[`../requirements.txt`](../requirements.txt)); `matplotlib` is only needed for
the plotting paths.
