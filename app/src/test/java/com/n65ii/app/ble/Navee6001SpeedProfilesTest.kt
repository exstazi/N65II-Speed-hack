package com.n65ii.app.ble

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Navee6001SpeedProfilesTest {
    @Test fun fieldAnchorsAndRangeStayPinned() {
        assertEquals(118, Navee6001SpeedProfiles.modeCapZ(6))
        assertEquals(298, Navee6001SpeedProfiles.modeCapZ(15))
        assertEquals(397, Navee6001SpeedProfiles.modeCapZ(20))
        assertEquals(795, Navee6001SpeedProfiles.modeCapZ(40))
        assertEquals(1193, Navee6001SpeedProfiles.modeCapZ(60))
    }

    @Test fun mode4GetsIndependentReleaseProfile() {
        val stock = File("src/main/assets/firmware/n65ii_6001_bldc0005_stock.bin").readBytes()
        val built = Navee6001SpeedProfiles.buildModes(stock, 6, 15, 20, 40)

        assertEquals(118, built.ecoCapZ)
        assertEquals(298, built.driveCapZ)
        assertEquals(397, built.sportCapZ)
        assertEquals(795, built.releaseCapZ)

        // 0x36BA remains the stock BEQ mode4 -> 0x36D4.
        assertEquals(0x0B, built.bytes[0x36BA].toInt() and 0xFF)
        assertEquals(0xD0, built.bytes[0x36BB].toInt() and 0xFF)

        // 0x36D4 is now B 0x36EC; 0x36EC keeps the stock literal load from 0x3978.
        assertEquals(0x0A, built.bytes[0x36D4].toInt() and 0xFF)
        assertEquals(0xE0, built.bytes[0x36D5].toInt() and 0xFF)
        assertEquals(0xA2, built.bytes[0x36EC].toInt() and 0xFF)
        assertEquals(0x48, built.bytes[0x36ED].toInt() and 0xFF)

        fun le32(off: Int): Int =
            (built.bytes[off].toInt() and 0xFF) or
                ((built.bytes[off + 1].toInt() and 0xFF) shl 8) or
                ((built.bytes[off + 2].toInt() and 0xFF) shl 16) or
                ((built.bytes[off + 3].toInt() and 0xFF) shl 24)

        assertEquals(397, le32(0x3964)) // normal SPORT
        assertEquals(298, le32(0x3968)) // DRIVE
        assertEquals(118, le32(0x396C)) // ECO
        assertEquals(795, le32(0x3978)) // hidden RELEASE mode 4
        assertTrue(built.wholeFileDiffCount > 0)
    }
}
