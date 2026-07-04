package com.esp32esso.tier1.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class ConnectionPhase {
    Idle,
    Scanning,
    Connecting,
    Connected,
    Failed,
}

// One time-series row for the brew graph. Tier 3/4 lanes (pressure/flow/
// weight) are carried as NaN until the hardware exists.
data class GraphSample(
    val uptimeMs: Long,
    val thermoblockC: Float,
    val groupC: Float,
    val setpointC: Float,
    val dutyPct: Float,
    val pressureBar: Float,
    val flowMlS: Float,
    val weightG: Float,
)

data class BleUiState(
    val phase: ConnectionPhase = ConnectionPhase.Idle,
    val deviceName: String? = null,
    val machineState: MachineState? = null,
    val settings: MachineSettings? = null,
    val pendingSetpointC: Float? = null,
    val pendingGain: Float? = null,
    val history: List<GraphSample> = emptyList(),
    val statusMessage: String? = null,
)

@SuppressLint("MissingPermission")
class Tier1BleClient(context: Context) {
    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private var gatt: BluetoothGatt? = null
    private var stateCharacteristic: BluetoothGattCharacteristic? = null
    private var setpointCharacteristic: BluetoothGattCharacteristic? = null
    private var gainCharacteristic: BluetoothGattCharacteristic? = null
    private var settingsCharacteristic: BluetoothGattCharacteristic? = null
    private var brewControlCharacteristic: BluetoothGattCharacteristic? = null

    private val history = ArrayDeque<GraphSample>()

    private val _uiState = MutableStateFlow(BleUiState())
    val uiState: StateFlow<BleUiState> = _uiState.asStateFlow()

    private val scanCallback =
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                stopScan()
                connect(device)
            }

            override fun onScanFailed(errorCode: Int) {
                _uiState.value =
                    _uiState.value.copy(
                        phase = ConnectionPhase.Failed,
                        statusMessage = "Scan failed ($errorCode)",
                    )
            }
        }

    private val gattCallback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    _uiState.value =
                        _uiState.value.copy(
                            phase = ConnectionPhase.Connecting,
                            deviceName = gatt.device.name ?: "Esp32esso",
                            statusMessage = "Discovering services…",
                        )
                    gatt.discoverServices()
                    return
                }
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    cleanupGatt()
                    _uiState.value =
                        BleUiState(
                            phase = ConnectionPhase.Idle,
                            statusMessage = if (status == 0) "Disconnected" else "Link lost ($status)",
                        )
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("Service discovery failed ($status)")
                    return
                }
                val service = gatt.getService(Tier1Gatt.serviceUuid)
                if (service == null) {
                    fail("Esp32esso service not found")
                    return
                }
                stateCharacteristic = service.getCharacteristic(Tier1Gatt.machineStateUuid)
                setpointCharacteristic = service.getCharacteristic(Tier1Gatt.setpointUuid)
                gainCharacteristic = service.getCharacteristic(Tier1Gatt.gainUuid)
                settingsCharacteristic = service.getCharacteristic(Tier1Gatt.settingsUuid)
                brewControlCharacteristic = service.getCharacteristic(Tier1Gatt.brewControlUuid)
                if (stateCharacteristic == null || setpointCharacteristic == null) {
                    fail("Missing Tier 2 characteristics")
                    return
                }
                _uiState.value =
                    _uiState.value.copy(
                        phase = ConnectionPhase.Connected,
                        statusMessage = null,
                    )
                // Serialize the GATT bring-up: the 48-byte MachineState does not
                // fit the default 23-byte ATT MTU, so grow the MTU first, then
                // enable notifications, then read the config chain — each step
                // waits for its own callback since Android runs one op at a time.
                if (!gatt.requestMtu(PREFERRED_MTU)) {
                    enableStateNotifications(gatt)
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                enableStateNotifications(gatt)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (descriptor.characteristic?.uuid == Tier1Gatt.machineStateUuid) {
                    // Notifications are live; now walk the read chain.
                    setpointCharacteristic?.let { gatt.readCharacteristic(it) }
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (characteristic.uuid == Tier1Gatt.machineStateUuid) {
                    publishMachineState(value)
                }
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                    characteristic.uuid == Tier1Gatt.machineStateUuid
                ) {
                    publishMachineState(characteristic.value ?: return)
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) return
                handleRead(gatt, characteristic.uuid, value)
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                if (status != BluetoothGatt.GATT_SUCCESS) return
                handleRead(gatt, characteristic.uuid, characteristic.value ?: return)
            }
        }

    fun start() {
        if (adapter == null || !adapter.isEnabled) {
            _uiState.value =
                BleUiState(
                    phase = ConnectionPhase.Failed,
                    statusMessage = "Bluetooth is off or unavailable",
                )
            return
        }
        disconnect()
        _uiState.value = BleUiState(phase = ConnectionPhase.Scanning, statusMessage = "Scanning…")
        val filter =
            ScanFilter.Builder()
                .setServiceUuid(android.os.ParcelUuid(Tier1Gatt.serviceUuid))
                .build()
        val settings =
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
        adapter.bluetoothLeScanner.startScan(listOf(filter), settings, scanCallback)
    }

    fun disconnect() {
        stopScan()
        cleanupGatt()
        _uiState.value = BleUiState(phase = ConnectionPhase.Idle)
    }

    fun writeSetpoint(celsius: Float) {
        val characteristic = setpointCharacteristic ?: return
        _uiState.value = _uiState.value.copy(pendingSetpointC = celsius)
        writeChar(characteristic, celsius.toLeFloatBytes())
        mergeSetpoint(celsius)
    }

    fun writeGain(gain: Float) {
        val characteristic = gainCharacteristic ?: return
        _uiState.value = _uiState.value.copy(pendingGain = gain)
        writeChar(characteristic, gain.toLeFloatBytes())
        val current = _uiState.value.machineState
        if (current != null) {
            _uiState.value = _uiState.value.copy(machineState = current.copy(gain = gain))
        }
    }

    fun writeSettings(settings: MachineSettings) {
        val characteristic = settingsCharacteristic ?: return
        _uiState.value = _uiState.value.copy(settings = settings)
        writeChar(characteristic, settings.toBytes())
    }

    fun writeBrew(active: Boolean) {
        val characteristic = brewControlCharacteristic ?: return
        writeChar(characteristic, byteArrayOf(if (active) 1 else 0))
    }

    private fun enableStateNotifications(gatt: BluetoothGatt) {
        val characteristic = stateCharacteristic ?: return
        gatt.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(CCCD_UUID) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }
        }
    }

    private fun connect(device: BluetoothDevice) {
        _uiState.value =
            _uiState.value.copy(
                phase = ConnectionPhase.Connecting,
                deviceName = device.name ?: "Esp32esso",
                statusMessage = "Connecting…",
            )
        gatt =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(appContext, false, gattCallback)
            }
    }

    private fun writeChar(characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
        val g = gatt ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            characteristic.value = bytes
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(characteristic)
        }
    }

    private fun handleRead(gatt: BluetoothGatt, uuid: UUID, value: ByteArray) {
        when (uuid) {
            Tier1Gatt.setpointUuid -> {
                floatOf(value)?.let { mergeSetpoint(it) }
                gainCharacteristic?.let { gatt.readCharacteristic(it) }
            }
            Tier1Gatt.gainUuid -> {
                settingsCharacteristic?.let { gatt.readCharacteristic(it) }
            }
            Tier1Gatt.settingsUuid -> {
                MachineSettings.fromBytes(value)?.let {
                    _uiState.value = _uiState.value.copy(settings = it)
                }
            }
        }
    }

    private fun stopScan() {
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private fun cleanupGatt() {
        gatt?.close()
        gatt = null
        stateCharacteristic = null
        setpointCharacteristic = null
        gainCharacteristic = null
        settingsCharacteristic = null
        brewControlCharacteristic = null
        history.clear()
    }

    private fun publishMachineState(bytes: ByteArray) {
        val state = MachineState.fromBytes(bytes) ?: return
        appendHistory(state)
        _uiState.value =
            _uiState.value.copy(
                machineState = state,
                pendingSetpointC = null,
                pendingGain = null,
                history = history.toList(),
            )
    }

    private fun appendHistory(state: MachineState) {
        history.addLast(
            GraphSample(
                uptimeMs = state.uptimeMs,
                thermoblockC = state.thermoblockTempC,
                groupC = state.groupTempC,
                setpointC = state.groupSetpointC,
                dutyPct = state.heaterDutyPct.toFloat(),
                pressureBar = state.pressureBar,
                flowMlS = state.flowMlS,
                weightG = state.weightG,
            ),
        )
        while (history.size > MAX_HISTORY) {
            history.removeFirst()
        }
    }

    private fun mergeSetpoint(celsius: Float) {
        val current = _uiState.value.machineState
        _uiState.value =
            _uiState.value.copy(
                machineState = current?.copy(groupSetpointC = celsius),
                pendingSetpointC = null,
            )
    }

    private fun fail(message: String) {
        cleanupGatt()
        _uiState.value =
            BleUiState(
                phase = ConnectionPhase.Failed,
                statusMessage = message,
            )
    }

    companion object {
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val MAX_HISTORY = 900

        // Max ATT MTU; the 48-byte MachineState needs more than the 23-byte
        // default. The peripheral negotiates down to what it supports.
        private const val PREFERRED_MTU = 517

        private fun floatOf(bytes: ByteArray): Float? {
            if (bytes.size < 4) return null
            return java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).float
        }
    }
}
