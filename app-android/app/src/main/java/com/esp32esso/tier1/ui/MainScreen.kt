package com.esp32esso.tier1.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.esp32esso.tier1.ble.BleUiState
import com.esp32esso.tier1.ble.BrewSource
import com.esp32esso.tier1.ble.ConnectionPhase
import com.esp32esso.tier1.ble.GraphSample
import com.esp32esso.tier1.ble.MachineState
import kotlinx.coroutines.launch
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
    onOffsetChanged: (Float) -> Unit,
    onThermoblockSetpointChanged: (Float) -> Unit,
    onBrewToggle: (Boolean) -> Unit,
    onHeaterToggle: (Boolean) -> Unit,
    onPreinfusionChanged: (Float) -> Unit,
    onFlush: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenRanking: () -> Unit,
    onSecretDemoToggle: () -> Unit,
    onSecretShowReport: () -> Unit,
    demoMode: Boolean,
    displayShotTimeMs: Long,
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
            onOffsetChanged = onOffsetChanged,
            onThermoblockSetpointChanged = onThermoblockSetpointChanged,
            onBrewToggle = onBrewToggle,
            onHeaterToggle = onHeaterToggle,
            onPreinfusionChanged = onPreinfusionChanged,
            onFlush = onFlush,
            onOpenSettings = onOpenSettings,
            onOpenLibrary = onOpenLibrary,
            onOpenRanking = onOpenRanking,
            onSecretDemoToggle = onSecretDemoToggle,
            onSecretShowReport = onSecretShowReport,
            demoMode = demoMode,
            displayShotTimeMs = displayShotTimeMs,
        )
    } else {
        MainScreenNarrow(
            uiState = uiState,
            connected = connected,
            machine = machine,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            onSetpointChanged = onSetpointChanged,
            onOffsetChanged = onOffsetChanged,
            onThermoblockSetpointChanged = onThermoblockSetpointChanged,
            onBrewToggle = onBrewToggle,
            onHeaterToggle = onHeaterToggle,
            onPreinfusionChanged = onPreinfusionChanged,
            onFlush = onFlush,
            onOpenSettings = onOpenSettings,
            onOpenLibrary = onOpenLibrary,
            onOpenRanking = onOpenRanking,
            onSecretDemoToggle = onSecretDemoToggle,
            onSecretShowReport = onSecretShowReport,
            demoMode = demoMode,
            displayShotTimeMs = displayShotTimeMs,
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
    onOffsetChanged: (Float) -> Unit,
    onThermoblockSetpointChanged: (Float) -> Unit,
    onBrewToggle: (Boolean) -> Unit,
    onHeaterToggle: (Boolean) -> Unit,
    onPreinfusionChanged: (Float) -> Unit,
    onFlush: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenRanking: () -> Unit,
    onSecretDemoToggle: () -> Unit,
    onSecretShowReport: () -> Unit,
    demoMode: Boolean,
    displayShotTimeMs: Long,
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
        )
        ShotCard(
            machine = machine,
            enabled = connected,
            setpoint = uiState.pendingSetpointC ?: machine.groupSetpointC,
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
        OffsetCard(
            offsetC = uiState.pendingThermoblockOffsetC ?: machine.thermoblockOffsetC,
            manualActive = machine.thermoblockManual,
            enabled = connected,
            onOffsetChanged = onOffsetChanged,
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
    onOffsetChanged: (Float) -> Unit,
    onThermoblockSetpointChanged: (Float) -> Unit,
    onBrewToggle: (Boolean) -> Unit,
    onHeaterToggle: (Boolean) -> Unit,
    onPreinfusionChanged: (Float) -> Unit,
    onFlush: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenRanking: () -> Unit,
    onSecretDemoToggle: () -> Unit,
    onSecretShowReport: () -> Unit,
    demoMode: Boolean,
    displayShotTimeMs: Long,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
            onSecretDemoToggle = onSecretDemoToggle,
        )

        // Row: controls on the left, graph + offset on the right. Height is
        // pinned to the intrinsic height of the taller (left) column, and the
        // right column fills it — the graph card then flexes to soak up the
        // slack so both columns share a top and bottom edge (a flush
        // rectangle) regardless of the offset card's fixed height.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(0.46f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TemperaturesCard(
                    machine = machine,
                    enabled = connected,
                    onHeaterToggle = onHeaterToggle,
                    onSetGroupTarget = onSetpointChanged,
                    onSetThermoblockTarget = onThermoblockSetpointChanged,
                )
                ShotCard(
                    machine = machine,
                    enabled = connected,
                    setpoint = uiState.pendingSetpointC ?: machine.groupSetpointC,
                    preinfusionSec = uiState.preinfusionSec ?: 2f,
                    displayShotTimeMs = displayShotTimeMs,
                    onBrewToggle = onBrewToggle,
                    onApplySetpoint = onSetpointChanged,
                    onPreinfusionChanged = onPreinfusionChanged,
                    onFlush = onFlush,
                    onSecretShowReport = onSecretShowReport,
                    demoMode = demoMode,
                )
            }
            Column(
                modifier = Modifier
                    .weight(0.54f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GraphCard(
                    history = uiState.history,
                    modifier = Modifier.weight(1f),
                    graphModifier = Modifier.fillMaxWidth().weight(1f),
                )
                OffsetCard(
                    offsetC = uiState.pendingThermoblockOffsetC ?: machine.thermoblockOffsetC,
                    manualActive = machine.thermoblockManual,
                    enabled = connected,
                    onOffsetChanged = onOffsetChanged,
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
    onSecretDemoToggle: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val onStatusTap = rememberTripleTap(onSecretDemoToggle)
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
                text = "${uiState.deviceName ?: "Esp32esso"} · $statusWord",
                color = if (connected) EssoOnSurface else EssoOnSurfaceMuted,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
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
            IconButton(onClick = onOpenRanking) {
                Icon(Icons.Filled.EmojiEvents, contentDescription = "Shot ranking", tint = EssoOrange)
            }
            IconButton(onClick = onOpenLibrary) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Coffee library", tint = EssoOrange)
            }
            Spacer(Modifier.width(30.dp))
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

@Composable
private fun TemperaturesCard(
    machine: MachineState,
    enabled: Boolean,
    onHeaterToggle: (Boolean) -> Unit,
    onSetGroupTarget: (Float) -> Unit,
    onSetThermoblockTarget: (Float) -> Unit,
) {
    // Tighter than the default 20 dp box: 5 dp off the top, 7 dp off the
    // bottom, shrinking the card's dead space around the readouts/heater row.
    FrostCard(
        accent = true,
        contentPadding = PaddingValues(start = 20.dp, top = 15.dp, end = 20.dp, bottom = 13.dp),
    ) {
        // Group / Pressure (centre column) / Thermoblock in a single row. The
        // pressure column collapses to fixed non-numeric text before the first
        // sample so the row does not jitter when the transducer starts pushing.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TempReadout(
                label = "Group / portafilter",
                value = machine.groupTempC,
                target = machine.groupSetpointC,
                emphasised = true,
                degraded = !machine.groupSensorOk,
                onPick = if (enabled) onSetGroupTarget else null,
                pickInitial = machine.groupSetpointC,
            )
            PressureReadout(machine.pressureBar)
            TempReadout(
                label = "Thermoblock",
                value = machine.thermoblockTempC,
                target = machine.thermoblockSetpointC,
                emphasised = false,
                degraded = false,
                onPick = if (enabled) onSetThermoblockTarget else null,
                pickInitial = machine.thermoblockSetpointC,
            )
        }
        // Pulled tighter (was 14 dp) so the card is shorter and the four
        // cards clear the panel without scrolling.
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
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
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                    colors =
                        SwitchDefaults.colors(
                            checkedThumbColor = EssoOrange,
                            checkedTrackColor = EssoOrange.copy(alpha = 0.4f),
                        ),
                )
            }
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
    emphasised: Boolean,
    degraded: Boolean,
    onPick: ((Float) -> Unit)? = null,
    pickInitial: Float = target,
) {
    var picking by remember { mutableStateOf(false) }
    Box {
        Column(
            modifier = if (onPick != null) {
                Modifier.clip(RoundedCornerShape(10.dp)).clickable { picking = true }
            } else {
                Modifier
            },
        ) {
            sectionLabel(label)
            Spacer(Modifier.height(2.dp))
            Text(
                text = formatTemp(value),
                style = if (emphasised) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = if (degraded) EssoOnSurfaceMuted else EssoOnSurface,
            )
            Text(
                text = "→ ${formatTemp(target)}",
                style = MaterialTheme.typography.labelMedium,
                color = if (onPick != null) EssoOrange else EssoOnSurfaceMuted,
            )
        }
        if (picking && onPick != null) {
            TempWheelPopup(
                title = label,
                initial = pickInitial,
                onDismiss = { picking = false },
                onConfirm = {
                    picking = false
                    onPick(it)
                },
            )
        }
    }
}

// Direct-set wheel: a spinning column of 80.0…100.0 °C values (0.5 steps) shown
// in a popover anchored under the tapped readout. The centred value is the
// selection; "Set" commits it. This bypasses the brew-target slider / offset
// slider so the operator can dial a sensor's target straight from its number.
private val TEMP_WHEEL_VALUES: List<Float> =
    generateSequence(80f) { it + 0.5f }.takeWhile { it <= 100.001f }.toList()

@Composable
private fun TempWheelPopup(
    title: String,
    initial: Float,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
) {
    val itemHeight = 44.dp
    val visibleCount = 5
    val values = TEMP_WHEEL_VALUES
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
                                text = "%.1f °C".format(v),
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

@Composable
private fun PressureReadout(pressureBar: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        sectionLabel("Pressure")
        Spacer(Modifier.height(2.dp))
        val label = if (pressureBar.isNaN()) "—" else "%.1f bar".format(pressureBar)
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = EssoOnSurface,
            textAlign = TextAlign.Center,
        )
    }
}

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
) {
    // Offline the setpoint arrives as NaN (no machine to report one); pin the
    // slider to a neutral mid-range value so roundToInt / the 80–110 range stay
    // valid. The control is disabled in that state anyway.
    val safeSetpoint = if (setpoint.isNaN()) 93f else setpoint
    var draft by remember(safeSetpoint) { mutableFloatStateOf(safeSetpoint) }
    val changed = abs(draft - safeSetpoint) >= 0.5f
    val running = machine.brewing
    // 30 % tighter vertical box (was 20 dp) so the card hugs its content.
    FrostCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
        // Top row: shot timer on the left, the shot action stack on the right
        // (Start shot on top, Flush directly beneath it). The stack is sized to
        // its widest child so Start shot and Flush share one width.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column {
                sectionLabel("Shot timer")
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
                        Text(if (running) "Stop" else "Start shot")
                    }
                    // Reveal the demo report via a transparent triple-tap target
                    // whenever the button carries no real function: offline (it
                    // is disabled) or in demo mode (brew control is a no-op).
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
                OutlinedButton(
                    onClick = onFlush,
                    enabled = enabled && !running,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Filled.WaterDrop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.padding(3.dp))
                    Text(if (machine.flushing) "Flushing…" else "Flush 3 s")
                }
            }
        }

        PreinfusionChips(seconds = preinfusionSec, enabled = enabled, onChange = onPreinfusionChanged)

        Spacer(Modifier.height(8.dp))
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
                modifier = Modifier.weight(1f),
            )
            FilledIconButton(
                onClick = { onApplySetpoint(draft) },
                enabled = enabled && changed,
                modifier = Modifier.size(40.dp).alpha(if (enabled && changed) 1f else 0.4f),
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

@Composable
private fun PreinfusionChips(seconds: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    val selected = seconds.roundToInt().takeIf { it in 0..7 }
    Column {
        sectionLabel("Pre-infusion (s)")
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (s in 0..7) {
                FilterChip(
                    selected = selected == s,
                    onClick = { onChange(s.toFloat()) },
                    enabled = enabled,
                    label = { Text("$s") },
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = EssoOrange.copy(alpha = 0.25f),
                        selectedLabelColor = EssoOrange,
                    ),
                )
            }
        }
    }
}

@Composable
private fun OffsetCard(
    offsetC: Float,
    manualActive: Boolean,
    enabled: Boolean,
    onOffsetChanged: (Float) -> Unit,
) {
    var draft by remember(offsetC) { mutableFloatStateOf(offsetC) }
    val context = LocalContext.current
    val presetStore = remember { OffsetPresetStore(context) }
    val presets = remember { mutableStateOf(presetStore.load()) }
    var selectedPreset by remember { mutableIntStateOf(-1) }
    // Bumped each time a preset is saved to trigger the pulse animation on
    // the corresponding PresetChip. The chip watches this + the slot index so
    // it only pulses when *its* slot was saved.
    var savedTick by remember { mutableIntStateOf(0) }
    var lastSavedSlot by remember { mutableIntStateOf(-1) }

    FrostCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            sectionLabel("Thermoblock offset")
            Text(
                text = formatOffset(draft),
                style = MaterialTheme.typography.titleMedium,
                color = EssoOrange,
                fontWeight = FontWeight.Bold,
            )
        }
        Slider(
            value = draft,
            onValueChange = {
                val q = (it * 2).roundToInt() / 2f  // 0.5 °C step
                if (q != draft) selectedPreset = -1
                draft = q
            },
            onValueChangeFinished = { onOffsetChanged(draft) },
            enabled = enabled,
            valueRange = -20f..20f,
            steps = 79,
        )
        Spacer(Modifier.height(10.dp))
        // Long-press any chip to overwrite it with the current slider value.
        // Tap = select and apply. The save button was dropped in favour of the
        // long-press because it clutters the row and adds a required extra tap
        // (select → save) for something the operator only does occasionally.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (i in 0 until OffsetPresetStore.SLOTS) {
                PresetChip(
                    label = "${i + 1}",
                    selected = selectedPreset == i,
                    enabled = enabled,
                    pulseKey = if (lastSavedSlot == i) savedTick else 0,
                    modifier = Modifier.weight(1f),
                    onTap = {
                        selectedPreset = i
                        val v = presets.value[i]
                        draft = v
                        onOffsetChanged(v)
                    },
                    onLongPress = {
                        val next = presets.value.copyOf()
                        next[i] = draft
                        presets.value = next
                        presetStore.save(next)
                        selectedPreset = i
                        lastSavedSlot = i
                        savedTick += 1  // re-triggers the chip's pulse
                    },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (manualActive) {
                "A manual thermoblock temperature is active — move this slider to " +
                    "return to group setpoint + offset."
            } else {
                "Signed °C added to the thermoblock setpoint. " +
                    "Tap a preset to apply · long-press to save the current value."
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (manualActive) EssoOrange else EssoOnSurfaceMuted,
        )
    }

    LaunchedEffect(offsetC, presets.value) {
        if (selectedPreset < 0) {
            val idx = presets.value.indexOfFirst { abs(it - offsetC) < 0.05f }
            if (idx >= 0) selectedPreset = idx
        }
    }
}

// Custom chip built on Box so we can combine tap + long-press cleanly (the
// M3 FilterChip's own onClick swallows the long-press we need). The pulse
// animation scales the chip up briefly whenever `pulseKey` changes to a
// non-zero value — the parent bumps that counter every time this slot is
// saved to a preset, and the chip runs a short scale-up/scale-down as an
// explicit "saved" acknowledgement.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    pulseKey: Int,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pulseKey) {
        if (pulseKey > 0) {
            scope.launch {
                // Snappy pulse: up in ~120 ms, settle back over 260 ms. Fast
                // enough to feel like a tactile confirmation, slow enough to
                // read as intentional feedback rather than a flicker.
                scale.animateTo(1.18f, tween(durationMillis = 120))
                scale.animateTo(1.0f, tween(durationMillis = 260))
            }
        }
    }
    val bg = if (selected) EssoOrange.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.05f)
    val borderColor = if (selected) EssoOrange.copy(alpha = 0.60f) else Color.White.copy(alpha = 0.14f)
    val labelColor = if (selected) EssoOrange else EssoOnSurface
    Box(
        modifier = modifier
            // Match the M3 FilterChip height used by the pre-infusion row so
            // the two chip rows read as the same control size.
            .height(32.dp)
            .graphicsLayer(scaleX = scale.value, scaleY = scale.value)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .combinedClickable(enabled = enabled, onClick = onTap, onLongClick = onLongPress),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = labelColor,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private class OffsetPresetStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): FloatArray = FloatArray(SLOTS) { prefs.getFloat("$KEY_PREFIX$it", 0f) }

    fun save(values: FloatArray) {
        prefs.edit().apply {
            for (i in 0 until SLOTS) putFloat("$KEY_PREFIX$i", values.getOrElse(i) { 0f })
        }.apply()
    }

    companion object {
        const val SLOTS = 5
        private const val PREFS = "esso_offset_presets"
        private const val KEY_PREFIX = "slot_"
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

private fun formatOffset(offsetC: Float): String {
    val sign = if (offsetC >= 0f) "+" else ""
    return "$sign%.1f °C".format(offsetC)
}

private fun formatShot(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
