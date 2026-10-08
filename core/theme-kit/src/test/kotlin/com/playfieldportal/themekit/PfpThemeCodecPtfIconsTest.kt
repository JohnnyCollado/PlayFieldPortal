package com.playfieldportal.themekit

import com.playfieldportal.themekit.PtfIcons.SlotRef
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `ptficons/<group>_<index>.png` bundle family: the PTF body images a theme keeps beyond the
 * slots it maps. Underscore names on purpose, so older readers keep them as safe passthrough.
 */
class PfpThemeCodecPtfIconsTest {

    private val manifest = PfpThemeManifest(name = "Ptf Extras", accentColor = "#3366CC")

    private fun png(seed: Int) = ByteArray(16) { (it + seed).toByte() }

    private fun bundle(ptf: Map<SlotRef, ThemeImage>, icons: Map<String, ThemeImage> = emptyMap()) =
        PfpThemeBundle(manifest = manifest, wallpaper = null, preview = null, icons = icons, ptfIcons = ptf)

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"manifest":"pfptheme","name":"Z","accentColor":"#112233"}""".toByteArray())
            zip.closeEntry()
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun entryNames(bytes: ByteArray): List<String> =
        ZipInputStream(bytes.inputStream()).use { zin -> generateSequence { zin.nextEntry?.name }.toList() }

    private fun PassthroughEntry.bytes(): ByteArray = ByteArrayOutputStream().also { copyTo(it) }.toByteArray()

    @Test
    fun `ptfIcons round-trip through write and read`() {
        val ptf = mapOf(
            SlotRef(2, 5) to ThemeImage(png(1), "png"),
            SlotRef(3, 8) to ThemeImage(png(2), "png"),
            SlotRef(4, 0) to ThemeImage(png(3), "png"),
        )

        val decoded = assertNotNull(PfpThemeCodec.read(PfpThemeCodec.write(bundle(ptf))))

        assertEquals(ptf, decoded.ptfIcons)
        assertTrue(decoded.passthrough.isEmpty())
    }

    @Test
    fun `identical bundles write byte-identical output`() {
        val a = mapOf(SlotRef(3, 8) to ThemeImage(png(2), "png"), SlotRef(2, 5) to ThemeImage(png(1), "png"))
        val b = mapOf(SlotRef(2, 5) to ThemeImage(png(1), "png"), SlotRef(3, 8) to ThemeImage(png(2), "png"))

        assertTrue(PfpThemeCodec.write(bundle(a)).contentEquals(PfpThemeCodec.write(bundle(b))))
    }

    @Test
    fun `entries are written sorted by name after the icon folders`() {
        val ptf = mapOf(SlotRef(3, 8) to ThemeImage(png(2), "png"), SlotRef(2, 10) to ThemeImage(png(1), "png"))
        val bytes = PfpThemeCodec.write(bundle(ptf, mapOf("catbar_games" to ThemeImage(png(9), "png"))))

        assertEquals(
            listOf("manifest.json", "icons/catbar_games.png", "ptficons/2_10.png", "ptficons/3_8.png"),
            entryNames(bytes),
        )
    }

    @Test
    fun `ungated names are not typed and come back as passthrough`() {
        val bytes = zipOf(
            "ptficons/3_9.png" to png(1),   // odd record: a focus variant
            "ptficons/5_0.png" to png(2),   // not an icon group
            "ptficons/2_1.gif" to png(3),   // not png
            "ptficons/2_256.png" to png(4), // index out of range
            "ptficons/2_06.png" to png(5),  // not the canonical name
            "ptficons/2_6.png" to png(6),   // gated: typed
        )

        val decoded = assertNotNull(PfpThemeCodec.read(bytes))

        assertEquals(setOf(SlotRef(2, 6)), decoded.ptfIcons.keys)
        assertEquals(
            setOf("ptficons/3_9.png", "ptficons/5_0.png", "ptficons/2_1.gif", "ptficons/2_256.png", "ptficons/2_06.png"),
            decoded.passthrough.map { it.name }.toSet(),
        )
        assertTrue(decoded.passthrough.first { it.name == "ptficons/3_9.png" }.bytes().contentEquals(png(1)))
    }

    @Test
    fun `ungated names survive a rewrite and a typed name cannot be smuggled in as passthrough`() {
        val read = assertNotNull(PfpThemeCodec.read(zipOf("ptficons/3_9.png" to png(1))))
        val clash = read.copy(
            passthrough = read.passthrough +
                PfpThemeCodec.passthroughFrom({ zipOf("ptficons/2_5.png" to png(7)).inputStream() }, "ptficons/2_5.png"),
        )

        val names = entryNames(PfpThemeCodec.write(clash))

        assertEquals(1, names.count { it == "ptficons/3_9.png" })
        assertEquals(0, names.count { it == "ptficons/2_5.png" })
    }

    @Test
    fun `gated but non-png images are skipped on write`() {
        val ptf = mapOf(SlotRef(2, 5) to ThemeImage(png(1), "gif"), SlotRef(2, 6) to ThemeImage(png(2), "PNG"))

        val decoded = assertNotNull(PfpThemeCodec.read(PfpThemeCodec.write(bundle(ptf))))

        assertEquals(setOf(SlotRef(2, 6)), decoded.ptfIcons.keys)
    }

    @Test
    fun `refs that are not body images are skipped on write`() {
        val ptf = mapOf(
            SlotRef(3, 9) to ThemeImage(png(1), "png"),
            SlotRef(5, 0) to ThemeImage(png(2), "png"),
            SlotRef(2, 256) to ThemeImage(png(3), "png"),
            SlotRef(2, 4) to ThemeImage(png(4), "png"),
        )

        val decoded = assertNotNull(PfpThemeCodec.read(PfpThemeCodec.write(bundle(ptf))))

        assertEquals(setOf(SlotRef(2, 4)), decoded.ptfIcons.keys)
    }

    @Test
    fun `writing 70 entries keeps the lowest 64`() {
        val ptf = (0 until 70).associate { SlotRef(2, it) to ThemeImage(png(it), "png") }

        val decoded = assertNotNull(PfpThemeCodec.read(PfpThemeCodec.write(bundle(ptf))))

        assertEquals((0 until 64).map { SlotRef(2, it) }.toSet(), decoded.ptfIcons.keys)
    }

    @Test
    fun `reading 70 entries keeps 64 and reports the rest over cap`() {
        val bytes = zipOf(*(0 until 70).map { "ptficons/2_$it.png" to png(it) }.toTypedArray())

        val result = assertNotNull(PfpThemeCodec.readDetailed(bytes))

        assertEquals(64, result.bundle.ptfIcons.size)
        assertEquals(6, result.diagnostics.dropped.count { it.reason == DropReason.OVER_CAP })
    }

    @Test
    fun `an entry over MAX_ICON_BYTES is dropped as over cap`() {
        val big = ByteArray(PfpThemeCodec.MAX_ICON_BYTES + 1)
        val bytes = zipOf("ptficons/2_5.png" to big, "ptficons/2_6.png" to png(1))

        val result = assertNotNull(PfpThemeCodec.readDetailed(bytes))

        assertEquals(setOf(SlotRef(2, 6)), result.bundle.ptfIcons.keys)
        assertEquals(listOf(DroppedEntry("ptficons/2_5.png", DropReason.OVER_CAP)), result.diagnostics.dropped)
    }

    @Test
    fun `every written name passes the passthrough name rule`() {
        val ptf = mapOf(
            SlotRef(2, 0) to ThemeImage(png(1), "png"),
            SlotRef(3, 44) to ThemeImage(png(2), "png"),
            SlotRef(4, 254) to ThemeImage(png(3), "png"),
        )

        val names = entryNames(PfpThemeCodec.write(bundle(ptf))).filter { it.startsWith("ptficons/") }

        assertEquals(3, names.size)
        assertTrue(names.all { PassthroughNames.isSafe(it) }, names.toString())
    }

    @Test
    fun `upgrade preserves ptfIcons`() {
        val ptf = mapOf(SlotRef(2, 5) to ThemeImage(png(1), "png"))

        val upgraded = ThemeUpgrade.upgrade(bundle(ptf), "2026-10-07")

        assertEquals(ptf, upgraded.ptfIcons)
    }

    @Test
    fun `ptfIcons take part in equality`() {
        val a = bundle(mapOf(SlotRef(2, 5) to ThemeImage(png(1), "png")))
        val same = bundle(mapOf(SlotRef(2, 5) to ThemeImage(png(1), "png")))
        val other = bundle(mapOf(SlotRef(2, 5) to ThemeImage(png(2), "png")))

        assertEquals(a, same)
        assertEquals(a.hashCode(), same.hashCode())
        assertTrue(a != other)
    }

    @Test
    fun `a worst-case bundle with 64 extras still reads`() {
        val icons = CustomizableIcons.ALL.associate { it.key to ThemeImage(png(1), "png") }
        val media = ThemeMediaSlots.ALL.associate { slot ->
            slot.key to ThemeMotion.ofBytes(png(2), slot.extensions.first())
        }
        val ptf = (0 until 64).associate { SlotRef(2, it) to ThemeImage(png(it), "png") }
        val full = PfpThemeBundle(
            manifest = manifest,
            wallpaper = png(3),
            preview = png(4),
            icons = icons,
            motion = ThemeMotion.ofBytes(png(5), "mp4"),
            media = media,
            lockScreen = png(6),
            ptfIcons = ptf,
        )

        val written = PfpThemeCodec.write(full)
        val decoded = assertNotNull(PfpThemeCodec.read(written))

        val iconEntries = entryNames(written).count {
            it.startsWith("icons/") || it.startsWith("sysicons/") || it.startsWith("mediaicons/")
        }
        assertEquals(64, decoded.ptfIcons.size)
        assertEquals(iconEntries, decoded.icons.size)
        assertEquals(media.keys, decoded.media.keys)
        assertTrue(entryNames(written).size <= PfpThemeCodec.BUNDLE_LIMITS.maxEntries)
    }
}
