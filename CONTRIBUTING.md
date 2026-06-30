# Contributing to Esp32esso

Thanks for taking the time to look. This project is small and early, so
contributions of any size are welcome, including documentation, install
reports for new machines, and bug reports.

By participating you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md)
and to license your contributions under the project's
[GPL-3.0-or-later](LICENSE) license.

## Where to start

- Found a bug or odd behaviour? Open an issue using the bug-report template.
- Want to add support for a new machine? Open a "new machine profile" issue
  before opening a PR so we can agree on the profile schema together.
- Picking up an existing issue? Comment on it so two people don't duplicate
  the work.
- Not sure where to start? Issues tagged `good first issue` are deliberately
  scoped small.

## Development setup

```bash
git clone https://github.com/<your-fork>/esp32esso.git
cd esp32esso/firmware
pio run                 # build the default env
pio run -t upload       # flash a connected ESP32-S3
pio device monitor      # serial telemetry
```

The Android app (added at Stage 2) lives under `app-android/` and is opened
with Android Studio.

## Branching

- Branch off a freshly pulled `main`.
- Name branches `<issue-number>-<short-kebab-title>` where possible.
- One logical change per branch. If the change is bigger than ~1500 lines or
  spans multiple architectural layers, split it into stacked PRs.

## Commits

- Subject style: `<scope>: <lowercase imperative description>`. Keep the
  subject under ~70 characters and skip the trailing period.
- Every commit must include a short body wrapped at ~80 columns explaining
  the *why*, not just the *what*. No subject-only commits.
- Commits must be atomic and self-contained: the project must build, lint,
  and (where applicable) flash at every commit.
- No AI co-authoring trailers and no `Generated-by:` footers.

Examples:

```
firmware: hal: add max31855 thermocouple driver

Wrap the SPI transfer behind a TemperatureSensor interface so the PID loop
stays unaware of the concrete sensor and we can mock the reading in unit
tests.
```

## Pull requests

- Title: `<Scope>: <Sentence-cased description>` (Title-Cased scope,
  user-facing tone). Don't restate the commit subject verbatim.
- Description: a short bullet list. For PRs that fix a bug, split into
  `**Problem:**` and `**Fix:**` sections. For features, a flat bullet list.
  End with `Closes #N` when an issue is being closed.
- Mark the PR ready for review only after CI is green locally
  (`pio run` + `pio check`).

## Hardware contributions

Hardware additions (a new machine profile, a wiring diagram, a per-tier BOM
update) should land in `hardware/` and be referenced from `docs/`. Always
include:

- machine model, voltage variant, and year range
- a clear photo of the wiring path you touched
- the safety notes specific to that machine (grounding, isolation, OPV
  pressure, plastic vs metal thermoblock, etc.)

## License of contributions

By submitting a contribution you license it to the project under
GPL-3.0-or-later. If you cannot license your contribution under those terms,
do not submit it.
