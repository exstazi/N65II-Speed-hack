package com.n65ii.app.ble

import java.security.MessageDigest

/**
 * Frozen N65i II 6001 / BLDC 0.0.0.5 four-profile speed builder.
 *
 * Only the field-calibrated ECO / DRIVE / SPORT route is retained in this dedicated app.
 * Each mode accepts 1..60 km/h. The builder fails closed on any stock byte/signature/hash
 * mismatch, patches only the known selector/literal bytes, keeps the compressed .data stream
 * byte-equivalent after decompression, and reseals both CRC-16/XMODEM fields.
 */
internal object Navee6001SpeedProfiles {
    const val STOCK_SHA256 = "03c8c60163eeea4e49367edd1b54235d9f9fd0776a1eee84f93a4248d8561b02"
    const val IMAGE_SIZE = 47_232

    private const val BODY_LEN = 0xB768
    private const val COMPRESSED_FILE_OFFSET = 0xB4D0
    private const val COMPRESSED_REGION_SIZE = 0x398
    private const val EXPECTED_COMPRESSED_USED = 0x397
    private const val DECOMPRESSED_SIZE = 0x8A4

    /**
     * Continuous capZ conversion used by ECO / DRIVE / SPORT / hidden RELEASE.
     * Anchors: 20 km/h -> 397 and 40 km/h -> 795.
     */
    fun modeCapZ(speedKmh: Int): Int {
        require(speedKmh in 1..60) { "Mode speed must be 1..60 km/h" }
        return (((speedKmh * 199) + 5) / 10) - 1
    }

    data class ModeBuildResult(
        val ecoSpeedKmh: Int,
        val driveSpeedKmh: Int,
        val sportSpeedKmh: Int,
        val releaseSpeedKmh: Int,
        val ecoCapZ: Int,
        val driveCapZ: Int,
        val sportCapZ: Int,
        val releaseCapZ: Int,
        val bytes: ByteArray,
        val sha256: String,
        val primaryCrc: Int,
        val secondaryCrc: Int,
        val changedFileOffsets: List<Int>,
        val wholeFileDiffCount: Int
    )

    /**
     * Exact N65i II 6001 mode mapping established by the previous field tests:
     * selector 0x0B = ECO, mode 2 = DRIVE, modes 3/5 = SPORT/Turbo.
     *
     * v1.1.4 keeps the stock mode-4 selector as a separate hidden runtime profile.
     * The normal SPORT cap therefore remains independent from the release cap:
     *   0x0B -> ECO, 2 -> DRIVE, 3/5 -> SPORT, 4 -> RELEASE.
     *
     * No new code cave or initialized-data mutation is required. Mode 4 branches directly
     * to its existing literal-load path, while the now-unused mode-4 flag literal at 0x396C
     * is reused as the ECO cap literal.
     */
    fun buildModes(stock: ByteArray, ecoSpeedKmh: Int, driveSpeedKmh: Int, sportSpeedKmh: Int, releaseSpeedKmh: Int): ModeBuildResult {
        verifyStock(stock)
        require(ecoSpeedKmh in 1..60 && driveSpeedKmh in 1..60 && sportSpeedKmh in 1..60 && releaseSpeedKmh in 1..60) {
            "Eco/Drive/Sport/Release speeds must be 1..60 km/h"
        }
        val ecoCap = modeCapZ(ecoSpeedKmh)
        val driveCap = modeCapZ(driveSpeedKmh)
        val sportCap = modeCapZ(sportSpeedKmh)
        val releaseCap = modeCapZ(releaseSpeedKmh)

        val out = stock.copyOf()
        fun expect(off: Int, vararg values: Int) {
            values.forEachIndexed { i, v ->
                require((out[off + i].toInt() and 0xFF) == v) {
                    "BLDC0005 selector signature mismatch @0x${(off + i).toString(16)}"
                }
            }
        }
        fun writeLe32(off: Int, value: Int) {
            out[off] = (value and 0xFF).toByte()
            out[off + 1] = ((value ushr 8) and 0xFF).toByte()
            out[off + 2] = ((value ushr 16) and 0xFF).toByte()
            out[off + 3] = ((value ushr 24) and 0xFF).toByte()
        }

        // Exact stock selector instructions/literals. Any firmware drift blocks the build.
        expect(0x36A4, 0x08, 0x78, 0x0B, 0x28, 0x0D, 0xD0)
        expect(0x36AA, 0x02, 0x28, 0x0D, 0xD0)
        expect(0x36B2, 0x0D, 0xD0)
        expect(0x36B4, 0x05, 0x28, 0x0B, 0xD0)
        expect(0x36BA, 0x0B, 0xD0)
        expect(0x36C6, 0x77, 0x20, 0x11, 0xE0)
        expect(0x36CA, 0xA7, 0x48, 0x00, 0x88)
        expect(0x36D0, 0x18, 0x88)
        require(readLe32(out, 0x3964) == 0x2000033A) { "sport literal pointer changed" }
        require(readLe32(out, 0x3968) == 0x20000338) { "drive literal pointer changed" }
        require(readLe32(out, 0x396C) == 0x2000033F) { "mode4 flag literal pointer changed" }
        require(readLe32(out, 0x3978) == 0x0000031B) { "stock mode4 795 literal changed" }
        expect(0x36D4, 0xA5, 0x48, 0x00, 0x78, 0x01, 0x28)
        expect(0x36EC, 0xA2, 0x48, 0xE0, 0x82)

        // ECO: load the independent ECO cap from 0x396C. This literal was only used by
        // the stock mode-4 flag path, which is bypassed below. LDR r0,[pc,#0x2A4].
        out[0x36C6] = 0xA9.toByte()
        out[0x36C7] = 0x48

        // DRIVE: 0x3968 becomes the cap itself; remove the LDRH indirection.
        out[0x36CC] = 0x00
        out[0x36CD] = 0xBF.toByte()

        // SPORT: r3 contains the literal value; MOV r0,r3 replaces LDRH.
        out[0x36D0] = 0x18
        out[0x36D1] = 0x46

        // Hidden mode 4 is the runtime release profile. Keep the stock selector branch
        // at 0x36BA intact and skip the stock flag gates: B 0x36EC. The existing 0x36EC
        // LDR then loads the dedicated release cap from 0x3978.
        out[0x36D4] = 0x0A
        out[0x36D5] = 0xE0.toByte()

        writeLe32(0x3964, sportCap)
        writeLe32(0x3968, driveCap)
        writeLe32(0x396C, ecoCap)
        writeLe32(0x3978, releaseCap)

        reseal(out)
        verifySeals(out)
        require(versionAt80(out) == "0.0.0.5") { "candidate version changed" }
        require(asciiAt(out, 0x90, 32) == "SZMC-ES-ZM-02831") { "candidate signature changed" }
        require(readBe32(out, 0x84) == BODY_LEN) { "candidate body length changed" }

        // Critical invariant: no initialized-data change is introduced by this selector/literal patch.
        val stockDecoded = decompressStockRegion(stock)
        val candidateDecoded = decompressStockRegion(out)
        require(candidateDecoded.output.contentEquals(stockDecoded.output)) {
            "mode patch unexpectedly changed scatterload .data"
        }
        require(candidateDecoded.consumed == EXPECTED_COMPRESSED_USED) { "compressed stream consumption changed" }

        val mutable = mutableSetOf(0x13, 0x14, 0xB0, 0xB1)
        mutable += (0x36C6..0x36C7)
        mutable += (0x36CC..0x36CD)
        mutable += (0x36D0..0x36D1)
        mutable += (0x36D4..0x36D5)
        mutable += (0x3964..0x396F)
        mutable += (0x3978..0x397B)
        val diffs = stock.indices.filter { stock[it] != out[it] }
        require(diffs.all { it in mutable }) {
            "mode patch changed unexpected bytes: ${diffs.filterNot { it in mutable }.joinToString { "0x${it.toString(16)}" }}"
        }

        return ModeBuildResult(
            ecoSpeedKmh = ecoSpeedKmh,
            driveSpeedKmh = driveSpeedKmh,
            sportSpeedKmh = sportSpeedKmh,
            releaseSpeedKmh = releaseSpeedKmh,
            ecoCapZ = ecoCap,
            driveCapZ = driveCap,
            sportCapZ = sportCap,
            releaseCapZ = releaseCap,
            bytes = out,
            sha256 = sha256Hex(out),
            primaryCrc = readBe16(out, 0xB0),
            secondaryCrc = readBe16(out, 0x13),
            changedFileOffsets = diffs,
            wholeFileDiffCount = diffs.size
        )
    }

    private data class Decoded(val output: ByteArray, val consumed: Int)

    private fun verifyStock(stock: ByteArray) {
        require(stock.size == IMAGE_SIZE) { "stock size ${stock.size} != $IMAGE_SIZE" }
        require(sha256Hex(stock) == STOCK_SHA256) { "stock SHA-256 mismatch" }
        require(versionAt80(stock) == "0.0.0.5") { "stock version is not 0.0.0.5" }
        require(asciiAt(stock, 0x90, 32) == "SZMC-ES-ZM-02831") { "stock controller signature mismatch" }
        require(readBe32(stock, 0x84) == BODY_LEN) { "stock body length mismatch" }
        verifySeals(stock)
    }

    private fun verifySeals(bytes: ByteArray) {
        val len = readBe32(bytes, 0x84)
        require(0x100 + len <= bytes.size) { "body range outside image" }
        require(readBe16(bytes, 0xB0) == crc16Xmodem(bytes, 0x100, 0x100 + len)) { "primary CRC mismatch" }
        require(readBe16(bytes, 0x13) == crc16Xmodem(bytes, 0x80, bytes.size)) { "secondary CRC mismatch" }
    }

    private fun reseal(bytes: ByteArray) {
        val len = readBe32(bytes, 0x84)
        writeBe16(bytes, 0xB0, crc16Xmodem(bytes, 0x100, 0x100 + len))
        writeBe16(bytes, 0x13, crc16Xmodem(bytes, 0x80, bytes.size))
    }

    private fun decompressStockRegion(image: ByteArray): Decoded {
        val source = image.copyOfRange(COMPRESSED_FILE_OFFSET, COMPRESSED_FILE_OFFSET + COMPRESSED_REGION_SIZE)
        val out = ByteArray(DECOMPRESSED_SIZE)
        var src = 0
        var dst = 0
        while (dst < out.size) {
            require(src < source.size) { "compressed stream exhausted" }
            val control = source[src++].toInt() and 0xFF
            var literalsField = control and 7
            if (literalsField == 0) {
                require(src < source.size) { "literal extension missing" }
                literalsField = source[src++].toInt() and 0xFF
            }
            var copyField = control ushr 4
            if (copyField == 0) {
                require(src < source.size) { "copy extension missing" }
                copyField = source[src++].toInt() and 0xFF
            }
            val literalCount = literalsField - 1
            require(literalCount >= 0) { "invalid literal field" }
            require(src + literalCount <= source.size && dst + literalCount <= out.size) { "literal range outside stream/output" }
            source.copyInto(out, dst, src, src + literalCount)
            src += literalCount
            dst += literalCount

            if ((control and 0x08) != 0) {
                require(src < source.size) { "back-reference distance missing" }
                val distance = source[src++].toInt() and 0xFF
                require(distance in 1..dst) { "invalid back-reference distance $distance at dst=$dst" }
                val count = copyField + 2
                require(dst + count <= out.size) { "back-reference overruns output" }
                repeat(count) {
                    out[dst] = out[dst - distance]
                    dst++
                }
            } else {
                val count = copyField
                require(dst + count <= out.size) { "zero run overruns output" }
                repeat(count) { out[dst++] = 0 }
            }
        }
        return Decoded(out, src)
    }

    private fun readBe16(bytes: ByteArray, off: Int): Int =
        ((bytes[off].toInt() and 0xFF) shl 8) or (bytes[off + 1].toInt() and 0xFF)

    private fun writeBe16(bytes: ByteArray, off: Int, value: Int) {
        bytes[off] = ((value ushr 8) and 0xFF).toByte()
        bytes[off + 1] = (value and 0xFF).toByte()
    }

    private fun readBe32(bytes: ByteArray, off: Int): Int =
        ((bytes[off].toInt() and 0xFF) shl 24) or
            ((bytes[off + 1].toInt() and 0xFF) shl 16) or
            ((bytes[off + 2].toInt() and 0xFF) shl 8) or
            (bytes[off + 3].toInt() and 0xFF)

    private fun readLe32(bytes: ByteArray, off: Int): Int =
        (bytes[off].toInt() and 0xFF) or
            ((bytes[off + 1].toInt() and 0xFF) shl 8) or
            ((bytes[off + 2].toInt() and 0xFF) shl 16) or
            ((bytes[off + 3].toInt() and 0xFF) shl 24)

    private fun versionAt80(bytes: ByteArray): String =
        (0 until 4).joinToString(".") { (bytes[0x80 + it].toInt() and 0xFF).toString() }

    private fun asciiAt(bytes: ByteArray, off: Int, max: Int): String {
        val end = (off until minOf(off + max, bytes.size)).firstOrNull {
            (bytes[it].toInt() and 0xFF) == 0x00 || (bytes[it].toInt() and 0xFF) == 0xFF
        } ?: minOf(off + max, bytes.size)
        return bytes.copyOfRange(off, end).toString(Charsets.US_ASCII).trim()
    }

    private fun crc16Xmodem(bytes: ByteArray, start: Int, end: Int): Int {
        require(start >= 0 && end <= bytes.size && start <= end)
        var crc = 0
        for (i in start until end) {
            crc = crc xor ((bytes[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x1021) and 0xFFFF
                else (crc shl 1) and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
