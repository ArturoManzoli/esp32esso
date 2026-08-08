package com.esp32esso.tier1.ui

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esp32esso.tier1.data.ShotEntity
import com.esp32esso.tier1.data.ShotSamples
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class RankAxis(val label: String) {
    Recent("Most recent"),
    Overall("Overall"),
    Acidity("Acidity"),
    Bitterness("Bitterness"),
    Body("Body"),
}

// Rolling-window filter for the ranking list. `windowDays == null` means "all
// time"; otherwise only shots whose `savedAtEpochMs` is within the last N days
// are shown.
enum class DateWindow(val label: String, val windowDays: Int?) {
    All("All time", null),
    Last7("7d", 7),
    Last30("30d", 30),
    Last90("90d", 90),
}

// Ranking wall: two dropdown selectors inline in the top bar pick the sort
// order (most recent first by default) and the date window. Grade-based axes
// hide ungraded brews (score 0 on the axis) so the list stays useful — a brew
// without an Overall grade won't show up under Overall — while "Most recent"
// shows every brew in the window regardless of grading.
@Composable
fun RankingScreen(
    shots: List<ShotEntity>,
    onOpen: (ShotEntity) -> Unit,
    // Non-null only while the most recently finished brew hasn't been saved
    // yet -- i.e. the operator dismissed the "Show brew report?" prompt (or
    // hasn't acted on it) and might regret it. Null hides the button.
    onOpenLastReport: (() -> Unit)?,
    onBack: () -> Unit,
) {
    var axis by remember { mutableStateOf(RankAxis.Recent) }
    var window by remember { mutableStateOf(DateWindow.All) }
    val scored =
        remember(shots, axis, window) {
            val cutoffMs = window.windowDays?.let {
                System.currentTimeMillis() - it * 24L * 60L * 60L * 1000L
            }
            val inWindow = shots.asSequence().filter { cutoffMs == null || it.savedAtEpochMs >= cutoffMs }
            if (axis == RankAxis.Recent) {
                inWindow.sortedByDescending { it.savedAtEpochMs }.map { it to 0 }.toList()
            } else {
                inWindow
                    .map { it to scoreFor(it, axis) }
                    .filter { it.second > 0 }
                    .sortedByDescending { it.second }
                    .toList()
            }
        }

    // Tablet centre-cap: keep the ranking column at a comfortable reading
    // width even on the SHT-W09's 1024 dp landscape viewport.
    Box(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp)
            .padding(start = 20.dp, top = 6.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        // 30% shorter than the original: a smaller icon-button footprint and
        // a smaller title cut the row's own height, and the dropdowns'
        // tighter content padding matches.
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = EssoOrange)
            }
            Text(
                text = "Recent brews",
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 17.sp),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
            DropdownSelector(selected = axis, options = RankAxis.entries, optionLabel = { it.label }, onSelect = { axis = it })
            Spacer(Modifier.width(6.dp))
            DropdownSelector(selected = window, options = DateWindow.entries, optionLabel = { it.label }, onSelect = { window = it })
        }

        if (onOpenLastReport != null) {
            Button(
                onClick = onOpenLastReport,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = EssoOrange),
            ) { Text("Show last brew report") }
        }

        if (scored.isEmpty()) {
            FrostCard {
                val windowNote = if (window == DateWindow.All) "" else " in the last ${window.label}"
                val message = if (axis == RankAxis.Recent) {
                    "No brews saved$windowNote.\nSave a brew from the brew report screen to populate this list."
                } else {
                    "No brews graded on ${axis.label.lowercase()}$windowNote.\nGrade a brew from the brew report screen to populate this list."
                }
                Text(
                    text = message,
                    color = EssoOnSurfaceMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            scored.forEach { (shot, _) ->
                ShotRankCard(
                    shot = shot,
                    onOpen = { onOpen(shot) },
                )
            }
        }
      }
    }
}

// Compact "current value + caret" trigger that opens a DropdownMenu of
// `options`. Used to keep the axis/date filters inline in the top bar instead
// of burning two full rows of chips.
@Composable
private fun <T> DropdownSelector(
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            colors = ButtonDefaults.textButtonColors(contentColor = EssoOrange),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(optionLabel(selected), style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = { onSelect(option); expanded = false },
                    trailingIcon = if (option == selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null, tint = EssoOrange) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

// Fixed (unscaled) width of the ratings block before the 30% shrink below —
// wide enough for the longest label ("Bitterness") plus five stars laid out
// side by side, same as the brew report's Grade card.
private val RatingsBlockWidth = 240.dp
// 30% narrower than the graph's original 480 dp thumbnail width.
private val GraphBlockWidth = 336.dp

@Composable
private fun ShotRankCard(
    shot: ShotEntity,
    onOpen: () -> Unit,
) {
    // Mirrors the brew report's own fallback so a brew renamed there shows up
    // the same way here, rather than always reading "Untitled shot".
    val fullTitle = shot.name.ifBlank { "Untitled Brew" }
    val title = if (fullTitle.length > 42) fullTitle.take(42) + "…" else fullTitle
    // Tablet rotation toggles the graph column on/off; portrait drops it so
    // the remaining two columns (title/specs, ratings) still fit comfortably.
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Samples decode once per shot (not per recomposition) since they back
    // both the graph and the average-thermoblock spec below.
    val samples = remember(shot.id) { ShotSamples.decode(shot.samplesJson) }
    val shotWindow = remember(shot.id, samples) {
        val start = samples.firstOrNull()?.uptimeMs?.plus(10_000L) ?: return@remember null
        start..(start + shot.durationMs)
    }
    val avgThermoblockC = remember(shot.id, samples, shotWindow) {
        val win = shotWindow ?: return@remember null
        val temps = samples.filter { it.uptimeMs in win && !it.thermoblockC.isNaN() }.map { it.thermoblockC }
        if (temps.isEmpty()) null else temps.average().toFloat()
    }

    val bits = buildList {
        add("%d s".format((shot.durationMs / 1000L).toInt()))
        if (!shot.avgBar.isNaN()) add("avg %.1f bar".format(shot.avgBar))
        add("target %d°C".format(shot.targetC.toInt()))
        shot.avgGroupC?.let { add("avg group %.1f°C".format(it)) }
        avgThermoblockC?.let { add("avg block %.1f°C".format(it)) }
    }

    // The whole card body is the tap target for opening the report — there
    // are no more per-card icon actions to fight with a nested clickable.
    // A single row holds all three columns so the card stays as short as the
    // tallest one (the ratings block, usually) instead of stacking the graph
    // underneath and adding its own height on top.
    FrostCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp)) {
        // Every column wrap-content and centred against the others: the row
        // takes the height of its tallest child and the shorter ones sit
        // centred within it, so the card shrink-fits with no dead strips.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatDate(shot.savedAtEpochMs),
                        color = EssoOnSurfaceMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    bits.forEach { line ->
                        Text(text = line, color = EssoOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (isLandscape) {
                // Full graph (axes, gridlines, and the colour-coded legend as
                // its "subtitle"), scaled down 25% the same draw-time way as
                // the ratings block. No fillMaxHeight: it wraps its content so
                // it doesn't force the row taller than it needs to be.
                Column(
                    modifier = Modifier.scale(0.75f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BrewGraph(
                        history = samples,
                        shotWindow = shotWindow,
                        modifier = Modifier.width(GraphBlockWidth).height(150.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    GraphLegend(modifier = Modifier.width(GraphBlockWidth))
                }
            }
            // Label-left/stars-right, exactly like the report's Grade card,
            // all five axes, scaled to 77% — a draw-time transform (like CSS's)
            // so the column still reserves its full unscaled footprint. Anchor
            // the scale to the right edge so the shrunk content hugs the card's
            // right padding instead of floating centred with a dead gap after
            // the stars; that keeps the right margin matching the left.
            Column(
                modifier = Modifier.width(RatingsBlockWidth).graphicsLayer {
                    scaleX = 0.77f
                    scaleY = 0.77f
                    transformOrigin = TransformOrigin(1f, 0.5f)
                },
            ) {
                StarRow("Bitterness", shot.bitterness)
                StarRow("Acidity", shot.acidity)
                StarRow("Body", shot.body)
                StarRow("Visuals", shot.visuals)
                StarRow("Overall", shot.overall)
            }
        }
    }
}

// Only called for the grade-based axes; "Most recent" sorts by date instead
// and never reaches this function.
private fun scoreFor(shot: ShotEntity, axis: RankAxis): Int =
    when (axis) {
        RankAxis.Recent -> 0
        RankAxis.Overall -> shot.overall
        RankAxis.Acidity -> shot.acidity
        RankAxis.Bitterness -> shot.bitterness
        RankAxis.Body -> shot.body
    }

private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatDate(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(dateFmt)
