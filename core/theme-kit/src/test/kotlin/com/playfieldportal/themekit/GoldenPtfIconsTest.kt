package com.playfieldportal.themekit

import java.io.File
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Icon extraction and tint over real themes: the `ptf-test-set` corpus (one theme per firmware
 * generation plus the edge cases; its README says why each file is there). Third-party content,
 * so not committed: unzip it to `<golden dir>/ptf-test-set/` (default `~/Downloads`); each case
 * self-skips when its file is absent.
 */
class GoldenPtfIconsTest {

    private val setDir = File(
        System.getProperty("themekit.golden.dir") ?: "${System.getProperty("user.home")}${File.separator}Downloads",
        "ptf-test-set",
    )

    private fun golden(name: String): ByteArray {
        val file = File(setDir, name)
        assumeTrue("golden file missing: $file — skipping", file.isFile)
        return file.readBytes()
    }

    // Counted in PTF records, not slot keys: one record fills every slot that shares its art.
    private val allKeys = PtfIcons.DIRECT.size
    private val categoryKeys = 6

    /** File → (direct-fit records it should yield, whether its wallpaper decodes). */
    private val expected = mapOf(
        "樂克樂克™ 主題 2(PSP®專用) (NPHW00012).PTF" to (allKeys to true),
        "Test.ptf" to (categoryKeys to true),
        "Carbon Fiber.ptf" to (allKeys - 1 to false),
        "win95_1_2.ptf" to (allKeys to true),
        "Lelouch-Code-Geass.ptf" to (allKeys to true),
        "GT Theme (NPEW00051).PTF" to (0 to true),
        "Clear Xmb White-500.ptf" to (allKeys to true),
        "iPhone-Black.ptf" to (allKeys to false),
        "US0010-NPUY00022_00-FAMILYGUYPSPTH01.PTF" to (allKeys to true),
        "Neon Star.ptf" to (allKeys to true),
        "DarkBlue.ptf" to (allKeys to true),
        "ModNationRacers3.ptf" to (allKeys to true),
        "Clannad.ptf" to (allKeys to true),
        "Post.ptf" to (allKeys to true),
    )

    @Test
    fun `every theme in the set yields its expected icons and wallpaper`() {
        assumeTrue("ptf-test-set missing: $setDir — skipping", setDir.isDirectory)
        for ((name, want) in expected) {
            val file = File(setDir, name)
            if (!file.isFile) continue
            val bytes = file.readBytes()
            val icons = PtfIcons.extract(assertNotNull(PtfUnpacker.unpack(bytes), name))
            assertEquals(want.first, PtfIcons.tintSources(icons).size, "$name: direct-fit records")
            icons.values.forEach { assertEquals(it.width, it.height, "$name: icons are padded square") }
            assertEquals(want.second, PtfParser.parse(bytes)?.wallpaper != null, "$name: wallpaper decodes")
        }
    }

    @Test
    fun `fw 3_70 LZR icons decode with real transparency`() {
        val bytes = golden("樂克樂克™ 主題 2(PSP®專用) (NPHW00012).PTF")
        val icons = PtfIcons.extract(assertNotNull(PtfUnpacker.unpack(bytes)))
        val umd = assertNotNull(icons["item_umd"])
        assertTrue(umd.argb.any { it ushr 24 == 0 }, "icon corners are transparent")
        assertTrue(umd.argb.any { it ushr 24 == 0xFF }, "icon body is opaque")
    }

    @Test
    fun `every theme with icons gets a tint, and the score is a percentage`() {
        assumeTrue("ptf-test-set missing: $setDir — skipping", setDir.isDirectory)
        for (name in expected.keys) {
            val file = File(setDir, name)
            if (!file.isFile) continue
            val icons = PtfIcons.extract(assertNotNull(PtfUnpacker.unpack(file.readBytes())))
            val tint = PtfIconTint.derive(PtfIcons.tintSources(icons))
            if (icons.isEmpty()) continue
            val result = assertNotNull(tint, name)
            assertTrue(result.score in 0..100, "$name: score ${result.score}")
            assertEquals(0xFF, result.argb ushr 24, "$name: tint is opaque")
        }
    }

    @Test
    fun `cxmb ctf is still recognised and refused`() {
        assertEquals(PtfParser.Kind.CXMB, PtfParser.detect(golden("PS4_Theme_for_PSP_6_61.ctf")))
    }

    @Test
    fun `every theme in the set yields at most 64 extras, none overlapping direct`() {
        assumeTrue("ptf-test-set missing: $setDir — skipping", setDir.isDirectory)
        for (name in expected.keys) {
            val file = File(setDir, name)
            if (!file.isFile) continue
            val extras = PtfIcons.extractExtras(assertNotNull(PtfUnpacker.unpack(file.readBytes()), name))
            assertTrue(extras.size <= PtfIcons.MAX_EXTRAS, "$name: ${extras.size} extras")
            assertTrue(extras.keys.none { it in PtfIcons.DIRECT.keys }, "$name: overlaps DIRECT")
            assertTrue(extras.keys.all(PtfIcons::isBody), "$name: only body records")
            extras.values.forEach { assertEquals(it.width, it.height, "$name: extras are padded square") }
        }
    }
}
