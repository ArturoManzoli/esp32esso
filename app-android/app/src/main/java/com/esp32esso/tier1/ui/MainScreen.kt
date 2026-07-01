package com.esp32esso.tier1.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.esp32esso.tier1.ble.ConnectionPhase
import com.esp32esso.tier1.ble.MachineState
import kotlin.math.roundToInt

@Composable
fun MainScreen(
    uiState: BleUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSetpointChanged: (Float) -> Unit,
) {
    val machine = uiState.machineState
    val sliderSetpoint =
        uiState.pendingSetpointC ?: machine?.setpointC ?: 93f
    var draftSetpoint by remember(sliderSetpoint) { mutableFloatStateOf(sliderSetpoint) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Esp32esso Tier 1",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        ConnectionBar(
            uiState = uiState,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
        )

        if (machine != null && uiState.phase == ConnectionPhase.Connected) {
            LiveStateCard(machine)
            SetpointCard(
                draftSetpoint = draftSetpoint,
                onDraftChange = { draftSetpoint = it },
                onApply = { onSetpointChanged(draftSetpoint) },
                minC = 80f,
                maxC = 150f,
            )
        } else {
            uiState.statusMessage?.let { message ->
                Text(text = message, color = MaterialTheme.colorScheme.error)
            }
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
                ConnectionPhase.Connected -> "Connected to ${uiState.deviceName ?: "Esp32esso"}"
                ConnectionPhase.Failed -> uiState.statusMessage ?: "Connection failed"
            }
        Text(text = label, modifier = Modifier.weight(1f))
        if (uiState.phase == ConnectionPhase.Connected) {
            OutlinedButton(onClick = onDisconnect) {
                Text("Disconnect")
            }
        } else if (uiState.phase != ConnectionPhase.Scanning &&
            uiState.phase != ConnectionPhase.Connecting
        ) {
            Button(onClick = onConnect) {
                Text("Connect")
            }
        }
    }
}

@Composable
private fun LiveStateCard(machine: MachineState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = formatTemp(machine.tempC),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(text = "Setpoint ${formatTemp(machine.setpointC)}")
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = if (machine.heaterOn) "Heater ON" else "Heater OFF",
                    fontWeight = FontWeight.Medium,
                    color =
                        if (machine.heaterOn) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                )
                if (machine.tuning) {
                    Text(text = "Tuning bench active")
                }
            }
            machine.faultLabel?.let { fault ->
                Text(text = fault, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SetpointCard(
    draftSetpoint: Float,
    onDraftChange: (Float) -> Unit,
    onApply: () -> Unit,
    minC: Float,
    maxC: Float,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Brew target ${draftSetpoint.roundToInt()} °C",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = draftSetpoint,
                onValueChange = onDraftChange,
                valueRange = minC..maxC,
                steps = ((maxC - minC).roundToInt() - 1).coerceAtLeast(0),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) {
                Text("Apply setpoint")
            }
        }
    }
}

private fun formatTemp(celsius: Float): String =
    if (celsius.isNaN()) "— °C" else "${celsius.roundToInt()} °C"
