package com.playfieldportal.themekit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TS-02: the typed v4 manifest fields (format spec 5.1) and their read/write rules. */
class ManifestV4Test {

    private fun manifest(waveStyle: String = "animated", waveStyleV4: String? = null) =
        PfpThemeManifest(name = "t", accentColor = "#FFFFFF", waveStyle = waveStyle, waveStyleV4 = waveStyleV4)

    // ── Wave resolve matrix ──────────────────────────────────────────────────────────────────

    @Test
    fun `recognized V4 wins over the legacy field`() {
        assertEquals("reduced_static", WaveStyles.resolveExact(manifest("static", "reduced_static")))
        assertEquals("reduced", WaveStyles.resolveExact(manifest("animated", "reduced")))
        assertEquals("animated", WaveStyles.resolveExact(manifest("static", "animated")))
    }

    @Test
    fun `unknown or absent V4 falls back to a recognized legacy value`() {
        assertEquals("static", WaveStyles.resolveExact(manifest("static", null)))
        assertEquals("reduced", WaveStyles.resolveExact(manifest("reduced", "wobbly")))
        assertEquals("static", WaveStyles.resolveExact(manifest("static", "")))
    }

    @Test
    fun `both unknown or absent resolves to animated`() {
        assertEquals("animated", WaveStyles.resolveExact(manifest("wobbly", "wobbly")))
        assertEquals("animated", WaveStyles.resolveExact(manifest("wobbly", null)))
        // legacy never carries reduced_static; an unrecognized-for-legacy value is not trusted
        assertEquals("animated", WaveStyles.resolveExact(manifest("reduced_static", null)))
    }

    @Test
    fun `reduced_static encodes legacy static and the exact value in V4`() {
        assertEquals("static" to "reduced_static", WaveStyles.encode("reduced_static"))
        assertEquals("animated" to "animated", WaveStyles.encode("animated"))
        assertEquals("reduced" to "reduced", WaveStyles.encode("reduced"))
        assertEquals("static" to "static", WaveStyles.encode("static"))
        assertEquals("animated" to "animated", WaveStyles.encode("garbage"))
    }

    // ── Motion crop sanitizer ────────────────────────────────────────────────────────────────

    private fun assertCrop(x: Float, y: Float, w: Float, h: Float, actual: MotionCrop?) {
        val c = assertNotNull(actual)
        assertEquals(x, c.x, 1e-4f)
        assertEquals(y, c.y, 1e-4f)
        assertEquals(w, c.w, 1e-4f)
        assertEquals(h, c.h, 1e-4f)
    }

    @Test
    fun `a valid crop is unchanged`() {
        assertCrop(0.1f, 0f, 0.8f, 1f, MotionCrop(0.1f, 0f, 0.8f, 1f).sanitized())
    }

    @Test
    fun `non-finite members make the crop absent`() {
        assertNull(MotionCrop(Float.NaN, 0f, 1f, 1f).sanitized())
        assertNull(MotionCrop(0f, 0f, Float.POSITIVE_INFINITY, 1f).sanitized())
        assertNull(MotionCrop(0f, Float.NEGATIVE_INFINITY, 1f, 1f).sanitized())
    }

    @Test
    fun `members clamp to the unit range`() {
        assertCrop(0f, 0f, 1f, 1f, MotionCrop(-0.5f, -2f, 5f, 9f).sanitized())
    }

    @Test
    fun `width and height have a minimum`() {
        assertCrop(0.2f, 0.2f, 0.05f, 0.05f, MotionCrop(0.2f, 0.2f, 0f, -1f).sanitized())
    }

    @Test
    fun `overflow shrinks the rect and never shifts it`() {
        assertCrop(0.5f, 0.75f, 0.5f, 0.25f, MotionCrop(0.5f, 0.75f, 0.9f, 0.9f).sanitized())
    }

    @Test
    fun `an origin on the far edge keeps the minimum size inside the frame`() {
        val c = assertNotNull(MotionCrop(1f, 1f, 0.5f, 0.5f).sanitized())
        assertTrue(c.w >= 0.05f - 1e-4f && c.h >= 0.05f - 1e-4f)
        assertTrue(c.x + c.w <= 1f + 1e-4f && c.y + c.h <= 1f + 1e-4f)
    }

    // ── Legibility ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `unknown legibility enum members are treated as absent`() {
        val l = ThemeLegibility(text = "hologram", icon = "contour_auto", solidUnfocusedIcons = true).sanitized()
        assertNull(l.text)
        assertEquals("contour_auto", l.icon)
        assertEquals(true, l.solidUnfocusedIcons)
    }

    @Test
    fun `every documented legibility value survives`() {
        for (t in listOf("auto", "none", "shadow", "outline", "plate")) {
            assertEquals(t, ThemeLegibility(text = t).sanitized().text)
        }
        for (i in listOf("none", "offset_shadow", "contour_dark", "contour_light", "contour_auto")) {
            assertEquals(i, ThemeLegibility(icon = i).sanitized().icon)
        }
    }

    // ── Manifest-level sanitizer and codec reads ─────────────────────────────────────────────

    @Test
    fun `description is clamped to 500 chars`() {
        val m = manifest().copy(description = "x".repeat(900)).sanitized()
        assertEquals(500, m.description?.length)
        assertEquals("short", manifest().copy(description = "short").sanitized().description)
    }

    @Test
    fun `schema version is at least 4 and a fresh manifest carries no v4 extras`() {
        // v5 (the lock screen image) is additive on top of v4; LockScreenCodecTest pins the number.
        assertTrue(PfpThemeManifest.SCHEMA_VERSION >= 4)
        assertEquals("reduced_static", PfpThemeManifest.WAVE_REDUCED_STATIC)
        val m = manifest()
        assertNull(m.author)
        assertNull(m.description)
        assertNull(m.updated)
        assertNull(m.textColorExact)
        assertNull(m.subTextColor)
        assertNull(m.waveStyleV4)
        assertNull(m.legibility)
        assertNull(m.motionCrop)
    }

    // ── Sub text colour ──────────────────────────────────────────────────────────────────────

    @Test
    fun `subTextColor round-trips through the codec`() {
        val written = PfpThemeCodec.write(
            PfpThemeBundle(
                manifest = manifest().copy(textColor = "#FF8800", subTextColor = "#88CCFF"),
                wallpaper = null,
                preview = null,
            ),
        )
        val m = assertNotNull(PfpThemeCodec.read(written)).manifest
        assertEquals("#FF8800", m.textColor)
        assertEquals("#88CCFF", m.subTextColor)
    }

    @Test
    fun `an invalid subTextColor reads as absent and is reported`() {
        for (bad in listOf("auto", "#GGGGGG", "88CCFF", "#88CCFF00", "")) {
            assertNull(manifest().copy(subTextColor = bad).sanitized().subTextColor, bad)
        }
        val result = assertNotNull(
            PfpThemeCodec.readDetailed(
                PfpThemeCodec.write(
                    PfpThemeBundle(manifest = manifest().copy(subTextColor = "teal"), wallpaper = null, preview = null),
                ),
            ),
        )
        assertNull(result.bundle.manifest.subTextColor)
        assertTrue(result.diagnostics.repaired.any { "Sub text color" in it }, result.diagnostics.repaired.toString())
    }

    @Test
    fun `a wrongly typed subTextColor costs only that field`() {
        val json = "{\"manifest\":\"pfptheme\",\"name\":\"T\",\"accentColor\":\"#FFFFFF\"," +
            "\"textColor\":\"#FF8800\",\"subTextColor\":{\"r\":1}}"
        val bytes = java.io.ByteArrayOutputStream().also { bos ->
            java.util.zip.ZipOutputStream(bos).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
                zip.write(json.toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val m = assertNotNull(PfpThemeCodec.read(bytes)).manifest
        assertNull(m.subTextColor)
        assertEquals("#FF8800", m.textColor)
    }

    @Test
    fun `v4 fields round-trip through the codec`() {
        val written = PfpThemeCodec.write(
            PfpThemeBundle(
                manifest = PfpThemeManifest(
                    name = "Full",
                    accentColor = "#3A6FD8",
                    author = "Jane",
                    description = "Neon over rain",
                    updated = "2026-10-02",
                    textColorExact = true,
                    waveStyle = "static",
                    waveStyleV4 = "reduced_static",
                    legibility = ThemeLegibility("outline", "contour_dark", true),
                    motionCrop = MotionCrop(0.1f, 0f, 0.8f, 1f),
                ),
                wallpaper = null,
                preview = null,
            ),
        )
        val m = assertNotNull(PfpThemeCodec.read(written)).manifest
        assertEquals("Jane", m.author)
        assertEquals("Neon over rain", m.description)
        assertEquals("2026-10-02", m.updated)
        assertEquals(true, m.textColorExact)
        assertEquals("reduced_static", WaveStyles.resolveExact(m))
        assertEquals(ThemeLegibility("outline", "contour_dark", true), m.legibility)
        assertEquals(MotionCrop(0.1f, 0f, 0.8f, 1f), m.motionCrop)
    }

    @Test
    fun `writing stamps the current schema and derives the legacy wave from the exact one`() {
        val written = PfpThemeCodec.write(
            PfpThemeBundle(
                manifest = manifest("animated", "reduced_static").copy(schemaVersion = 3),
                wallpaper = null,
                preview = null,
            ),
        )
        val m = assertNotNull(PfpThemeCodec.read(written)).manifest
        assertEquals(PfpThemeManifest.SCHEMA_VERSION, m.schemaVersion)
        assertEquals("static", m.waveStyle)
        assertEquals("reduced_static", m.waveStyleV4)
    }

    @Test
    fun `writing leaves a manifest without an exact wave alone`() {
        val written = PfpThemeCodec.write(
            PfpThemeBundle(manifest = manifest("reduced"), wallpaper = null, preview = null),
        )
        val m = assertNotNull(PfpThemeCodec.read(written)).manifest
        assertEquals("reduced", m.waveStyle)
        assertNull(m.waveStyleV4)
    }

    @Test
    fun `reading sanitizes legibility, crop and description`() {
        val written = PfpThemeCodec.write(
            PfpThemeBundle(
                manifest = PfpThemeManifest(
                    name = "Dirty",
                    accentColor = "#000000",
                    description = "d".repeat(600),
                    legibility = ThemeLegibility("hologram", "none"),
                    motionCrop = MotionCrop(0.5f, 0f, 0.9f, 1f),
                ),
                wallpaper = null,
                preview = null,
            ),
        )
        val m = assertNotNull(PfpThemeCodec.read(written)).manifest
        assertEquals(500, m.description?.length)
        assertNull(m.legibility?.text)
        assertEquals("none", m.legibility?.icon)
        assertEquals(0.5f, m.motionCrop?.w ?: -1f, 1e-4f)
    }

    // ── Golden fixtures ──────────────────────────────────────────────────────────────────────

    @Test
    fun `old fixtures read with the v4 fields absent`() {
        for (bytes in listOf(ThemeFixtures.v1(), ThemeFixtures.v2(), ThemeFixtures.v3())) {
            val m = assertNotNull(PfpThemeCodec.read(bytes)).manifest
            assertNull(m.author)
            assertNull(m.description)
            assertNull(m.updated)
            assertNull(m.textColorExact)
            assertNull(m.subTextColor)
            assertNull(m.waveStyleV4)
            assertNull(m.legibility)
            assertNull(m.motionCrop)
        }
        assertEquals("reduced", WaveStyles.resolveExact(assertNotNull(PfpThemeCodec.read(ThemeFixtures.v1())).manifest))
        assertEquals("static", WaveStyles.resolveExact(assertNotNull(PfpThemeCodec.read(ThemeFixtures.v2())).manifest))
    }

    @Test
    fun `v4 fixture reads its typed fields`() {
        val m = assertNotNull(PfpThemeCodec.read(ThemeFixtures.v4())).manifest
        assertEquals("Jane", m.author)
        assertEquals("Neon over rain", m.description)
        assertEquals("2026-10-02", m.updated)
        assertEquals(false, m.textColorExact)
        assertEquals("reduced_static", WaveStyles.resolveExact(m))
        assertEquals(ThemeLegibility("auto", "contour_auto", false), m.legibility)
        assertEquals(MotionCrop(0.1f, 0f, 0.8f, 1f), m.motionCrop)
    }

    @Test
    fun `future fixture degrades unknown values`() {
        val m = assertNotNull(PfpThemeCodec.read(ThemeFixtures.future())).manifest
        assertEquals("animated", WaveStyles.resolveExact(m))
        assertNull(m.legibility?.text)
        assertEquals("contour_auto", m.legibility?.icon)
    }
}
