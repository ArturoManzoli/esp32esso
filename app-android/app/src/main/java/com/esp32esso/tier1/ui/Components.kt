package com.esp32esso.tier1.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

// Frosted-glass panel used for every card in the app.
@Composable
fun FrostCard(
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(FrostShape)
                .background(FrostFill)
                .border(if (accent) FrostBorderAccent else FrostBorder, FrostShape)
                .padding(18.dp),
        content = content,
    )
}
