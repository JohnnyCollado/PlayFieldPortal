package com.playfieldportal.themekit

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PfpThemeCodecTest {

    private val manifest = PfpThemeManifest(
        name = "Classy Pink",
        accentColor = "#FF72B1",
        layout = XmbLayoutSpec(barTopFraction = 0.11f),
        source = PfpThemeSource(type = PfpThemeSource.TYPE_PTF_IMPORT, file = "classypink.ptf", firmware = "5.00"),
        created = "2026-07-06",
    )

    @Test
    fun `round-trips a full bundle`() {
        val bundle = PfpThemeBundle(
            manifest = manifest,
            wallpaper = ByteArray(512) { it.toByte() },
            preview = ByteArray(256) { (it * 3).toByte() },
        )
        val decoded = assertNotNull(PfpThemeCodec.read(PfpThemeCodec.write(bundle)))
        assertEquals(bundle, decoded)
    }

    @Test
    fun `round-trips a wave-only theme without wallpaper`() {
        val bundle = PfpThemeBundle(manifest = manifest.copy(name = "Wave Only"), wallpaper = null, preview = ByteArray(64))
        val decoded = assertNotNull(PfpThemeCodec.read(PfpThemeCodec.write(bundle)))
        assertNull(decoded.wallpaper)
        assertEquals("Wave Only", decoded.manifest.name)
    }

    @Test
    fun `layout spec survives the manifest round-trip`() {
        val bundle = PfpThemeBundle(manifest = manifest, wallpaper = null, preview = null)
        val decoded = assertNotNull(PfpThemeCodec.read(PfpThemeCodec.write(bundle)))
        assertEquals(0.11f, assertNotNull(decoded.manifest.layout).barTopFraction)
    }

    @Test
    fun `rejects a zip without manifest`() {
        val zip = ByteArrayOutputStream().also { baos ->
            ZipOutputStream(baos).use { z ->
                z.putNextEntry(ZipEntry("wallpaper.png")); z.write(ByteArray(8)); z.closeEntry()
            }
        }.toByteArray()
        assertNull(PfpThemeCodec.read(zip))
    }

    @Test
    fun `rejects a manifest of a different type`() {
        val zip = ByteArrayOutputStream().also { baos ->
            ZipOutputStream(baos).use { z ->
                z.putNextEntry(ZipEntry("manifest.json"))
                z.write("""{"manifest":"other","schemaVersion":1,"name":"x","accentColor":"#FFFFFF"}""".toByteArray())
                z.closeEntry()
            }
        }.toByteArray()
        assertNull(PfpThemeCodec.read(zip))
    }

    @Test
    fun `rejects garbage bytes`() {
        assertNull(PfpThemeCodec.read("definitely not a zip".toByteArray()))
    }

    @Test
    fun `ignores unknown zip entries for forward compatibility`() {
        val base = PfpThemeCodec.write(PfpThemeBundle(manifest, null, null))
        // Re-zip with an extra entry a future schema might add.
        val withExtra = ByteArrayOutputStream().also { baos ->
            ZipOutputStream(baos).use { z ->
                java.util.zip.ZipInputStream(base.inputStream()).use { src ->
                    while (true) {
                        val e = src.nextEntry ?: break
                        z.putNextEntry(ZipEntry(e.name)); z.write(src.readBytes()); z.closeEntry()
                    }
                }
                z.putNextEntry(ZipEntry("sounds/cursor.at3")); z.write(ByteArray(4)); z.closeEntry()
            }
        }.toByteArray()
        assertNotNull(PfpThemeCodec.read(withExtra))
    }
}
