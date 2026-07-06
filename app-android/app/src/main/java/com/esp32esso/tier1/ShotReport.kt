package com.esp32esso.tier1

import com.esp32esso.tier1.ble.GraphSample

// Snapshot of a single shot assembled by MainViewModel once the 10s post-shot
// hold has elapsed. All fields come from the graph samples so the summary
// numbers stay consistent with the plot beneath them.
data class ShotReport(
    val durationMs: Long,
    val targetC: Float,
    val preinfusionSec: Float,
    val peakBar: Float,
    val avgBar: Float,
    val avgGroupC: Float,
    val finalGroupC: Float,
    val peakGroupC: Float,
    // Full slice of samples from (startUptime - 10s) to (endUptime + 10s) so the
    // report graph shows lead-in and cool-down context around the shot itself.
    val samples: List<GraphSample>,
    // Absolute ESP32 uptime bracket of the shot itself. Together with the ±10 s
    // padding around it, this is what the report graph paints as a shaded band
    // and what it uses to re-label the x axis relative to shot start.
    val shotStartUptimeMs: Long = 0L,
    val shotEndUptimeMs: Long = 0L,
)
