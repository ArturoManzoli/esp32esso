package com.esp32esso.tier1

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.esp32esso.tier1.ui.Esp32essoTheme
import com.esp32esso.tier1.ui.MainScreen
import com.esp32esso.tier1.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) {
                viewModel.connect()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Esp32essoTheme {
                val uiState by viewModel.uiState.collectAsState()
                var showSettings by remember { mutableStateOf(false) }

                if (showSettings) {
                    SettingsScreen(
                        settings = uiState.settings,
                        onApply = viewModel::applySettings,
                        onBack = { showSettings = false },
                    )
                } else {
                    MainScreen(
                        uiState = uiState,
                        onConnect = { requestPermissionsAndConnect() },
                        onDisconnect = viewModel::disconnect,
                        onSetpointChanged = viewModel::applySetpoint,
                        onGainChanged = viewModel::applyGain,
                        onBrewToggle = viewModel::setBrewing,
                        onOpenSettings = { showSettings = true },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        viewModel.disconnect()
        super.onDestroy()
    }

    private fun requestPermissionsAndConnect() {
        val needed =
            buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
        if (needed.isEmpty()) {
            viewModel.connect()
            return
        }
        permissionLauncher.launch(needed.toTypedArray())
    }
}
