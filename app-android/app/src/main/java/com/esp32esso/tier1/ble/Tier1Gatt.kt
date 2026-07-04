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
    val gainUuid: UUID = UUID.fromString("a7b3c210-0004-4000-8000-000000000001")
    val settingsUuid: UUID = UUID.fromString("a7b3c210-0005-4000-8000-000000000001")
    val brewControlUuid: UUID = UUID.fromString("a7b3c210-0006-4000-8000-000000000001")
    const val machineStateSize = 48
    const val settingsSize = 16
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
    val gain: Float,
    val shotTimeMs: Long,
    val uptimeMs: Long,
    val heaterDutyPct: Int,
    val heaterOn: Boolean,
    val faultCode: Int,
    val tuning: Boolean,
    val brewing: Boolean,
    val brewSource: BrewSource,
    val groupSensorOk: Boolean,
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
            val gain = b.float
            val shot = b.int.toLong() and 0xFFFF_FFFFL
            val uptime = b.int.toLong() and 0xFFFF_FFFFL
            val duty = b.get().toInt() and 0xFF
            val heater = b.get().toInt() and 0xFF
            val fault = b.get().toInt() and 0xFF
            val tuning = b.get().toInt() and 0xFF
            val brewing = b.get().toInt() and 0xFF
            val source = b.get().toInt() and 0xFF
            val groupOk = b.get().toInt() and 0xFF
            return MachineState(
                thermoblockTempC = thermoblock,
                groupTempC = group,
                groupSetpointC = groupSp,
                thermoblockSetpointC = thermoblockSp,
                pressureBar = pressure,
                flowMlS = flow,
                weightG = weight,
                gain = gain,
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
            )
        }
    }
}

data class MachineSettings(
    val kp: Float,
    val ki: Float,
    val kd: Float,
    val steamSetpointC: Float,
) {
    fun toBytes(): ByteArray =
        ByteBuffer.allocate(Tier1Gatt.settingsSize).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(kp).putFloat(ki).putFloat(kd).putFloat(steamSetpointC).array()

    companion object {
        fun fromBytes(bytes: ByteArray): MachineSettings? {
            if (bytes.size < Tier1Gatt.settingsSize) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return MachineSettings(b.float, b.float, b.float, b.float)
        }
    }
}

fun Float.toLeFloatBytes(): ByteArray =
    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(this).array()
