#!/usr/bin/env python3
"""Fit FOPDT model and derive PID gains from a captured step-response CSV.

Reads a CSV produced by ``capture.py``, isolates the rows captured while
the firmware was in tuning mode (``tuning==1``), and fits a first-order
plus dead time (FOPDT) model:

    y(t) = T0                                       for t < L
    y(t) = T0 + K * (1 - exp(-(t - L) / tau))       for t >= L

with t measured from the start of the bench in seconds. K already folds
the duty step into its units (delta degrees Celsius across the whole
input step), so the per-duty process gain reported is ``K / duty``.

The fitted FOPDT params are then plugged into open-loop tuning rules:

  * Ziegler-Nichols (PID):
        Kp = 1.2 * tau / (K_proc * L)
        Ti = 2 * L
        Td = 0.5 * L
  * Cohen-Coon (PID):
        r  = tau / L
        Kp = (1.35 + 0.25 / r) * tau / (K_proc * L)
        Ti = L * (2.5 + 0.46 / r) / (1.0 + 0.61 / r)
        Td = 0.37 * L / (1.0 + 0.19 / r)

Outputs a JSON with the FOPDT parameters and both rule sets, and a PNG
plot overlaying the measured curve with the fitted model.
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from typing import Optional

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
from scipy.optimize import curve_fit  # noqa: E402


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--in", dest="in_path", required=True, help="input CSV path")
    p.add_argument("--duty", type=float, default=0.50,
                   help="heater duty fraction used during the bench (default: 0.50)")
    p.add_argument("--out-json", dest="out_json", required=True,
                   help="output JSON path")
    p.add_argument("--out-plot", dest="out_plot", required=True,
                   help="output PNG plot path")
    return p.parse_args()


def fopdt(t: np.ndarray, K: float, tau: float, L: float, T0: float) -> np.ndarray:
    return np.where(t < L, T0, T0 + K * (1.0 - np.exp(-(t - L) / tau)))


def load_rows(path: str) -> list[dict]:
    rows: list[dict] = []
    with open(path, "r") as f:
        for r in csv.DictReader(f):
            try:
                rows.append({
                    "t_ms": float(r["t_ms"]),
                    "temp_c": float(r["temp_c"]),
                    "tuning": int(r.get("tuning", 0) or 0),
                    "fault": r.get("fault", ""),
                })
            except (ValueError, KeyError):
                continue
    return rows


def zn_pid(K_proc: float, tau: float, L: float) -> Optional[dict]:
    if L <= 0.01 or K_proc <= 0:
        return None
    kp = 1.2 * tau / (K_proc * L)
    ti = 2.0 * L
    td = 0.5 * L
    return {"kp": kp, "ki": kp / ti, "kd": kp * td, "ti_s": ti, "td_s": td}


def cohen_coon_pid(K_proc: float, tau: float, L: float) -> Optional[dict]:
    if L <= 0.01 or K_proc <= 0:
        return None
    r = tau / L
    kp = (1.35 + 0.25 / r) * tau / (K_proc * L)
    ti = L * (2.5 + 0.46 / r) / (1.0 + 0.61 / r)
    td = 0.37 * L / (1.0 + 0.19 / r)
    return {"kp": kp, "ki": kp / ti, "kd": kp * td, "ti_s": ti, "td_s": td}


def main() -> int:
    args = parse_args()
    rows = load_rows(args.in_path)
    if not rows:
        print("error: no rows in input", file=sys.stderr)
        return 1

    tune_rows = [r for r in rows if r["tuning"] == 1]
    if len(tune_rows) < 10:
        print(
            f"error: only {len(tune_rows)} tuning samples (need >= 10)",
            file=sys.stderr,
        )
        return 1

    t0_ms = tune_rows[0]["t_ms"]
    pre = [r for r in rows if t0_ms - 3000 <= r["t_ms"] < t0_ms]
    T0_guess = (
        float(np.mean([r["temp_c"] for r in pre]))
        if pre
        else float(tune_rows[0]["temp_c"])
    )

    t_arr = np.array([(r["t_ms"] - t0_ms) / 1000.0 for r in tune_rows])
    y_arr = np.array([r["temp_c"] for r in tune_rows])

    tail = max(5, len(y_arr) // 5)
    Tss_guess = float(np.mean(y_arr[-tail:]))
    K_step_guess = max(Tss_guess - T0_guess, 1.0)
    tau_guess = max((t_arr[-1] - t_arr[0]) / 3.0, 1.0)
    L_guess = 1.0

    try:
        popt, _ = curve_fit(
            fopdt,
            t_arr,
            y_arr,
            p0=[K_step_guess, tau_guess, L_guess, T0_guess],
            bounds=([0.0, 0.1, 0.0, -50.0], [500.0, 1200.0, 60.0, 200.0]),
            maxfev=10000,
        )
    except Exception as e:
        print(f"error: FOPDT fit failed: {e}", file=sys.stderr)
        return 1

    K_step, tau, L, T0 = (float(v) for v in popt)
    K_proc = K_step / args.duty if args.duty > 0 else float("nan")

    zn = zn_pid(K_proc, tau, L)
    cc = cohen_coon_pid(K_proc, tau, L)

    result = {
        "input": args.in_path,
        "duty": args.duty,
        "samples": len(tune_rows),
        "duration_s": float(t_arr[-1]),
        "fopdt": {
            "K_celsius_per_duty": K_proc,
            "tau_s": tau,
            "L_s": L,
            "T0_c": T0,
            "Tss_c": T0 + K_step,
        },
        "gains": {
            "ziegler_nichols": zn,
            "cohen_coon": cc,
        },
    }

    with open(args.out_json, "w") as f:
        json.dump(result, f, indent=2)
        f.write("\n")

    fig, ax = plt.subplots(figsize=(10, 6))
    ax.plot(t_arr, y_arr, "b.", label="measured", markersize=3)
    t_fine = np.linspace(float(t_arr[0]), float(t_arr[-1]), 500)
    ax.plot(
        t_fine,
        fopdt(t_fine, K_step, tau, L, T0),
        "r-",
        label=f"FOPDT fit: K={K_proc:.2f} C/duty  tau={tau:.1f}s  L={L:.2f}s",
    )
    ax.axhline(
        T0 + 0.632 * K_step,
        color="g", linestyle="--", alpha=0.5,
        label="63.2 % rise",
    )
    ax.axvline(
        L, color="orange", linestyle="--", alpha=0.5,
        label=f"dead time L={L:.2f}s",
    )
    ax.set_xlabel("time since bench start (s)")
    ax.set_ylabel("brew-side temperature (deg C)")
    ax.set_title(f"Step response, open-loop duty {args.duty * 100:.0f}%")
    ax.legend(loc="lower right")
    ax.grid(True, alpha=0.3)
    fig.tight_layout()
    fig.savefig(args.out_plot, dpi=120)

    print(
        f"fitted: K={K_proc:.3f} C/duty  tau={tau:.2f}s  L={L:.2f}s  "
        f"T0={T0:.1f}C  Tss={T0 + K_step:.1f}C"
    )
    if zn:
        print(
            f"ZN  PID: kp={zn['kp']:.4f}  ki={zn['ki']:.5f}  kd={zn['kd']:.3f}"
        )
    if cc:
        print(
            f"CC  PID: kp={cc['kp']:.4f}  ki={cc['ki']:.5f}  kd={cc['kd']:.3f}"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
