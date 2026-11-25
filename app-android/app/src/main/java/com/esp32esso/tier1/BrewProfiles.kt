package com.esp32esso.tier1

import java.nio.ByteBuffer
import java.nio.ByteOrder

// Curated espresso pressure/flow profiles, transcribed from the open-source
// gaggimate (jniebuhr/gaggimate), gaggiuino (Zer0-bit/gaggiuino, `community`
// branch) and Decent (decentespresso/de1app) projects and normalised into one
// phase model the firmware can execute by duty-cycling the pump. The three
// projects use different schemas (gaggimate: label/phases/pump{target,pressure,
// flow}; gaggiuino: name/phases[type,target{start,end,curve,time},restriction,
// stopConditions]; Decent: tcl advanced_shot frames, or top-level
// preinfusion/hold/decline parameters); all collapse into the ProfilePhase
// below.
//
// Upstream phases often exit on brewed weight, or on flow/pressure crossing a
// threshold. This machine has neither a scale nor a flow meter, so those become
// the published time cap (or, where that cap is only a runaway fallback, a
// duration matching the author's stated shot time). Profiles that step
// temperature per phase are pinned to their starting temperature, the only one
// `temperatureC` can express.
//
// Execution reality on this hardware: the pump is a vibration pump on an AC
// phase dimmer, so power is set by conduction angle, and there is no flow
// sensor. PRESSURE phases are held with a coarse closed loop on the
// brew-line transducer (feedforward + P trim); FLOW phases are open-loop
// (target ml/s mapped to a pump duty), with the phase's pressure limit used as
// a soft cap when the transducer is present. So a profile's *shape* is honoured
// but the absolute flow figures are approximate. See
// firmware/src/control/temperature_loop.cpp (updateProfileExecution).

enum class ProfileSource(val label: String) {
    GAGGIMATE("gaggimate"),
    GAGGIUINO("gaggiuino"),
    DECENT("decent"),
    // Drawn by the operator in the profile editor (see UserProfiles.kt).
    CUSTOM("custom"),
}

// The variable a phase drives the pump toward. The other variable (pressure for
// a FLOW phase, flow for a PRESSURE phase) becomes the phase's `restriction`.
enum class PhaseMode(val wire: Int) { PRESSURE(0), FLOW(1) }

// Transition shape from the previous phase's end value to this phase's target.
enum class PhaseCurve(val wire: Int) { INSTANT(0), LINEAR(1), EASE(2) }

// One profile phase. `targetStart`/`targetEnd` are in the phase's own unit
// (bar for PRESSURE, ml/s for FLOW); the value ramps start -> end over
// `transitionSec` using `curve`, then holds `targetEnd` for the rest of
// `durationSec`. `restriction` is the limit on the *other* variable (0 = none):
// a flow ceiling for a PRESSURE phase, a pressure ceiling for a FLOW phase.
// `stopPressureAbove` advances to the next phase early once the transducer
// reads at/above that pressure (0 = time only).
// `blockTempStartC`/`blockTempEndC` ramp the thermoblock across the phase while
// it runs (0 = the phase leaves temperature control alone). Only the editable
// profiles built in UserProfiles.kt use them; the transcribed library carries
// one advisory `temperatureC` per profile instead.
data class ProfilePhase(
    val name: String,
    val mode: PhaseMode,
    val targetStart: Float,
    val targetEnd: Float,
    val curve: PhaseCurve,
    val transitionSec: Float,
    val durationSec: Float,
    val valveOpen: Boolean = true,
    val restriction: Float = 0f,
    val stopPressureAbove: Float = 0f,
    val blockTempStartC: Float = 0f,
    val blockTempEndC: Float = 0f,
)

data class BrewProfile(
    val id: String,
    val name: String,
    val source: ProfileSource,
    val temperatureC: Float,
    val description: String,
    val phases: List<ProfilePhase>,
) {
    // "Londinium (gaggiuino)" — the label the dropdown shows.
    val displayName: String get() = "$name (${source.label})"

    val totalDurationSec: Float get() = phases.fold(0f) { acc, p -> acc + p.durationSec }

    // Serialise to the BLE BrewProfile blob (see protocol/ble/tier2.md and
    // firmware/src/ble/tier1_gatt.h). Little-endian: an 8-byte header
    // (version, phaseCount, 2 reserved, f32 temperature) followed by one
    // 36-byte v2 record per phase. Phases beyond WIRE_MAX_PHASES are dropped.
    fun toWireBytes(): ByteArray {
        val used = phases.take(WIRE_MAX_PHASES)
        val buf = ByteBuffer
            .allocate(WIRE_HEADER_SIZE + used.size * WIRE_PHASE_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.put(WIRE_VERSION.toByte())
        buf.put(used.size.toByte())
        buf.put(0)
        buf.put(0)
        buf.putFloat(temperatureC)
        for (p in used) {
            buf.put(p.mode.wire.toByte())
            buf.put(p.curve.wire.toByte())
            buf.put(if (p.valveOpen) 1 else 0)
            buf.put(0)
            buf.putFloat(p.targetStart)
            buf.putFloat(p.targetEnd)
            buf.putFloat(p.transitionSec)
            buf.putFloat(p.durationSec)
            buf.putFloat(p.restriction)
            buf.putFloat(p.stopPressureAbove)
            buf.putFloat(p.blockTempStartC)
            buf.putFloat(p.blockTempEndC)
        }
        return buf.array()
    }

    companion object {
        // v2 = per-phase thermoblock temperature ramp appended to each record.
        // The firmware still reads v1 blobs (its NVS copy may predate an update).
        const val WIRE_VERSION = 2
        const val WIRE_HEADER_SIZE = 8
        const val WIRE_PHASE_SIZE = 36
        const val WIRE_MAX_PHASES = 12

        // "None" selection: an 8-byte header with phaseCount = 0 clears any
        // profile firmware-side (back to the plain pre-infusion + pump-ramp
        // shot behaviour).
        fun clearWireBytes(): ByteArray =
            ByteBuffer.allocate(WIRE_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .put(WIRE_VERSION.toByte()).put(0).put(0).put(0).putFloat(0f)
                .array()
    }
}

// The bundled, selectable library. `id` values are stable (persisted locally as
// the current selection); tweak data freely but keep ids constant.
object BrewProfileLibrary {
    private fun pressure(
        name: String,
        start: Float,
        end: Float,
        durationSec: Float,
        curve: PhaseCurve = PhaseCurve.INSTANT,
        transitionSec: Float = 0f,
        restriction: Float = 0f,
        stopPressureAbove: Float = 0f,
        valveOpen: Boolean = true,
    ) = ProfilePhase(
        name, PhaseMode.PRESSURE, start, end, curve, transitionSec, durationSec,
        valveOpen, restriction, stopPressureAbove,
    )

    private fun flow(
        name: String,
        start: Float,
        end: Float,
        durationSec: Float,
        curve: PhaseCurve = PhaseCurve.INSTANT,
        transitionSec: Float = 0f,
        restriction: Float = 0f,
        stopPressureAbove: Float = 0f,
        valveOpen: Boolean = true,
    ) = ProfilePhase(
        name, PhaseMode.FLOW, start, end, curve, transitionSec, durationSec,
        valveOpen, restriction, stopPressureAbove,
    )

    val profiles: List<BrewProfile> = listOf(
        BrewProfile(
            id = "gm-9bar",
            name = "9 Bar Espresso",
            source = ProfileSource.GAGGIMATE,
            temperatureC = 93f,
            description = "Classic flat 9 bar.",
            phases = listOf(
                pressure("Pump", 9f, 9f, durationSec = 28f),
            ),
        ),
        BrewProfile(
            id = "gm-lever",
            name = "Cremina Lever",
            source = ProfileSource.GAGGIMATE,
            temperatureC = 86.5f,
            description = "Darker roasts · very fine · 40–60s · 1:1.5–2.2.",
            phases = listOf(
                pressure("Preinfusion start", 1.1f, 1.1f, durationSec = 2f),
                pressure("Preinfusion", 1.1f, 1.1f, durationSec = 3f, stopPressureAbove = 3f),
                pressure("Soak", 1.1f, 1.1f, durationSec = 10f),
                pressure("Ramp", 1.1f, 9f, durationSec = 10f, curve = PhaseCurve.EASE, transitionSec = 10f),
                pressure("Ramp-down", 9f, 3f, durationSec = 50f, curve = PhaseCurve.LINEAR, transitionSec = 50f),
            ),
        ),
        BrewProfile(
            id = "gm-lmleva",
            name = "LM Leva",
            source = ProfileSource.GAGGIMATE,
            temperatureC = 89f,
            description = "Light–medium · very fine · 20–45s · 1:2.0–2.5 · simulates a La Marzocco Leva.",
            phases = listOf(
                pressure("Fill start", 1.8f, 1.8f, durationSec = 2f),
                pressure("Fill", 1.8f, 1.8f, durationSec = 20f, stopPressureAbove = 1f),
                pressure("Preinfusion", 1.8f, 2.2f, durationSec = 10f, stopPressureAbove = 4f),
                pressure("Rise", 2.2f, 8f, durationSec = 5f, stopPressureAbove = 8f),
                pressure("Hold", 8f, 8f, durationSec = 5f),
                pressure("Decline", 8f, 2.2f, durationSec = 58f, curve = PhaseCurve.LINEAR, transitionSec = 58f),
            ),
        ),
        BrewProfile(
            id = "gm-adaptive",
            name = "Adaptive v2",
            source = ProfileSource.GAGGIMATE,
            temperatureC = 93f,
            description = "Light–medium · fine · 25–40s · 1:2–2.5 · flow fill, compress, then adaptive extraction.",
            phases = listOf(
                flow("Prefill", 0f, 8f, durationSec = 5f, curve = PhaseCurve.EASE, transitionSec = 2f),
                flow("Fill", 8f, 8f, durationSec = 12f, stopPressureAbove = 3f),
                pressure("Compress", 3f, 3f, durationSec = 12f),
                pressure("Dripping", 3f, 0.1f, durationSec = 6f, curve = PhaseCurve.EASE, transitionSec = 2f),
                pressure("Pressurize", 0.1f, 11f, durationSec = 6f, curve = PhaseCurve.LINEAR, transitionSec = 6f, restriction = 3.5f, stopPressureAbove = 8.8f),
                // Upstream holds whatever flow the puck settled at under a
                // 9.5 bar ceiling; without a flow meter this pins the previous
                // phase's limit instead, and runs to the published time cap.
                flow("Extraction", 3.5f, 3.5f, durationSec = 60f, restriction = 9.5f),
            ),
        ),
        BrewProfile(
            id = "gu-londinium",
            name = "Londinium",
            source = ProfileSource.GAGGIUINO,
            temperatureC = 92f,
            description = "Lever-style declining pressure, 9 → 3 bar.",
            phases = listOf(
                flow("Fill", 9f, 9f, durationSec = 10f, restriction = 4f, stopPressureAbove = 4f),
                flow("Soak", 0f, 0f, durationSec = 10f),
                pressure("Ramp", 0f, 9f, durationSec = 1f, curve = PhaseCurve.EASE, transitionSec = 1f),
                pressure("Hold", 9f, 9f, durationSec = 4f, restriction = 3f),
                pressure("Decline", 9f, 3f, durationSec = 20f, curve = PhaseCurve.EASE, transitionSec = 20f),
            ),
        ),
        BrewProfile(
            id = "gu-blooming",
            name = "Blooming Espresso",
            source = ProfileSource.GAGGIUINO,
            temperatureC = 93f,
            description = "Fill, bloom pause, then a gentle 2 ml/s extraction.",
            phases = listOf(
                flow("Fill", 4f, 4f, durationSec = 20f, restriction = 7f, stopPressureAbove = 7f),
                flow("Bloom", 0f, 0f, durationSec = 30f),
                flow("Ramp", 0f, 2f, durationSec = 5f, curve = PhaseCurve.EASE, transitionSec = 5f),
                flow("Extract", 2f, 2f, durationSec = 30f, restriction = 9f),
            ),
        ),
        BrewProfile(
            id = "gu-filter21",
            name = "Filter 2.1",
            source = ProfileSource.GAGGIUINO,
            temperatureC = 89f,
            description = "Long, gentle filter-style extraction for light roasts.",
            phases = listOf(
                flow("Fill", 4f, 4f, durationSec = 15f, restriction = 1f, stopPressureAbove = 1f),
                flow("Steep", 0.2f, 0.2f, durationSec = 90f),
                flow("Extract", 0.2f, 3f, durationSec = 10f, curve = PhaseCurve.EASE, transitionSec = 10f, restriction = 9f),
            ),
        ),
        BrewProfile(
            id = "gu-allonge",
            name = "Allonge",
            source = ProfileSource.GAGGIUINO,
            temperatureC = 95f,
            description = "Long shot (Rao allonge): fill, pause, gentle ramp.",
            phases = listOf(
                flow("Fill", 0f, 4f, durationSec = 15f, curve = PhaseCurve.EASE, transitionSec = 3f, restriction = 4f, stopPressureAbove = 4f),
                flow("Pause", 0f, 0f, durationSec = 10f, restriction = 2f),
                flow("Extract", 0f, 4f, durationSec = 30f, curve = PhaseCurve.EASE, transitionSec = 5f, restriction = 9f),
            ),
        ),
        BrewProfile(
            id = "gu-extractamundo",
            name = "Extractamundo Dos!",
            source = ProfileSource.GAGGIUINO,
            temperatureC = 93f,
            description = "Turbo shot for light roasts: fast 8 ml/s fill, quick soak, 3 ml/s under 6 bar.",
            phases = listOf(
                flow("Fill", 8f, 8f, durationSec = 20f, restriction = 4.5f, stopPressureAbove = 4.5f),
                flow("Soak", 0f, 0f, durationSec = 12f),
                flow("Extract", 0f, 3f, durationSec = 25f, curve = PhaseCurve.EASE, transitionSec = 3f, restriction = 6f),
            ),
        ),
        BrewProfile(
            id = "gu-zer0",
            name = "Zer0",
            source = ProfileSource.GAGGIUINO,
            temperatureC = 95f,
            description = "All-flow all-rounder: slow 5 ml/s fill, restricted soak, then open up to 3.5 ml/s.",
            phases = listOf(
                flow("Fill", 0f, 5f, durationSec = 15f, curve = PhaseCurve.EASE, transitionSec = 15f, restriction = 6f, stopPressureAbove = 6f),
                flow("Soak", 0f, 1f, durationSec = 10f, curve = PhaseCurve.EASE, transitionSec = 10f, restriction = 2.5f),
                flow("Extract", 0f, 3.5f, durationSec = 15f, curve = PhaseCurve.EASE, transitionSec = 10f, restriction = 9f),
            ),
        ),
        BrewProfile(
            id = "gu-lmd98",
            name = "LMD 9-8",
            source = ProfileSource.GAGGIUINO,
            temperatureC = 91f,
            description = "Medium–dark: pressure to 9 bar, then hands over to flow so pressure decays.",
            phases = listOf(
                flow("Fill start", 4f, 4f, durationSec = 2f, restriction = 2f),
                flow("Fill", 4f, 4f, durationSec = 15f, restriction = 2f, stopPressureAbove = 2f),
                flow("Soak", 0f, 0f, durationSec = 6f),
                pressure("Ramp", 2f, 9f, durationSec = 5f, curve = PhaseCurve.EASE, transitionSec = 4f, restriction = 3.5f),
                pressure("Hold", 9f, 8f, durationSec = 4f, curve = PhaseCurve.EASE, transitionSec = 4f, restriction = 2f),
                flow("Taper", 2f, 1.3f, durationSec = 10f, curve = PhaseCurve.EASE, transitionSec = 10f, restriction = 8f),
                flow("Finish", 1.3f, 2.2f, durationSec = 15f, curve = PhaseCurve.EASE, transitionSec = 3f, restriction = 8f),
            ),
        ),
        BrewProfile(
            id = "gu-iuiuiu",
            name = "IUIUIU Classic",
            source = ProfileSource.GAGGIUINO,
            temperatureC = 93f,
            description = "All-rounder: flow preinfusion, free-falling soak, flow-limited 7.5 bar decline.",
            phases = listOf(
                flow("Fill", 3f, 3f, durationSec = 20f, restriction = 4f, stopPressureAbove = 4f),
                flow("Bloom", 0f, 0f, durationSec = 30f),
                pressure("Ramp", 0f, 7.5f, durationSec = 5f, curve = PhaseCurve.EASE, transitionSec = 5f),
                pressure("Decline", 7.5f, 6f, durationSec = 25f, curve = PhaseCurve.EASE, transitionSec = 4f, restriction = 3f),
            ),
        ),
        BrewProfile(
            id = "gu-lowrider",
            name = "LowRider",
            source = ProfileSource.GAGGIUINO,
            temperatureC = 94f,
            description = "2:30 allongé: fast fill, two-minute trickle steep, then a gentle 3 ml/s pour.",
            phases = listOf(
                flow("Fill", 12f, 12f, durationSec = 5f, restriction = 5f),
                flow("Steep", 0f, 0f, durationSec = 115f, curve = PhaseCurve.EASE, transitionSec = 115f, restriction = 0.5f),
                flow("Ramp", 0f, 3f, durationSec = 5f, curve = PhaseCurve.EASE, transitionSec = 4f, restriction = 6f),
                flow("Extract", 3f, 2.5f, durationSec = 20f, curve = PhaseCurve.EASE, transitionSec = 20f, restriction = 5f),
            ),
        ),
        BrewProfile(
            id = "gu-adaptlight",
            name = "Adaptive Light Roast v3",
            source = ProfileSource.GAGGIUINO,
            temperatureC = 93f,
            description = "Light roasts: 6 bar preinfusion, bloom, then a long rising-flow tail for clarity.",
            phases = listOf(
                pressure("Preinfusion", 6f, 6f, durationSec = 10f, stopPressureAbove = 6f),
                pressure("Bloom", 1f, 1f, durationSec = 6f),
                pressure("Ramp", 0f, 9f, durationSec = 5f, curve = PhaseCurve.LINEAR, transitionSec = 5f, restriction = 3f),
                flow("Extract", 0f, 4f, durationSec = 60f, curve = PhaseCurve.EASE, transitionSec = 60f, restriction = 9f),
            ),
        ),
        BrewProfile(
            id = "de-turbobloom",
            name = "Turbo Bloom",
            source = ProfileSource.DECENT,
            temperatureC = 86f,
            description = "Dynamic bloom into a 6 bar decline, for high-extraction grinders.",
            phases = listOf(
                flow("Preinfusion start", 8f, 8f, durationSec = 2f),
                flow("Preinfusion", 8f, 8f, durationSec = 20f, stopPressureAbove = 4f),
                flow("Bloom", 0f, 0f, durationSec = 40f),
                pressure("Ramp", 6f, 6f, durationSec = 4f),
                pressure("6 bar", 6f, 6f, durationSec = 2f, restriction = 4.5f),
                pressure("Decline", 6f, 3f, durationSec = 25f, curve = PhaseCurve.LINEAR, transitionSec = 25f, restriction = 4.5f),
            ),
        ),
        BrewProfile(
            id = "de-bestpressure",
            name = "Best Overall Pressure",
            source = ProfileSource.DECENT,
            temperatureC = 88f,
            description = "Decent's safest bet: 8.4 bar declining to 6, flow-capped at 3.5 ml/s.",
            phases = listOf(
                flow("Preinfusion", 8f, 8f, durationSec = 20f, restriction = 4f, stopPressureAbove = 4f),
                pressure("Hold", 8.4f, 8.4f, durationSec = 10f, restriction = 3.5f),
                pressure("Decline", 8.4f, 6f, durationSec = 30f, curve = PhaseCurve.LINEAR, transitionSec = 30f, restriction = 3.5f),
            ),
        ),
        BrewProfile(
            id = "de-gentlesweet",
            name = "Gentle and Sweet",
            source = ProfileSource.DECENT,
            temperatureC = 88f,
            description = "Forgiving low-pressure shot: 6 bar easing to 4, low acidity, hard to channel.",
            phases = listOf(
                flow("Preinfusion", 8f, 8f, durationSec = 20f, restriction = 4f, stopPressureAbove = 4f),
                pressure("Hold", 6f, 6f, durationSec = 16f, restriction = 4f),
                pressure("Decline", 6f, 4f, durationSec = 30f, curve = PhaseCurve.LINEAR, transitionSec = 30f, restriction = 4f),
            ),
        ),
    )

    fun byId(id: String?): BrewProfile? = id?.let { wanted -> profiles.firstOrNull { it.id == wanted } }
}
