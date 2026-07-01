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

data class BleUiState(
    val phase: ConnectionPhase = ConnectionPhase.Idle,
    val deviceName: String? = null,
    val machineState: MachineState? = null,
    val pendingSetpointC: Float? = null,
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
                if (stateCharacteristic == null || setpointCharacteristic == null) {
                    fail("Missing Tier 1 characteristics")
                    return
                }
                gatt.setCharacteristicNotification(stateCharacteristic, true)
                val cccd = stateCharacteristic!!.getDescriptor(CCCD_UUID)
                cccd?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(it)
                }
                gatt.readCharacteristic(setpointCharacteristic)
                _uiState.value =
                    _uiState.value.copy(
                        phase = ConnectionPhase.Connected,
                        statusMessage = null,
                    )
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
                if (characteristic.uuid == Tier1Gatt.setpointUuid) {
                    val sp = ByteBufferWrap.getFloat(value) ?: return
                    mergeSetpoint(sp)
                }
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                if (status != BluetoothGatt.GATT_SUCCESS) return
                if (characteristic.uuid == Tier1Gatt.setpointUuid) {
                    val sp = ByteBufferWrap.getFloat(characteristic.value ?: return) ?: return
                    mergeSetpoint(sp)
                }
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
        val g = gatt ?: return
        _uiState.value = _uiState.value.copy(pendingSetpointC = celsius)
        val bytes = celsius.toSetpointBytes()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            characteristic.value = bytes
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(characteristic)
        }
        mergeSetpoint(celsius)
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

    private fun stopScan() {
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private fun cleanupGatt() {
        gatt?.close()
        gatt = null
        stateCharacteristic = null
        setpointCharacteristic = null
    }

    private fun publishMachineState(bytes: ByteArray) {
        val state = MachineState.fromBytes(bytes) ?: return
        _uiState.value =
            _uiState.value.copy(
                machineState = state,
                pendingSetpointC = null,
            )
    }

    private fun mergeSetpoint(celsius: Float) {
        val current = _uiState.value.machineState
        _uiState.value =
            _uiState.value.copy(
                machineState = current?.copy(setpointC = celsius) ?: MachineState(
                    tempC = Float.NaN,
                    setpointC = celsius,
                    heaterOn = false,
                    faultCode = 0,
                    tuning = false,
                    uptimeMs = 0,
                ),
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
    }
}

private object ByteBufferWrap {
    fun getFloat(bytes: ByteArray): Float? {
        if (bytes.size < 4) return null
        return java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).float
    }
}
