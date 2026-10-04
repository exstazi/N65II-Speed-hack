package com.n65ii.app.ble

import java.util.TimeZone

data class NaveeFrame(
    val flag: Int,
    val command: Int,
    val payload: ByteArray,
    val checksumOk: Boolean,
    val raw: ByteArray
)

data class NaveeDrivingTelemetryV1(
    val batteryPercent: Int,
    val drivingStatus: Int,
    val realTimeSpeedKmh: Double
)

data class NaveeFirmwareVersions(
    val meter: String,
    val bldc: String,
    val bms: String
)

data class NaveeVehicleIdentity(
    val serial: String,
    val pid: String?,
    val region: String?,
    val derivedSku: String?
)

data class NaveeBatteryInfo(val chargePercent: Int?)

/** Minimal NAVEE D0FF protocol surface required by the dedicated N65i II app. */
object NaveeProtocol {
    const val SERVICE_UUID = "0000d0ff-3c17-d293-8e48-14fe2e4da212"
    const val TX_UUID = "0000b002-0000-1000-8000-00805f9b34fb"
    const val RX_UUID = "0000b003-0000-1000-8000-00805f9b34fb"

    fun buildRead(command: Int, flag: Int = 0): ByteArray {
        require(command in 0..255 && flag in 0..255)
        val out = byteArrayOf(
            0x55.toByte(), 0xAA.toByte(), flag.toByte(), command.toByte(),
            0x00, 0xFE.toByte(), 0xFD.toByte()
        )
        out[4] = checksum(out, 0, 4).toByte()
        return out
    }

    fun buildWrite(command: Int, payload: ByteArray, flag: Int = 0): ByteArray {
        require(command in 0..255 && flag in 0..255 && payload.size <= 255)
        val out = ByteArray(8 + payload.size)
        out[0] = 0x55.toByte()
        out[1] = 0xAA.toByte()
        out[2] = flag.toByte()
        out[3] = command.toByte()
        out[4] = payload.size.toByte()
        payload.copyInto(out, 5)
        val checksumIndex = 5 + payload.size
        out[checksumIndex] = checksum(out, 0, checksumIndex).toByte()
        out[checksumIndex + 1] = 0xFE.toByte()
        out[checksumIndex + 2] = 0xFD.toByte()
        return out
    }

    /** NAVEE post-connect clock sync: 0x6F sub-command 6 + local epoch seconds, big-endian. */
    fun buildTimeSync(nowMillis: Long = System.currentTimeMillis()): ByteArray {
        val localEpochSeconds = (nowMillis / 1000L) + (TimeZone.getDefault().getOffset(nowMillis) / 1000L)
        return buildWrite(
            0x6F,
            byteArrayOf(
                0x06,
                (localEpochSeconds ushr 24).toByte(),
                (localEpochSeconds ushr 16).toByte(),
                (localEpochSeconds ushr 8).toByte(),
                localEpochSeconds.toByte()
            )
        )
    }

    fun parse(bytes: ByteArray): NaveeFrame? {
        if (bytes.size < 7) return null
        if ((bytes[0].toInt() and 0xFF) != 0x55 || (bytes[1].toInt() and 0xFF) != 0xAA) return null
        if ((bytes[bytes.lastIndex - 1].toInt() and 0xFF) != 0xFE ||
            (bytes[bytes.lastIndex].toInt() and 0xFF) != 0xFD
        ) return null

        val flag = bytes[2].toInt() and 0xFF
        val command = bytes[3].toInt() and 0xFF
        if (bytes.size == 7) {
            return NaveeFrame(
                flag = flag,
                command = command,
                payload = byteArrayOf(),
                checksumOk = checksum(bytes, 0, 4) == (bytes[4].toInt() and 0xFF),
                raw = bytes.copyOf()
            )
        }

        val payloadLen = bytes[4].toInt() and 0xFF
        if (bytes.size != 8 + payloadLen) return null
        val checksumIndex = 5 + payloadLen
        return NaveeFrame(
            flag = flag,
            command = command,
            payload = bytes.copyOfRange(5, checksumIndex),
            checksumOk = checksum(bytes, 0, checksumIndex) == (bytes[checksumIndex].toInt() and 0xFF),
            raw = bytes.copyOf()
        )
    }

    fun statusByte(frame: NaveeFrame): Int? = frame.payload.firstOrNull()?.toInt()?.and(0xFF)

    /** N65i II live telemetry from command 0x92. */
    fun drivingTelemetryV1(frame: NaveeFrame): NaveeDrivingTelemetryV1? {
        if (frame.command != 0x92 || !frame.checksumOk || statusByte(frame) != 0) return null
        val d = frame.payload.drop(1)
        if (d.size < 4) return null
        val speedRaw = (d[2].toInt() and 0xFF) or ((d[3].toInt() and 0xFF) shl 8)
        return NaveeDrivingTelemetryV1(
            batteryPercent = d[0].toInt() and 0xFF,
            drivingStatus = d[1].toInt() and 0xFF,
            realTimeSpeedKmh = speedRaw / 10.0
        )
    }

    /** Vehicle identity used to confirm PID 2509 and derive the normal NAVEE region/SKU behavior. */
    fun vehicleIdentity(frame: NaveeFrame): NaveeVehicleIdentity? {
        if (frame.command != 0x74 || !frame.checksumOk || statusByte(frame) != 0) return null
        val data = frame.payload.drop(1)
        if (data.isEmpty()) return null
        val serial = buildString {
            data.forEach { b ->
                val c = b.toInt() and 0xFF
                if (c in 0x20..0x7E) append(c.toChar())
            }
        }.trim()
        val pid = Regex("[A-Za-z]?(\\d{4})").find(serial)?.groupValues?.getOrNull(1)
        val region = if (serial.length >= 10) serial.substring(8, 10).uppercase() else null
        val sku = when (region) {
            "IT", "DE", "NE" -> "ITA"
            "US", "CN", "RU" -> "USA"
            null -> null
            else -> "EUR"
        }
        return NaveeVehicleIdentity(serial, pid, region, sku)
    }

    /** Battery SOC used by the flash preflight. */
    fun batteryInfo(frame: NaveeFrame): NaveeBatteryInfo? {
        if (frame.command != 0x72 || !frame.checksumOk || statusByte(frame) != 0) return null
        val d = frame.payload.drop(1)
        return NaveeBatteryInfo(d.getOrNull(1)?.toInt()?.and(0xFF))
    }

    /** Meter / BLDC / BMS versions from command 0x73. */
    fun firmwareVersions(frame: NaveeFrame): NaveeFirmwareVersions? {
        if (frame.command != 0x73 || !frame.checksumOk || statusByte(frame) != 0) return null
        val d = frame.payload.drop(1)
        if (d.size < 8) return null

        fun chunk(offset: Int): String {
            if (offset >= d.size) return ""
            val bytes = d.drop(offset).take(4)
            if (bytes.isEmpty()) return ""
            val printable = bytes.all { (it.toInt() and 0xFF) in 0x20..0x7E }
            return if (printable) {
                bytes.map { it.toInt().toChar() }.joinToString("").trim('\u0000', ' ')
            } else {
                bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
            }
        }

        return NaveeFirmwareVersions(
            meter = chunk(0),
            bldc = chunk(4),
            bms = chunk(8)
        )
    }

    private fun checksum(bytes: ByteArray, start: Int, endExclusive: Int): Int {
        var sum = 0
        for (i in start until endExclusive) sum = (sum + (bytes[i].toInt() and 0xFF)) and 0xFF
        return sum
    }
}
