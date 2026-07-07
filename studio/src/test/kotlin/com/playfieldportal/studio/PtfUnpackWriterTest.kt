package com.playfieldportal.studio

import com.playfieldportal.studio.io.PtfUnpackWriter
import com.playfieldportal.themekit.PtfUnpacker
import com.playfieldportal.themekit.TestFixtures
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PtfUnpackWriterTest {

    @Test
    fun `writes wallpaper png plus report`() {
        val ptf = TestFixtures.buildPtf(
            name = "Wp",
            firmware = "5.00",
            wallpaperBmp = TestFixtures.buildBmp(16, 8) { _, _ -> 0xFF3050E0.toInt() },
        )
        val dump = assertNotNull(PtfUnpacker.unpack(ptf))
        val outDir = Files.createTempDirectory("unpack").toFile()
        try {
            val summary = PtfUnpackWriter.write(dump, outDir)
            assertEquals(1, summary.images)
            assertEquals(0, summary.failed)

            val wallpaper = outDir.resolve("wallpaper.png")
            assertTrue(wallpaper.isFile, "wallpaper alias missing")
            val img = assertNotNull(ImageIO.read(wallpaper))
            assertEquals(16, img.width)
            assertTrue(outDir.resolve("slot1_res0.png").isFile)
            assertTrue(outDir.resolve("report.txt").readText().contains("slot1_res0.png"))
        } finally {
            outDir.deleteRecursively()
        }
    }
}
