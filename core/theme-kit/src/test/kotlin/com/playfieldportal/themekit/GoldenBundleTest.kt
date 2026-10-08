package com.playfieldportal.themekit

import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Characterization of today's read behaviour for every historical schema, through both the
 * production [PfpThemeCodec] and the frozen [V3EraReader]. These pin what readers do *now*; later
 * format tasks update the expectations deliberately, in the open, when behaviour is meant to move.
 */
class GoldenBundleTest {

    private val fixtures = mapOf(
        "v1" to ThemeFixtures.v1(),
        "v2" to ThemeFixtures.v2(),
        "v3" to ThemeFixtures.v3(),
        "v4" to ThemeFixtures.v4(),
        "future" to ThemeFixtures.future(),
    )

    private fun codec(name: String): PfpThemeBundle =
        assertNotNull(PfpThemeCodec.read(fixtures.getValue(name)), "$name must read through PfpThemeCodec")

    private fun frozen(name: String): V3EraReader.Result =
        assertNotNull(V3EraReader.read(fixtures.getValue(name)), "$name must read through V3EraReader")

    // -- frozen snapshot sanity ------------------------------------------------------------

    @Test
    fun `frozen reader key lists match the 52 and 40 of the v3 era`() {
        assertEquals(52, V3EraReader.ICON_KEYS.size)
        assertEquals(40, V3EraReader.SYSICON_IDS.size)
        // TS-06 relaxed the icons tripwire: production grew past 52 slots, but every frozen v3 key
        // must still be a slot (never renamed or dropped, A3) or be retired onto one that is, so
        // an old bundle's art still lands (IconSlots.RETIRED).
        val live = IconSlots.ALL.map { it.key }.toSet()
        assertTrue(live.containsAll(V3EraReader.ICON_KEYS - IconSlots.RETIRED.keys))
        V3EraReader.ICON_KEYS.filter { it !in live }.forEach { key ->
            val target = assertNotNull(IconSlots.RETIRED[key], "$key was dropped without retiring it")
            assertTrue(CustomizableIcons.isValidKey(target), "$key retires onto $target, which is not a slot")
        }
        assertEquals(V3EraReader.SYSICON_IDS, SYSICON_PLATFORM_IDS.toSet())
    }

    @Test
    fun `every fixture reads non-null through both readers`() {
        for (name in fixtures.keys) {
            codec(name)
            frozen(name)
        }
    }

    // -- v1 ----------------------------------------------------------------------------------

    @Test
    fun `v1 reads wallpaper preview and manifest with defaults for later fields`() {
        val b = codec("v1")
        assertEquals(1, b.manifest.schemaVersion)
        assertEquals("Golden V1", b.manifest.name)
        assertEquals("#3A6FD8", b.manifest.accentColor)
        assertEquals("auto", b.manifest.iconColor)
        assertEquals("auto", b.manifest.textColor)
        assertEquals(PfpThemeManifest.WAVE_REDUCED, b.manifest.waveStyle)
        assertEquals(0.2f, b.manifest.layout?.barTopFraction)
        assertEquals(PfpThemeSource.TYPE_PTF_IMPORT, b.manifest.source?.type)
        assertEquals("classic.ptf", b.manifest.source?.file)
        assertEquals("6.60", b.manifest.source?.firmware)
        assertEquals("2026-07-06", b.manifest.created)
        assertContentEquals(ThemeFixtures.WALLPAPER, b.wallpaper)
        assertContentEquals(ThemeFixtures.PREVIEW, b.preview)
        assertTrue(b.icons.isEmpty())
        assertTrue(b.consoleArt.isEmpty())
        assertNull(b.motion)

        val f = frozen("v1")
        assertEquals(1, f.manifest.schemaVersion)
        assertEquals("reduced", f.manifest.waveStyle)
        assertEquals("auto", f.manifest.textColor)
        assertContentEquals(ThemeFixtures.WALLPAPER, f.wallpaper)
        assertContentEquals(ThemeFixtures.PREVIEW, f.preview)
        assertTrue(f.icons.isEmpty() && f.sysicons.isEmpty())
        assertNull(f.motionExtension)
    }

    // -- v2 ----------------------------------------------------------------------------------

    @Test
    fun `v2 reads png icons`() {
        val b = codec("v2")
        assertEquals(2, b.manifest.schemaVersion)
        assertEquals("#FFFFFF", b.manifest.iconColor)
        assertEquals(PfpThemeManifest.WAVE_STATIC, b.manifest.waveStyle)
        assertNull(b.manifest.layout)
        assertEquals(setOf("catbar_games", "item_add"), b.icons.keys)
        assertEquals(ThemeImage(ThemeFixtures.ICON_PNG, "png"), b.icons["catbar_games"])
        assertTrue(b.consoleArt.isEmpty())
        assertNull(b.motion)

        val f = frozen("v2")
        assertEquals(mapOf("catbar_games" to "png", "item_add" to "png"), f.icons)
        assertEquals("#FFFFFF", f.manifest.iconColor)
    }

    // -- v3 ----------------------------------------------------------------------------------

    @Test
    fun `v3 reads gif icon sysicon motion textColor and full layout`() {
        val b = codec("v3")
        assertEquals(3, b.manifest.schemaVersion)
        assertEquals("#EEEEEE", b.manifest.textColor)
        assertEquals(PfpThemeManifest.WAVE_ANIMATED, b.manifest.waveStyle)
        assertEquals(0.15f, b.manifest.layout?.barTopFraction)
        assertEquals(0.4f, b.manifest.layout?.previousItemRiseRows)
        assertEquals(setOf("catbar_games", "status_bluetooth"), b.slotIcons.keys)
        assertEquals(ThemeImage(ThemeFixtures.ICON_GIF, "gif"), b.icons["status_bluetooth"])
        assertEquals(setOf("psx"), b.consoleArt.keys)
        assertEquals("mp4", b.motion?.extension)
        val motion = ByteArrayOutputStream().also { b.motion!!.copyTo(it) }.toByteArray()
        assertContentEquals(ThemeFixtures.MOTION_MP4, motion)

        val f = frozen("v3")
        assertEquals("#EEEEEE", f.manifest.textColor)
        assertEquals(0.15f, f.manifest.layout?.barTopFraction)
        assertEquals(mapOf("catbar_games" to "png", "status_bluetooth" to "gif"), f.icons)
        assertEquals(mapOf("psx" to "png"), f.sysicons)
        assertEquals("mp4", f.motionExtension)
    }

    // -- v4 (today's readers know none of the additions) ---------------------------------

    @Test
    fun `v4 yields its v3 subset and drops v4-only entries today`() {
        val b = codec("v4")
        assertEquals(4, b.manifest.schemaVersion)
        // The legacy wave field is what today's reader sees; waveStyleV4 is an unknown key.
        assertEquals(PfpThemeManifest.WAVE_STATIC, b.manifest.waveStyle)
        assertEquals("auto", b.manifest.textColor)
        assertEquals("2026-07-06", b.manifest.created)
        assertContentEquals(ThemeFixtures.WALLPAPER, b.wallpaper)
        // status_wifi is a v4-only key; TS-06 registered it, so the current reader keeps it
        // (the frozen v3-era reader below still drops it).
        assertEquals(setOf("catbar_games", "status_wifi"), b.slotIcons.keys)
        assertEquals(setOf("psx"), b.consoleArt.keys)
        assertEquals("mp4", b.motion?.extension)

        val f = frozen("v4")
        assertEquals(4, f.manifest.schemaVersion)
        assertEquals("static", f.manifest.waveStyle)
        assertEquals(mapOf("catbar_games" to "png"), f.icons)
        assertEquals(mapOf("psx" to "png"), f.sysicons)
        assertEquals("mp4", f.motionExtension)
        assertContentEquals(ThemeFixtures.WALLPAPER, f.wallpaper)
        assertContentEquals(ThemeFixtures.PREVIEW, f.preview)
    }

    // -- future --------------------------------------------------------------------------------

    @Test
    fun `future file opens with the known subset and ignores everything unknown`() {
        val b = codec("future")
        assertEquals(99, b.manifest.schemaVersion)
        assertEquals("Golden Future", b.manifest.name)
        // An unknown enum value is carried through as the raw string today (no normalisation).
        assertEquals("wobbly", b.manifest.waveStyle)
        assertContentEquals(ThemeFixtures.WALLPAPER, b.wallpaper)
        assertNull(b.preview)
        assertEquals(setOf("catbar_games"), b.slotIcons.keys, "unregistered icon key dropped")
        assertEquals(setOf("psx"), b.consoleArt.keys, "unregistered console key dropped")
        assertNull(b.motion)

        val f = frozen("future")
        assertEquals(99, f.manifest.schemaVersion)
        assertEquals("wobbly", f.manifest.waveStyle)
        assertEquals(mapOf("catbar_games" to "png"), f.icons)
        assertEquals(mapOf("psx" to "png"), f.sysicons)
        assertNull(f.preview)
        assertNull(f.motionExtension)
    }

    @Test
    fun `unknown manifest keys and entries are retained by the codec since TS-03`() {
        // Was a characterization of the gap TS-03 closes; now flipped deliberately: a
        // read-then-write keeps unknown data.
        val rewritten = PfpThemeCodec.write(codec("future"))
        val names = ZipInputStream(rewritten.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name }.toList()
        }
        assertTrue("extras/thing.bin" in names)
        assertTrue("readme.txt" in names)
        val manifestJson = ZipInputStream(rewritten.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name == "manifest.json" }
            zip.readBytes().decodeToString()
        }
        assertTrue("someFutureField" in manifestJson)
        assertTrue("anotherFutureKey" in manifestJson)
    }
}
