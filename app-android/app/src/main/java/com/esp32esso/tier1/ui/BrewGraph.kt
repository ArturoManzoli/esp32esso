package com.esp32esso.tier1.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.esp32esso.tier1.ble.GraphSample
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import kotlin.math.roundToInt

private val ThermoblockColor = EssoError
private val GroupColor = EssoOrange
private val SetpointColor = Color(0xFF6FC3FF)

// The x-data is elapsed seconds; without a formatter Vico prints the raw GCD
// tick positions (0.2 s during a shot) with default decimals, which renders as
// "0 0 0 0.01". Snap the labels to whole seconds instead.
private val TimeAxisFormatter = CartesianValueFormatter { _, value, _ -> "${value.roundToInt()}s" }
private val TempAxisFormatter = CartesianValueFormatter { _, value, _ -> "${value.roundToInt()}°" }

// Temperature brew graph. Thermoblock, group, and setpoint are always drawn;
// the pressure/flow/weight lanes are wired for Tier 3/4 and stay empty until
// that hardware reports non-NaN samples.
@Composable
fun BrewGraph(history: List<GraphSample>, modifier: Modifier = Modifier) {
    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(history) {
        if (history.isEmpty()) return@LaunchedEffect
        // Derive x from the integer-millisecond uptime so every value is an
        // exact multiple of 0.001 s. Downcasting to Float first (as before)
        // injected sub-millisecond noise that made Vico's x-delta GCD exceed
        // four decimal places and throw once the shot rate hit 5 Hz.
        val t0 = history.first().uptimeMs
        val x = history.map { (it.uptimeMs - t0) / 1000.0 }
        val thermoblock = history.map { it.thermoblockC.orZero() }
        val group = history.map { it.groupC.orZero() }
        val setpoint = history.map { it.setpointC.orZero() }
        modelProducer.runTransaction {
            lineSeries {
                series(x = x, y = thermoblock)
                series(x = x, y = group)
                series(x = x, y = setpoint)
            }
        }
    }

    val lineLayer =
        rememberLineCartesianLayer(
            lineProvider =
                LineCartesianLayer.LineProvider.series(
                    solidLine(ThermoblockColor),
                    solidLine(GroupColor),
                    solidLine(SetpointColor),
                ),
        )

    Box(modifier = modifier) {
        if (history.size < 2) {
            Text(
                text = "Waiting for telemetry…",
                style = MaterialTheme.typography.bodyMedium,
                color = EssoOnSurfaceMuted,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            CartesianChartHost(
                chart =
                    rememberCartesianChart(
                        lineLayer,
                        startAxis = VerticalAxis.rememberStart(valueFormatter = TempAxisFormatter),
                        bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = TimeAxisFormatter),
                    ),
                modelProducer = modelProducer,
                zoomState = rememberVicoZoomState(zoomEnabled = true),
                modifier = Modifier.fillMaxWidth().height(220.dp),
            )
        }
    }
}

@Composable
fun GraphLegend(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendDot("Thermoblock", ThermoblockColor)
        LegendDot("Group", GroupColor)
        LegendDot("Target", SetpointColor)
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.padding(3.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = EssoOnSurfaceMuted)
    }
}

@Composable
private fun solidLine(color: Color) =
    LineCartesianLayer.rememberLine(fill = LineCartesianLayer.LineFill.single(fill(color)))

private fun Float.orZero(): Double = if (isNaN()) 0.0 else toDouble()
