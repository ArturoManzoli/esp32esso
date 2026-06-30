#!/usr/bin/env python3
"""Capture esp32esso JSON-lines telemetry to CSV.

Opens the serial port, triggers the open-loop step-response bench by
sending the `s` command (unless --no-trigger), then records every
telemetry line until the firmware reports the bench has ended (the
`tune` field transitions back from 1 to 0) plus a short cooldown.
Ctrl-C aborts the bench safely (sends `q`) before closing.
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
import time
from typing import Optional

import serial  # pyserial


CSV_FIELDS = [
    "t_ms",
    "temp_c",
    "setpoint_c",
    "pid_out",
    "heater_on",
    "fault",
    "tuning",
]


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--port", default="/dev/ttyACM0",
                   help="serial device path (default: /dev/ttyACM0)")
    p.add_argument("--baud", type=int, default=115200,
                   help="baud rate (default: 115200)")
    p.add_argument("--out", required=True, help="output CSV path")
    p.add_argument("--max-duration", type=float, default=240.0,
                   help="hard cap on capture duration in seconds (default: 240)")
    p.add_argument("--cooldown", type=float, default=5.0,
                   help="seconds to keep recording after the bench ends (default: 5)")
    p.add_argument("--no-trigger", action="store_true",
                   help="do not send the start command; just record")
    return p.parse_args()


def parse_line(raw: str) -> Optional[dict]:
    line = raw.strip()
    if not line or line.startswith("#"):
        return None
    try:
        obj = json.loads(line)
    except json.JSONDecodeError:
        return None
    if "t" not in obj:
        return None
    return obj


def make_row(obj: dict) -> dict:
    return {
        "t_ms": obj.get("t", ""),
        "temp_c": obj.get("temp", ""),
        "setpoint_c": obj.get("sp", ""),
        "pid_out": obj.get("out", ""),
        "heater_on": obj.get("heat", ""),
        "fault": obj.get("fault", ""),
        "tuning": int(obj.get("tune", 0) or 0),
    }


def main() -> int:
    args = parse_args()
    print(f"# opening {args.port} @ {args.baud}", flush=True)
    ser = serial.Serial(args.port, args.baud, timeout=0.5)
    time.sleep(1.5)
    ser.reset_input_buffer()

    if not args.no_trigger:
        print('# sending start command "s"', flush=True)
        ser.write(b"s\n")
        ser.flush()

    print(f"# writing {args.out}", flush=True)
    started = time.monotonic()
    rows_written = 0
    max_temp = float("-inf")
    saw_tune_active = False
    bench_ended_at: Optional[float] = None

    try:
        with open(args.out, "w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
            writer.writeheader()

            while True:
                if time.monotonic() - started >= args.max_duration:
                    print(f"# max duration {args.max_duration}s reached", flush=True)
                    break

                raw = ser.readline().decode("utf-8", errors="replace")
                if not raw:
                    continue

                obj = parse_line(raw)
                if obj is None:
                    sys.stdout.write(raw if raw.endswith("\n") else raw + "\n")
                    sys.stdout.flush()
                    continue

                row = make_row(obj)
                writer.writerow(row)
                f.flush()
                rows_written += 1

                try:
                    t = float(row["temp_c"])
                    if t > max_temp:
                        max_temp = t
                except (TypeError, ValueError):
                    pass

                if row["tuning"] == 1:
                    saw_tune_active = True
                    bench_ended_at = None
                elif saw_tune_active and row["tuning"] == 0 and bench_ended_at is None:
                    bench_ended_at = time.monotonic()
                    print("# bench finished, recording cooldown", flush=True)

                if bench_ended_at is not None and (
                    time.monotonic() - bench_ended_at
                ) >= args.cooldown:
                    break
    except KeyboardInterrupt:
        print("# Ctrl-C: aborting bench", flush=True)
        try:
            ser.write(b"q\n")
            ser.flush()
        except Exception:
            pass
    finally:
        ser.close()

    print(
        f"# captured {rows_written} samples, max temp "
        f"{max_temp if max_temp != float('-inf') else 'n/a'}C",
        flush=True,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
