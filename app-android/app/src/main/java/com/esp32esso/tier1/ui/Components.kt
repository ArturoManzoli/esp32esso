package com.esp32esso.tier1.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

// Frosted-glass panel used for every card in the app. The stack is:
//   clip to rounded rect → translucent gradient fill → gradient border →
//   padded content column.
// The gradient fill is lighter at the top-left and fades toward the bottom-
// right, echoing how ambient light catches real glass. Accent cards trade
// the white border for an orange one to draw attention (temperatures, shot
// summary). No drop shadow: the darker gradient background gives the cards
// enough contrast on its own, and the previous shadow read as a black halo
// against the coffee palette.
@Composable
fun FrostCard(
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(FrostShape)
                .background(FrostFillBrush)
                .border(if (accent) FrostBorderAccent else FrostBorder, FrostShape)
                .padding(contentPadding),
        content = content,
    )
}

// Caps the child content at a comfortable reading width and centres it inside
// its parent. On phones (viewport already narrow) the cap is a no-op; on
// tablets in landscape it prevents forms and long lists from spanning the
// whole panel width, which reads uncomfortably at 1600 dp+.
@Composable
fun WidthCappedColumn(
    modifier: Modifier = Modifier,
    maxContentWidth: androidx.compose.ui.unit.Dp = 720.dp,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    verticalArrangement: androidx.compose.foundation.layout.Arrangement.Vertical =
        androidx.compose.foundation.layout.Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = maxContentWidth),
            horizontalAlignment = horizontalAlignment,
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}
