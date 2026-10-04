package com.playfieldportal.themekit

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A4: themes may carry art for the console extras; pre-v4 readers must simply not see them. */
class SysiconExtrasTest {

    private val png = ThemeFixtures.SYSICON_PNG

    @Test
    fun `codec round-trips sysicons cps1 and default`() {
        val bundle = PfpThemeBundle(
            manifest = PfpThemeManifest(name = "Extras", accentColor = "#FF72B1"),
            wallpaper = null,
            preview = null,
            icons = consoleArt("cps1" to ThemeImage(png, "png"), "default" to ThemeImage(png, "png")),
        )
        val decoded = assertNotNull(PfpThemeCodec.read(PfpThemeCodec.write(bundle)))
        assertEquals(setOf("cps1", "default"), decoded.consoleArt.keys)
    }

    @Test
    fun `v3-era reader drops the extra console entries`() {
        val bytes = zip(
            "manifest.json" to """{"schemaVersion":4,"name":"x","accentColor":"#FF72B1"}""".toByteArray(),
            "sysicons/cps1.png" to png,
            "sysicons/default.png" to png,
            "sysicons/psx.png" to png,
        )
        val frozen = assertNotNull(V3EraReader.read(bytes))
        assertEquals(setOf("psx"), frozen.sysicons.keys)
        val current = assertNotNull(PfpThemeCodec.read(bytes))
        assertEquals(setOf("psx", "cps1", "default"), current.consoleArt.keys)
        assertTrue(V3EraReader.SYSICON_IDS.none { it in SYSICON_EXTRA_IDS })
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        }
        return out.toByteArray()
    }
}
