package com.esp32esso.tier1.ui

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.esp32esso.tier1.ProfileVertex
import com.esp32esso.tier1.UserProfile
import kotlin.math.roundToInt

// Profile module: create, edit and remove the operator's own brew profiles.
// A profile is a timeline of vertices — each pinning a target pressure and a
// target thermoblock temperature — that the machine interpolates through over
// the shot, so the drawn curve is the program.
@Composable
fun ProfilesScreen(
    profiles: List<UserProfile>,
    selectedId: String?,
    onSave: (UserProfile) -> Unit,
    onDelete: (UserProfile) -> Unit,
    onSelect: (UserProfile) -> Unit,
    onBack: () -> Unit,
) {
    var editing by remember { mutableStateOf<UserProfile?>(null) }

    editing?.let { draft ->
        ProfileEditor(
            initial = draft,
            onSave = {
                onSave(it)
                editing = null
            },
            onCancel = { editing = null },
        )
        return
    }

    // Two profiles per row in landscape, where the cards would otherwise be a
    // single stretched column on a tablet held sideways.
    val columns =
        if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) 2 else 1

    Box(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = if (columns > 1) 1100.dp else 760.dp)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = EssoOrange,
                    )
                }
                Text(
                    text = "Brew profiles",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { editing = UserProfile.blank(nextName(profiles)) }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.height(18.dp))
                    Spacer(Modifier.padding(3.dp))
                    Text("New")
                }
            }

            if (profiles.isEmpty()) {
                FrostCard {
                    Text(
                        text = "No profiles yet. \"New\" starts one you can shape by tapping " +
                            "vertices onto the timeline.",
                        style = MaterialTheme.typography.bodySmall,
                        color = EssoOnSurfaceMuted,
                    )
                }
            }

            for (row in profiles.chunked(columns)) {
                Row(
                    // Cards in a row share a height so their graphs line up even
                    // when one name or subtitle wraps and the other does not.
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    for (profile in row) {
                        ProfileListCard(
                            profile = profile,
                            selected = selectedId == UserProfile.wireId(profile.id),
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onEdit = { editing = profile },
                            onDuplicate = {
                                onSave(
                                    profile.copy(id = 0L, name = "${profile.name} copy"),
                                )
                            },
                            onDelete = { onDelete(profile) },
                            onSelect = { onSelect(profile) },
                        )
                    }
                    // Keeps a lone card on the last row at column width instead of
                    // letting it stretch across the whole grid.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ProfileListCard(
    profile: UserProfile,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
) {
    FrostCard(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).clickable(onClick = onEdit)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) EssoOrange else EssoOnSurface,
                )
                Text(
                    text = profileSubtitle(profile),
                    style = MaterialTheme.typography.labelSmall,
                    color = EssoOnSurfaceMuted,
                )
            }
            IconButton(onClick = onDuplicate) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "Duplicate profile",
                    tint = EssoOnSurfaceMuted,
                    modifier = Modifier.height(20.dp),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = "Delete profile",
                    tint = EssoOrange,
                    modifier = Modifier.height(22.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        ProfileEditorGraph(
            vertices = profile.vertices,
            axis = ProfileEditAxis.PRESSURE,
            selectedIndex = -1,
            spanSec = editorSpanSec(profile.vertices),
            modifier = Modifier.fillMaxWidth().height(120.dp),
            editable = false,
        )
        Spacer(Modifier.weight(1f, fill = false))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onEdit) { Text("Edit") }
            Button(
                onClick = onSelect,
                colors = ButtonDefaults.buttonColors(containerColor = EssoOrange),
            ) {
                Text("Use")
            }
        }
    }
}

// Vertex editor. The graph owns the shape; the row under it handles the one
// thing a fingertip cannot do on a curve — removing a point — plus nudges for
// dialling an exact value in.
@Composable
private fun ProfileEditor(
    initial: UserProfile,
    onSave: (UserProfile) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var vertices by remember(initial.id) { mutableStateOf(initial.vertices.sortedBy { it.timeSec }) }
    var axis by remember { mutableStateOf(ProfileEditAxis.PRESSURE) }
    var selected by remember { mutableIntStateOf(-1) }

    // The first vertex anchors t=0 and two points are the minimum a ramp can be
    // drawn from, so those are the ones a double tap cannot remove.
    fun canRemove(index: Int): Boolean = vertices.size > 2 && index > 0

    Box(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            // Wider than the list: the graph and its side controls share this row.
            modifier = Modifier.fillMaxWidth().widthIn(max = 1100.dp).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Discard changes",
                        tint = EssoOrange,
                    )
                }
                Text(
                    text = if (initial.id == 0L) "New profile" else "Edit profile",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                // Min-intrinsic height plus fillMaxHeight on both children is what
                // keeps the side card exactly as tall as the graph beside it.
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FrostCard(modifier = Modifier.weight(0.75f).fillMaxHeight()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        sectionLabel("Timeline")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AxisToggle("Pressure", axis == ProfileEditAxis.PRESSURE, EssoOrange) {
                                axis = ProfileEditAxis.PRESSURE
                            }
                            AxisToggle(
                                "Block temp",
                                axis == ProfileEditAxis.TEMPERATURE,
                                EssoOnSurface,
                            ) {
                                axis = ProfileEditAxis.TEMPERATURE
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    ProfileEditorGraph(
                        vertices = vertices,
                        axis = axis,
                        selectedIndex = selected,
                        spanSec = editorSpanSec(vertices),
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                        onSelect = { selected = it },
                        onAdd = { added ->
                            if (vertices.size < UserProfile.MAX_VERTICES &&
                                vertices.none { sameInstant(it.timeSec, added.timeSec) }
                            ) {
                                val next = (vertices + added).sortedBy { it.timeSec }
                                vertices = next
                                selected =
                                    next.indexOfFirst { sameInstant(it.timeSec, added.timeSec) }
                            }
                        },
                        onMove = { index, moved ->
                            vertices = vertices.toMutableList().also { it[index] = moved }
                        },
                        onDelete = { index ->
                            if (canRemove(index)) {
                                vertices = vertices.filterIndexed { i, _ -> i != index }
                                selected = -1
                            }
                        },
                    )
                }

                FrostCard(
                    modifier = Modifier.weight(0.25f).fillMaxHeight(),
                    contentPadding = PaddingValues(14.dp),
                ) {
                    val v = vertices.getOrNull(selected)
                    if (v == null) {
                        sectionLabel("Editing")
                        Spacer(Modifier.height(8.dp))
                        for (line in EDITOR_HELP) {
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                color = EssoOnSurfaceMuted,
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            sectionLabel("Vertex ${selected + 1}/${vertices.size}")
                            if (canRemove(selected)) {
                                IconButton(
                                    onClick = {
                                        vertices = vertices.filterIndexed { i, _ -> i != selected }
                                        selected = -1
                                    },
                                    modifier = Modifier.height(24.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.DeleteOutline,
                                        contentDescription = "Remove vertex",
                                        tint = EssoOrange,
                                    )
                                }
                            }
                        }
                        Text(
                            text = "${"%.1f".format(v.timeSec)} s",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${"%.1f".format(v.pressureBar)} bar · " +
                                "${v.blockTempC.roundToInt()} °C",
                            style = MaterialTheme.typography.bodySmall,
                            color = EssoOnSurfaceMuted,
                        )

                        Spacer(Modifier.height(10.dp))
                        sectionLabel("Pressure")
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            NudgeButton("−0.5", Modifier.weight(1f)) {
                                vertices = nudge(vertices, selected) {
                                    it.copy(
                                        pressureBar = (it.pressureBar - 0.5f)
                                            .coerceIn(0f, UserProfile.MAX_PRESSURE_BAR),
                                    )
                                }
                            }
                            NudgeButton("+0.5", Modifier.weight(1f)) {
                                vertices = nudge(vertices, selected) {
                                    it.copy(
                                        pressureBar = (it.pressureBar + 0.5f)
                                            .coerceIn(0f, UserProfile.MAX_PRESSURE_BAR),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        sectionLabel("Block temp")
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            NudgeButton("−1", Modifier.weight(1f)) {
                                vertices = nudge(vertices, selected) {
                                    it.copy(
                                        blockTempC = (it.blockTempC - 1f).coerceIn(
                                            UserProfile.MIN_BLOCK_TEMP_C,
                                            UserProfile.MAX_BLOCK_TEMP_C,
                                        ),
                                    )
                                }
                            }
                            NudgeButton("+1", Modifier.weight(1f)) {
                                vertices = nudge(vertices, selected) {
                                    it.copy(
                                        blockTempC = (it.blockTempC + 1f).coerceIn(
                                            UserProfile.MIN_BLOCK_TEMP_C,
                                            UserProfile.MAX_BLOCK_TEMP_C,
                                        ),
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${vertices.size}/${UserProfile.MAX_VERTICES} vertices · " +
                            "${"%.0f".format(vertices.maxOfOrNull { it.timeSec } ?: 0f)} s",
                        style = MaterialTheme.typography.labelSmall,
                        color = EssoOnSurfaceMuted,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            onSave(
                                initial.copy(
                                    name = name.ifBlank { "Untitled profile" },
                                    vertices = vertices.sortedBy { it.timeSec },
                                ),
                            )
                        },
                        enabled = vertices.size >= 2,
                        colors = ButtonDefaults.buttonColors(containerColor = EssoOrange),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Save profile")
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

private val EDITOR_HELP = listOf(
    "Tap a handle to select it.",
    "Drag a handle to reshape the curve.",
    "Double-tap the curve to add a vertex.",
    "Double-tap a handle to remove it.",
)

@Composable
private fun AxisToggle(
    label: String,
    selected: Boolean,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = tint.copy(alpha = 0.25f)),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = EssoOnSurfaceMuted)
        }
    }
}

@Composable
private fun NudgeButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private fun nudge(
    vertices: List<ProfileVertex>,
    index: Int,
    transform: (ProfileVertex) -> ProfileVertex,
): List<ProfileVertex> {
    if (index !in vertices.indices) return vertices
    return vertices.toMutableList().also { it[index] = transform(it[index]) }
}

private fun profileSubtitle(profile: UserProfile): String {
    val bars = profile.vertices.map { it.pressureBar }
    val temps = profile.vertices.map { it.blockTempC }
    val peak = bars.maxOrNull() ?: 0f
    val loC = temps.minOrNull()?.roundToInt() ?: 0
    val hiC = temps.maxOrNull()?.roundToInt() ?: 0
    val tempText = if (loC == hiC) "$loC °C" else "$loC–$hiC °C"
    return "${profile.vertices.size} vertices · ${"%.0f".format(profile.durationSec)} s · " +
        "peak ${"%.1f".format(peak)} bar · $tempText block"
}

private fun nextName(existing: List<UserProfile>): String {
    var n = existing.size + 1
    val taken = existing.map { it.name }.toSet()
    while (taken.contains("My profile $n")) n++
    return "My profile $n"
}
