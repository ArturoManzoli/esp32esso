# tools/tuning

Step-response benching and PID-gain derivation for the closed-loop
temperature controller. The procedure assumes the firmware has the
open-loop tuning mode (the `s` serial command) shipped in
[`firmware/src/control/temperature_loop.h`](../../firmware/src/control/temperature_loop.h).

## Workflow

1. Flash the firmware with the matching machine-profile env, for example
   `pio run -e esp32-s3-oster-xpert -t upload`.
2. With the machine cold and the watcher on the bench, run `capture.py`.
   It opens the serial port, sends the `s` command to start the bench,
   records every JSON telemetry line to CSV, and exits cleanly when the
   firmware reports the bench is over.
3. Run `analyze.py` on the captured CSV to fit a first-order plus dead
   time (FOPDT) model and get suggested Ziegler-Nichols and Cohen-Coon
   PID gains.
4. Update the active machine profile in `firmware/src/profile/<machine>.cpp`
   with the chosen gains, re-flash, and verify closed-loop behaviour.

See [`docs/machines/oster-xpert.md`](../../docs/machines/oster-xpert.md)
for the full safety checklist and the step-by-step bench procedure.

## Scripts

### `capture.py`

```bash
python3 tools/tuning/capture.py \
    --port /dev/ttyACM0 \
    --out tools/tuning/data/step-$(date +%Y%m%dT%H%M%S).csv
```

Opens the port, gives the board ~1.5 s to settle, sends the `s` command,
and records JSON lines to CSV until the firmware reports the bench has
ended (the `tune` field transitions back from 1 to 0) plus a short
cooldown. `Ctrl-C` aborts the bench by sending `q` first, then flushes
whatever rows were captured. Pass `--no-trigger` if you want to capture
without starting a bench (e.g. to record idle behaviour).

### `analyze.py`

```bash
python3 tools/tuning/analyze.py \
    --in tools/tuning/data/step-20260630T200000.csv \
    --duty 0.50 \
    --out-json tools/tuning/data/step-20260630T200000.json \
    --out-plot tools/tuning/data/step-20260630T200000.png
```

Reads the CSV, isolates the rows where `tuning==1`, fits the FOPDT model
via `scipy.optimize.curve_fit`, and writes:

- a JSON file with the process gain K, time constant tau, dead time L,
  ambient T0, steady-state Tss, and PID-gain suggestions via
  Ziegler-Nichols and Cohen-Coon open-loop rules
- a PNG plot of the measured curve, the fitted model, and the 63.2 % /
  dead-time markers
