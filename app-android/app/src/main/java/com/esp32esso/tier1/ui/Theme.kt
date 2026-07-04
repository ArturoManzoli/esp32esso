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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Orange-accented dark palette. Backgrounds stay near-black with a warm tint;
// surfaces are drawn as translucent "frost glass" panels (see FrostCard).
val EssoOrange = Color(0xFFFF7A1A)
val EssoOrangeSoft = Color(0xFFFFB27A)
val EssoBackgroundTop = Color(0xFF0B0B0D)
val EssoBackgroundBottom = Color(0xFF17110E)
val EssoOnSurface = Color(0xFFF3F2F5)
val EssoOnSurfaceMuted = Color(0xFF9A98A2)
val EssoError = Color(0xFFFF5C5C)

private val EssoColorScheme =
    darkColorScheme(
        primary = EssoOrange,
        onPrimary = Color(0xFF1A0E03),
        secondary = EssoOrangeSoft,
        background = EssoBackgroundTop,
        onBackground = EssoOnSurface,
        surface = EssoBackgroundTop,
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
                        .background(
                            Brush.verticalGradient(
                                listOf(EssoBackgroundTop, EssoBackgroundBottom),
                            ),
                        ),
            ) {
                content()
            }
        }
    }
}

// Reusable frosted-glass surface: a translucent fill over the gradient
// background with a hairline light border, optionally tinted by the accent.
val FrostShape = RoundedCornerShape(22.dp)
val FrostFill = Color.White.copy(alpha = 0.055f)
val FrostBorder = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
val FrostBorderAccent = BorderStroke(1.dp, EssoOrange.copy(alpha = 0.45f))

@Composable
fun sectionLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = EssoOnSurfaceMuted)
}
