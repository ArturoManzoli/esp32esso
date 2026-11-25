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
    // Pump pressure-ramp / soft-start duration (float seconds); 0 disables it.
    val pumpRampUuid: UUID = UUID.fromString("a7b3c210-000b-4000-8000-000000000001")
    // Brew pressure/flow profile program (variable-length blob; see BrewProfile
    // .toWireBytes / protocol/ble/tier2.md). An 8-byte header with phaseCount 0
    // clears the profile.
    val brewProfileUuid: UUID = UUID.fromString("a7b3c210-000c-4000-8000-000000000001")
    // Manual brew-pressure hold (float bar); 0 clears it and returns the shot to
    // the selected profile.
    val pressureSetpointUuid: UUID = UUID.fromString("a7b3c210-000d-4000-8000-000000000001")
    // Drip-tray scale tare; any non-zero byte zeroes the tray at its current load.
    val scaleTareUuid: UUID = UUID.fromString("a7b3c210-000e-4000-8000-000000000001")
    // Stop-on-weight target in grams; 0 disables the stop.
    val weightTargetUuid: UUID = UUID.fromString("a7b3c210-000f-4000-8000-000000000001")
    // v3 payload size, still the minimum a notification must carry. v4 appends
    // the manual pressure hold, v5 the running profile's position and v6 the
    // per-cell drip-tray weights and v7 the stop-on-weight target; parsing stays
    // tolerant of every length so a newer app keeps working against firmware
    // that predates a field.
    const val machineStateSize = 52
    const val machineStateSizeV4 = 56
    const val machineStateSizeV5 = 60
    const val machineStateSizeV6 = 68
    const val machineStateSizeV7 = 72
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
    val pressureManual: Boolean = false,
    val pressureSetpointBar: Float = Float.NaN,
    // Seconds into the running brew profile's own timeline (phases that ended
    // early on pressure skip ahead), or NaN when no profile is driving the pump.
    val profileElapsedSec: Float = Float.NaN,
    // Drip-tray cells reported separately so a bad corner or a dead cell is
    // visible instead of silently halving the total. NaN when no scale is bound.
    val weightLeftG: Float = Float.NaN,
    val weightRightG: Float = Float.NaN,
    // Grams at which the firmware ends the shot by itself; 0 when disabled.
    val weightTargetG: Float = 0f,
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
            val hasV4 = bytes.size >= Tier1Gatt.machineStateSizeV4
            val pressureManual = if (hasV4) b.get().toInt() and 0xFF else 0
            if (hasV4) b.get()  // pad, keeps the trailing float aligned
            val pressureSetpoint = if (hasV4) b.float else Float.NaN
            val profileElapsed =
                if (bytes.size >= Tier1Gatt.machineStateSizeV5) b.float else Float.NaN
            val hasV6 = bytes.size >= Tier1Gatt.machineStateSizeV6
            val weightLeft = if (hasV6) b.float else Float.NaN
            val weightRight = if (hasV6) b.float else Float.NaN
            val weightTarget = if (bytes.size >= Tier1Gatt.machineStateSizeV7) b.float else 0f
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
                pressureManual = pressureManual != 0,
                pressureSetpointBar = pressureSetpoint,
                profileElapsedSec = profileElapsed,
                weightLeftG = weightLeft,
                weightRightG = weightRight,
                weightTargetG = weightTarget,
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
