# tools

Host-side tooling for the Esp32esso firmware. Each subdirectory is its own
self-contained tool with its own README.

| Tool | Purpose |
| ---- | ------- |
| [`tuning/`](tuning/) | Capture step-response telemetry and derive PID gains from the fitted FOPDT model |

## Installation

All tools share a single `requirements.txt`:

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r tools/requirements.txt
```

Tested on Python 3.10+.
