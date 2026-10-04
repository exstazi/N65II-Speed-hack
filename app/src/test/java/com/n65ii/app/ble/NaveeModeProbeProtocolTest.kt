package com.n65ii.app.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class NaveeModeProbeProtocolTest {
    @Test fun modeProbeFramesAreExact() {
        assertArrayEquals(hex("55 AA 00 58 01 04 5C FE FD"), NaveeProtocol.buildWrite(0x58, byteArrayOf(4)))
        assertArrayEquals(hex("55 AA 00 58 01 06 5E FE FD"), NaveeProtocol.buildWrite(0x58, byteArrayOf(6)))
        assertArrayEquals(hex("55 AA 00 58 01 07 5F FE FD"), NaveeProtocol.buildWrite(0x58, byteArrayOf(7)))
    }

    private fun hex(value: String): ByteArray = value.split(" ").map { it.toInt(16).toByte() }.toByteArray()
}
