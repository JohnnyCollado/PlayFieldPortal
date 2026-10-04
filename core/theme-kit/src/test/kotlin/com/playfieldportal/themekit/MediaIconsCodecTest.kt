package com.playfieldportal.themekit

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `mediaicons/<platformId>.<png|gif>`: physical-media art (the disc, cart or UMD a game shows in
 * Physical Media mode). The Studio wrote these as passthrough before the launcher knew them; they
 * are now a typed part of the bundle, gated by [CustomizableIcons]' `physmedia_` keys.
 */
class MediaIconsCodecTest {

    private val manifest = PfpThemeManifest(name = "Media", accentColor = "#112233")
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(8) { it.toByte() }
    private val gif = "GIF89a".toByteArray() + ByteArray(16) { it.toByte() }

    /** A bundle carrying [art] (platform id → image) as physical-media art. */
    private fun bundle(art: Map<String, ThemeImage>) =
        PfpThemeBundle(manifest = manifest, wallpaper = null, preview = null, icons = mediaArt(*art.toList().toTypedArray()))

    @Test
    fun `media icons round-trip under mediaicons`() {
        val written = PfpThemeCodec.write(
            bundle(mapOf("psp" to ThemeImage(png, "png"), "snes" to ThemeImage(gif, "gif"))),
        )

        assertEquals(listOf("manifest.json", "mediaicons/psp.png", "mediaicons/snes.gif"), zipNames(written))
        val read = assertNotNull(PfpThemeCodec.readDetailed(written))
        assertEquals(setOf("psp", "snes"), read.bundle.mediaArt.keys)
        assertContentEquals(gif, read.bundle.mediaArt["snes"]!!.bytes)
        assertEquals("gif", read.bundle.mediaArt["snes"]!!.extension)
        assertTrue(read.bundle.passthrough.isEmpty(), "registered media icons are never passthrough")
    }

    @Test
    fun `a Studio bundle that carried them as passthrough now reads them typed`() {
        val legacy = zip(
            "manifest.json" to """{"manifest":"pfptheme","schemaVersion":4,"name":"Old","accentColor":"#000000"}""".toByteArray(),
            "mediaicons/psx.png" to png,
        )
        val read = assertNotNull(PfpThemeCodec.readDetailed(legacy))
        assertEquals(setOf("psx"), read.bundle.mediaArt.keys)
        assertTrue(read.bundle.passthrough.none { it.name == "mediaicons/psx.png" })
    }

    @Test
    fun `write drops ids with no physical media and refused extensions`() {
        val written = PfpThemeCodec.write(
            bundle(
                mapOf(
                    "android" to ThemeImage(png, "png"),
                    "not_a_console" to ThemeImage(png, "png"),
                    "psx" to ThemeImage(png, "jpg"),
                    "nes" to ThemeImage(png, "png"),
                ),
            ),
        )
        assertEquals(listOf("manifest.json", "mediaicons/nes.png"), zipNames(written))
    }

    @Test
    fun `an unregistered mediaicons name stays passthrough`() {
        val bytes = zip(
            "manifest.json" to """{"manifest":"pfptheme","schemaVersion":4,"name":"X","accentColor":"#000000"}""".toByteArray(),
            "mediaicons/android.png" to png,
        )
        val read = assertNotNull(PfpThemeCodec.readDetailed(bytes))
        assertTrue(read.bundle.mediaArt.isEmpty())
        assertEquals(listOf("mediaicons/android.png"), read.bundle.passthrough.map { it.name })
    }

    @Test
    fun `a registered media id with a refused extension stays passthrough, as in icons and sysicons`() {
        val bytes = zip(
            "manifest.json" to """{"manifest":"pfptheme","schemaVersion":4,"name":"X","accentColor":"#000000"}""".toByteArray(),
            "mediaicons/psp.bmp" to png,
            "sysicons/psx.bmp" to png,
            "icons/catbar_games.bmp" to png,
        )
        val read = assertNotNull(PfpThemeCodec.readDetailed(bytes))
        assertTrue(read.bundle.icons.isEmpty())
        assertEquals(
            setOf("mediaicons/psp.bmp", "sysicons/psx.bmp", "icons/catbar_games.bmp"),
            read.bundle.passthrough.map { it.name }.toSet(),
        )
    }

    @Test
    fun `an over-cap media icon is dropped and reported`() {
        val bytes = zip(
            "manifest.json" to """{"manifest":"pfptheme","schemaVersion":4,"name":"X","accentColor":"#000000"}""".toByteArray(),
            "mediaicons/psp.png" to ByteArray(PfpThemeCodec.MAX_ICON_BYTES + 1),
        )
        val read = assertNotNull(PfpThemeCodec.readDetailed(bytes))
        assertTrue(read.bundle.mediaArt.isEmpty())
        assertEquals(listOf(DroppedEntry("mediaicons/psp.png", DropReason.OVER_CAP)), read.diagnostics.dropped)
    }

    @Test
    fun `a passthrough entry cannot shadow a registered media icon`() {
        val written = PfpThemeCodec.write(
            PfpThemeBundle(
                manifest = manifest,
                wallpaper = null,
                preview = null,
                icons = mediaArt("psp" to ThemeImage(png, "png")),
                passthrough = listOf(PassthroughEntry.ofBytes("mediaicons/psp.png", gif)),
            ),
        )
        assertEquals(listOf("manifest.json", "mediaicons/psp.png"), zipNames(written))
        assertContentEquals(png, zipEntry(written, "mediaicons/psp.png"))
    }

    @Test
    fun `equality covers media icons`() {
        val a = bundle(mapOf("psp" to ThemeImage(png, "png")))
        assertEquals(a, bundle(mapOf("psp" to ThemeImage(png.copyOf(), "png"))))
        assertEquals(a.hashCode(), bundle(mapOf("psp" to ThemeImage(png.copyOf(), "png"))).hashCode())
        assertNotEquals(a, bundle(mapOf("psp" to ThemeImage(gif, "gif"))))
        assertNotEquals(a, bundle(emptyMap()))
    }

    private fun zipNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) { names += e.name; e = z.nextEntry }
        }
        return names
    }

    private fun zipEntry(bytes: ByteArray, name: String): ByteArray? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name == name) return z.readBytes()
                e = z.nextEntry
            }
        }
        return null
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { baos ->
            ZipOutputStream(baos).use { z ->
                for ((name, data) in entries) {
                    z.putNextEntry(ZipEntry(name))
                    z.write(data)
                    z.closeEntry()
                }
            }
        }.toByteArray()
}
