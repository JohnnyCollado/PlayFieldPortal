package com.playfieldportal.themekit

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Theme files arrive via SAF from arbitrary sources — these tests pin the guards that keep
 * crafted files from crashing the app (decompression bombs, absurd dimensions).
 */
class HostileInputTest {

    @Test
    fun `zlib bomb in the wallpaper slot is rejected, not inflated to OOM`() {
        // ~64MB of zeros deflates to ~64KB — inflating it must stop at the cap, not allocate it all.
        val bombPayload = TestFixtures.zlib(ByteArray(64 * 1024 * 1024))
        // A structurally-valid PTF whose wallpaper stream is the bomb: reuse the fixture builder
        // by handing it a fake "BMP" the size of the bomb source is impractical — instead build
        // the container manually around the pre-deflated payload.
        val small = TestFixtures.buildPtf("Bomb", "6.20", TestFixtures.buildBmp(2, 2) { _, _ -> 0 })
        // Replace the wallpaper stream: rebuild with the bomb spliced after the 32-byte lead-in.
        val dataOffset = 0x140
        val leadIn = 32
        val file = ByteArray(dataOffset + leadIn + bombPayload.size)
        small.copyInto(file, 0, 0, dataOffset) // header + slot table (sizes below overwrite)
        // Fix the slot size to cover the bomb payload.
        val slotSize = leadIn + bombPayload.size
        file[0x124] = (slotSize and 0xFF).toByte()
        file[0x125] = (slotSize shr 8 and 0xFF).toByte()
        file[0x126] = (slotSize shr 16 and 0xFF).toByte()
        file[0x127] = (slotSize shr 24 and 0xFF).toByte()
        bombPayload.copyInto(file, dataOffset + leadIn)

        val theme = assertNotNull(PtfParser.parse(file))
        assertNull(theme.wallpaper, "bomb wallpaper must be rejected")
    }

    @Test
    fun `bmp with absurd dimensions is rejected before allocation`() {
        // Hand-build a BMP header claiming a ~800M-pixel-wide image (width*3 overflows Int).
        val header = ByteArray(64)
        header[0] = 'B'.code.toByte(); header[1] = 'M'.code.toByte()
        header.putU32(10, 54)          // pixel offset
        header.putU32(14, 40)          // info header size
        header.putU32(18, 800_000_000) // width
        header.putU32(22, 2)           // height
        header.putU16(26, 1)
        header.putU16(28, 24)          // 24bpp
        assertNull(Bmp.decode(header))

        // And a merely-huge-but-plausible one still over the cap.
        header.putU32(18, 20_000)
        assertNull(Bmp.decode(header))
    }

    @Test
    fun `pfptheme zip bomb entry is rejected`() {
        // A bundle whose wallpaper entry deflates ~64MB of zeros from a tiny file.
        val zip = ByteArrayOutputStream().also { baos ->
            ZipOutputStream(baos).use { z ->
                z.putNextEntry(ZipEntry("manifest.json"))
                z.write("""{"manifest":"pfptheme","schemaVersion":1,"name":"Bomb","accentColor":"#FFFFFF"}""".toByteArray())
                z.closeEntry()
                z.putNextEntry(ZipEntry("wallpaper.png"))
                val chunk = ByteArray(1024 * 1024)
                repeat(64) { z.write(chunk) }
                z.closeEntry()
            }
        }.toByteArray()
        assertNull(PfpThemeCodec.read(zip))
    }

    private fun ByteArray.putU16(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = (value shr 8 and 0xFF).toByte()
    }

    private fun ByteArray.putU32(offset: Int, value: Int) {
        putU16(offset, value and 0xFFFF)
        putU16(offset + 2, value ushr 16)
    }
}
