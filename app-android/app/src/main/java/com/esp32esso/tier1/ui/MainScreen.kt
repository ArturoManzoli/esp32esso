package com.esp32esso.tier1.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.esp32esso.tier1.ble.BleUiState
import com.esp32esso.tier1.ble.BrewSource
import com.esp32esso.tier1.ble.ConnectionPhase
import com.esp32esso.tier1.ble.GraphSample
import com.esp32esso.tier1.ble.MachineState
import kotlin.math.roundToInt

@Composable
fun MainScreen(
    uiState: BleUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSetpointChanged: (Float) -> Unit,
    onGainChanged: (Float) -> Unit,
    onBrewToggle: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val machine = uiState.machineState
    val connected = uiState.phase == ConnectionPhase.Connected

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Esp32esso",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Tier 2 · dual-sensor cascade",
                    style = MaterialTheme.typography.labelMedium,
                    color = EssoOnSurfaceMuted,
                )
            }
            if (connected) {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = EssoOrange)
                }
            }
        }

        ConnectionBar(uiState, onConnect, onDisconnect)

        if (connected && machine != null) {
            TemperaturesCard(machine)
            ShotCard(machine, onBrewToggle)
            GainCard(
                gain = uiState.pendingGain ?: machine.gain,
                onGainChanged = onGainChanged,
            )
            SetpointCard(
                current = uiState.pendingSetpointC ?: machine.groupSetpointC,
                onApply = onSetpointChanged,
            )
            GraphCard(uiState.history)
        } else {
            uiState.statusMessage?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ConnectionBar(
    uiState: BleUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val label =
            when (uiState.phase) {
                ConnectionPhase.Idle -> "Not connected"
                ConnectionPhase.Scanning -> "Scanning…"
                ConnectionPhase.Connecting -> "Connecting…"
                ConnectionPhase.Connected -> "Connected · ${uiState.deviceName ?: "Esp32esso"}"
                ConnectionPhase.Failed -> uiState.statusMessage ?: "Connection failed"
            }
        Text(text = label, modifier = Modifier.weight(1f), color = EssoOnSurfaceMuted)
        if (uiState.phase == ConnectionPhase.Connected) {
            OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
        } else if (uiState.phase != ConnectionPhase.Scanning &&
            uiState.phase != ConnectionPhase.Connecting
        ) {
            Button(onClick = onConnect) { Text("Connect") }
        }
    }
}

@Composable
private fun TemperaturesCard(machine: MachineState) {
    FrostCard(accent = true) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TempReadout(
                label = "Group / portafilter",
                value = machine.groupTempC,
                target = machine.groupSetpointC,
                emphasised = true,
                degraded = !machine.groupSensorOk,
            )
            TempReadout(
                label = "Thermoblock",
                value = machine.thermoblockTempC,
                target = machine.thermoblockSetpointC,
                emphasised = false,
                degraded = false,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                text = if (machine.heaterOn) "Heater ON · ${machine.heaterDutyPct}%" else "Heater OFF · ${machine.heaterDutyPct}%",
                fontWeight = FontWeight.Medium,
                color = if (machine.heaterOn) EssoOrange else EssoOnSurfaceMuted,
            )
            if (machine.tuning) Text("Tuning bench", color = EssoOnSurfaceMuted)
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

@Composable
private fun TempReadout(
    label: String,
    value: Float,
    target: Float,
    emphasised: Boolean,
    degraded: Boolean,
) {
    Column {
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
            color = EssoOnSurfaceMuted,
        )
    }
}

@Composable
private fun ShotCard(machine: MachineState, onBrewToggle: (Boolean) -> Unit) {
    FrostCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                sectionLabel("Shot timer")
                Text(
                    text = formatShot(machine.shotTimeMs),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (machine.brewing) EssoOrange else EssoOnSurface,
                )
                val src =
                    when (machine.brewSource) {
                        BrewSource.Manual -> "manual"
                        BrewSource.Switch -> "brew switch"
                        BrewSource.Idle -> "idle"
                    }
                Text(text = src, style = MaterialTheme.typography.labelSmall, color = EssoOnSurfaceMuted)
            }
            val running = machine.brewing
            Button(
                onClick = { onBrewToggle(!running) },
                colors =
                    if (running) {
                        ButtonDefaults.buttonColors(containerColor = EssoError)
                    } else {
                        ButtonDefaults.buttonColors(containerColor = EssoOrange)
                    },
            ) {
                Text(if (running) "Stop" else "Start shot")
            }
        }
    }
}

@Composable
private fun GainCard(gain: Float, onGainChanged: (Float) -> Unit) {
    var draft by remember(gain) { mutableFloatStateOf(gain) }
    FrostCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            sectionLabel("Cascade gain")
            Text(
                text = formatGain(draft),
                style = MaterialTheme.typography.titleMedium,
                color = EssoOrange,
                fontWeight = FontWeight.Bold,
            )
        }
        Slider(
            value = draft,
            onValueChange = { draft = (it * 10).roundToInt() / 10f },
            onValueChangeFinished = { onGainChanged(draft) },
            valueRange = 0f..10f,
            steps = 99,
        )
        Text(
            text = "Higher gain drives the thermoblock hotter to hit the cup target.",
            style = MaterialTheme.typography.labelSmall,
            color = EssoOnSurfaceMuted,
        )
    }
}

@Composable
private fun SetpointCard(current: Float, onApply: (Float) -> Unit) {
    var draft by remember(current) { mutableFloatStateOf(current) }
    FrostCard {
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
        Slider(
            value = draft,
            onValueChange = { draft = it },
            valueRange = 80f..110f,
            steps = 29,
        )
        Button(onClick = { onApply(draft) }, modifier = Modifier.fillMaxWidth()) {
            Text("Apply brew target")
        }
    }
}

@Composable
private fun GraphCard(history: List<GraphSample>) {
    FrostCard {
        sectionLabel("Brew graph")
        Spacer(Modifier.height(8.dp))
        BrewGraph(history = history, modifier = Modifier.fillMaxWidth().height(220.dp))
        Spacer(Modifier.height(10.dp))
        GraphLegend()
        Text(
            text = "Pressure · flow · weight arrive with Tier 3/4 hardware.",
            style = MaterialTheme.typography.labelSmall,
            color = EssoOnSurfaceMuted,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun formatTemp(celsius: Float): String =
    if (celsius.isNaN()) "— °C" else "${celsius.roundToInt()} °C"

private fun formatGain(gain: Float): String = "%.1f".format(gain)

private fun formatShot(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
