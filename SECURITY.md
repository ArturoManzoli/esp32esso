# Security and Safety Policy

Esp32esso straddles two kinds of "security" risks: classic software
vulnerabilities (firmware/BLE/app) and **physical safety** risks (mains
voltage, hot water under pressure, a heater capable of starting a fire).
This file covers both.

## Supported versions

The project is pre-1.0 and changes rapidly. Only the tip of `main` is
supported. Tagged releases are best-effort.

## Reporting a vulnerability or a safety issue

Please **do not** open a public GitHub issue for either of the categories
below. Open a private report instead:

1. Preferred: open a [GitHub Security Advisory](
   https://github.com/ArturoManzoli/esp32esso/security/advisories/new) on this
   repository.
2. Fallback: contact the maintainer privately via a direct message on
   GitHub (`@ArturoManzoli`).

Reports should include:

- a clear description of the issue and how to reproduce it
- the firmware version (`git describe --tags` or commit SHA) and the machine
  profile in use
- whether the issue is exploitable remotely (BLE proximity counts as remote)
  or only with physical access

You can expect an acknowledgement within 7 days and a triage decision within
30 days.

## What counts as a safety-critical issue

The following must be reported privately because a public PoC could harm
someone:

- the heater can be driven above the configured safety limit
- the SSR or pump can be left ON after a watchdog reset, brown-out, or BLE
  disconnect
- the firmware can ignore the brew-switch off state
- the BLE service accepts setpoint or profile changes without the user's
  device pairing/bonding being valid
- OTA can be triggered without authentication (once OTA exists)

## What is explicitly out of scope

- physical electrical safety of your own installation (grounding, wire gauge,
  isolation, OPV pressure) - that is on you, see the warning in `README.md`
- damage caused by ignoring the documented machine profile constraints
- support for non-compatible machines (heat exchangers, thermocoils with
  cast-in heaters, rotary pumps, etc.)
- third-party hardware quality (cheap SSRs, fake MAX31855 chips, etc.)

## Disclosure timeline

Once a fix is available we will publish the advisory with credit to the
reporter (unless they prefer to remain anonymous), describe the affected
versions, and recommend a remediation. Coordinated disclosure is preferred.
