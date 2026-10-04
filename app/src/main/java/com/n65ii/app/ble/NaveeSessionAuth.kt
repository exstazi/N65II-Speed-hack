package com.n65ii.app.ble

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * NAVEE OEM-style 0x30/0x31 session authentication helper.
 *
 * Some bound NAVEE controllers close the BLE link a few seconds after connect unless the
 * two-round challenge/response session is completed. This helper only implements the
 * challenge transform; state sequencing is owned by BleManager.
 */
object NaveeSessionAuth {
    private val keys = arrayOf(
        byteArrayOf(0xA0.toByte(),0xA1.toByte(),0xA2.toByte(),0xA3.toByte(),0xA4.toByte(),0xA5.toByte(),0xA6.toByte(),0xA7.toByte(),0xA8.toByte(),0xA9.toByte(),0xAA.toByte(),0xAB.toByte(),0xAC.toByte(),0xAD.toByte(),0xAE.toByte(),0xAF.toByte()),
        byteArrayOf(0x44,0x6D,0x10,0x72,0x6D,0xBE.toByte(),0x05,0xF6.toByte(),0x62,0xDF.toByte(),0xAA.toByte(),0xF0.toByte(),0x13,0x27,0x30,0x3F),
        byteArrayOf(0xA2.toByte(),0x85.toByte(),0xCC.toByte(),0xEC.toByte(),0x81.toByte(),0x4F,0xE9.toByte(),0x61,0x74,0x29,0x95.toByte(),0xE8.toByte(),0xEB.toByte(),0xA9.toByte(),0x22,0x47),
        byteArrayOf(0x3F,0xEE.toByte(),0x80.toByte(),0xFF.toByte(),0x96.toByte(),0xDF.toByte(),0x5C,0xF5.toByte(),0x42,0xEA.toByte(),0xAC.toByte(),0x93.toByte(),0x28,0x1F,0xE5.toByte(),0x29),
        byteArrayOf(0x4E,0xB4.toByte(),0xD4.toByte(),0x64,0xD6.toByte(),0xEF.toByte(),0x53,0xED.toByte(),0x6C,0xE9.toByte(),0x45,0x58,0xDE.toByte(),0x9A.toByte(),0x5E,0xE3.toByte())
    )

    private const val keyIndex = 1
    private val random = SecureRandom()

    enum class Kind { CHALLENGE, COMPLETE, REJECTED, NONE }
    data class Result(val kind: Kind, val response: ByteArray? = null)

    fun newAnonymousSessionId(): Long = random.nextLong().ushr(1).coerceAtLeast(1L)

    fun authInitPayload(sessionId: Long, shareAccount: Boolean): ByteArray {
        val idBytes = sixBytes(sessionId.coerceAtLeast(1L))
        return ByteArray(9).also { p ->
            p[0] = keyIndex.toByte()
            p[1] = if (shareAccount) 1 else 0
            idBytes.copyInto(p, 2)
            p[8] = 0
        }
    }

    fun parse30(frame: NaveeFrame): Result {
        if (frame.command != 0x30 || !frame.checksumOk || frame.payload.isEmpty()) return Result(Kind.NONE)
        val err = frame.payload[0].toInt() and 0xFF
        if (err != 0) return Result(Kind.REJECTED)
        if (frame.payload.size <= 1) return Result(Kind.COMPLETE)

        val data = frame.payload.copyOfRange(1, frame.payload.size)
        val challenge = ByteArray(16)
        val useXor: Boolean
        if (data.size > 16) {
            val mode = data[0].toInt() and 0xFF
            if (data.size < 17) return Result(Kind.NONE)
            data.copyInto(challenge, 0, 1, 17)
            useXor = mode == 0
        } else {
            if (data.size < 16) return Result(Kind.NONE)
            data.copyInto(challenge, 0, 0, 16)
            useXor = false
        }

        val key = keys[keyIndex]
        val response = if (useXor) {
            ByteArray(16) { i -> ((challenge[i].toInt() and 0xFF) xor (key[i].toInt() and 0xFF)).toByte() }
        } else {
            runCatching {
                val cipher = Cipher.getInstance("AES/ECB/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
                cipher.doFinal(challenge)
            }.getOrNull() ?: return Result(Kind.NONE)
        }
        return Result(Kind.CHALLENGE, response)
    }

    fun parse31(frame: NaveeFrame): Boolean? {
        if (frame.command != 0x31 || !frame.checksumOk || frame.payload.isEmpty()) return null
        return (frame.payload[0].toInt() and 0xFF) == 0
    }

    private fun sixBytes(raw: Long): ByteArray {
        var value = raw and 0xFFFFFFFFFFFFL
        if (value == 0L) value = 1L
        val out = ByteArray(6)
        for (i in 5 downTo 0) {
            out[i] = (value and 0xFF).toByte()
            value = value ushr 8
        }
        val first = out[0].toInt() and 0xFF
        if (first == 0 || first >= 0x80) out[0] = 0x88.toByte()
        return out
    }
}
