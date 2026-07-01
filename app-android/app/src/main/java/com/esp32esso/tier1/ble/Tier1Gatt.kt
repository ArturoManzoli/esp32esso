package com.esp32esso.tier1.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object Tier1Gatt {
    val serviceUuid: UUID = UUID.fromString("a7b3c210-0001-4000-8000-000000000001")
    val machineStateUuid: UUID = UUID.fromString("a7b3c210-0002-4000-8000-000000000001")
    val setpointUuid: UUID = UUID.fromString("a7b3c210-0003-4000-8000-000000000001")
    const val machineStateSize = 16
}

data class MachineState(
    val tempC: Float,
    val setpointC: Float,
    val heaterOn: Boolean,
    val faultCode: Int,
    val tuning: Boolean,
    val uptimeMs: Long,
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
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val temp = buffer.float
            val setpoint = buffer.float
            val heater = buffer.get().toInt() and 0xFF
            val fault = buffer.get().toInt() and 0xFF
            val tuning = buffer.get().toInt() and 0xFF
            buffer.get() // reserved
            val uptime = buffer.int.toLong() and 0xFFFF_FFFFL
            return MachineState(
                tempC = temp,
                setpointC = setpoint,
                heaterOn = heater != 0,
                faultCode = fault,
                tuning = tuning != 0,
                uptimeMs = uptime,
            )
        }
    }
}

fun Float.toSetpointBytes(): ByteArray =
    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(this).array()
