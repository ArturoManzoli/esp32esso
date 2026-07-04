package com.esp32esso.tier1

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.esp32esso.tier1.ble.BleUiState
import com.esp32esso.tier1.ble.MachineSettings
import com.esp32esso.tier1.ble.Tier1BleClient
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val client = Tier1BleClient(application)

    val uiState: StateFlow<BleUiState> = client.uiState

    fun connect() {
        client.start()
    }

    fun disconnect() {
        client.disconnect()
    }

    fun applySetpoint(celsius: Float) {
        client.writeSetpoint(celsius)
    }

    fun applyGain(gain: Float) {
        client.writeGain(gain)
    }

    fun applySettings(settings: MachineSettings) {
        client.writeSettings(settings)
    }

    fun setBrewing(active: Boolean) {
        client.writeBrew(active)
    }
}
