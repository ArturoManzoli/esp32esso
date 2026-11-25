package com.esp32esso.tier1.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esp32esso.tier1.ProfileVertex
import com.esp32esso.tier1.UserProfile
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Which curve taps and drags act on. The vertices themselves are shared — a
// vertex pins a pressure *and* a block temperature at one instant — so the mode
// only decides which of the two values an edit moves.
enum class ProfileEditAxis { PRESSURE, TEMPERATURE }

private val PressureColor = EssoOrange
private val TempColor = Color(0xFF6FC3FF)  // same blue the brew graph uses for the block
private val EditorGridColor = Color.White.copy(alpha = 0.08f)
private val HandleFill = Color(0xFF1A0E03)
private val CalloutFill = Color(0xFF241206)

// Editable profile timeline. The curve drawn here is exactly what the machine
// runs: linear interpolation between vertices, pressure on the left axis and
// thermoblock temperature on the right.
//
// The gestures are: single tap selects, drag reshapes (with a live callout),
// double tap on a handle removes it and double tap on the curve adds one.
@Composable
fun ProfileEditorGraph(
    vertices: List<ProfileVertex>,
    axis: ProfileEditAxis,
    selectedIndex: Int,
    spanSec: Float,
    modifier: Modifier = Modifier,
    editable: Boolean = true,
    onSelect: (Int) -> Unit = {},
    onAdd: (ProfileVertex) -> Unit = {},
    onMove: (index: Int, vertex: ProfileVertex) -> Unit = { _, _ -> },
    onDelete: (Int) -> Unit = {},
) {
    val geometry = remember { EditorGeometry() }
    val labelArgb = EssoOnSurfaceMuted.toArgb()
    var dragIndex by remember { mutableIntStateOf(-1) }

    // The gesture loop must not be keyed on the data it edits: every move
    // changes `vertices` (and can grow `spanSec`), which would restart the
    // pointer coroutine and cancel the drag on its first millimetre.
    val liveVertices by rememberUpdatedState(vertices)
    val liveAxis by rememberUpdatedState(axis)
    val liveSelect by rememberUpdatedState(onSelect)
    val liveAdd by rememberUpdatedState(onAdd)
    val liveMove by rememberUpdatedState(onMove)
    val liveDelete by rememberUpdatedState(onDelete)

    Box(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (!editable) {
                        Modifier
                    } else {
                        Modifier.pointerInput(Unit) {
                            val hitPx = 24.dp.toPx()
                            val slopPx = viewConfiguration.touchSlop
                            val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis
                            // Carried across gestures to pair up a double tap.
                            var lastTapMs = 0L
                            var lastTapTarget = Int.MIN_VALUE
                            var lastTapPos = Offset.Zero

                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (!geometry.ready) return@awaitEachGesture
                                val index =
                                    geometry.hitTest(liveVertices, liveAxis, down.position, hitPx)
                                val onHandle = index >= 0
                                if (onHandle) {
                                    // A handle owns the gesture outright: this canvas sits
                                    // in a scrolling column, and a vertical drag on a
                                    // vertex has to move it instead of scrolling the page
                                    // out from under the finger. Empty canvas stays
                                    // unclaimed so it can still scroll.
                                    down.consume()
                                    liveSelect(index)
                                }

                                var slipped = false
                                var dragging = false
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change =
                                            event.changes.firstOrNull { it.id == down.id } ?: break

                                        if (change.changedToUpIgnoreConsumed()) {
                                            if (dragging) change.consume()
                                            if (!slipped) {
                                                val doubleTap = lastTapTarget == index &&
                                                    change.uptimeMillis - lastTapMs <=
                                                    doubleTapMs &&
                                                    (down.position - lastTapPos).getDistance() <=
                                                    hitPx
                                                if (doubleTap) {
                                                    lastTapTarget = Int.MIN_VALUE
                                                    if (onHandle) {
                                                        liveDelete(index)
                                                    } else {
                                                        liveAdd(
                                                            geometry.vertexAt(
                                                                liveVertices,
                                                                liveAxis,
                                                                down.position,
                                                            ),
                                                        )
                                                    }
                                                } else {
                                                    lastTapTarget = index
                                                    lastTapMs = change.uptimeMillis
                                                    lastTapPos = down.position
                                                    // A single tap off the curve is a
                                                    // deselect, nothing more.
                                                    if (!onHandle) liveSelect(-1)
                                                }
                                            }
                                            break
                                        }

                                        if (!slipped &&
                                            (change.position - down.position).getDistance() >
                                            slopPx
                                        ) {
                                            slipped = true
                                            // A drag is not a tap, so it must not pair up
                                            // with the next one into a double tap.
                                            lastTapTarget = Int.MIN_VALUE
                                            if (onHandle) {
                                                dragging = true
                                                dragIndex = index
                                                // Pin the time axis for the drag: it is
                                                // derived from the vertices being dragged,
                                                // so letting it rescale mid-gesture makes
                                                // the handle chase the finger.
                                                geometry.frozenSpanSec = geometry.spanSec
                                            }
                                        }

                                        if (dragging && change.positionChange() != Offset.Zero) {
                                            change.consume()
                                            val list = liveVertices
                                            if (index in list.indices) {
                                                liveMove(
                                                    index,
                                                    geometry.moved(
                                                        list,
                                                        index,
                                                        liveAxis,
                                                        change.position,
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                } finally {
                                    if (dragging) {
                                        dragIndex = -1
                                        geometry.frozenSpanSec = null
                                    }
                                }
                            }
                        }
                    },
                ),
        ) {
            drawEditor(vertices, axis, selectedIndex, dragIndex, spanSec, labelArgb, geometry)
        }
    }
}

// Px <-> data mapping, written while drawing and read by the gesture handlers
// (the same split BrewGraph uses, since only the draw pass knows the real size).
private class EditorGeometry {
    var left = 0f
    var right = 0f
    var top = 0f
    var bottom = 0f
    var spanSec = UserProfile.DEFAULT_DURATION_SEC
    var ready = false

    // Set while a handle is being dragged, so the axis stops following the data
    // it is being used to edit (see the drag branch in the gesture handler).
    var frozenSpanSec: Float? = null

    private val w: Float get() = (right - left).coerceAtLeast(1f)
    private val h: Float get() = (bottom - top).coerceAtLeast(1f)

    fun xAt(t: Float): Float = left + w * (t / spanSec.coerceAtLeast(0.1f))

    fun tAt(x: Float): Float =
        (((x - left) / w) * spanSec).coerceIn(0f, min(spanSec, UserProfile.MAX_DURATION_SEC))

    fun yBar(bar: Float): Float = bottom - h * (bar / UserProfile.MAX_PRESSURE_BAR)
    fun barAt(y: Float): Float =
        (((bottom - y) / h) * UserProfile.MAX_PRESSURE_BAR)
            .coerceIn(0f, UserProfile.MAX_PRESSURE_BAR)

    fun yTemp(c: Float): Float {
        val lo = UserProfile.MIN_BLOCK_TEMP_C
        val hi = UserProfile.MAX_BLOCK_TEMP_C
        return bottom - h * ((c - lo) / (hi - lo))
    }

    fun tempAt(y: Float): Float {
        val lo = UserProfile.MIN_BLOCK_TEMP_C
        val hi = UserProfile.MAX_BLOCK_TEMP_C
        return (lo + ((bottom - y) / h) * (hi - lo)).coerceIn(lo, hi)
    }

    fun yOf(v: ProfileVertex, axis: ProfileEditAxis): Float =
        if (axis == ProfileEditAxis.PRESSURE) yBar(v.pressureBar) else yTemp(v.blockTempC)

    // Nearest handle within `radiusPx`, or -1. Distance is measured in both axes
    // so two vertices at the same time are still separable.
    fun hitTest(
        vertices: List<ProfileVertex>,
        axis: ProfileEditAxis,
        pos: Offset,
        radiusPx: Float,
    ): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for ((i, v) in vertices.withIndex()) {
            val dx = xAt(v.timeSec) - pos.x
            val dy = yOf(v, axis) - pos.y
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return if (best >= 0 && bestD <= radiusPx * radiusPx) best else -1
    }

    // A new vertex at the tapped point: the tapped axis takes the tap's value,
    // the other axis inherits whatever the existing curve reads at that instant,
    // so adding a pressure point never silently moves the temperature program.
    fun vertexAt(
        vertices: List<ProfileVertex>,
        axis: ProfileEditAxis,
        pos: Offset,
    ): ProfileVertex {
        val t = quantizeTime(tAt(pos.x))
        return if (axis == ProfileEditAxis.PRESSURE) {
            ProfileVertex(t, quantizeBar(barAt(pos.y)), interpolateTemp(vertices, t))
        } else {
            ProfileVertex(t, interpolatePressure(vertices, t), quantizeTemp(tempAt(pos.y)))
        }
    }

    // Drag result for `index`: free movement in time plus the tapped axis, with
    // the other axis untouched. The endpoints keep their time so the profile
    // cannot lose its t=0 start or have its tail dragged behind its neighbours.
    fun moved(
        vertices: List<ProfileVertex>,
        index: Int,
        axis: ProfileEditAxis,
        pos: Offset,
    ): ProfileVertex {
        val v = vertices[index]
        val loT = if (index == 0) 0f else vertices[index - 1].timeSec + UserProfile.MIN_SEGMENT_SEC
        val hiT =
            if (index == vertices.lastIndex) min(spanSec, UserProfile.MAX_DURATION_SEC)
            else vertices[index + 1].timeSec - UserProfile.MIN_SEGMENT_SEC
        val t = if (index == 0) 0f else quantizeTime(tAt(pos.x)).coerceIn(loT, max(loT, hiT))
        return if (axis == ProfileEditAxis.PRESSURE) {
            v.copy(timeSec = t, pressureBar = quantizeBar(barAt(pos.y)))
        } else {
            v.copy(timeSec = t, blockTempC = quantizeTemp(tempAt(pos.y)))
        }
    }
}

// Editing steps: fine enough to draw a real curve, coarse enough that a
// fingertip lands on a repeatable value.
private fun quantizeTime(t: Float): Float = (t * 2f).roundToInt() / 2f      // 0.5 s
private fun quantizeBar(b: Float): Float = (b * 10f).roundToInt() / 10f     // 0.1 bar
private fun quantizeTemp(c: Float): Float = c.roundToInt().toFloat()        // 1 °C

private fun interpolate(
    vertices: List<ProfileVertex>,
    t: Float,
    value: (ProfileVertex) -> Float,
    fallback: Float,
): Float {
    if (vertices.isEmpty()) return fallback
    val sorted = vertices.sortedBy { it.timeSec }
    if (t <= sorted.first().timeSec) return value(sorted.first())
    if (t >= sorted.last().timeSec) return value(sorted.last())
    for ((a, b) in sorted.zipWithNext()) {
        if (t in a.timeSec..b.timeSec) {
            val span = b.timeSec - a.timeSec
            if (span <= 0f) return value(b)
            val f = (t - a.timeSec) / span
            return value(a) + (value(b) - value(a)) * f
        }
    }
    return value(sorted.last())
}

private fun interpolatePressure(vertices: List<ProfileVertex>, t: Float): Float =
    quantizeBar(interpolate(vertices, t, { it.pressureBar }, 9f))

private fun interpolateTemp(vertices: List<ProfileVertex>, t: Float): Float =
    quantizeTemp(interpolate(vertices, t, { it.blockTempC }, 105f))

private fun DrawScope.drawEditor(
    vertices: List<ProfileVertex>,
    axis: ProfileEditAxis,
    selectedIndex: Int,
    dragIndex: Int,
    spanSec: Float,
    labelArgb: Int,
    geometry: EditorGeometry,
) {
    val leftPad = 26.dp.toPx()
    val rightPad = 30.dp.toPx()
    val topPad = 10.dp.toPx()
    val bottomPad = 20.dp.toPx()
    val plotLeft = leftPad
    val plotTop = topPad
    val plotRight = size.width - rightPad
    val plotBottom = size.height - bottomPad
    if (plotRight - plotLeft <= 0f || plotBottom - plotTop <= 0f) return

    geometry.left = plotLeft
    geometry.right = plotRight
    geometry.top = plotTop
    geometry.bottom = plotBottom
    geometry.spanSec = geometry.frozenSpanSec ?: spanSec.coerceAtLeast(1f)
    geometry.ready = true

    val barPaint = Paint().apply {
        color = PressureColor.toArgb()
        textSize = 9.sp.toPx()
        isAntiAlias = true
        textAlign = Paint.Align.RIGHT
    }
    val tempPaint = Paint().apply {
        color = TempColor.toArgb()
        textSize = 9.sp.toPx()
        isAntiAlias = true
        textAlign = Paint.Align.LEFT
    }
    val timePaint = Paint().apply {
        color = labelArgb
        textSize = 9.sp.toPx()
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    val canvas = drawContext.canvas.nativeCanvas

    val div = 4
    for (i in 0..div) {
        val y = plotBottom - (plotBottom - plotTop) * i / div
        drawLine(EditorGridColor, Offset(plotLeft, y), Offset(plotRight, y), strokeWidth = 1f)
        val bar = UserProfile.MAX_PRESSURE_BAR * i / div
        val temp = UserProfile.MIN_BLOCK_TEMP_C +
            (UserProfile.MAX_BLOCK_TEMP_C - UserProfile.MIN_BLOCK_TEMP_C) * i / div
        canvas.drawText("${bar.roundToInt()}", plotLeft - 4.dp.toPx(), y + 3.dp.toPx(), barPaint)
        canvas.drawText("${temp.roundToInt()}", plotRight + 4.dp.toPx(), y + 3.dp.toPx(), tempPaint)
    }

    val step = niceTimeStep(geometry.spanSec)
    var t = 0f
    while (t <= geometry.spanSec + 0.001f) {
        val x = geometry.xAt(t)
        drawLine(EditorGridColor, Offset(x, plotTop), Offset(x, plotBottom), strokeWidth = 1f)
        canvas.drawText("${t.roundToInt()}s", x, plotBottom + 14.dp.toPx(), timePaint)
        t += step
    }

    if (vertices.isEmpty()) return
    val sorted = vertices.sortedBy { it.timeSec }

    // Inactive curve first so the one being edited reads on top.
    if (axis == ProfileEditAxis.PRESSURE) {
        drawCurve(sorted, geometry::xAt, geometry::yTemp, { it.blockTempC }, TempColor, 1.6f, dashed = true)
        drawCurve(sorted, geometry::xAt, geometry::yBar, { it.pressureBar }, PressureColor, 2.4f, dashed = false)
    } else {
        drawCurve(sorted, geometry::xAt, geometry::yBar, { it.pressureBar }, PressureColor, 1.6f, dashed = true)
        drawCurve(sorted, geometry::xAt, geometry::yTemp, { it.blockTempC }, TempColor, 2.4f, dashed = false)
    }

    // Handles on the active curve only, so there is never any doubt which value
    // a drag is about to change.
    val active = if (axis == ProfileEditAxis.PRESSURE) PressureColor else TempColor
    for ((i, v) in vertices.withIndex()) {
        val center = Offset(geometry.xAt(v.timeSec), geometry.yOf(v, axis))
        val selected = i == selectedIndex
        val dragged = i == dragIndex
        val r = if (dragged) 7.5.dp.toPx() else if (selected) 6.dp.toPx() else 4.5.dp.toPx()
        if (dragged) {
            drawCircle(active.copy(alpha = 0.18f), radius = r * 2.2f, center = center)
        }
        drawCircle(HandleFill, radius = r, center = center)
        drawCircle(
            active,
            radius = r,
            center = center,
            style = Stroke(width = if (selected || dragged) 2.6.dp.toPx() else 1.8.dp.toPx()),
        )
        if (selected) {
            drawLine(
                active.copy(alpha = 0.5f),
                Offset(center.x, plotTop),
                Offset(center.x, plotBottom),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }

    val dragged = vertices.getOrNull(dragIndex)
    if (dragged != null) {
        drawDragCallout(
            center = Offset(geometry.xAt(dragged.timeSec), geometry.yOf(dragged, axis)),
            primary =
                if (axis == ProfileEditAxis.PRESSURE) {
                    "${"%.1f".format(dragged.pressureBar)} bar"
                } else {
                    "${dragged.blockTempC.roundToInt()} °C"
                },
            secondary = "${"%.1f".format(dragged.timeSec)} s",
            accent = active,
            labelArgb = labelArgb,
        )
    }
}

// Live readout for the handle under the finger. It is parked a fingertip's width
// to the side so the value stays visible while the hand covers the vertex, and
// flips to the other side rather than running off the plot.
private fun DrawScope.drawDragCallout(
    center: Offset,
    primary: String,
    secondary: String,
    accent: Color,
    labelArgb: Int,
) {
    val gap = 46.dp.toPx()  // clears a fingertip
    val padH = 9.dp.toPx()
    val padV = 7.dp.toPx()
    val lineGap = 2.dp.toPx()
    val primaryPaint = Paint().apply {
        color = accent.toArgb()
        textSize = 15.sp.toPx()
        isAntiAlias = true
        isFakeBoldText = true
        textAlign = Paint.Align.LEFT
    }
    val secondaryPaint = Paint().apply {
        color = labelArgb
        textSize = 10.sp.toPx()
        isAntiAlias = true
        textAlign = Paint.Align.LEFT
    }
    val fm1 = primaryPaint.fontMetrics
    val fm2 = secondaryPaint.fontMetrics
    val h1 = fm1.descent - fm1.ascent
    val h2 = fm2.descent - fm2.ascent
    val boxW = max(primaryPaint.measureText(primary), secondaryPaint.measureText(secondary)) +
        padH * 2
    val boxH = padV * 2 + h1 + lineGap + h2

    val flipped = center.x + gap + boxW > size.width
    val boxLeft =
        (if (flipped) center.x - gap - boxW else center.x + gap)
            .coerceIn(0f, max(0f, size.width - boxW))
    val boxTop = (center.y - boxH / 2f).coerceIn(0f, max(0f, size.height - boxH))

    drawLine(
        accent.copy(alpha = 0.35f),
        center,
        Offset(if (flipped) boxLeft + boxW else boxLeft, boxTop + boxH / 2f),
        strokeWidth = 1.dp.toPx(),
    )
    val corner = CornerRadius(7.dp.toPx())
    drawRoundRect(
        CalloutFill,
        topLeft = Offset(boxLeft, boxTop),
        size = Size(boxW, boxH),
        cornerRadius = corner,
    )
    drawRoundRect(
        accent.copy(alpha = 0.75f),
        topLeft = Offset(boxLeft, boxTop),
        size = Size(boxW, boxH),
        cornerRadius = corner,
        style = Stroke(width = 1.5.dp.toPx()),
    )
    val canvas = drawContext.canvas.nativeCanvas
    canvas.drawText(primary, boxLeft + padH, boxTop + padV - fm1.ascent, primaryPaint)
    canvas.drawText(
        secondary,
        boxLeft + padH,
        boxTop + padV + h1 + lineGap - fm2.ascent,
        secondaryPaint,
    )
}

private fun DrawScope.drawCurve(
    sorted: List<ProfileVertex>,
    xAt: (Float) -> Float,
    yAt: (Float) -> Float,
    value: (ProfileVertex) -> Float,
    color: Color,
    widthDp: Float,
    dashed: Boolean,
) {
    if (sorted.size < 2) {
        if (sorted.size == 1) {
            drawCircle(
                color,
                radius = 3.dp.toPx(),
                center = Offset(xAt(sorted[0].timeSec), yAt(value(sorted[0]))),
            )
        }
        return
    }
    val path = Path()
    path.moveTo(xAt(sorted[0].timeSec), yAt(value(sorted[0])))
    for (v in sorted.drop(1)) path.lineTo(xAt(v.timeSec), yAt(value(v)))
    drawPath(
        path,
        color,
        style = Stroke(
            width = widthDp.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect =
                if (dashed) {
                    PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()), 0f)
                } else {
                    null
                },
        ),
    )
}

private fun niceTimeStep(spanSec: Float): Float {
    val candidates = floatArrayOf(1f, 2f, 5f, 10f, 15f, 30f, 60f)
    for (c in candidates) if (spanSec / c <= 6f) return c
    return candidates.last()
}

// Time axis for the editor: always a whole number of nice steps past the last
// vertex, so there is empty canvas to the right to extend the profile into.
fun editorSpanSec(vertices: List<ProfileVertex>): Float {
    val last = vertices.maxOfOrNull { it.timeSec } ?: 0f
    val wanted = max(UserProfile.DEFAULT_DURATION_SEC, last + 6f)
        .coerceAtMost(UserProfile.MAX_DURATION_SEC)
    val step = niceTimeStep(wanted)
    return (ceil(wanted / step) * step).coerceAtMost(UserProfile.MAX_DURATION_SEC)
}

// True when two vertices are close enough in time to be the same point, used by
// the screen to avoid stacking handles on top of each other.
fun sameInstant(a: Float, b: Float): Boolean = abs(a - b) < UserProfile.MIN_SEGMENT_SEC
