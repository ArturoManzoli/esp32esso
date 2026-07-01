package com.esp32esso.tier1

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.esp32esso.tier1.ui.MainScreen

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
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    val uiState by viewModel.uiState.collectAsState()
                    MainScreen(
                        uiState = uiState,
                        onConnect = { requestPermissionsAndConnect() },
                        onDisconnect = viewModel::disconnect,
                        onSetpointChanged = viewModel::applySetpoint,
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
