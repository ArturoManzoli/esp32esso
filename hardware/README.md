# Hardware

Per-machine wiring diagrams and bills of materials live in their own
subdirectory:

| Machine | Folder |
| ------- | ------ |
| Oster Xpert (220-240 V) | [`oster-xpert/`](oster-xpert/) |

Each machine folder contains a `README.md` with the per-tier BOM, and one
markdown file per install tier with the wiring change required to enable
that tier. KiCad schematics for any custom carrier boards live alongside.

## Shared bill of materials (any machine, Tier 1)

These items are common to every Tier 1 install regardless of machine:

| Qty | Part | Notes |
| --- | ---- | ----- |
| 1 | ESP32 dev board | **Pick one.** Classic **ESP32-WROOM** DevKit (≥ 4 MB flash) is the cheapest Tier 1 option and the primary target. An **ESP32-S3-DevKitC-1** (N16R8 / N8R2) is recommended if you plan to grow into Tier 2-4. |
| 1 | Thermocouple amplifier breakout | **WROOM:** MAX6675 (common Mercado Livre / AliExpress module). **S3:** MAX31855. Both are 3.3 V SPI; verify the chip is not a counterfeit |
| 1 | K-type thermocouple, mineral-insulated, M5 thread | High-temperature wire; M5 thread mates a stainless hose clamp |
| 1 | 25 A opto-isolated SSR | Common Fotek SSR-25DA-style; verify with a heat sink or spec for resistive load |
| 1 | SSR heatsink + thermal pad | Mandatory above ~10 A continuous |
| 1 | 5 V / 1 A buck converter | Powers the ESP32 off mains; isolated supply only |
| 1 | 220 ohm 1/4 W resistor | SSR input current limit |
| ~ | 22 AWG silicone wire, ferrules, heatshrink | Heat-rated wire near the heater |
| ~ | M3 / M4 fasteners, standoffs | Mount the boards in the machine's empty space |
| 1 | IEC C13 inline fuse holder (5 A slow-blow) | Optional but recommended on the controller's mains tap |

> [!WARNING]
> Every part on this list interacts with mains voltage, hot metal, or
> both. Pick brand-name SSRs and bucks; counterfeit Fotek SSRs in
> particular have a track record of failing closed (heater stuck ON).
