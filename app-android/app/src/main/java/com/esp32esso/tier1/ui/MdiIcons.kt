package com.esp32esso.tier1.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// Pictogrammers "Material Design Icons" glyphs used by the header shortcuts.
// These aren't part of material-icons-extended, so the path data (24x24
// viewBox) is copied verbatim from https://pictogrammers.com/library/mdi/.
private fun mdiIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(pathData).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()

val MdiCoffeeToGoOutline: ImageVector by lazy {
    mdiIcon(
        name = "MdiCoffeeToGoOutline",
        pathData = "M3 3V11A4 4 0 0 0 7 15H11A4 4 0 0 0 15 11V10H17C18.11 10 19 9.11 19 8V5C19 " +
            "3.9 18.11 3 17 3H3M5 5H13V11A2 2 0 0 1 11 13H7A2 2 0 0 1 5 11V5M15 5H17V8H15V5M16.67 " +
            "13.83L15.26 15.24L17 17H3V19H17L15.26 20.76L16.67 22.17L20.84 18L16.67 13.83Z",
    )
}

val MdiCoffeeMakerOutline: ImageVector by lazy {
    mdiIcon(
        name = "MdiCoffeeMakerOutline",
        pathData = "M18 6V4H20V2H6C4.9 2 4 2.9 4 4V20C4 21.1 4.9 22 6 22H20V20H15.97C17.2 19.09 " +
            "18 17.64 18 16V11H8V16C8 17.64 8.81 19.09 10.03 20H6V4H8V6C8 6.55 8.45 7 9 7H17C17.55 " +
            "7 18 6.55 18 6M10 16V13H16V16C16 17.65 14.65 19 13 19S10 17.65 10 16M13 8C13.55 8 14 " +
            "8.45 14 9S13.55 10 13 10 12 9.55 12 9 12.45 8 13 8Z",
    )
}
