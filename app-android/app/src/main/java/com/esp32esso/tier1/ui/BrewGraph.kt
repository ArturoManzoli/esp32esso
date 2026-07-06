package com.esp32esso.tier1.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

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
    Canvas(modifier) { drawBrewChart(history, labelArgb, shotWindow) }
}

private fun DrawScope.drawBrewChart(
    history: List<GraphSample>,
    labelArgb: Int,
    shotWindow: LongRange?,
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
