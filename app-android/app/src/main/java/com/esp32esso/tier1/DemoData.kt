package com.esp32esso.tier1

import com.esp32esso.tier1.ble.BleUiState
import com.esp32esso.tier1.ble.BrewSource
import com.esp32esso.tier1.ble.ConnectionPhase
import com.esp32esso.tier1.ble.GraphSample
import com.esp32esso.tier1.ble.MachineSettings
import com.esp32esso.tier1.ble.MachineState
import kotlin.math.sin

// Synthetic-but-plausible data backing the two offline developer shortcuts
// (see the triple-tap gestures in MainScreen). Everything here is derived from
// the same generated sample curves so the readouts, graph, and report summary
// stay internally consistent — exactly what a live machine would report.
object DemoData {
    private const val IDLE_SAMPLES = 180
    private const val IDLE_STEP_MS = 500L

    // Idle warm-up trace for the main dashboard graph.
    private val idleHistory: List<GraphSample> = buildList {
        for (i in 0 until IDLE_SAMPLES) {
            val thermoblock = 95f + sin(i * 0.30f) * 0.6f
            val group = 92.5f + sin(i * 0.25f + 1f) * 0.5f
            add(
                GraphSample(
                    uptimeMs = i * IDLE_STEP_MS,
                    thermoblockC = thermoblock,
                    groupC = group,
                    setpointC = 95f,
                    dutyPct = (35f + sin(i * 0.40f) * 15f).coerceIn(0f, 100f),
                    pressureBar = 1.0f + sin(i * 0.20f) * 0.1f,
                    flowMlS = Float.NaN,
                    weightG = Float.NaN,
                ),
            )
        }
    }

    private val machine = MachineState(
        thermoblockTempC = 94.8f,
        groupTempC = 92.7f,
        groupSetpointC = 93f,
        thermoblockSetpointC = 95f,
        pressureBar = 1.0f,
        flowMlS = Float.NaN,
        weightG = Float.NaN,
        thermoblockOffsetC = 2f,
        shotTimeMs = 0L,
        uptimeMs = IDLE_SAMPLES * IDLE_STEP_MS,
        heaterDutyPct = 42,
        heaterOn = true,
        faultCode = 0,
        tuning = false,
        brewing = false,
        brewSource = BrewSource.Idle,
        groupSensorOk = true,
        heaterEnabled = true,
        flushing = false,
        thermoblockManual = false,
    )

    private val settings = MachineSettings(
        kp = 18f,
        ki = 0.6f,
        kd = 60f,
        steamSetpointC = 145f,
        heaterTimeoutMin = 30f,
    )

    // Full connected-looking state used when demo mode is toggled on.
    val uiState = BleUiState(
        phase = ConnectionPhase.Connected,
        deviceName = "Demo machine",
        machineState = machine,
        settings = settings,
        preinfusionSec = 2f,
        history = idleHistory,
    )

    // A believable 32 s espresso pull with ±10 s of lead-in / cool-down, so the
    // report graph paints a shaded shot band with context on either side.
    private const val SHOT_STEP_MS = 250L
    private const val SHOT_BASE_MS = 300_000L
    private const val SHOT_PAD_MS = 10_000L
    private const val SHOT_DURATION_MS = 32_000L

    private val shotSamples: List<GraphSample> = buildList {
        val shotStart = SHOT_BASE_MS + SHOT_PAD_MS
        val shotEnd = shotStart + SHOT_DURATION_MS
        val totalEnd = shotEnd + SHOT_PAD_MS
        var t = SHOT_BASE_MS
        var i = 0
        while (t <= totalEnd) {
            val inShot = t in shotStart..shotEnd
            val rel = (t - shotStart) / 1000f
            val pressure = when {
                !inShot -> 0.3f + sin(i * 0.2f) * 0.1f
                rel < 3f -> 2f + rel * 0.5f              // pre-infusion
                rel < 6f -> 3.5f + (rel - 3f) * 1.8f     // ramp to line pressure
                rel < 26f -> 9f + sin(rel) * 0.15f       // extraction plateau
                else -> (9f - (rel - 26f) * 0.9f)        // taper off
            }.coerceAtLeast(0f)
            val group = if (inShot) 93.2f - rel * 0.03f + sin(i * 0.3f) * 0.2f else 93f + sin(i * 0.2f) * 0.15f
            add(
                GraphSample(
                    uptimeMs = t,
                    thermoblockC = 95f + sin(i * 0.25f) * 0.5f,
                    groupC = group,
                    setpointC = 95f,
                    dutyPct = if (inShot) 55f else 30f,
                    pressureBar = pressure,
                    flowMlS = Float.NaN,
                    weightG = Float.NaN,
                ),
            )
            t += SHOT_STEP_MS
            i++
        }
    }

    // Fresh copy each call so the transient report screen never shares state.
    fun report(): ShotReport {
        val shotStart = SHOT_BASE_MS + SHOT_PAD_MS
        val shotEnd = shotStart + SHOT_DURATION_MS
        val inShot = shotSamples.filter { it.uptimeMs in shotStart..shotEnd }
        val pressures = inShot.map { it.pressureBar }
        val groupTemps = inShot.map { it.groupC }
        return ShotReport(
            durationMs = SHOT_DURATION_MS,
            targetC = 93f,
            preinfusionSec = 2f,
            peakBar = pressures.maxOrNull() ?: Float.NaN,
            avgBar = if (pressures.isEmpty()) Float.NaN else pressures.average().toFloat(),
            avgGroupC = if (groupTemps.isEmpty()) Float.NaN else groupTemps.average().toFloat(),
            finalGroupC = groupTemps.lastOrNull() ?: Float.NaN,
            peakGroupC = groupTemps.maxOrNull() ?: Float.NaN,
            samples = shotSamples,
            shotStartUptimeMs = shotStart,
            shotEndUptimeMs = shotEnd,
        )
    }

}
