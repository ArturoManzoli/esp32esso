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
    val pendingThermoblockOffsetC: Float? = null,
    val preinfusionSec: Float? = null,
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
    private var thermoblockOffsetCharacteristic: BluetoothGattCharacteristic? = null
    private var settingsCharacteristic: BluetoothGattCharacteristic? = null
    private var brewControlCharacteristic: BluetoothGattCharacteristic? = null
    private var heaterEnableCharacteristic: BluetoothGattCharacteristic? = null
    private var preinfusionCharacteristic: BluetoothGattCharacteristic? = null
    private var flushCharacteristic: BluetoothGattCharacteristic? = null
    private var thermoblockSetpointCharacteristic: BluetoothGattCharacteristic? = null

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
                    // Force-invalidate the Android BLE stack's cached service
                    // list before discovering. The ESP32 gets its GATT table
                    // rewritten every time we flash new characteristics (Flush,
                    // Thermoblock Offset, etc.), but Android caches the pre-
                    // flash service list on the phone side and keeps handing us
                    // stale characteristic references — which is why writes
                    // silently target attributes that no longer exist. The
                    // NimBLE server on the ESP does not issue a Service Changed
                    // indication after reboot, so we have to prod the stack
                    // ourselves via the hidden BluetoothGatt.refresh() method.
                    forceRefreshGattCache(gatt)
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
                thermoblockOffsetCharacteristic = service.getCharacteristic(Tier1Gatt.thermoblockOffsetUuid)
                settingsCharacteristic = service.getCharacteristic(Tier1Gatt.settingsUuid)
                brewControlCharacteristic = service.getCharacteristic(Tier1Gatt.brewControlUuid)
                heaterEnableCharacteristic = service.getCharacteristic(Tier1Gatt.heaterEnableUuid)
                preinfusionCharacteristic = service.getCharacteristic(Tier1Gatt.preinfusionUuid)
                flushCharacteristic = service.getCharacteristic(Tier1Gatt.flushUuid)
                thermoblockSetpointCharacteristic =
                    service.getCharacteristic(Tier1Gatt.thermoblockSetpointUuid)
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

    fun writeThermoblockOffset(offsetC: Float) {
        val characteristic = thermoblockOffsetCharacteristic ?: return
        _uiState.value = _uiState.value.copy(pendingThermoblockOffsetC = offsetC)
        writeChar(characteristic, offsetC.toLeFloatBytes())
        val current = _uiState.value.machineState
        if (current != null) {
            // Writing the offset clears any absolute manual override firmware-side,
            // so optimistically drop the flag too.
            _uiState.value = _uiState.value.copy(
                machineState = current.copy(thermoblockOffsetC = offsetC, thermoblockManual = false),
            )
        }
    }

    // Pins the thermoblock at an absolute temperature, overriding group+offset.
    // Passing 0 clears the override and returns to the relational behaviour.
    fun writeThermoblockSetpoint(celsius: Float) {
        val characteristic = thermoblockSetpointCharacteristic ?: return
        writeChar(characteristic, celsius.toLeFloatBytes())
        val current = _uiState.value.machineState
        if (current != null) {
            val on = celsius > 0f
            _uiState.value = _uiState.value.copy(
                machineState = current.copy(
                    thermoblockManual = on,
                    thermoblockSetpointC = if (on) celsius else current.thermoblockSetpointC,
                ),
            )
        }
    }

    fun writeFlush(start: Boolean) {
        val characteristic = flushCharacteristic
        if (characteristic == null) {
            android.util.Log.w("EssoBLE", "writeFlush($start) skipped: characteristic null")
            return
        }
        android.util.Log.w("EssoBLE", "writeFlush($start) -> writing 1 byte")
        writeChar(characteristic, byteArrayOf(if (start) 1 else 0))
        val current = _uiState.value.machineState
        if (current != null) {
            // Optimistic reflect; the next MachineState notification confirms.
            _uiState.value = _uiState.value.copy(machineState = current.copy(flushing = start))
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

    fun writePreinfusion(seconds: Float) {
        val characteristic = preinfusionCharacteristic ?: return
        _uiState.value = _uiState.value.copy(preinfusionSec = seconds)
        writeChar(characteristic, seconds.toLeFloatBytes())
    }

    fun writeHeaterEnabled(enabled: Boolean) {
        val characteristic = heaterEnableCharacteristic ?: return
        writeChar(characteristic, byteArrayOf(if (enabled) 1 else 0))
        // Optimistically reflect the toggle so the switch tracks the tap before
        // the next MachineState notification confirms it.
        val current = _uiState.value.machineState
        if (current != null) {
            _uiState.value = _uiState.value.copy(machineState = current.copy(heaterEnabled = enabled))
        }
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
        val g = gatt
        if (g == null) {
            android.util.Log.w("EssoBLE", "writeChar skipped: gatt null")
            return
        }
        val ok =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.value = bytes
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                g.writeCharacteristic(characteristic)
            }
        android.util.Log.w("EssoBLE",
            "writeChar uuid=${characteristic.uuid} len=${bytes.size} ok=$ok")
    }

    private fun handleRead(gatt: BluetoothGatt, uuid: UUID, value: ByteArray) {
        when (uuid) {
            Tier1Gatt.setpointUuid -> {
                floatOf(value)?.let { mergeSetpoint(it) }
                thermoblockOffsetCharacteristic?.let { gatt.readCharacteristic(it) }
            }
            Tier1Gatt.thermoblockOffsetUuid -> {
                floatOf(value)?.let { offset ->
                    val current = _uiState.value.machineState
                    _uiState.value = _uiState.value.copy(
                        machineState = current?.copy(thermoblockOffsetC = offset),
                        pendingThermoblockOffsetC = null,
                    )
                }
                settingsCharacteristic?.let { gatt.readCharacteristic(it) }
            }
            Tier1Gatt.settingsUuid -> {
                MachineSettings.fromBytes(value)?.let {
                    _uiState.value = _uiState.value.copy(settings = it)
                }
                preinfusionCharacteristic?.let { gatt.readCharacteristic(it) }
            }
            Tier1Gatt.preinfusionUuid -> {
                floatOf(value)?.let {
                    _uiState.value = _uiState.value.copy(preinfusionSec = it)
                }
            }
        }
    }

    private fun stopScan() {
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    // Calls the hidden BluetoothGatt.refresh() to drop the on-device GATT
    // cache before discovery. Not part of the public SDK, but the same trick
    // every BLE app that talks to reflashable peripherals uses. Failures here
    // are non-fatal: on a device where reflection is blocked, we simply keep
    // the (possibly stale) cache and rely on the peripheral not having changed
    // its attribute table.
    private fun forceRefreshGattCache(gatt: BluetoothGatt) {
        try {
            val method = gatt.javaClass.getMethod("refresh")
            val ok = method.invoke(gatt) as? Boolean ?: false
            android.util.Log.w("EssoBLE", "gatt.refresh() -> $ok")
        } catch (t: Throwable) {
            android.util.Log.w("EssoBLE", "gatt.refresh() unavailable: ${t.message}")
        }
    }

    private fun cleanupGatt() {
        gatt?.close()
        gatt = null
        stateCharacteristic = null
        setpointCharacteristic = null
        thermoblockOffsetCharacteristic = null
        settingsCharacteristic = null
        brewControlCharacteristic = null
        heaterEnableCharacteristic = null
        preinfusionCharacteristic = null
        flushCharacteristic = null
        thermoblockSetpointCharacteristic = null
        history.clear()
    }

    private fun publishMachineState(bytes: ByteArray) {
        val state = MachineState.fromBytes(bytes) ?: return
        appendHistory(state)
        _uiState.value =
            _uiState.value.copy(
                machineState = state,
                pendingSetpointC = null,
                pendingThermoblockOffsetC = null,
                history = history.toList(),
            )
    }

    private fun appendHistory(state: MachineState) {
        history.addLast(
            GraphSample(
                uptimeMs = state.uptimeMs,
                thermoblockC = state.thermoblockTempC,
                groupC = state.groupTempC,
                // Plot the thermoblock setpoint (group + offset), not the group
                // setpoint on its own — that way the offset slider moves the
                // target line up/down in the graph, which is what the operator
                // wants to see when tuning the offset. The group setpoint stays
                // visible in the temperatures card's "→ NN °C" subtitle.
                setpointC = state.thermoblockSetpointC,
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

        // Max ATT MTU; the 52-byte MachineState needs more than the 23-byte
        // default. The peripheral negotiates down to what it supports.
        private const val PREFERRED_MTU = 517

        private fun floatOf(bytes: ByteArray): Float? {
            if (bytes.size < 4) return null
            return java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).float
        }
    }
}
