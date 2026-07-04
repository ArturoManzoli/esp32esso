#!/usr/bin/env python3
"""Supervisory CLI for calibrating the thermoblock NTC against the thermocouple.

The firmware exposes a stepped-soak calibration bench: it walks a ladder of
constant heater duties and, on each rung, waits for the *thermocouple* (the
trusted reference) to stop changing before emitting one clean settled sample.
Settling matters -- during a continuous ramp the NTC bead and the thermocouple
sit at different real temperatures because of their different thermal time
constants, which shows up as a wide hysteresis loop in the error plot and
poisons the fit. Only settled points, where both probes agree, are fit.

The firmware streams two kinds of CSV rows: `cal,...` (the continuous stream,
used for temp-vs-time plots) and `sample,...` (one settled point per rung, used
for the fit). A dedicated 200 C thermocouple hard-cut guards the run.

    run     run the soak ladder, record the stream + settled points
    fit     least-squares fit R0/Beta from settled points (optionally --apply)
    verify  run again and overlay NTC error before vs after
    auto    run(before) -> fit --apply -> run(after) -> before/after plots
    monitor just echo telemetry (no heating)

Fit model (Beta / B-parameter), thermocouple in kelvin as the reference:

    ln(R) = ln(R0) + Beta * (1/T - 1/T0)     (T0 = 25 C)

so a straight-line fit of ln(R) against (1/T - 1/T0) yields Beta (slope) and
R0 (exp of the intercept). The fit uses the measured resistance, which is
independent of the NTC calibration, so it is valid regardless of the values
currently flashed.

Ctrl-C always aborts the bench (sends `cal stop`) before closing the port.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import sys
import time
from dataclasses import dataclass
from typing import List, Optional

import serial  # pyserial

T0_C = 25.0
KELVIN = 273.15
CSV_FIELDS = ["t_ms", "phase", "tc_c", "ntc_c", "r_ntc_ohm", "mv", "duty", "sp_c"]
SAMPLE_FIELDS = ["t_ms", "step", "duty", "tc_c", "ntc_c", "r_ntc_ohm", "mv"]


@dataclass
class Sample:
    t_ms: int
    phase: str
    tc_c: float
    ntc_c: float
    r_ntc_ohm: float
    mv: float
    duty: float
    sp_c: float


@dataclass
class SettledPoint:
    """One thermally-settled rung: the only data the fit trusts."""
    t_ms: int
    step: int
    duty: float
    tc_c: float
    ntc_c: float
    r_ntc_ohm: float
    mv: float


# --------------------------------------------------------------------------- #
# Serial helpers
# --------------------------------------------------------------------------- #
def open_port(port: str, baud: int) -> serial.Serial:
    print(f"# opening {port} @ {baud}", flush=True)
    ser = serial.Serial(port, baud, timeout=0.5)
    time.sleep(1.5)
    ser.reset_input_buffer()
    return ser


def send(ser: serial.Serial, line: str) -> None:
    ser.write((line + "\n").encode("ascii"))
    ser.flush()


def parse_cal_row(raw: str) -> Optional[Sample]:
    line = raw.strip()
    if not line.startswith("cal,"):
        return None
    parts = line.split(",")
    if len(parts) != 9:
        return None
    try:
        return Sample(
            t_ms=int(parts[1]),
            phase=parts[2],
            tc_c=float(parts[3]),
            ntc_c=float(parts[4]),
            r_ntc_ohm=float(parts[5]),
            mv=float(parts[6]),
            duty=float(parts[7]),
            sp_c=float(parts[8]),
        )
    except ValueError:
        return None


def parse_sample_row(raw: str) -> Optional[SettledPoint]:
    line = raw.strip()
    if not line.startswith("sample,"):
        return None
    parts = line.split(",")
    if len(parts) != 8:
        return None
    try:
        return SettledPoint(
            t_ms=int(parts[1]),
            step=int(parts[2]),
            duty=float(parts[3]),
            tc_c=float(parts[4]),
            ntc_c=float(parts[5]),
            r_ntc_ohm=float(parts[6]),
            mv=float(parts[7]),
        )
    except ValueError:
        return None


def samples_path_for(out_path: str) -> str:
    stem = out_path[:-4] if out_path.endswith(".csv") else out_path
    return f"{stem}.samples.csv"


def wait_until_cool(ser: serial.Serial, target_c: float, timeout_s: float) -> None:
    """Hold the heater OFF (0 % open-loop) until the machine cools below target.

    Opening the port resets the ESP32, which boots into normal control and
    starts heating toward the brew setpoint; and even idle the loop reheats
    once it drops below setpoint. Parking it in a 0 %-duty tuning step overrides
    the PID so it can actually cool to a useful low-temperature anchor before
    the soak ladder starts. Runs on the already-open connection (no reopen, so
    no extra reset).
    """
    abort_c = max(target_c + 30.0, 140.0)
    print(f"# precool: heater OFF until TC<= {target_c:.0f}C (timeout {timeout_s:.0f}s)",
          flush=True)
    send(ser, f"heat 0 {int(timeout_s) + 60} {abort_c:.0f}")
    start = time.monotonic()
    last_print = 0.0
    while time.monotonic() - start < timeout_s:
        raw = ser.readline().decode("utf-8", errors="replace").strip()
        if not raw or not raw.startswith("{"):
            continue
        try:
            d = json.loads(raw)
        except json.JSONDecodeError:
            continue
        # Gate on the thermoblock (the mass being characterised); the group TC
        # sits on a different mass and cools at its own rate.
        tb = d.get("tb")
        if not isinstance(tb, (int, float)):
            continue
        now = time.monotonic()
        if now - last_print >= 10:
            print(f"  cooling: tb={tb} grp={d.get('grp')}", flush=True)
            last_print = now
        if tb <= target_c:
            print(f"# precool: reached {tb:.1f}C after {now - start:.0f}s", flush=True)
            break
    else:
        print("# precool: timeout; proceeding anyway", flush=True)
    send(ser, "stop")  # end the 0 % hold before the ladder
    time.sleep(0.5)
    ser.reset_input_buffer()


# --------------------------------------------------------------------------- #
# run
# --------------------------------------------------------------------------- #
def do_run(ser: serial.Serial, out_path: str, end_c: float, step_c: float,
           duty_pct: float, max_duration: float,
           precool_c: float = 0.0, precool_timeout: float = 900.0) -> tuple:
    """Run the target-temperature soak ladder. Returns (stream, settled_points).

    The continuous `cal,` stream is written to out_path (for temp-vs-time
    plots); the settled `sample,` points are written to <out>.samples.csv and
    are what the fit consumes. When precool_c > 0 the heater is first held off
    until the machine cools below it, so the ladder starts from a low anchor.
    """
    if precool_c > 0.0:
        wait_until_cool(ser, precool_c, precool_timeout)
    print(f"# cal start {end_c} {step_c} {duty_pct}", flush=True)
    send(ser, f"cal start {end_c} {step_c} {duty_pct}")

    stream: List[Sample] = []
    settled: List[SettledPoint] = []
    started = time.monotonic()
    began = False
    ended = False

    samples_out = samples_path_for(out_path)
    try:
        with open(out_path, "w", newline="") as f, \
                open(samples_out, "w", newline="") as sf:
            writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
            writer.writeheader()
            swriter = csv.DictWriter(sf, fieldnames=SAMPLE_FIELDS)
            swriter.writeheader()
            while True:
                if time.monotonic() - started >= max_duration:
                    print(f"# max duration {max_duration}s reached, stopping", flush=True)
                    send(ser, "cal stop")
                    break
                raw = ser.readline().decode("utf-8", errors="replace")
                if not raw:
                    continue
                stripped = raw.strip()
                if stripped.startswith("#"):
                    print(stripped, flush=True)
                    if "cal_begin" in stripped:
                        began = True
                    if "cal_end" in stripped:
                        ended = True
                        break
                    continue
                p = parse_sample_row(raw)
                if p is not None:
                    began = True
                    swriter.writerow({
                        "t_ms": p.t_ms, "step": p.step, "duty": f"{p.duty:.3f}",
                        "tc_c": f"{p.tc_c:.2f}", "ntc_c": f"{p.ntc_c:.2f}",
                        "r_ntc_ohm": f"{p.r_ntc_ohm:.1f}", "mv": f"{p.mv:.1f}",
                    })
                    sf.flush()
                    settled.append(p)
                    print(f"  * settled rung {p.step} duty={p.duty*100:4.0f}%  "
                          f"tc={p.tc_c:6.1f}C  ntc={p.ntc_c:6.1f}C  "
                          f"R={p.r_ntc_ohm:8.0f}", flush=True)
                    continue
                s = parse_cal_row(raw)
                if s is None:
                    continue
                began = True
                writer.writerow({
                    "t_ms": s.t_ms, "phase": s.phase, "tc_c": f"{s.tc_c:.2f}",
                    "ntc_c": f"{s.ntc_c:.2f}", "r_ntc_ohm": f"{s.r_ntc_ohm:.1f}",
                    "mv": f"{s.mv:.1f}", "duty": f"{s.duty:.3f}", "sp_c": f"{s.sp_c:.2f}",
                })
                f.flush()
                stream.append(s)
    except KeyboardInterrupt:
        print("# Ctrl-C: aborting calibration", flush=True)
        try:
            send(ser, "cal stop")
        except Exception:
            pass

    if not began:
        print("# WARNING: bench never started (TC not OK / faulted / tuning?)", flush=True)
    elif not ended:
        print("# WARNING: no cal_end seen; run may be incomplete", flush=True)
    print(f"# recorded {len(stream)} stream rows -> {out_path}", flush=True)
    print(f"# recorded {len(settled)} settled points -> {samples_out}", flush=True)
    return stream, settled


# --------------------------------------------------------------------------- #
# fit
# --------------------------------------------------------------------------- #
def load_csv(path: str) -> List[Sample]:
    out: List[Sample] = []
    with open(path, newline="") as f:
        for row in csv.DictReader(f):
            try:
                out.append(Sample(
                    t_ms=int(float(row["t_ms"])), phase=row["phase"],
                    tc_c=float(row["tc_c"]), ntc_c=float(row["ntc_c"]),
                    r_ntc_ohm=float(row["r_ntc_ohm"]), mv=float(row["mv"]),
                    duty=float(row["duty"]), sp_c=float(row["sp_c"]),
                ))
            except (ValueError, KeyError):
                continue
    return out


def load_settled_csv(path: str) -> Optional[List[SettledPoint]]:
    """Load a <out>.samples.csv (settled points). None if not that format."""
    with open(path, newline="") as f:
        reader = csv.DictReader(f)
        if reader.fieldnames is None or "step" not in reader.fieldnames:
            return None
        out: List[SettledPoint] = []
        for row in reader:
            try:
                out.append(SettledPoint(
                    t_ms=int(float(row["t_ms"])), step=int(float(row["step"])),
                    duty=float(row["duty"]), tc_c=float(row["tc_c"]),
                    ntc_c=float(row["ntc_c"]), r_ntc_ohm=float(row["r_ntc_ohm"]),
                    mv=float(row["mv"]),
                ))
            except (ValueError, KeyError):
                continue
    return out


def settled_from_stream(stream: List[Sample], slope_max: float = 0.05,
                        win: int = 5) -> List[SettledPoint]:
    """Fallback: extract near-settled points from a `cal,` stream by slope.

    Used only when fitting a stream CSV that has no settled `sample` rows
    (e.g. a legacy recording). Real runs carry explicit settled points.
    """
    pts: List[SettledPoint] = []
    n = len(stream)
    for i in range(n):
        a = max(0, i - win)
        b = min(n - 1, i + win)
        dt = (stream[b].t_ms - stream[a].t_ms) / 1000.0
        if dt <= 0:
            continue
        slope = (stream[b].tc_c - stream[a].tc_c) / dt
        if abs(slope) < slope_max:
            s = stream[i]
            pts.append(SettledPoint(s.t_ms, -1, s.duty, s.tc_c, s.ntc_c,
                                    s.r_ntc_ohm, s.mv))
    return pts


def fit_beta_r0(pairs: List[tuple]) -> tuple:
    """Linear least squares of ln(R) vs (1/T - 1/T0). Returns (r0, beta, rmse_c)."""
    import numpy as np

    if len(pairs) < 2:
        raise ValueError("need at least two temperature points to fit")
    t0k = T0_C + KELVIN
    x = np.array([1.0 / (tc + KELVIN) - 1.0 / t0k for tc, _ in pairs])
    y = np.array([math.log(r) for _, r in pairs])
    slope, intercept = np.polyfit(x, y, 1)
    beta = float(slope)
    r0 = float(math.exp(intercept))

    # RMSE expressed in C: invert the model to predict T from each R.
    resid = []
    for tc, r in pairs:
        inv_t = 1.0 / t0k + (1.0 / beta) * math.log(r / r0)
        pred_c = 1.0 / inv_t - KELVIN
        resid.append(pred_c - tc)
    rmse = float(math.sqrt(sum(e * e for e in resid) / len(resid)))
    return r0, beta, rmse


def do_fit(points: List[SettledPoint], tmin: float, tmax: float) -> tuple:
    """Fit R0/Beta from settled points and print a per-point residual table."""
    usable = [p for p in points
              if math.isfinite(p.tc_c) and math.isfinite(p.r_ntc_ohm)
              and p.r_ntc_ohm > 0.0 and tmin <= p.tc_c <= tmax]
    if len(usable) < 2:
        raise SystemExit("# fit: need >=2 settled points in range "
                         f"({tmin:.0f}..{tmax:.0f}C); got {len(usable)}")
    pairs = [(p.tc_c, p.r_ntc_ohm) for p in usable]
    r0, beta, rmse = fit_beta_r0(pairs)
    t0k = T0_C + KELVIN
    print(f"# fit: {len(usable)} settled points ({tmin:.0f}..{tmax:.0f}C)", flush=True)
    print("#   duty%   tc_C     R_ohm    fit_C   resid_C", flush=True)
    for p in usable:
        inv_t = 1.0 / t0k + (1.0 / beta) * math.log(p.r_ntc_ohm / r0)
        pred_c = 1.0 / inv_t - KELVIN
        print(f"#   {p.duty*100:4.0f}  {p.tc_c:7.2f}  {p.r_ntc_ohm:8.0f}  "
              f"{pred_c:7.2f}  {pred_c - p.tc_c:+7.2f}", flush=True)
    print(f"# fit: R0={r0:.1f} ohm  Beta={beta:.1f} K  RMSE={rmse:.2f} C", flush=True)
    return r0, beta, rmse


def apply_calibration(ser: serial.Serial, r0: float, beta: float) -> None:
    print(f"# ntc set {r0:.1f} {beta:.1f}", flush=True)
    send(ser, f"ntc set {r0:.4f} {beta:.4f}")
    time.sleep(0.3)
    send(ser, "ntc save")
    time.sleep(0.3)
    # Drain acknowledgements.
    deadline = time.monotonic() + 1.5
    while time.monotonic() < deadline:
        raw = ser.readline().decode("utf-8", errors="replace").strip()
        if raw:
            print(raw, flush=True)


# --------------------------------------------------------------------------- #
# plots
# --------------------------------------------------------------------------- #
def plot_run(samples: List[Sample], path: str, title: str) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    if not samples:
        print("# plot: no samples", flush=True)
        return
    t0 = samples[0].t_ms
    t = [(s.t_ms - t0) / 1000.0 for s in samples]
    tc = [s.tc_c for s in samples]
    ntc = [s.ntc_c for s in samples]
    duty = [s.duty * 100.0 for s in samples]

    fig, ax = plt.subplots(figsize=(10, 5))
    ax.plot(t, tc, label="thermocouple (reference)", color="#111111", linewidth=1.6)
    ax.plot(t, ntc, label="NTC", color="#d9480f", linewidth=1.4)
    ax.set_xlabel("time (s)")
    ax.set_ylabel("temperature (C)")
    ax.set_title(title)
    ax.grid(True, alpha=0.3)

    ax2 = ax.twinx()
    ax2.fill_between(t, duty, 0, color="#4dabf7", alpha=0.15, step="pre")
    ax2.set_ylabel("heater duty (%)", color="#1971c2")
    ax2.set_ylim(0, 100)

    ax.legend(loc="upper left")
    fig.tight_layout()
    fig.savefig(path, dpi=150)
    plt.close(fig)
    print(f"# wrote {path}", flush=True)


def plot_error_overlay(before: List[Sample], after: List[Sample], path: str) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    fig, ax = plt.subplots(figsize=(9, 5))
    for samples, color, label in ((before, "#e03131", "before"),
                                   (after, "#2f9e44", "after")):
        pts = [(s.tc_c, s.ntc_c - s.tc_c) for s in samples
               if math.isfinite(s.tc_c) and math.isfinite(s.ntc_c)]
        if pts:
            xs, ys = zip(*sorted(pts))
            ax.scatter(xs, ys, s=8, color=color, alpha=0.5, label=label)
    ax.axhline(0.0, color="#111111", linewidth=1.0)
    ax.set_xlabel("thermocouple reference (C)")
    ax.set_ylabel("NTC error (NTC - TC) (C)")
    ax.set_title("NTC calibration error vs thermocouple")
    ax.grid(True, alpha=0.3)
    ax.legend()
    fig.tight_layout()
    fig.savefig(path, dpi=150)
    plt.close(fig)
    print(f"# wrote {path}", flush=True)


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def add_common_serial(p: argparse.ArgumentParser) -> None:
    p.add_argument("--port", default="/dev/ttyUSB0", help="serial device (default: /dev/ttyUSB0)")
    p.add_argument("--baud", type=int, default=115200)


def add_run_opts(p: argparse.ArgumentParser) -> None:
    p.add_argument("--end", type=float, default=110.0,
                   help="highest rung target C (default: 110)")
    p.add_argument("--step", type=float, default=20.0,
                   help="target increment between rungs C (default: 20)")
    p.add_argument("--duty", type=float, default=60.0,
                   help="max heater duty %% while chasing a target (default: 60)")
    p.add_argument("--precool", type=float, default=40.0,
                   help="hold heater off until TC below this C before the ladder "
                        "(0 disables; default: 40)")
    p.add_argument("--precool-timeout", type=float, default=900.0,
                   help="max precool wait seconds (default: 900)")
    p.add_argument("--max-duration", type=float, default=1800.0,
                   help="hard cap on capture seconds (default: 1800)")


def add_fit_opts(p: argparse.ArgumentParser) -> None:
    p.add_argument("--tmin", type=float, default=20.0, help="min TC C for fit (default: 20)")
    p.add_argument("--tmax", type=float, default=200.0, help="max TC C for fit (default: 200)")


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    pr = sub.add_parser("run", help="record a calibration ramp to CSV")
    add_common_serial(pr)
    add_run_opts(pr)
    pr.add_argument("--out", required=True)
    pr.add_argument("--plot", help="optional temp-vs-time PNG path")

    pf = sub.add_parser("fit", help="fit R0/Beta from a recorded CSV")
    add_common_serial(pf)
    add_fit_opts(pf)
    pf.add_argument("--csv", required=True)
    pf.add_argument("--apply", action="store_true", help="push + save fit to the board")

    pv = sub.add_parser("verify", help="record an after-run and overlay error vs a before CSV")
    add_common_serial(pv)
    add_run_opts(pv)
    pv.add_argument("--before", required=True, help="before CSV to overlay")
    pv.add_argument("--out", required=True, help="after CSV path")
    pv.add_argument("--plot", default="ntc_before_after.png")

    pa = sub.add_parser("auto", help="run(before) -> fit --apply -> run(after) -> plots")
    add_common_serial(pa)
    add_run_opts(pa)
    add_fit_opts(pa)
    pa.add_argument("--prefix", default="ntc_cal", help="output file prefix")

    pm = sub.add_parser("monitor", help="echo telemetry, no heating")
    add_common_serial(pm)

    return p.parse_args()


def main() -> int:
    args = parse_args()

    if args.cmd == "fit":
        # Offline path: only touches serial when --apply. Prefer the settled
        # `.samples.csv`; fall back to slope-filtering a raw stream CSV.
        points = load_settled_csv(args.csv)
        if points is None:
            print("# fit: no settled columns, slope-filtering stream", flush=True)
            points = settled_from_stream(load_csv(args.csv))
        r0, beta, _ = do_fit(points, args.tmin, args.tmax)
        if args.apply:
            ser = open_port(args.port, args.baud)
            try:
                apply_calibration(ser, r0, beta)
            finally:
                ser.close()
        return 0

    ser = open_port(args.port, args.baud)
    try:
        if args.cmd == "monitor":
            print("# monitoring (Ctrl-C to stop)", flush=True)
            while True:
                raw = ser.readline().decode("utf-8", errors="replace").rstrip()
                if raw:
                    print(raw, flush=True)

        if args.cmd == "run":
            stream, _ = do_run(ser, args.out, args.end, args.step, args.duty,
                               args.max_duration, args.precool, args.precool_timeout)
            if args.plot:
                plot_run(stream, args.plot, "NTC calibration run")

        elif args.cmd == "verify":
            after, _ = do_run(ser, args.out, args.end, args.step, args.duty,
                              args.max_duration, args.precool, args.precool_timeout)
            before = load_csv(args.before)
            plot_error_overlay(before, after, args.plot)

        elif args.cmd == "auto":
            before_csv = f"{args.prefix}_before.csv"
            after_csv = f"{args.prefix}_after.csv"
            print("=== run BEFORE ===", flush=True)
            before, before_pts = do_run(ser, before_csv, args.end, args.step,
                                        args.duty, args.max_duration,
                                        args.precool, args.precool_timeout)
            plot_run(before, f"{args.prefix}_before.png", "NTC run (before calibration)")
            print("=== fit + apply ===", flush=True)
            r0, beta, _ = do_fit(before_pts, args.tmin, args.tmax)
            apply_calibration(ser, r0, beta)
            time.sleep(1.0)
            ser.reset_input_buffer()
            print("=== run AFTER ===", flush=True)
            after, _ = do_run(ser, after_csv, args.end, args.step, args.duty,
                              args.max_duration, args.precool, args.precool_timeout)
            plot_run(after, f"{args.prefix}_after.png", "NTC run (after calibration)")
            plot_error_overlay(before, after, f"{args.prefix}_before_after.png")
            print("# done", flush=True)
    except KeyboardInterrupt:
        try:
            send(ser, "cal stop")
        except Exception:
            pass
    finally:
        ser.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
