package com.esp32esso.tier1.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Orange-accented dark palette. The background is a rich coffee → crema
// diagonal gradient; surfaces are drawn as translucent "frost glass" panels
// (see FrostCard) that let the warm backdrop bleed through.
val EssoOrange = Color(0xFFFF7A1A)
val EssoOrangeSoft = Color(0xFFFFB27A)
val EssoOnSurface = Color(0xFFF3F2F5)
val EssoOnSurfaceMuted = Color(0xFFB9B0A6)
val EssoError = Color(0xFFFF5C5C)

// Background gradient stops. Read top-left → bottom-right as espresso
// (near-black brown), full-body brown, roast, and finally a burnt-orange
// crema wash. Values are ~half the brightness of the first pass so the
// frost cards have more contrast to lift off from and the palette reads
// as deep-espresso rather than mid-day-latte.
val EssoBgEspresso = Color(0xFF090403)
val EssoBgBrown = Color(0xFF1D0D04)
val EssoBgRoast = Color(0xFF3D1B06)
val EssoBgCrema = Color(0xFF5A2A10)

private val EssoColorScheme =
    darkColorScheme(
        primary = EssoOrange,
        onPrimary = Color(0xFF1A0E03),
        secondary = EssoOrangeSoft,
        background = EssoBgEspresso,
        onBackground = EssoOnSurface,
        surface = EssoBgEspresso,
        onSurface = EssoOnSurface,
        onSurfaceVariant = EssoOnSurfaceMuted,
        error = EssoError,
    )

@Composable
fun Esp32essoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = EssoColorScheme) {
        Surface {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        // Diagonal linear gradient anchored to the panel corners
                        // (Offset.Zero → Offset.Infinite). Landscape and portrait
                        // both render as espresso→crema without letterboxing.
                        .background(
                            Brush.linearGradient(
                                colorStops = arrayOf(
                                    0.00f to EssoBgEspresso,
                                    0.45f to EssoBgBrown,
                                    0.85f to EssoBgRoast,
                                    1.00f to EssoBgCrema,
                                ),
                                start = Offset.Zero,
                                end = Offset.Infinite,
                            ),
                        ),
            ) {
                // No extra top gutter: the activity runs in immersive mode
                // (system bars hidden by default), so the first card sits
                // flush with the top of the panel — which is what the tablet
                // mount wants and phones can tolerate because pulling the
                // status bar down just paints over the header transiently.
                Box(modifier = Modifier.fillMaxSize()) {
                    content()
                }
            }
        }
    }
}

// Reusable frosted-glass surface. `FrostFillBrush` is a soft top-to-bottom
// highlight (lighter at the top edge) drawn OVER the warm background so the
// panels catch a subtle sheen. `FrostBorderBrush` adds a hairline top-left
// highlight fading to a darker bottom-right, mimicking the way real glass
// picks up ambient light along its rim. Accent versions swap the border for
// the app's orange for hero cards (temperatures, summary).
val FrostShape = RoundedCornerShape(24.dp)

val FrostFillBrush: Brush = Brush.linearGradient(
    colorStops = arrayOf(
        0f to Color.White.copy(alpha = 0.16f),
        1f to Color.White.copy(alpha = 0.06f),
    ),
    start = Offset.Zero,
    end = Offset.Infinite,
)

val FrostBorderBrush: Brush = Brush.linearGradient(
    colorStops = arrayOf(
        0f to Color.White.copy(alpha = 0.42f),
        1f to Color.White.copy(alpha = 0.10f),
    ),
    start = Offset.Zero,
    end = Offset.Infinite,
)

val FrostBorderAccentBrush: Brush = Brush.linearGradient(
    colorStops = arrayOf(
        0f to EssoOrange.copy(alpha = 0.75f),
        1f to EssoOrange.copy(alpha = 0.20f),
    ),
    start = Offset.Zero,
    end = Offset.Infinite,
)

val FrostBorder = BorderStroke(1.dp, FrostBorderBrush)
val FrostBorderAccent = BorderStroke(1.5.dp, FrostBorderAccentBrush)

@Composable
fun sectionLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = EssoOnSurfaceMuted)
}
