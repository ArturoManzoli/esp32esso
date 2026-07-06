package com.esp32esso.tier1.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.esp32esso.tier1.ble.MachineSettings

@Composable
fun SettingsScreen(
    settings: MachineSettings?,
    onApply: (MachineSettings) -> Unit,
    onBack: () -> Unit,
) {
    // Wrap the scroll column in a Box so we can centre-cap it on tablets;
    // 720 dp reads comfortably without stretching the fields to full width.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 720.dp)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = EssoOrange)
            }
            Text(
                text = "Machine settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        if (settings == null) {
            Text("Reading settings…", color = EssoOnSurfaceMuted)
            return@Column
        }

        var kp by remember(settings) { mutableStateOf(settings.kp.toString()) }
        var ki by remember(settings) { mutableStateOf(settings.ki.toString()) }
        var kd by remember(settings) { mutableStateOf(settings.kd.toString()) }
        var steam by remember(settings) { mutableStateOf(settings.steamSetpointC.toString()) }
        var heaterTimeout by remember(settings) { mutableStateOf(settings.heaterTimeoutMin.toString()) }

        FrostCard(accent = true) {
            sectionLabel("Inner PID (thermoblock)")
            Spacer(Modifier.height(10.dp))
            NumberField("Kp — proportional", kp) { kp = it }
            Spacer(Modifier.height(10.dp))
            NumberField("Ki — integral", ki) { ki = it }
            Spacer(Modifier.height(10.dp))
            NumberField("Kd — derivative", kd) { kd = it }
        }

        FrostCard {
            sectionLabel("Steam mode")
            Spacer(Modifier.height(10.dp))
            NumberField("Steam target °C", steam) { steam = it }
            Text(
                text = "Reserved for the steam mode; stored on the controller.",
                style = MaterialTheme.typography.labelSmall,
                color = EssoOnSurfaceMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        FrostCard {
            sectionLabel("Safety")
            Spacer(Modifier.height(10.dp))
            NumberField("Heater auto-off (minutes)", heaterTimeout) { heaterTimeout = it }
            Text(
                text = "Turns the heater off after this long with no activity " +
                    "(setpoint/gain change, brew, or heater toggle). 0 disables it.",
                style = MaterialTheme.typography.labelSmall,
                color = EssoOnSurfaceMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Button(
            onClick = {
                onApply(
                    MachineSettings(
                        kp = kp.toFloatOrNull() ?: settings.kp,
                        ki = ki.toFloatOrNull() ?: settings.ki,
                        kd = kd.toFloatOrNull() ?: settings.kd,
                        steamSetpointC = steam.toFloatOrNull() ?: settings.steamSetpointC,
                        heaterTimeoutMin = heaterTimeout.toFloatOrNull() ?: settings.heaterTimeoutMin,
                    ),
                )
                onBack()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save to controller")
        }
      }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
}
