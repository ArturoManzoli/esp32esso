package com.esp32esso.tier1.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

// Mirrors protocol/ble/tier2.md. Names keep the Tier1* prefix for continuity
// with the module layout; the contract itself is the Tier 2 revision.
object Tier1Gatt {
    val serviceUuid: UUID = UUID.fromString("a7b3c210-0001-4000-8000-000000000001")
    val machineStateUuid: UUID = UUID.fromString("a7b3c210-0002-4000-8000-000000000001")
    val setpointUuid: UUID = UUID.fromString("a7b3c210-0003-4000-8000-000000000001")
    // UUID inherited from the v2 cascade-gain characteristic; wire format is
    // still a 4-byte float, but the value is now a ±°C setpoint offset (see
    // protocol/ble/tier2.md → ThermoblockOffset).
    val thermoblockOffsetUuid: UUID = UUID.fromString("a7b3c210-0004-4000-8000-000000000001")
    val settingsUuid: UUID = UUID.fromString("a7b3c210-0005-4000-8000-000000000001")
    val brewControlUuid: UUID = UUID.fromString("a7b3c210-0006-4000-8000-000000000001")
    val heaterEnableUuid: UUID = UUID.fromString("a7b3c210-0007-4000-8000-000000000001")
    val preinfusionUuid: UUID = UUID.fromString("a7b3c210-0008-4000-8000-000000000001")
    val flushUuid: UUID = UUID.fromString("a7b3c210-0009-4000-8000-000000000001")
    // Absolute manual thermoblock setpoint (°C float); 0 clears the override.
    val thermoblockSetpointUuid: UUID = UUID.fromString("a7b3c210-000a-4000-8000-000000000001")
    const val machineStateSize = 52
    const val settingsSize = 20
}

enum class BrewSource { Idle, Manual, Switch }

data class MachineState(
    val thermoblockTempC: Float,
    val groupTempC: Float,
    val groupSetpointC: Float,
    val thermoblockSetpointC: Float,
    val pressureBar: Float,
    val flowMlS: Float,
    val weightG: Float,
    val thermoblockOffsetC: Float,
    val shotTimeMs: Long,
    val uptimeMs: Long,
    val heaterDutyPct: Int,
    val heaterOn: Boolean,
    val faultCode: Int,
    val tuning: Boolean,
    val brewing: Boolean,
    val brewSource: BrewSource,
    val groupSensorOk: Boolean,
    val heaterEnabled: Boolean,
    val flushing: Boolean,
    val thermoblockManual: Boolean,
) {
    val faultLabel: String?
        get() = when (faultCode) {
            0 -> null
            1 -> "Sensor fault"
            2 -> "Overtemp"
            else -> "Fault"
        }

    companion object {
        fun fromBytes(bytes: ByteArray): MachineState? {
            if (bytes.size < Tier1Gatt.machineStateSize) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val thermoblock = b.float
            val group = b.float
            val groupSp = b.float
            val thermoblockSp = b.float
            val pressure = b.float
            val flow = b.float
            val weight = b.float
            val thermoblockOffset = b.float
            val shot = b.int.toLong() and 0xFFFF_FFFFL
            val uptime = b.int.toLong() and 0xFFFF_FFFFL
            val duty = b.get().toInt() and 0xFF
            val heater = b.get().toInt() and 0xFF
            val fault = b.get().toInt() and 0xFF
            val tuning = b.get().toInt() and 0xFF
            val brewing = b.get().toInt() and 0xFF
            val source = b.get().toInt() and 0xFF
            val groupOk = b.get().toInt() and 0xFF
            val heaterEnabled = b.get().toInt() and 0xFF
            val flushing = b.get().toInt() and 0xFF
            val thermoblockManual = b.get().toInt() and 0xFF
            // 2 pad bytes reserved for future flags — ignored on read.
            return MachineState(
                thermoblockTempC = thermoblock,
                groupTempC = group,
                groupSetpointC = groupSp,
                thermoblockSetpointC = thermoblockSp,
                pressureBar = pressure,
                flowMlS = flow,
                weightG = weight,
                thermoblockOffsetC = thermoblockOffset,
                shotTimeMs = shot,
                uptimeMs = uptime,
                heaterDutyPct = duty,
                heaterOn = heater != 0,
                faultCode = fault,
                tuning = tuning != 0,
                brewing = brewing != 0,
                brewSource = when (source) {
                    1 -> BrewSource.Manual
                    2 -> BrewSource.Switch
                    else -> BrewSource.Idle
                },
                groupSensorOk = groupOk != 0,
                heaterEnabled = heaterEnabled != 0,
                flushing = flushing != 0,
                thermoblockManual = thermoblockManual != 0,
            )
        }
    }
}

data class MachineSettings(
    val kp: Float,
    val ki: Float,
    val kd: Float,
    val steamSetpointC: Float,
    val heaterTimeoutMin: Float,
) {
    fun toBytes(): ByteArray =
        ByteBuffer.allocate(Tier1Gatt.settingsSize).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(kp).putFloat(ki).putFloat(kd).putFloat(steamSetpointC)
            .putFloat(heaterTimeoutMin).array()

    companion object {
        fun fromBytes(bytes: ByteArray): MachineSettings? {
            if (bytes.size < Tier1Gatt.settingsSize) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return MachineSettings(b.float, b.float, b.float, b.float, b.float)
        }
    }
}

fun Float.toLeFloatBytes(): ByteArray =
    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(this).array()
