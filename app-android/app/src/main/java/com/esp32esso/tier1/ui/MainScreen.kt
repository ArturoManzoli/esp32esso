package com.esp32esso.tier1.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.esp32esso.tier1.BrewProfile
import com.esp32esso.tier1.ble.BleUiState
import com.esp32esso.tier1.ble.BrewSource
import com.esp32esso.tier1.ble.ConnectionPhase
import com.esp32esso.tier1.ble.GraphSample
import com.esp32esso.tier1.ble.MachineState
import kotlin.math.abs
import kotlin.math.roundToInt

// Screen-width threshold above which the main screen swaps to the two-column
// tablet layout. sw600dp catches the Huawei SHT-W09 in landscape (~1024 dp)
// as well as bigger tablets and unfolded foldables, while leaving phones in
// portrait and small landscape phones on the single-column layout.
private const val WIDE_LAYOUT_MIN_DP = 600

// Stand-in machine state used before the first notification arrives / while
// disconnected, so the full dashboard still renders instead of a bare status
// line. NaN sensor fields fall through the readouts' formatters as "—", and
// the controls are disabled (see the `enabled` flag) so the placeholder is
// never mistaken for a live machine.
private val OFFLINE_MACHINE = MachineState(
    thermoblockTempC = Float.NaN,
    groupTempC = Float.NaN,
    groupSetpointC = Float.NaN,
    thermoblockSetpointC = Float.NaN,
    pressureBar = Float.NaN,
    flowMlS = Float.NaN,
    weightG = Float.NaN,
    thermoblockOffsetC = 0f,
    shotTimeMs = 0L,
    uptimeMs = 0L,
    heaterDutyPct = 0,
    heaterOn = false,
    faultCode = 0,
    tuning = false,
    brewing = false,
    brewSource = BrewSource.Idle,
    groupSensorOk = true,
    heaterEnabled = false,
    flushing = false,
    thermoblockManual = false,
)

@Composable
fun MainScreen(
    uiState: BleUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSetpointChanged: (Float) -> Unit,
    onThermoblockSetpointChanged: (Float) -> Unit,
    onPressureSetpointChanged: (Float) -> Unit,
    onTareScale: () -> Unit,
    onWeightTargetChanged: (Float) -> Unit,
    onBrewToggle: (Boolean) -> Unit,
    onProfileBrewToggle: (Boolean) -> Unit,
    onHeaterToggle: (Boolean) -> Unit,
    onPreinfusionChanged: (Float) -> Unit,
    onFlush: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenRanking: () -> Unit,
    onOpenProfiles: () -> Unit,
    onSecretDemoToggle: () -> Unit,
    onSecretShowReport: () -> Unit,
    demoMode: Boolean,
    displayShotTimeMs: Long,
    selectedProfile: BrewProfile?,
    profiles: List<BrewProfile>,
    onProfileSelected: (BrewProfile?) -> Unit,
) {
    val connected = uiState.phase == ConnectionPhase.Connected
    // Render the live state when we have it, otherwise fall back to the offline
    // placeholder so the dashboard layout is always present. Controls key off
    // `connected`, so the placeholder shows readouts as "—" without pretending
    // the machine is reachable.
    val machine = uiState.machineState ?: OFFLINE_MACHINE
    val config = LocalConfiguration.current
    val wide = config.screenWidthDp >= WIDE_LAYOUT_MIN_DP

    if (wide) {
        MainScreenWide(
            uiState = uiState,
            machine = machine,
            connected = connected,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            onSetpointChanged = onSetpointChanged,
            onThermoblockSetpointChanged = onThermoblockSetpointChanged,
            onPressureSetpointChanged = onPressureSetpointChanged,
            onTareScale = onTareScale,
            onWeightTargetChanged = onWeightTargetChanged,
            onBrewToggle = onBrewToggle,
            onProfileBrewToggle = onProfileBrewToggle,
            onHeaterToggle = onHeaterToggle,
            onPreinfusionChanged = onPreinfusionChanged,
            onFlush = onFlush,
            onOpenSettings = onOpenSettings,
            onOpenLibrary = onOpenLibrary,
            onOpenRanking = onOpenRanking,
            onOpenProfiles = onOpenProfiles,
            onSecretDemoToggle = onSecretDemoToggle,
            onSecretShowReport = onSecretShowReport,
            demoMode = demoMode,
            displayShotTimeMs = displayShotTimeMs,
            selectedProfile = selectedProfile,
            profiles = profiles,
            onProfileSelected = onProfileSelected,
        )
    } else {
        MainScreenNarrow(
            uiState = uiState,
            connected = connected,
            machine = machine,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            onSetpointChanged = onSetpointChanged,
            onThermoblockSetpointChanged = onThermoblockSetpointChanged,
            onPressureSetpointChanged = onPressureSetpointChanged,
            onTareScale = onTareScale,
            onWeightTargetChanged = onWeightTargetChanged,
            onBrewToggle = onBrewToggle,
            onProfileBrewToggle = onProfileBrewToggle,
            onHeaterToggle = onHeaterToggle,
            onPreinfusionChanged = onPreinfusionChanged,
            onFlush = onFlush,
            onOpenSettings = onOpenSettings,
            onOpenLibrary = onOpenLibrary,
            onOpenRanking = onOpenRanking,
            onOpenProfiles = onOpenProfiles,
            onSecretDemoToggle = onSecretDemoToggle,
            onSecretShowReport = onSecretShowReport,
            demoMode = demoMode,
            displayShotTimeMs = displayShotTimeMs,
            selectedProfile = selectedProfile,
            profiles = profiles,
            onProfileSelected = onProfileSelected,
        )
    }
}

// Portrait / phone-landscape single-column stack (the historical layout).
@Composable
private fun MainScreenNarrow(
    uiState: BleUiState,
    connected: Boolean,
    machine: MachineState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSetpointChanged: (Float) -> Unit,
    onThermoblockSetpointChanged: (Float) -> Unit,
    onPressureSetpointChanged: (Float) -> Unit,
    onTareScale: () -> Unit,
    onWeightTargetChanged: (Float) -> Unit,
    onBrewToggle: (Boolean) -> Unit,
    onProfileBrewToggle: (Boolean) -> Unit,
    onHeaterToggle: (Boolean) -> Unit,
    onPreinfusionChanged: (Float) -> Unit,
    onFlush: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenRanking: () -> Unit,
    onOpenProfiles: () -> Unit,
    onSecretDemoToggle: () -> Unit,
    onSecretShowReport: () -> Unit,
    demoMode: Boolean,
    displayShotTimeMs: Long,
    selectedProfile: BrewProfile?,
    profiles: List<BrewProfile>,
    onProfileSelected: (BrewProfile?) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HeaderBar(
            uiState = uiState,
            connected = connected,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            onOpenSettings = onOpenSettings,
            onOpenLibrary = onOpenLibrary,
            onOpenRanking = onOpenRanking,
            onOpenProfiles = onOpenProfiles,
            onSecretDemoToggle = onSecretDemoToggle,
        )

        if (!connected) {
            uiState.statusMessage?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
        }
        TemperaturesCard(
            machine = machine,
            enabled = connected,
            onHeaterToggle = onHeaterToggle,
            onSetGroupTarget = onSetpointChanged,
            onSetThermoblockTarget = onThermoblockSetpointChanged,
            onSetPressureTarget = onPressureSetpointChanged,
            onTareScale = onTareScale,
            onWeightTargetChanged = onWeightTargetChanged,
        )
        ShotCard(
            machine = machine,
            enabled = connected,
            setpoint = uiState.pendingSetpointC ?: resolvedGroupTargetC(machine),
            preinfusionSec = uiState.preinfusionSec ?: 2f,
            displayShotTimeMs = displayShotTimeMs,
            onBrewToggle = onBrewToggle,
            onApplySetpoint = onSetpointChanged,
            onPreinfusionChanged = onPreinfusionChanged,
            onFlush = onFlush,
            onSecretShowReport = onSecretShowReport,
            demoMode = demoMode,
        )
        GraphCard(uiState.history)
        ProfileCard(
            selected = selectedProfile,
            profiles = profiles,
            enabled = connected,
            running = machine.brewing,
            elapsedSec = machine.profileElapsedSec,
            onSelect = onProfileSelected,
            onRunToggle = onProfileBrewToggle,
        )
    }
}

// Tablet / landscape 2-column layout. The whole thing lives inside a single
// vertical scroll so both columns move together — previously each column had
// its own scroll state, which reads awkwardly on a mounted tablet where the
// operator expects the "main panel" to behave as a single surface. Graph is
// given a fixed height so it plays nicely inside the outer scroll (no infinite
// constraint), tall enough to remain the hero panel.
@Composable
private fun MainScreenWide(
    uiState: BleUiState,
    machine: MachineState,
    connected: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSetpointChanged: (Float) -> Unit,
    onThermoblockSetpointChanged: (Float) -> Unit,
    onPressureSetpointChanged: (Float) -> Unit,
    onTareScale: () -> Unit,
    onWeightTargetChanged: (Float) -> Unit,
    onBrewToggle: (Boolean) -> Unit,
    onProfileBrewToggle: (Boolean) -> Unit,
    onHeaterToggle: (Boolean) -> Unit,
    onPreinfusionChanged: (Float) -> Unit,
    onFlush: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenRanking: () -> Unit,
    onOpenProfiles: () -> Unit,
    onSecretDemoToggle: () -> Unit,
    onSecretShowReport: () -> Unit,
    demoMode: Boolean,
    displayShotTimeMs: Long,
    selectedProfile: BrewProfile?,
    profiles: List<BrewProfile>,
    onProfileSelected: (BrewProfile?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Vertical padding halved (was 10 dp) and the header→cards gap
            // removed so the four cards ride right up under the top bar.
            .padding(horizontal = 22.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        HeaderBar(
            uiState = uiState,
            connected = connected,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            onOpenSettings = onOpenSettings,
            onOpenLibrary = onOpenLibrary,
            onOpenRanking = onOpenRanking,
            onOpenProfiles = onOpenProfiles,
            onSecretDemoToggle = onSecretDemoToggle,
        )

        // Row: controls on the left, graph + profile on the right. It claims
        // every pixel the header leaves and splits it evenly, so the four cards
        // come out the same size and fill the panel edge to edge. Each card
        // spreads its content over that height instead of hugging it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TemperaturesCard(
                    machine = machine,
                    enabled = connected,
                    onHeaterToggle = onHeaterToggle,
                    onSetGroupTarget = onSetpointChanged,
                    onSetThermoblockTarget = onThermoblockSetpointChanged,
                    onSetPressureTarget = onPressureSetpointChanged,
                    onTareScale = onTareScale,
                    onWeightTargetChanged = onWeightTargetChanged,
                    modifier = Modifier.weight(1f),
                )
                ShotCard(
                    machine = machine,
                    enabled = connected,
                    setpoint = uiState.pendingSetpointC ?: resolvedGroupTargetC(machine),
                    preinfusionSec = uiState.preinfusionSec ?: 2f,
                    displayShotTimeMs = displayShotTimeMs,
                    onBrewToggle = onBrewToggle,
                    onApplySetpoint = onSetpointChanged,
                    onPreinfusionChanged = onPreinfusionChanged,
                    onFlush = onFlush,
                    onSecretShowReport = onSecretShowReport,
                    demoMode = demoMode,
                    modifier = Modifier.weight(1f),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GraphCard(
                    history = uiState.history,
                    modifier = Modifier.weight(1f),
                    graphModifier = Modifier.fillMaxWidth().weight(1f),
                )
                ProfileCard(
                    selected = selectedProfile,
                    profiles = profiles,
                    enabled = connected,
                    running = machine.brewing,
                    elapsedSec = machine.profileElapsedSec,
                    onSelect = onProfileSelected,
                    onRunToggle = onProfileBrewToggle,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// Minimal header bar. Left: a hamburger menu that hosts every section jump
// (ranking, library, settings), followed by the title "Esp32esso · <status>".
// Right: a single connect/disconnect icon. Everything that used to live inline
// as separate buttons/icons now collapses into the menu, keeping the bar a
// single slim row so the four cards fit the panel without scrolling.
@Composable
private fun HeaderBar(
    uiState: BleUiState,
    connected: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenRanking: () -> Unit,
    onOpenProfiles: () -> Unit,
    onSecretDemoToggle: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val onStatusTap = rememberTripleTap(onSecretDemoToggle)
    // A phone cannot fit three labelled section buttons and the status line side
    // by side, so it gets the icons alone and hands the width back to the title.
    val compact = LocalConfiguration.current.screenWidthDp < 600
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f),
        ) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = EssoOrange)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Settings") },
                        onClick = { menuOpen = false; onOpenSettings() },
                        leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    )
                }
            }
            // Inverted order: device name first, connection status after.
            val statusWord =
                when (uiState.phase) {
                    ConnectionPhase.Idle -> "Not connected"
                    ConnectionPhase.Scanning -> "Scanning…"
                    ConnectionPhase.Connecting -> "Connecting…"
                    ConnectionPhase.Connected -> "Connected"
                    ConnectionPhase.Failed -> "Connection failed"
                }
            Text(
                // The device name is a constant in practice, so where there is
                // only room for one of the two, the status is the half worth
                // keeping.
                text =
                    if (compact) {
                        statusWord
                    } else {
                        "${uiState.deviceName ?: "Esp32esso"} · $statusWord"
                    },
                color = if (connected) EssoOnSurface else EssoOnSurfaceMuted,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                // Never let a tight header wrap this into a one-glyph-per-line
                // column; it truncates instead.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Hidden offline shortcut: triple-tap toggles the demo state.
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onStatusTap,
                ),
            )
        }
        // Right cluster: the section shortcuts (ranking + library) sit inline
        // again, separated from the connect/disconnect icon by a 30 dp gutter
        // so the Bluetooth toggle stays visually distinct on the far edge.
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Ranking + library read the local Room database, so they stay
            // reachable whether or not a machine is connected.
            // The to-go cup glyph faces left by default; flipped so the spout
            // points the same direction the icon reads (→ text).
            SectionButton(
                icon = MdiCoffeeToGoOutline,
                label = "recent brews",
                compact = compact,
                mirrored = true,
                onClick = onOpenRanking,
            )
            SectionButton(
                icon = MdiChartLine,
                label = "profiles",
                compact = compact,
                onClick = onOpenProfiles,
            )
            SectionButton(
                icon = MdiCoffeeMakerOutline,
                label = "recipes",
                compact = compact,
                onClick = onOpenLibrary,
            )
            Spacer(Modifier.width(if (compact) 6.dp else 30.dp))
            // Single connect/disconnect icon. Tapping toggles the link; the
            // glyph reflects the current state and is muted while a scan/
            // connect is in flight (tap is a no-op then).
            when (uiState.phase) {
                ConnectionPhase.Connected ->
                    IconButton(onClick = onDisconnect) {
                        Icon(Icons.Filled.BluetoothConnected, contentDescription = "Disconnect", tint = EssoOrange)
                    }
                ConnectionPhase.Scanning, ConnectionPhase.Connecting ->
                    IconButton(onClick = {}, enabled = false) {
                        Icon(Icons.AutoMirrored.Filled.BluetoothSearching, contentDescription = "Connecting", tint = EssoOnSurfaceMuted)
                    }
                else ->
                    IconButton(onClick = onConnect) {
                        Icon(Icons.Filled.Bluetooth, contentDescription = "Connect", tint = EssoOrange)
                    }
            }
        }
    }
}

// Header shortcut: labelled on a tablet, icon-only where the width does not
// allow for the label.
@Composable
private fun SectionButton(
    icon: ImageVector,
    label: String,
    compact: Boolean,
    onClick: () -> Unit,
    mirrored: Boolean = false,
) {
    val glyph = Modifier.size(20.dp).let { if (mirrored) it.graphicsLayer(scaleX = -1f) else it }
    if (compact) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = label, tint = EssoOrange, modifier = glyph)
        }
    } else {
        TextButton(
            onClick = onClick,
            colors = ButtonDefaults.textButtonColors(contentColor = EssoOrange),
        ) {
            Icon(icon, contentDescription = null, modifier = glyph)
            Spacer(Modifier.width(6.dp))
            Text(label)
        }
    }
}

// The two temperature targets (group, thermoblock) are reactively linked and
// exactly one is the "auto" (derived) side at any time. In the default
// relational mode the operator drives the group and the thermoblock auto-tracks
// group + offset; when a manual thermoblock override is active the roles invert
// and the group figure becomes a live estimate (block − offset). Whichever side
// is derived reads off the other plus/minus the currently dialled offset, so it
// keeps tracking both inputs instead of showing a stale number.
private fun resolvedGroupTargetC(machine: MachineState): Float =
    if (machine.thermoblockManual) {
        machine.thermoblockSetpointC - machine.thermoblockOffsetC
    } else {
        machine.groupSetpointC
    }

private fun resolvedBlockTargetC(machine: MachineState): Float =
    if (machine.thermoblockManual) {
        machine.thermoblockSetpointC
    } else {
        machine.groupSetpointC + machine.thermoblockOffsetC
    }

// A resting tray still wanders a tenth of a gram at a time, and a figure that
// repaints at the notification rate reads as broken rather than precise. Hold a
// new value for 700 ms while things are quiet; a shot in progress or a jump big
// enough to be an actual load (a cup landing) goes straight through, so nothing
// that matters is ever late.
private const val WEIGHT_SETTLE_MS = 700L
private const val WEIGHT_SETTLE_JUMP_G = 2f

// Both card switches are drawn down to this factor. Scaling paints smaller
// without changing the layout box, which is what keeps the two stacked switches
// on the same vertical line.
private const val SWITCH_SCALE = 0.7f

// Purely positional trims: the scale row's outer elements sit low against the
// total's headline figure, and the switch column sits closer to the card edge.
private val HEATER_ROW_DROP = 6.dp
private val SCALE_ROW_LIFT = 6.dp
private val SCALE_ROW_DROP = 9.dp
private val SWITCH_NUDGE_RIGHT = 7.dp

@Composable
private fun settledGrams(grams: Float, brewing: Boolean): Float {
    val shown = remember { mutableFloatStateOf(grams) }
    val lastMs = remember { mutableLongStateOf(0L) }
    LaunchedEffect(grams, brewing) {
        val now = System.currentTimeMillis()
        val jumped = abs(grams - shown.floatValue) >= WEIGHT_SETTLE_JUMP_G
        if (brewing || jumped || now - lastMs.longValue >= WEIGHT_SETTLE_MS) {
            shown.floatValue = grams
            lastMs.longValue = now
        }
    }
    return shown.floatValue
}

private fun formatGrams(grams: Float): String =
    if (grams.isNaN()) "— g" else "%.1f g".format(grams)

// A single cell, kept deliberately small: these are a wiring/health check, not
// a number anyone brews by. White like the rest of the scale row.
// Both columns are fixed width and the figure is right-aligned, so gaining or
// losing a digit moves nothing: an unpinned number here jitters sideways every
// time the last decimal ticks over.
@Composable
private fun CellWeightReadout(label: String, grams: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f),
            maxLines = 1,
            modifier = Modifier.width(30.dp),
        )
        Text(
            text = formatGrams(grams),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(40.dp),
        )
    }
}

// Arms and disarms the firmware-side stop without losing the dialled yield: the
// switch writes 0 to disable, and the last target back to re-enable, so toggling
// it does not force the operator through the wheel again.
@Composable
private fun StopOnWeightSwitch(
    targetG: Float,
    enabled: Boolean,
    onArmedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val armed = targetG > 0f
    val lastTarget = remember { mutableFloatStateOf(DEFAULT_WEIGHT_TARGET_G) }
    LaunchedEffect(targetG) { if (targetG > 0f) lastTarget.floatValue = targetG }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Stop on\nweight",
            style = MaterialTheme.typography.labelMedium,
            color = EssoOnSurfaceMuted,
            textAlign = TextAlign.End,
        )
        Spacer(Modifier.width(4.dp))
        Switch(
            checked = armed,
            onCheckedChange = { on -> onArmedChange(if (on) lastTarget.floatValue else 0f) },
            enabled = enabled,
            modifier = Modifier.scale(SWITCH_SCALE),
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = EssoOrange,
                    checkedTrackColor = EssoOrange.copy(alpha = 0.4f),
                ),
        )
    }
}

// The tray total, settable the same way the pressure readout is: tapping it
// dials the yield the firmware then cuts the shot at, and resetting clears the
// stop. Kept in the compact white style of the cells beside it rather than
// reusing TempReadout wholesale, so the scale row stays visually one group.
@Composable
private fun TotalWeightReadout(
    grams: Float,
    targetG: Float,
    onPick: ((Float) -> Unit)?,
) {
    var picking by remember { mutableStateOf(false) }
    val armed = targetG > 0f
    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = if (onPick != null) {
                Modifier.clip(RoundedCornerShape(10.dp)).clickable { picking = true }
            } else {
                Modifier
            },
        ) {
            Text(
                text = "Total",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
            Text(
                text = formatGrams(grams),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = if (armed) "→ %.0f g STOP".format(targetG) else "→ — g",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (armed) FontWeight.Bold else FontWeight.Normal,
                color = if (armed) EssoOrange else EssoOnSurfaceMuted,
            )
        }
        if (picking && onPick != null) {
            TempWheelPopup(
                title = "Stop at",
                initial = if (armed) targetG else DEFAULT_WEIGHT_TARGET_G,
                values = WEIGHT_WHEEL_VALUES,
                format = { "%.0f g".format(it) },
                resetLabel = "Reset weight stop",
                onDismiss = { picking = false },
                onConfirm = {
                    picking = false
                    onPick(it)
                },
                onReset = { picking = false; onPick(0f) },
            )
        }
    }
}

@Composable
private fun TareButton(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Color.White.copy(alpha = if (enabled) 0.5f else 0.2f), RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "T",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = if (enabled) 1f else 0.3f),
        )
    }
}

@Composable
private fun TemperaturesCard(
    machine: MachineState,
    enabled: Boolean,
    onHeaterToggle: (Boolean) -> Unit,
    onSetGroupTarget: (Float) -> Unit,
    onSetThermoblockTarget: (Float) -> Unit,
    onSetPressureTarget: (Float) -> Unit,
    onTareScale: () -> Unit,
    onWeightTargetChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Tighter than the default 20 dp box: 5 dp off the top, 7 dp off the
    // bottom, shrinking the card's dead space around the readouts/heater row.
    // When the caller stretches the card (wide layout), the readouts stay at the
    // top and the drip-tray row settles against the bottom edge.
    FrostCard(
        modifier = modifier,
        accent = true,
        contentPadding = PaddingValues(start = 20.dp, top = 15.dp, end = 20.dp, bottom = 13.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Group / Pressure (centre column) / Thermoblock in a single row. The
        // pressure column collapses to fixed non-numeric text before the first
        // sample so the row does not jitter when the transducer starts pushing.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Group is the operator-set master by default and becomes the
            // derived "auto" estimate (block − offset) once a manual thermoblock
            // override takes over; the block mirrors it.
            val groupTarget = resolvedGroupTargetC(machine)
            val blockTarget = resolvedBlockTargetC(machine)
            TempReadout(
                label = "Group / portafilter",
                value = machine.groupTempC,
                target = groupTarget,
                degraded = !machine.groupSensorOk,
                onPick = if (enabled) onSetGroupTarget else null,
                pickInitial = groupTarget,
                resetValue = DEFAULT_GROUP_TEMP_C,
                autoActive = machine.thermoblockManual,
                ovrActive = !machine.thermoblockManual,
            )
            PressureReadout(
                pressureBar = machine.pressureBar,
                setpointBar = machine.pressureSetpointBar,
                manual = machine.pressureManual,
                onPick = if (enabled) onSetPressureTarget else null,
            )
            TempReadout(
                label = "Thermoblock",
                value = machine.thermoblockTempC,
                target = blockTarget,
                degraded = false,
                onPick = if (enabled) onSetThermoblockTarget else null,
                pickInitial = blockTarget,
                // 0 °C is the client's sentinel for "clear the manual thermoblock
                // override", returning it to group setpoint + offset.
                resetValue = 0f,
                autoActive = !machine.thermoblockManual,
                ovrActive = machine.thermoblockManual,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().offset(y = HEATER_ROW_DROP),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                val heaterState =
                    when {
                        !machine.heaterEnabled -> "Heater standby"
                        machine.heaterOn -> "Heater ON · ${machine.heaterDutyPct}%"
                        else -> "Heater idle · ${machine.heaterDutyPct}%"
                    }
                Text(
                    text = heaterState,
                    fontWeight = FontWeight.Medium,
                    color =
                        when {
                            !machine.heaterEnabled -> EssoOnSurfaceMuted
                            machine.heaterOn -> EssoOrange
                            else -> EssoOnSurfaceMuted
                        },
                )
                if (machine.tuning) Text("Tuning bench", style = MaterialTheme.typography.labelSmall, color = EssoOnSurfaceMuted)
            }
            Row(
                modifier = Modifier.offset(x = SWITCH_NUDGE_RIGHT),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (machine.heaterEnabled) "On" else "Off",
                    style = MaterialTheme.typography.labelMedium,
                    color = EssoOnSurfaceMuted,
                )
                Spacer(Modifier.padding(4.dp))
                Switch(
                    checked = machine.heaterEnabled,
                    onCheckedChange = onHeaterToggle,
                    enabled = enabled,
                    modifier = Modifier.scale(SWITCH_SCALE),
                    colors =
                        SwitchDefaults.colors(
                            checkedThumbColor = EssoOrange,
                            checkedTrackColor = EssoOrange.copy(alpha = 0.4f),
                        ),
                )
            }
        }
        // Drip tray: the two cells stacked small on the left (they only exist to
        // make a bad corner or a dead one visible), the settable total in the
        // middle, and the stop-on-weight switch flush right so it stacks under
        // the heater switch above it.
        Row(
            modifier = Modifier.fillMaxWidth().offset(y = -SCALE_ROW_LIFT),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Everything but the total is nudged down so it reads against the
            // total's big figure rather than its "Total" caption. Offsets, so the
            // row still measures on the real content and nothing reflows.
            Column(modifier = Modifier.offset(y = SCALE_ROW_DROP)) {
                CellWeightReadout("Left", settledGrams(machine.weightLeftG, machine.brewing))
                CellWeightReadout("Right", settledGrams(machine.weightRightG, machine.brewing))
            }
            // Its own row child rather than part of the total, so the even
            // spacing puts it midway between the cells and the input.
            TareButton(
                onClick = onTareScale,
                enabled = enabled,
                modifier = Modifier.offset(y = SCALE_ROW_DROP),
            )
            TotalWeightReadout(
                grams = settledGrams(machine.weightG, machine.brewing),
                targetG = machine.weightTargetG,
                onPick = if (enabled) onWeightTargetChanged else null,
            )
            StopOnWeightSwitch(
                targetG = machine.weightTargetG,
                enabled = enabled,
                onArmedChange = onWeightTargetChanged,
                modifier = Modifier.offset(x = SWITCH_NUDGE_RIGHT, y = SCALE_ROW_DROP),
            )
        }
        if (!machine.groupSensorOk) {
            Text(
                text = "Group sensor offline — holding thermoblock at target",
                style = MaterialTheme.typography.labelSmall,
                color = EssoError,
            )
        }
        machine.faultLabel?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
    }
}

// `onPick` (when non-null) turns the whole readout into a tap target that
// opens a rolling wheel to set that sensor's target directly. `pickInitial`
// seeds the wheel with the current target.
@Composable
private fun TempReadout(
    label: String,
    value: Float,
    target: Float,
    degraded: Boolean,
    onPick: ((Float) -> Unit)? = null,
    pickInitial: Float = target,
    resetValue: Float? = null,
    // Exactly one of these is set per readout: `autoActive` marks the derived
    // side (green "auto"), `ovrActive` marks the operator-set side (orange
    // "OVR"). Which is which flips with the manual-thermoblock mode.
    autoActive: Boolean = false,
    ovrActive: Boolean = false,
    // Defaults describe a temperature readout; the pressure readout overrides
    // them so both sensors share one tap-to-set pattern.
    format: (Float) -> String = ::formatTemp,
    wheelValues: List<Float> = TEMP_WHEEL_VALUES,
    // The wheel keeps a decimal so its half-steps stay distinguishable, which
    // the rounded readout format would collapse.
    wheelFormat: (Float) -> String = { "%.1f °C".format(it) },
    resetLabel: String = "Reset temp override",
    alignment: Alignment.Horizontal = Alignment.Start,
) {
    var picking by remember { mutableStateOf(false) }
    Box {
        Column(
            modifier = if (onPick != null) {
                Modifier.clip(RoundedCornerShape(10.dp)).clickable { picking = true }
            } else {
                Modifier
            },
            horizontalAlignment = alignment,
        ) {
            sectionLabel(label)
            Spacer(Modifier.height(2.dp))
            Text(
                text = format(value),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = if (degraded) EssoOnSurfaceMuted else EssoOnSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "→ ${format(target)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        autoActive -> EssoGreen
                        onPick != null -> EssoOrange
                        else -> EssoOnSurfaceMuted
                    },
                )
                if (autoActive || ovrActive) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (autoActive) "AUTO" else "FIXED",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (autoActive) EssoGreen else EssoOrange,
                    )
                }
            }
        }
        if (picking && onPick != null) {
            TempWheelPopup(
                title = label,
                initial = pickInitial,
                values = wheelValues,
                format = wheelFormat,
                resetLabel = resetLabel,
                onDismiss = { picking = false },
                onConfirm = {
                    picking = false
                    onPick(it)
                },
                onReset = resetValue?.let { rv -> { picking = false; onPick(rv) } },
            )
        }
    }
}

// Direct-set wheel: a spinning column of 88.0…140.0 °C values (0.5 steps) shown
// in a popover anchored under the tapped readout. The centred value is the
// selection; "Set" commits it. This bypasses the brew-target slider / offset
// slider so the operator can dial a sensor's target straight from its number.
private val TEMP_WHEEL_VALUES: List<Float> =
    generateSequence(88f) { it + 0.5f }.takeWhile { it <= 140.001f }.toList()

// Brew-pressure wheel. Capped at the top of the measured pump curve (see
// kMaxManualPressureBar in the firmware), since the pump cannot hold more.
private val PRESSURE_WHEEL_VALUES: List<Float> =
    generateSequence(1f) { it + 0.5f }.takeWhile { it <= 12.001f }.toList()

// Falls back to the machine profile's default brew temperature (see
// firmware defaultBrewTempC) when the operator resets the group dial.
private const val DEFAULT_GROUP_TEMP_C = 93f

// Seeds the pressure wheel when no hold is set yet.
private const val DEFAULT_PRESSURE_BAR = 9f

// Stop-on-weight wheel, in whole grams: wide enough for a lungo or a small
// carafe, and starting above the few grams a drip could trip the stop with.
private val WEIGHT_WHEEL_VALUES: List<Float> =
    generateSequence(10f) { it + 1f }.takeWhile { it <= 120.001f }.toList()

private const val DEFAULT_WEIGHT_TARGET_G = 36f

@Composable
private fun TempWheelPopup(
    title: String,
    initial: Float,
    values: List<Float>,
    format: (Float) -> String,
    resetLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
    onReset: (() -> Unit)? = null,
) {
    val itemHeight = 44.dp
    val visibleCount = 5
    val initialIndex = values
        .indexOfFirst { abs(it - initial) < 0.25f }
        .let { if (it < 0) values.indexOfFirst { v -> abs(v - initial) <= 0.5f }.coerceAtLeast(0) else it }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val fling = rememberSnapFlingBehavior(lazyListState = listState)
    val centeredIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            info.visibleItemsInfo
                .minByOrNull { abs((it.offset + it.size / 2f) - center) }
                ?.index ?: initialIndex
        }
    }

    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(0, 0),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(160.dp)
                .clip(FrostShape)
                .background(Color(0xFF241811))
                .border(FrostBorderAccent, FrostShape)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (onReset != null) {
                Text(
                    text = resetLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    textDecoration = TextDecoration.Underline,
                    color = EssoOrange,
                    modifier = Modifier.clickable(onClick = onReset),
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = EssoOnSurfaceMuted,
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight * visibleCount),
                contentAlignment = Alignment.Center,
            ) {
                // Selection band behind the centred row.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .clip(RoundedCornerShape(10.dp))
                        .background(EssoOrange.copy(alpha = 0.16f)),
                )
                LazyColumn(
                    state = listState,
                    flingBehavior = fling,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    contentPadding = PaddingValues(vertical = itemHeight * (visibleCount / 2)),
                ) {
                    items(values.size) { i ->
                        val v = values[i]
                        val isCenter = i == centeredIndex
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(itemHeight),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = format(v),
                                style = if (isCenter) {
                                    MaterialTheme.typography.titleLarge
                                } else {
                                    MaterialTheme.typography.titleMedium
                                },
                                fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                                color = if (isCenter) EssoOrange else EssoOnSurfaceMuted,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { onConfirm(values[centeredIndex]) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = EssoOrange),
            ) {
                Text("Set")
            }
        }
    }
}

// Live brew pressure, settable the same way the two temperatures are: tapping
// it dials a fixed pressure the shot then holds (FIXED), and resetting hands
// the shot back to the selected profile (AUTO). There is no derived target to
// show in AUTO, since the profile varies pressure across the shot.
@Composable
private fun PressureReadout(
    pressureBar: Float,
    setpointBar: Float,
    manual: Boolean,
    onPick: ((Float) -> Unit)?,
) {
    TempReadout(
        label = "Pressure",
        value = pressureBar,
        target = if (manual) setpointBar else Float.NaN,
        degraded = false,
        onPick = onPick,
        pickInitial = if (manual && !setpointBar.isNaN()) setpointBar else DEFAULT_PRESSURE_BAR,
        resetValue = 0f,
        autoActive = !manual,
        ovrActive = manual,
        format = ::formatBar,
        wheelValues = PRESSURE_WHEEL_VALUES,
        wheelFormat = { "%.1f bar".format(it) },
        resetLabel = "Reset pressure hold",
        alignment = Alignment.CenterHorizontally,
    )
}

private fun formatBar(bar: Float): String =
    if (bar.isNaN()) "— bar" else "%.1f bar".format(bar)

@Composable
private fun ShotCard(
    machine: MachineState,
    enabled: Boolean,
    setpoint: Float,
    preinfusionSec: Float,
    displayShotTimeMs: Long,
    onBrewToggle: (Boolean) -> Unit,
    onApplySetpoint: (Float) -> Unit,
    onPreinfusionChanged: (Float) -> Unit,
    onFlush: () -> Unit,
    onSecretShowReport: () -> Unit,
    demoMode: Boolean,
    modifier: Modifier = Modifier,
) {
    // Offline the setpoint arrives as NaN (no machine to report one); pin the
    // slider to a neutral mid-range value so roundToInt / the 80–110 range stay
    // valid. The control is disabled in that state anyway.
    val safeSetpoint = if (setpoint.isNaN()) 93f else setpoint
    var draft by remember(safeSetpoint) { mutableFloatStateOf(safeSetpoint) }
    val changed = abs(draft - safeSetpoint) >= 0.5f
    val running = machine.brewing
    // 30 % tighter vertical box (was 20 dp) so the card hugs its content.
    FrostCard(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Top row: shot timer on the left, the shot action stack on the right
        // (Start shot on top, Flush directly beneath it). The stack is sized to
        // its widest child so Start shot and Flush share one width.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column {
                sectionLabel("Brew timer")
                Text(
                    text = formatShot(displayShotTimeMs),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (running) EssoOrange else EssoOnSurface,
                )
                val src =
                    when (machine.brewSource) {
                        BrewSource.Manual -> "manual"
                        BrewSource.Switch -> "brew switch"
                        BrewSource.Idle -> "idle"
                    }
                Text(text = src, style = MaterialTheme.typography.labelSmall, color = EssoOnSurfaceMuted)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                // Pre-infusion selector sits directly to the left of Start brew.
                PreinfusionDropdown(
                    seconds = preinfusionSec,
                    enabled = enabled,
                    onChange = onPreinfusionChanged,
                )
                Column(
                    modifier = Modifier.width(IntrinsicSize.Max),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val onStartSecretTap = rememberTripleTap(onSecretShowReport)
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { onBrewToggle(!running) },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                            colors =
                                if (running) {
                                    ButtonDefaults.buttonColors(containerColor = EssoError)
                                } else {
                                    ButtonDefaults.buttonColors(containerColor = EssoOrange)
                                },
                        ) {
                            Text(if (running) "Stop" else "Manual brew")
                        }
                        // Reveal the demo report via a transparent triple-tap
                        // target whenever the button carries no real function:
                        // offline (disabled) or in demo mode (brew is a no-op).
                        if (!enabled || demoMode) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onStartSecretTap,
                                    ),
                            )
                        }
                    }
                    // Secondary to Manual brew, so it runs at 80 % of the
                    // default button metrics. Width still tracks the stack, so
                    // the two buttons keep a shared edge.
                    OutlinedButton(
                        onClick = onFlush,
                        enabled = enabled && !running,
                        modifier = Modifier.fillMaxWidth().height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    ) {
                        Icon(
                            Icons.Filled.WaterDrop,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.padding(3.dp))
                        Text(
                            text = if (machine.flushing) "Flushing…" else "Flush 3 s",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            sectionLabel("Brew target (at the cup)")
            Text(
                text = "${draft.roundToInt()} °C",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Slider(
                value = draft,
                onValueChange = { draft = it },
                enabled = enabled,
                valueRange = 80f..110f,
                steps = 29,
                modifier = Modifier.weight(1f).height(COMPACT_SLIDER_HEIGHT),
            )
            FilledIconButton(
                onClick = { onApplySetpoint(draft) },
                enabled = enabled && changed,
                modifier = Modifier.size(36.dp).alpha(if (enabled && changed) 1f else 0.4f),
                colors =
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = EssoOrange,
                        contentColor = Color(0xFF1A0E03),
                        disabledContainerColor = EssoOrange,
                        disabledContentColor = Color(0xFF1A0E03),
                    ),
            ) {
                Icon(Icons.Filled.Check, contentDescription = "Apply brew target")
            }
        }
    }
}

// Compact pre-infusion picker (0–7 s) shown left of the Start brew button.
@Composable
private fun PreinfusionDropdown(seconds: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = seconds.roundToInt().coerceIn(0, 7)
    Column {
        sectionLabel("Pre-infusion")
        Spacer(Modifier.height(4.dp))
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text("$current s")
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for (s in 0..7) {
                    DropdownMenuItem(
                        text = { Text("$s s") },
                        onClick = {
                            onChange(s.toFloat())
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

// Material3 sliders reserve a 48 dp touch row. The shot card needs the vertical
// space more than the padding, so its slider runs shorter to leave room for
// the readouts card above.
private val COMPACT_SLIDER_HEIGHT = 28.dp

// Replaces the old thermoblock-offset card. Lets the operator pick a bundled
// espresso pressure/flow profile (sourced from gaggimate + gaggiuino) or
// "None". The dropdown sits on the left with the profile's temperature +
// description; the ProfileGraph on the right illustrates the target
// pressure/flow shape. Selecting writes the profile to the firmware, which
// executes it by duty-cycling the pump (see MainViewModel.applyBrewProfile).
@Composable
private fun ProfileCard(
    selected: BrewProfile?,
    profiles: List<BrewProfile>,
    enabled: Boolean,
    running: Boolean,
    elapsedSec: Float,
    onSelect: (BrewProfile?) -> Unit,
    onRunToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    FrostCard(modifier = modifier, verticalArrangement = Arrangement.SpaceBetween) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sectionLabel("Pressure profile")
            if (selected != null) {
                Text(
                    text = "${selected.temperatureC.roundToInt()} °C · ${selected.phases.size} phases",
                    style = MaterialTheme.typography.labelMedium,
                    color = EssoOrange,
                    fontWeight = FontWeight.Bold,
                )
            }
            // Profile shots are pulled from here; the shot card's button always
            // runs the plain pre-infusion pull instead.
            Button(
                onClick = { onRunToggle(!running) },
                enabled = enabled && (running || selected != null),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                colors = if (running) {
                    ButtonDefaults.buttonColors(containerColor = EssoError)
                } else {
                    ButtonDefaults.buttonColors(containerColor = EssoOrange)
                },
            ) {
                if (!running) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.padding(2.dp))
                }
                Text(if (running) "Stop" else "Run")
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(132.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(0.42f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProfileDropdown(
                    selected = selected,
                    profiles = profiles,
                    enabled = enabled,
                    onSelect = onSelect,
                )
                Text(
                    text = selected?.description
                        ?: "None — the shot runs on the pre-infusion setting above.",
                    style = MaterialTheme.typography.labelSmall,
                    color = EssoOnSurfaceMuted,
                )
            }
            Column(
                modifier = Modifier.weight(0.58f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ProfileGraph(
                    profile = selected,
                    modifier = Modifier.fillMaxWidth().height(104.dp),
                    elapsedSec = elapsedSec,
                )
                if (selected != null) ProfileGraphLegend()
            }
        }
    }
}

// "None" + every selectable profile as "name (source)", the operator's own
// drawn ones first (see the profiles module). Selecting null clears the profile
// (back to the plain shot behaviour).
@Composable
private fun ProfileDropdown(
    selected: BrewProfile?,
    profiles: List<BrewProfile>,
    enabled: Boolean,
    onSelect: (BrewProfile?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = selected?.displayName ?: "None",
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("None") },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            for (profile in profiles) {
                DropdownMenuItem(
                    text = { Text(profile.displayName) },
                    onClick = {
                        onSelect(profile)
                        expanded = false
                    },
                )
            }
        }
    }
}

// `modifier` sizes the card itself (the wide layout hands it a weight so the
// card flexes to fill the right column). `graphModifier` sizes the plot area —
// wide layout passes a weight so the graph soaks up the card's slack, while
// narrow layout falls back to a fixed height.
@Composable
private fun GraphCard(
    history: List<GraphSample>,
    modifier: Modifier = Modifier,
    graphModifier: Modifier = Modifier.fillMaxWidth().height(240.dp),
) {
    FrostCard(modifier = modifier) {
        sectionLabel("Brew graph")
        Spacer(Modifier.height(8.dp))
        BrewGraph(
            history = history,
            modifier = graphModifier,
            interactive = true,
        )
        Spacer(Modifier.height(10.dp))
        GraphLegend()
        Text(
            text = "Flow · weight arrive with Tier 3/4 hardware.",
            style = MaterialTheme.typography.labelSmall,
            color = EssoOnSurfaceMuted,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

// Returns a tap handler that fires `onTriple` on the third tap within a short
// window. Backs the two hidden offline developer shortcuts.
@Composable
private fun rememberTripleTap(onTriple: () -> Unit): () -> Unit {
    val windowMs = 600L
    var count by remember { mutableIntStateOf(0) }
    var last by remember { mutableLongStateOf(0L) }
    return {
        val now = System.currentTimeMillis()
        count = if (now - last <= windowMs) count + 1 else 1
        last = now
        if (count >= 3) {
            count = 0
            onTriple()
        }
    }
}

private fun formatTemp(celsius: Float): String =
    if (celsius.isNaN()) "— °C" else "${celsius.roundToInt()} °C"

private fun formatShot(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
