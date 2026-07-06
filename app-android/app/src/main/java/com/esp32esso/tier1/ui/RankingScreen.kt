package com.esp32esso.tier1.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.esp32esso.tier1.data.BeanEntity
import com.esp32esso.tier1.data.GrinderEntity
import com.esp32esso.tier1.data.ShotEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class RankAxis(val label: String) {
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

// Ranking wall: filter chips at the top select which grade axis to sort by
// (Overall by default). Ungraded shots (score 0 on the axis) are hidden so the
// list stays useful — a shot without an Overall grade won't show up under
// Overall.
@Composable
fun RankingScreen(
    shots: List<ShotEntity>,
    beans: List<BeanEntity>,
    grinders: List<GrinderEntity>,
    onDelete: (ShotEntity) -> Unit,
    onOpen: (ShotEntity) -> Unit,
    onBack: () -> Unit,
) {
    var axis by remember { mutableStateOf(RankAxis.Overall) }
    var window by remember { mutableStateOf(DateWindow.All) }
    val scored =
        remember(shots, axis, window) {
            val cutoffMs = window.windowDays?.let {
                System.currentTimeMillis() - it * 24L * 60L * 60L * 1000L
            }
            shots
                .asSequence()
                .filter { cutoffMs == null || it.savedAtEpochMs >= cutoffMs }
                .map { it to scoreFor(it, axis) }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .toList()
        }

    // Tablet centre-cap: keep the ranking column at a comfortable reading
    // width even on the SHT-W09's 1024 dp landscape viewport.
    Box(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = EssoOrange)
            }
            Text(
                text = "Ranking",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RankAxis.entries.forEach { candidate ->
                FilterChip(
                    selected = candidate == axis,
                    onClick = { axis = candidate },
                    label = { Text(candidate.label) },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EssoOrange.copy(alpha = 0.25f),
                            selectedLabelColor = EssoOrange,
                        ),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DateWindow.entries.forEach { candidate ->
                FilterChip(
                    selected = candidate == window,
                    onClick = { window = candidate },
                    label = { Text(candidate.label) },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EssoOrange.copy(alpha = 0.25f),
                            selectedLabelColor = EssoOrange,
                        ),
                )
            }
        }

        if (scored.isEmpty()) {
            FrostCard {
                val windowNote = if (window == DateWindow.All) "" else " in the last ${window.label}"
                Text(
                    text = "No shots graded on ${axis.label.lowercase()}$windowNote.\nGrade a shot from the brew report screen to populate this list.",
                    color = EssoOnSurfaceMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            scored.forEach { (shot, _) ->
                ShotRankCard(
                    shot = shot,
                    axis = axis,
                    bean = beans.firstOrNull { it.id == shot.beanId },
                    grinder = grinders.firstOrNull { it.id == shot.grinderId },
                    onOpen = { onOpen(shot) },
                    onDelete = { onDelete(shot) },
                )
            }
        }
      }
    }
}

@Composable
private fun ShotRankCard(
    shot: ShotEntity,
    axis: RankAxis,
    bean: BeanEntity?,
    grinder: GrinderEntity?,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val layer = rememberGraphicsLayer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val title = bean?.name ?: "Untitled shot"

    // Card body is what we capture for sharing; making it the clickable target
    // (rather than the outer FrostCard) keeps the ripple contained and lets
    // the icon column stay out of the tap area.
    FrostCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                },
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val specs = beanSpecs(bean)
                if (specs.isNotBlank()) {
                    Text(text = specs, color = EssoOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                }
                val g = grinderLine(grinder, shot.grinderSetting)
                if (g.isNotBlank()) {
                    Text(text = g, color = EssoOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(6.dp))
                StarRow("Overall", shot.overall)
                StarRow(axis.label, scoreFor(shot, axis).coerceAtLeast(0))
                val bits = buildList {
                    add("%d s".format((shot.durationMs / 1000L).toInt()))
                    add("peak %.1f bar".format(shot.peakBar))
                    add("%d°C".format(shot.targetC.toInt()))
                    shot.doseG?.let { add("dose %.1f g".format(it)) }
                }
                Text(
                    text = bits.joinToString(" · "),
                    color = EssoOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // Right-hand action column: delete + share stacked at the top,
            // date pinned just below them. Grouped this way per the layout
            // spec so the icons read as a single cluster.
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete shot", tint = EssoError)
                    }
                    IconButton(onClick = {
                        shareLayerAsImage(context, scope, layer, "Shot · $title")
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share", tint = EssoOrange)
                    }
                }
                Text(
                    text = formatDate(shot.savedAtEpochMs),
                    color = EssoOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}

private fun scoreFor(shot: ShotEntity, axis: RankAxis): Int =
    when (axis) {
        RankAxis.Overall -> shot.overall
        RankAxis.Acidity -> shot.acidity
        RankAxis.Bitterness -> shot.bitterness
        RankAxis.Body -> shot.body
    }

private fun beanSpecs(bean: BeanEntity?): String {
    if (bean == null) return ""
    val bits = mutableListOf<String>()
    if (bean.roaster.isNotBlank()) bits += bean.roaster
    if (bean.origin.isNotBlank()) bits += bean.origin
    if (bean.process.isNotBlank()) bits += bean.process
    if (bean.roastLevel.isNotBlank()) bits += bean.roastLevel
    return bits.joinToString(" · ")
}

private fun grinderLine(grinder: GrinderEntity?, setting: String): String {
    val name = grinder?.name?.takeIf { it.isNotBlank() }
    val s = setting.takeIf { it.isNotBlank() }
    return when {
        name != null && s != null -> "$name @ $s"
        name != null -> name
        s != null -> "grind @ $s"
        else -> ""
    }
}

private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatDate(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(dateFmt)
