package com.esp32esso.tier1.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esp32esso.tier1.ble.GraphSample
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

private val GroupColor = Color(0xFF7CE38B)   // green
private val ThermoblockColor = Color(0xFF6FC3FF)  // blue
private val SetpointColor = EssoError         // red (thin dotted)
private val PressureColor = EssoOrange        // orange (thin)
private val GridColor = Color.White.copy(alpha = 0.08f)
// Report-mode shot-window tint: soft baby-blue so the temperature/pressure
// traces stay legible over it. 0.3 alpha per the design brief.
private val ShotWindowTint = Color(0xFF6FC3FF).copy(alpha = 0.3f)

// Display smoothing windows (samples). Pressure gets a much wider window to tame
// the vibration-pump "seismograph" ripple; temps only need a light pass to drop
// the sensor-quantisation crispiness.
private const val TempSmoothWindow = 5
private const val PressureSmoothWindow = 21

// Rolling brew graph. Every retained sample is plotted against its real
// device-uptime timestamp (so a variable notify rate still reads true on the
// time axis). Group/thermoblock/target share the left °C axis; brew-line
// pressure, when a transducer reports it, is drawn on the right bar axis.
//
// Report mode: when `shotWindow` is supplied, the x axis is clamped to
// `[shotStart - 10s, shotEnd + 10s]` regardless of the sample span, labels are
// shot-relative ("-10 s", "0", "duration", "+10 s"…), and a translucent blue
// band paints the shot itself so the user can eyeball what happened before,
// during, and after the pull. In live mode the parameter is `null` and the
// axis auto-scales to the retained samples.
//
// Hand-drawn on a Canvas: the time axis, the dual y-axes, the second-based
// gridlines, per-series smoothing, and the draw order are all trivial to control
// here, and it avoids the chart-library quirks that collapsed the plot earlier.
@Composable
fun BrewGraph(
    history: List<GraphSample>,
    modifier: Modifier = Modifier,
    shotWindow: LongRange? = null,
    // When true, tapping the plot reads out the exact sample under the finger
    // and dragging horizontally reads out the average of every series across the
    // swept region. Off by default so the axis-less ranking thumbnails (which
    // open the report on tap) keep their own gesture.
    interactive: Boolean = false,
) {
    if (history.size < 2) {
        Box(modifier) {
            Text(
                text = "Waiting for telemetry…",
                style = MaterialTheme.typography.bodyMedium,
                color = EssoOnSurfaceMuted,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        return
    }
    val labelArgb = EssoOnSurfaceMuted.toArgb()
    if (!interactive) {
        Canvas(modifier) { drawBrewChart(history, labelArgb, shotWindow) }
        return
    }

    // Plot geometry is written by the draw pass (single source of truth for the
    // px<->time mapping) and read back by the gesture handlers. Selection is
    // Compose state so a tap/drag re-triggers the draw and paints the marker.
    val geometry = remember { PlotGeometry() }
    var selection by remember(shotWindow) { mutableStateOf<GraphSelection?>(null) }
    Box(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(history, shotWindow) {
                    val thresholdPx = 10.dp.toPx()
                    detectTapGestures(
                        onTap = { pos ->
                            selection = computeSelection(
                                geometry, history, shotWindow, pos.x, pos.x, thresholdPx,
                            )
                        },
                    )
                }
                .pointerInput(history, shotWindow) {
                    val thresholdPx = 10.dp.toPx()
                    var startX = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { pos ->
                            startX = pos.x
                            selection = computeSelection(
                                geometry, history, shotWindow, pos.x, pos.x, thresholdPx,
                            )
                        },
                        onHorizontalDrag = { change, _ ->
                            selection = computeSelection(
                                geometry, history, shotWindow, startX, change.position.x, thresholdPx,
                            )
                        },
                    )
                },
        ) {
            drawBrewChart(history, labelArgb, shotWindow, selection, geometry)
        }
        selection?.let { GraphReadout(it) { selection = null } }
    }
}

// Px<->time mapping for the plot area, filled in by the draw pass so the gesture
// handlers map a touch x back to a sample without re-deriving the axis padding.
internal class PlotGeometry {
    var plotLeft = 0f
    var plotRight = 0f
    var plotW = 1f
    var t0 = 0L
    var spanMs = 1f
    var ready = false
}

// A tap selection (isRange = false) carries the values of the single nearest
// sample; a drag selection carries the NaN-aware average of each series over the
// swept time window. `label` is the shot-relative time (or range) for display.
internal data class GraphSelection(
    val startMs: Long,
    val endMs: Long,
    val isRange: Boolean,
    val group: Float,
    val block: Float,
    val target: Float,
    val pressure: Float,
    val label: String,
)

private fun nearestIndex(history: List<GraphSample>, ms: Long): Int {
    var best = 0
    var bestD = Long.MAX_VALUE
    for (i in history.indices) {
        val d = abs(history[i].uptimeMs - ms)
        if (d < bestD) {
            bestD = d
            best = i
        }
    }
    return best
}

private fun computeSelection(
    g: PlotGeometry,
    history: List<GraphSample>,
    shotWindow: LongRange?,
    x1: Float,
    x2: Float,
    tapThresholdPx: Float,
): GraphSelection? {
    if (!g.ready || g.plotW <= 0f || history.isEmpty()) return null
    val cx1 = x1.coerceIn(g.plotLeft, g.plotRight)
    val cx2 = x2.coerceIn(g.plotLeft, g.plotRight)
    val base = shotWindow?.first ?: history.first().uptimeMs
    fun timeAt(x: Float): Long = g.t0 + ((x - g.plotLeft) / g.plotW * g.spanMs).toLong()
    fun relSec(ms: Long): Int = round((ms - base) / 1000.0).toInt()
    fun relStr(sec: Int): String = if (shotWindow != null && sec > 0) "+${sec}s" else "${sec}s"

    if (abs(cx2 - cx1) < tapThresholdPx) {
        val s = history[nearestIndex(history, timeAt((cx1 + cx2) / 2f))]
        return GraphSelection(
            startMs = s.uptimeMs,
            endMs = s.uptimeMs,
            isRange = false,
            group = s.groupC,
            block = s.thermoblockC,
            target = s.setpointC,
            pressure = s.pressureBar,
            label = "t = ${relStr(relSec(s.uptimeMs))}",
        )
    }

    val ta = minOf(timeAt(cx1), timeAt(cx2))
    val tb = maxOf(timeAt(cx1), timeAt(cx2))
    var gSum = 0f; var gN = 0
    var bSum = 0f; var bN = 0
    var tSum = 0f; var tN = 0
    var pSum = 0f; var pN = 0
    for (s in history) {
        if (s.uptimeMs < ta || s.uptimeMs > tb) continue
        if (!s.groupC.isNaN()) { gSum += s.groupC; gN++ }
        if (!s.thermoblockC.isNaN()) { bSum += s.thermoblockC; bN++ }
        if (!s.setpointC.isNaN()) { tSum += s.setpointC; tN++ }
        if (!s.pressureBar.isNaN()) { pSum += s.pressureBar; pN++ }
    }
    return GraphSelection(
        startMs = ta,
        endMs = tb,
        isRange = true,
        group = if (gN > 0) gSum / gN else Float.NaN,
        block = if (bN > 0) bSum / bN else Float.NaN,
        target = if (tN > 0) tSum / tN else Float.NaN,
        pressure = if (pN > 0) pSum / pN else Float.NaN,
        label = "avg ${relStr(relSec(ta))} – ${relStr(relSec(tb))}",
    )
}

@Composable
private fun BoxScope.GraphReadout(sel: GraphSelection, onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(start = 10.dp, end = 22.dp, top = 6.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = sel.label,
                style = MaterialTheme.typography.labelSmall,
                color = EssoOnSurfaceMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!sel.group.isNaN()) ReadoutValue("Grp", "${fmt1(sel.group)}°", GroupColor)
                if (!sel.block.isNaN()) ReadoutValue("Blk", "${fmt1(sel.block)}°", ThermoblockColor)
                if (!sel.target.isNaN()) ReadoutValue("Tgt", "${fmt1(sel.target)}°", SetpointColor)
                if (!sel.pressure.isNaN()) ReadoutValue("", "${fmt1(sel.pressure)} bar", PressureColor)
            }
        }
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Close readout",
            tint = EssoOnSurfaceMuted,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .clickable(onClick = onClose)
                .padding(4.dp)
                .size(13.dp),
        )
    }
}

@Composable
private fun ReadoutValue(label: String, value: String, color: Color) {
    Text(
        text = if (label.isEmpty()) value else "$label $value",
        style = MaterialTheme.typography.labelMedium,
        color = color,
    )
}

private fun fmt1(v: Float): String = "%.1f".format(v)

private fun DrawScope.drawBrewChart(
    history: List<GraphSample>,
    labelArgb: Int,
    shotWindow: LongRange?,
    selection: GraphSelection? = null,
    geometry: PlotGeometry? = null,
) {
    val hasPressure = history.any { !it.pressureBar.isNaN() }

    val leftPad = 34.dp.toPx()
    val rightPad = if (hasPressure) 30.dp.toPx() else 8.dp.toPx()
    val topPad = 8.dp.toPx()
    val bottomPad = 20.dp.toPx()
    val plotLeft = leftPad
    val plotTop = topPad
    val plotRight = size.width - rightPad
    val plotBottom = size.height - bottomPad
    val plotW = plotRight - plotLeft
    val plotH = plotBottom - plotTop
    if (plotW <= 0f || plotH <= 0f) return

    // Report mode fixes the axis to shot ±10 s; live mode tracks samples.
    val t0: Long
    val tN: Long
    if (shotWindow != null) {
        t0 = shotWindow.first - 10_000L
        tN = shotWindow.last + 10_000L
    } else {
        t0 = history.first().uptimeMs
        tN = history.last().uptimeMs
    }
    val spanMs = max(1L, tN - t0).toFloat()
    val xs = FloatArray(history.size) { plotLeft + plotW * ((history[it].uptimeMs - t0).toFloat() / spanMs) }

    // Draw the shot-window tint before everything else so lines paint over it.
    if (shotWindow != null) {
        val xShotStart = plotLeft + plotW * ((shotWindow.first - t0).toFloat() / spanMs)
        val xShotEnd = plotLeft + plotW * ((shotWindow.last - t0).toFloat() / spanMs)
        val left = xShotStart.coerceIn(plotLeft, plotRight)
        val right = xShotEnd.coerceIn(plotLeft, plotRight)
        if (right > left) {
            drawRect(
                color = ShotWindowTint,
                topLeft = androidx.compose.ui.geometry.Offset(left, plotTop),
                size = androidx.compose.ui.geometry.Size(right - left, plotH),
            )
        }
    }

    // Temperature range across all three temp series, padded so lines don't hug
    // the frame. Falls back to a sane band when nothing is finite yet.
    var tMin = Float.MAX_VALUE
    var tMax = -Float.MAX_VALUE
    for (s in history) {
        for (v in floatArrayOf(s.thermoblockC, s.groupC, s.setpointC)) {
            if (!v.isNaN()) {
                if (v < tMin) tMin = v
                if (v > tMax) tMax = v
            }
        }
    }
    if (tMin > tMax) {
        tMin = 0f
        tMax = 100f
    }
    val tPad = max(2f, (tMax - tMin) * 0.1f)
    tMin -= tPad
    tMax += tPad
    val tRange = max(1f, tMax - tMin)
    val yTemp = { c: Float -> plotBottom - plotH * ((c - tMin) / tRange) }

    // Pressure axis: 0..a little above the peak.
    var pMax = 0f
    for (s in history) if (!s.pressureBar.isNaN() && s.pressureBar > pMax) pMax = s.pressureBar
    val pTop = max(2f, ceil((pMax * 1.15f).toDouble()).toFloat())
    val yPress = { b: Float -> plotBottom - plotH * (b / pTop) }

    drawAxes(history, xs, spanMs, plotLeft, plotTop, plotRight, plotBottom, plotH,
        tMin, tRange, pTop, hasPressure, yTemp, yPress, labelArgb,
        shotStartOffsetSec = shotWindow?.let { (it.first - t0).toFloat() / 1000f })

    // Draw order is back-to-front: pressure and target sit behind, so the group
    // and thermoblock traces stay legible on top (front-most).
    val thin = 1.4.dp.toPx()
    val bold = 2.2.dp.toPx()
    if (hasPressure) {
        plotSeries(xs, floatArrayOf2(history) { it.pressureBar }, PressureSmoothWindow, yPress, PressureColor, thin, dashed = false)
    }
    plotSeries(xs, floatArrayOf2(history) { it.setpointC }, TempSmoothWindow, yTemp, SetpointColor, thin, dashed = true)
    plotSeries(xs, floatArrayOf2(history) { it.thermoblockC }, TempSmoothWindow, yTemp, ThermoblockColor, bold, dashed = false)
    plotSeries(xs, floatArrayOf2(history) { it.groupC }, TempSmoothWindow, yTemp, GroupColor, bold, dashed = false)

    // Publish the mapping for the gesture handlers, then paint the active
    // scrub marker (a vertical line + per-series dots for a tap, a shaded band
    // for a drag range) over the traces.
    geometry?.apply {
        this.plotLeft = plotLeft
        this.plotRight = plotRight
        this.plotW = plotW
        this.t0 = t0
        this.spanMs = spanMs
        this.ready = true
    }
    if (selection != null) {
        fun projX(ms: Long) = plotLeft + plotW * ((ms - t0).toFloat() / spanMs)
        if (selection.isRange) {
            val xa = projX(selection.startMs).coerceIn(plotLeft, plotRight)
            val xb = projX(selection.endMs).coerceIn(plotLeft, plotRight)
            drawRect(
                color = Color.White.copy(alpha = 0.10f),
                topLeft = Offset(min(xa, xb), plotTop),
                size = Size(abs(xb - xa), plotH),
            )
            for (edge in floatArrayOf(xa, xb)) {
                drawLine(Color.White.copy(alpha = 0.5f), Offset(edge, plotTop), Offset(edge, plotBottom), strokeWidth = 1.dp.toPx())
            }
        } else {
            val s = history[nearestIndex(history, selection.startMs)]
            val x = projX(s.uptimeMs).coerceIn(plotLeft, plotRight)
            drawLine(Color.White.copy(alpha = 0.6f), Offset(x, plotTop), Offset(x, plotBottom), strokeWidth = 1.dp.toPx())
            val r = 3.dp.toPx()
            if (!s.groupC.isNaN()) drawCircle(GroupColor, r, Offset(x, yTemp(s.groupC)))
            if (!s.thermoblockC.isNaN()) drawCircle(ThermoblockColor, r, Offset(x, yTemp(s.thermoblockC)))
            if (!s.setpointC.isNaN()) drawCircle(SetpointColor, r, Offset(x, yTemp(s.setpointC)))
            if (hasPressure && !s.pressureBar.isNaN()) drawCircle(PressureColor, r, Offset(x, yPress(s.pressureBar)))
        }
    }
}

private fun DrawScope.drawAxes(
    history: List<GraphSample>,
    xs: FloatArray,
    spanMs: Float,
    plotLeft: Float,
    plotTop: Float,
    plotRight: Float,
    plotBottom: Float,
    plotH: Float,
    tMin: Float,
    tRange: Float,
    pTop: Float,
    hasPressure: Boolean,
    yTemp: (Float) -> Float,
    yPress: (Float) -> Float,
    labelArgb: Int,
    // In report mode this is the axis coordinate (in seconds since t0) that
    // corresponds to shot start; time labels subtract it so the axis reads as
    // "-10 s .. 0 .. duration .. +10 s". Null in live mode → labels stay
    // absolute ("0 s .. spanSec").
    shotStartOffsetSec: Float?,
) {
    val tempLabelPaint = Paint().apply {
        color = labelArgb
        textSize = 9.sp.toPx()
        isAntiAlias = true
        textAlign = Paint.Align.RIGHT
    }
    val timeLabelPaint = Paint().apply {
        color = labelArgb
        textSize = 9.sp.toPx()
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    val pressLabelPaint = Paint().apply {
        color = PressureColor.toArgb()
        textSize = 9.sp.toPx()
        isAntiAlias = true
        textAlign = Paint.Align.LEFT
    }
    val nativeCanvas = drawContext.canvas.nativeCanvas

    val hDiv = 4
    for (i in 0..hDiv) {
        val c = tMin + tRange * i / hDiv
        val y = yTemp(c)
        drawLine(GridColor, Offset(plotLeft, y), Offset(plotRight, y), strokeWidth = 1f)
        nativeCanvas.drawText("${c.roundToIntF()}°", plotLeft - 4.dp.toPx(), y + 3.dp.toPx(), tempLabelPaint)
    }

    if (hasPressure) {
        val pDiv = 2
        for (i in 0..pDiv) {
            val b = pTop * i / pDiv
            val y = yPress(b)
            nativeCanvas.drawText("${b.roundToIntF()}b", plotRight + 4.dp.toPx(), y + 3.dp.toPx(), pressLabelPaint)
        }
    }

    val spanSec = spanMs / 1000f
    val stepSec = niceTimeStep(spanSec)
    var t = 0f
    while (t <= spanSec + 0.001f) {
        val x = plotLeft + (plotRight - plotLeft) * (t / spanSec)
        drawLine(GridColor, Offset(x, plotTop), Offset(x, plotBottom), strokeWidth = 1f)
        val labelSec = if (shotStartOffsetSec != null) (t - shotStartOffsetSec).toInt() else t.toInt()
        val label = if (shotStartOffsetSec != null && labelSec > 0) "+${labelSec}s" else "${labelSec}s"
        nativeCanvas.drawText(label, x, plotBottom + 14.dp.toPx(), timeLabelPaint)
        t += stepSec
    }
}

// Smooths the values with a moving average (NaN-aware), then strokes a smooth
// Catmull-Rom curve through each contiguous finite run.
private fun DrawScope.plotSeries(
    xs: FloatArray,
    values: FloatArray,
    window: Int,
    project: (Float) -> Float,
    color: Color,
    widthPx: Float,
    dashed: Boolean,
) {
    val sm = movingAverage(values, window)
    val stroke =
        Stroke(
            width = widthPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = if (dashed) {
                PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()), 0f)
            } else {
                null
            },
        )
    var i = 0
    while (i < sm.size) {
        if (sm[i].isNaN()) {
            i++
            continue
        }
        val pts = ArrayList<Offset>()
        var j = i
        while (j < sm.size && !sm[j].isNaN()) {
            pts.add(Offset(xs[j], project(sm[j])))
            j++
        }
        if (pts.size >= 2) {
            drawPath(catmullRomPath(pts), color, style = stroke)
        } else if (pts.size == 1) {
            drawCircle(color, radius = widthPx / 2f, center = pts[0])
        }
        i = j
    }
}

// Axis-less thumbnail used by the ranking card: same colour coding as the
// full graph (group green, thermoblock blue, pressure orange) but no grid,
// labels, or legend — at this size the shot-window tint alone gives enough
// context, and a scale label would be unreadable anyway.
@Composable
fun MiniBrewGraph(samples: List<GraphSample>, shotWindow: LongRange?, modifier: Modifier = Modifier) {
    if (samples.size < 2) return
    Canvas(modifier) { drawMiniBrewChart(samples, shotWindow) }
}

private fun DrawScope.drawMiniBrewChart(samples: List<GraphSample>, shotWindow: LongRange?) {
    val t0 = shotWindow?.let { it.first - 10_000L } ?: samples.first().uptimeMs
    val tN = shotWindow?.let { it.last + 10_000L } ?: samples.last().uptimeMs
    val spanMs = max(1L, tN - t0).toFloat()
    val xs = FloatArray(samples.size) { size.width * ((samples[it].uptimeMs - t0).toFloat() / spanMs) }

    if (shotWindow != null) {
        val xStart = (size.width * ((shotWindow.first - t0).toFloat() / spanMs)).coerceIn(0f, size.width)
        val xEnd = (size.width * ((shotWindow.last - t0).toFloat() / spanMs)).coerceIn(0f, size.width)
        if (xEnd > xStart) {
            drawRect(color = ShotWindowTint, topLeft = Offset(xStart, 0f), size = Size(xEnd - xStart, size.height))
        }
    }

    var tMin = Float.MAX_VALUE
    var tMax = -Float.MAX_VALUE
    for (s in samples) {
        for (v in floatArrayOf(s.thermoblockC, s.groupC)) {
            if (!v.isNaN()) {
                if (v < tMin) tMin = v
                if (v > tMax) tMax = v
            }
        }
    }
    if (tMin > tMax) {
        tMin = 0f
        tMax = 100f
    }
    val tPad = max(1f, (tMax - tMin) * 0.15f)
    tMin -= tPad
    tMax += tPad
    val tRange = max(1f, tMax - tMin)
    val yTemp = { c: Float -> size.height - size.height * ((c - tMin) / tRange) }

    var pMax = 0f
    for (s in samples) if (!s.pressureBar.isNaN() && s.pressureBar > pMax) pMax = s.pressureBar
    val pTop = max(1f, pMax * 1.15f)
    val yPress = { b: Float -> size.height - size.height * (b / pTop) }

    val thin = 1.dp.toPx()
    val bold = 1.6.dp.toPx()
    if (samples.any { !it.pressureBar.isNaN() }) {
        plotSeries(xs, floatArrayOf2(samples) { it.pressureBar }, PressureSmoothWindow, yPress, PressureColor, thin, dashed = false)
    }
    plotSeries(xs, floatArrayOf2(samples) { it.thermoblockC }, TempSmoothWindow, yTemp, ThermoblockColor, bold, dashed = false)
    plotSeries(xs, floatArrayOf2(samples) { it.groupC }, TempSmoothWindow, yTemp, GroupColor, bold, dashed = false)
}

@Composable
fun GraphLegend(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendDot("Group", GroupColor)
        LegendDot("Thermoblock", ThermoblockColor)
        LegendDot("Target", SetpointColor)
        LegendDot("Pressure", PressureColor)
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

private inline fun floatArrayOf2(history: List<GraphSample>, sel: (GraphSample) -> Float): FloatArray =
    FloatArray(history.size) { sel(history[it]) }

// NaN-aware centred moving average. Positions whose source is NaN stay NaN (so
// the line breaks there); other positions average the finite samples in the
// window around them.
private fun movingAverage(src: FloatArray, window: Int): FloatArray {
    if (window <= 1) return src
    val half = window / 2
    return FloatArray(src.size) { i ->
        if (src[i].isNaN()) {
            Float.NaN
        } else {
            var sum = 0f
            var n = 0
            for (k in max(0, i - half)..min(src.size - 1, i + half)) {
                val v = src[k]
                if (!v.isNaN()) {
                    sum += v
                    n++
                }
            }
            if (n > 0) sum / n else Float.NaN
        }
    }
}

private fun catmullRomPath(pts: List<Offset>): Path {
    val path = Path()
    path.moveTo(pts[0].x, pts[0].y)
    for (i in 0 until pts.size - 1) {
        val p0 = pts[max(0, i - 1)]
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = pts[min(pts.size - 1, i + 2)]
        val c1x = p1.x + (p2.x - p0.x) / 6f
        val c1y = p1.y + (p2.y - p0.y) / 6f
        val c2x = p2.x - (p3.x - p1.x) / 6f
        val c2y = p2.y - (p3.y - p1.y) / 6f
        path.cubicTo(c1x, c1y, c2x, c2y, p2.x, p2.y)
    }
    return path
}

private fun Float.roundToIntF(): Int = kotlin.math.round(this).toInt()

// Whole-second gridline step that keeps the label count around 4-7.
private fun niceTimeStep(spanSec: Float): Float {
    val candidates = floatArrayOf(1f, 2f, 5f, 10f, 15f, 30f, 60f, 120f, 300f, 600f, 1800f)
    for (c in candidates) if (spanSec / c <= 6f) return c
    return candidates.last()
}
