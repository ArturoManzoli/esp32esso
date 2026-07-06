package com.esp32esso.tier1.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// 5-star rating row. `rating` is 0..5; 0 renders all outlines (ungraded).
// `onChange` is null to make the row purely decorative (e.g. inside a saved
// shot's summary card). Label sits at the row's left edge, stars at its right.
@Composable
fun StarRow(
    label: String,
    rating: Int,
    onChange: ((Int) -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = EssoOnSurfaceMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (i in 1..5) {
                val filled = i <= rating
                val tint = if (filled) EssoOrange else EssoOnSurfaceMuted
                val icon = if (filled) Icons.Filled.Star else Icons.Outlined.StarBorder
                val star =
                    Modifier
                        .size(28.dp)
                        .let { mod ->
                            if (onChange != null) {
                                mod.clickable {
                                    // Tapping a filled star again clears it back
                                    // to (i-1), so the row is a rating slider by
                                    // taps rather than a monotonic increment.
                                    onChange(if (rating == i) i - 1 else i)
                                }
                            } else {
                                mod
                            }
                        }
                Icon(icon, contentDescription = null, tint = tint, modifier = star)
            }
        }
    }
}
