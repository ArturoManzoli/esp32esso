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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esp32esso.tier1.BrewProfile
import com.esp32esso.tier1.PhaseCurve
import com.esp32esso.tier1.PhaseMode
import com.esp32esso.tier1.ProfilePhase
import kotlin.math.ceil
import kotlin.math.max

private val PressureLineColor = EssoOrange
private val FlowLineColor = Color(0xFF39D0C6) // teal, distinct from the temp blues
private val ProfileGridColor = Color.White.copy(alpha = 0.08f)
private val MarkerColor = Color.White.copy(alpha = 0.75f)

// Static illustration of a profile's characteristic: the target pressure curve
// (bar, left axis, orange) and the target flow curve (ml/s, right axis, teal)
// over the profile's timeline. Purely descriptive — it plots the profile's
// *intent*, not live telemetry (that stays on the main brew graph). Where a
// phase drives pressure, flow is shown only as its ceiling (restriction), and
// vice-versa, so the two lines read as "target" vs "limit" at every instant.
//
// `elapsedSec` (NaN when no profile shot is running) rides a playhead along that
// timeline so the operator can see where the machine currently is.
@Composable
fun ProfileGraph(
    profile: BrewProfile?,
    modifier: Modifier = Modifier,
    elapsedSec: Float = Float.NaN,
) {
    if (profile == null || profile.phases.isEmpty()) {
        Box(modifier) {
            Text(
                text = "No profile — pump runs at the pressure-ramp setting.",
                style = MaterialTheme.typography.bodySmall,
                color = EssoOnSurfaceMuted,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        return
    }
    val labelArgb = EssoOnSurfaceMuted.toArgb()
    Canvas(modifier) { drawProfileChart(profile, labelArgb, elapsedSec) }
}

@Composable
fun ProfileGraphLegend(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendSwatch("Pressure (bar)", PressureLineColor)
        LegendSwatch("Flow (ml/s)", FlowLineColor)
    }
}

@Composable
private fun LegendSwatch(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.padding(3.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = EssoOnSurfaceMuted)
    }
}

// One sampled instant of the profile: t in seconds, and the pressure/flow the
// profile intends at that instant (NaN where that variable is not being shaped
// and has no ceiling either, so the line simply breaks there).
private class ProfileSample(val t: Float, val pressure: Float, val flow: Float)

private fun phaseValue(p: ProfilePhase, elapsedSec: Float): Float {
    if (p.curve == PhaseCurve.INSTANT || p.transitionSec <= 0f || elapsedSec >= p.transitionSec) {
        return p.targetEnd
    }
    val f = (elapsedSec / p.transitionSec).coerceIn(0f, 1f)
    val eased = if (p.curve == PhaseCurve.EASE) f * f * (3f - 2f * f) else f
    return p.targetStart + (p.targetEnd - p.targetStart) * eased
}

private fun sampleProfile(profile: BrewProfile): List<ProfileSample> {
    val total = max(0.1f, profile.totalDurationSec)
    val steps = 260
    val dt = total / steps
    // Precompute cumulative phase start times.
    val starts = FloatArray(profile.phases.size)
    var acc = 0f
    for (i in profile.phases.indices) {
        starts[i] = acc
        acc += profile.phases[i].durationSec
    }
    val out = ArrayList<ProfileSample>(steps + 1)
    for (s in 0..steps) {
        val t = (s * dt).coerceAtMost(total)
        // Locate the active phase (last phase whose start <= t).
        var idx = 0
        for (i in profile.phases.indices) if (starts[i] <= t + 1e-3f) idx = i
        val p = profile.phases[idx]
        val primary = phaseValue(p, t - starts[idx])
        val pressure: Float
        val flow: Float
        if (p.mode == PhaseMode.PRESSURE) {
            pressure = primary
            flow = if (p.restriction > 0f) p.restriction else Float.NaN
        } else {
            flow = primary
            pressure = when {
                primary <= 0.05f -> 0f // pump off (bloom/soak) — pressure bleeds off
                p.restriction > 0f -> p.restriction
                else -> Float.NaN
            }
        }
        out.add(ProfileSample(t, pressure, flow))
    }
    return out
}

private fun DrawScope.drawProfileChart(
    profile: BrewProfile,
    labelArgb: Int,
    elapsedSec: Float,
) {
    val samples = sampleProfile(profile)
    val leftPad = 26.dp.toPx()
    val rightPad = 26.dp.toPx()
    val topPad = 8.dp.toPx()
    val bottomPad = 18.dp.toPx()
    val plotLeft = leftPad
    val plotTop = topPad
    val plotRight = size.width - rightPad
    val plotBottom = size.height - bottomPad
    val plotW = plotRight - plotLeft
    val plotH = plotBottom - plotTop
    if (plotW <= 0f || plotH <= 0f) return

    val total = max(0.1f, profile.totalDurationSec)

    var pMax = 0f
    var fMax = 0f
    for (s in samples) {
        if (!s.pressure.isNaN() && s.pressure > pMax) pMax = s.pressure
        if (!s.flow.isNaN() && s.flow > fMax) fMax = s.flow
    }
    val pTop = max(2f, ceil((pMax * 1.1f).toDouble()).toFloat())
    val fTop = max(2f, ceil((fMax * 1.1f).toDouble()).toFloat())

    val xAt = { t: Float -> plotLeft + plotW * (t / total) }
    val yPress = { b: Float -> plotBottom - plotH * (b / pTop) }
    val yFlow = { fl: Float -> plotBottom - plotH * (fl / fTop) }

    val pressLabelPaint = Paint().apply {
        color = PressureLineColor.toArgb()
        textSize = 9.sp.toPx()
        isAntiAlias = true
        textAlign = Paint.Align.RIGHT
    }
    val flowLabelPaint = Paint().apply {
        color = FlowLineColor.toArgb()
        textSize = 9.sp.toPx()
        isAntiAlias = true
        textAlign = Paint.Align.LEFT
    }
    val timeLabelPaint = Paint().apply {
        color = labelArgb
        textSize = 9.sp.toPx()
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    val canvas = drawContext.canvas.nativeCanvas

    // Horizontal grid + dual y labels (pressure left / flow right).
    val div = 4
    for (i in 0..div) {
        val y = plotBottom - plotH * i / div
        drawLine(ProfileGridColor, Offset(plotLeft, y), Offset(plotRight, y), strokeWidth = 1f)
        val bar = pTop * i / div
        val fl = fTop * i / div
        canvas.drawText("${bar.toInt()}", plotLeft - 4.dp.toPx(), y + 3.dp.toPx(), pressLabelPaint)
        canvas.drawText("${fl.toInt()}", plotRight + 4.dp.toPx(), y + 3.dp.toPx(), flowLabelPaint)
    }

    // Vertical phase-boundary markers + time labels.
    var acc = 0f
    val boundaries = ArrayList<Float>()
    boundaries.add(0f)
    for (p in profile.phases) {
        acc += p.durationSec
        boundaries.add(acc)
    }
    for (b in boundaries) {
        val x = xAt(b)
        drawLine(ProfileGridColor, Offset(x, plotTop), Offset(x, plotBottom), strokeWidth = 1f)
    }
    // Time ticks at a whole-second step keeping ~4-6 labels.
    val step = niceStep(total)
    var t = 0f
    while (t <= total + 0.001f) {
        val x = xAt(t)
        canvas.drawText("${t.toInt()}s", x, plotBottom + 13.dp.toPx(), timeLabelPaint)
        t += step
    }

    drawProfileSeries(samples, xAt, yFlow, { it.flow }, FlowLineColor, 1.6.dp.toPx(), dashed = true)
    drawProfileSeries(samples, xAt, yPress, { it.pressure }, PressureLineColor, 2.2.dp.toPx(), dashed = false)

    // Playhead: a full-height rule at the current position, with a dot pinned to
    // the pressure curve so the shot's target reads off the line directly.
    if (!elapsedSec.isNaN()) {
        val t = elapsedSec.coerceIn(0f, total)
        val x = xAt(t)
        drawLine(
            MarkerColor,
            Offset(x, plotTop),
            Offset(x, plotBottom),
            strokeWidth = 1.5.dp.toPx(),
        )
        val here = samples.lastOrNull { it.t <= t } ?: samples.first()
        if (!here.pressure.isNaN()) {
            drawCircle(MarkerColor, radius = 3.dp.toPx(), center = Offset(x, yPress(here.pressure)))
        }
    }
}

private fun DrawScope.drawProfileSeries(
    samples: List<ProfileSample>,
    xAt: (Float) -> Float,
    project: (Float) -> Float,
    value: (ProfileSample) -> Float,
    color: Color,
    widthPx: Float,
    dashed: Boolean,
) {
    val stroke = Stroke(
        width = widthPx,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()), 0f) else null,
    )
    var i = 0
    while (i < samples.size) {
        if (value(samples[i]).isNaN()) {
            i++
            continue
        }
        val path = Path()
        var started = false
        var j = i
        while (j < samples.size && !value(samples[j]).isNaN()) {
            val x = xAt(samples[j].t)
            val y = project(value(samples[j]))
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
            j++
        }
        if (started) drawPath(path, color, style = stroke)
        i = j
    }
}

private fun niceStep(spanSec: Float): Float {
    val candidates = floatArrayOf(1f, 2f, 5f, 10f, 15f, 30f, 60f)
    for (c in candidates) if (spanSec / c <= 6f) return c
    return candidates.last()
}
