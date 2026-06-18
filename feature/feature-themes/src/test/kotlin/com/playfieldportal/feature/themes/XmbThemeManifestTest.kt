package com.playfieldportal.feature.themes

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

private val json = Json { ignoreUnknownKeys = true }

class XmbThemeManifestTest {

    @Test
    fun `round-trip serialization preserves all fields`() {
        val original = XmbThemeManifest(
            formatVersion  = 1,
            id             = "test_theme",
            name           = "Test Theme",
            author         = "Tester",
            version        = "2.0",
            waveColor      = "#1A2B3C",
            waveOpacity    = 0.5f,
            waveSpeed      = 1.5f,
            waveAmplitude  = 0.8f,
            accentColor    = "#FF0000",
            textColor      = "#AABBCC",
            hasBackground  = true,
            hasBootAnimation = false,
            hasSoundPack   = true,
            fontKey        = "retro",
        )

        val encoded = json.encodeToString(XmbThemeManifest.serializer(), original)
        val decoded = json.decodeFromString(XmbThemeManifest.serializer(), encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun `default values applied when optional fields omitted`() {
        val minimal = """{"id":"minimal","name":"Minimal"}"""
        val manifest = json.decodeFromString(XmbThemeManifest.serializer(), minimal)

        assertEquals(THEME_FORMAT_VERSION, manifest.formatVersion)
        assertEquals("minimal", manifest.id)
        assertEquals("Minimal", manifest.name)
        assertNull(manifest.author)
        assertEquals("1.0", manifest.version)
        assertEquals("#0055AA", manifest.waveColor)
        assertEquals(0.7f, manifest.waveOpacity, 0.001f)
        assertEquals(1.0f, manifest.waveSpeed, 0.001f)
        assertEquals(1.0f, manifest.waveAmplitude, 0.001f)
        assertEquals("#FFFFFF", manifest.accentColor)
        assertEquals("#FFFFFF", manifest.textColor)
        assertEquals("system_default", manifest.fontKey)
        assertFalse(manifest.hasBackground)
        assertFalse(manifest.hasBootAnimation)
        assertFalse(manifest.hasSoundPack)
    }

    @Test
    fun `format version is serialized as format_version snake_case key`() {
        val manifest = XmbThemeManifest(id = "x", name = "X", formatVersion = 99)
        val encoded = json.encodeToString(XmbThemeManifest.serializer(), manifest)
        assertTrue("Expected format_version key in JSON", encoded.contains("\"format_version\""))
        assertTrue("Expected value 99 in JSON", encoded.contains("99"))
        assertFalse("Should not contain formatVersion camelCase", encoded.contains("\"formatVersion\""))
    }

    @Test
    fun `wave_color and accent_color use snake_case keys`() {
        val manifest = XmbThemeManifest(id = "x", name = "X")
        val encoded = json.encodeToString(XmbThemeManifest.serializer(), manifest)
        assertTrue(encoded.contains("\"wave_color\""))
        assertTrue(encoded.contains("\"accent_color\""))
        assertTrue(encoded.contains("\"text_color\""))
        assertTrue(encoded.contains("\"has_background\""))
        assertTrue(encoded.contains("\"has_boot_animation\""))
        assertTrue(encoded.contains("\"has_sound_pack\""))
        assertTrue(encoded.contains("\"font_key\""))
    }

    @Test
    fun `unknown future fields are ignored on decode`() {
        val withExtra = """{"id":"t","name":"T","unknown_future_field":"value","another":42}"""
        val manifest = json.decodeFromString(XmbThemeManifest.serializer(), withExtra)
        assertEquals("t", manifest.id)
        assertEquals("T", manifest.name)
    }

    @Test
    fun `format version newer than supported can be detected by caller`() {
        val future = """{"format_version":999,"id":"f","name":"Future"}"""
        val manifest = json.decodeFromString(XmbThemeManifest.serializer(), future)
        assertEquals(999, manifest.formatVersion)
        assertTrue(manifest.formatVersion > THEME_FORMAT_VERSION)
    }
}

class ParseHexColorTest {

    @Test
    fun `6-char hex with hash parses as opaque ARGB`() {
        assertEquals(0xFF0055AAL, parseHexColor("#0055AA"))
    }

    @Test
    fun `6-char lowercase hex is accepted`() {
        assertEquals(0xFF0055AAL, parseHexColor("#0055aa"))
    }

    @Test
    fun `8-char hex with hash parses as ARGB including alpha`() {
        assertEquals(0x800055AAL, parseHexColor("#800055AA"))
    }

    @Test
    fun `fully opaque 8-char hex`() {
        assertEquals(0xFFFFFFFFL, parseHexColor("#FFFFFFFF"))
    }

    @Test
    fun `hash prefix is optional for 6-char`() {
        assertEquals(0xFF0055AAL, parseHexColor("0055AA"))
    }

    @Test
    fun `hash prefix is optional for 8-char`() {
        assertEquals(0x800055AAL, parseHexColor("800055AA"))
    }

    @Test
    fun `5-char hex returns null`() {
        assertNull(parseHexColor("#12345"))
    }

    @Test
    fun `7-char hex returns null`() {
        assertNull(parseHexColor("#1234567"))
    }

    @Test
    fun `empty string returns null`() {
        assertNull(parseHexColor(""))
    }

    @Test
    fun `hash-only returns null`() {
        assertNull(parseHexColor("#"))
    }

    @Test
    fun `non-hex characters return null`() {
        assertNull(parseHexColor("#GGGGGG"))
        assertNull(parseHexColor("#XY0055"))
        assertNull(parseHexColor("#ZZZZZZ"))
    }

    @Test
    fun `black parses correctly`() {
        assertEquals(0xFF000000L, parseHexColor("#000000"))
    }

    @Test
    fun `white parses correctly`() {
        assertEquals(0xFFFFFFFFL, parseHexColor("#FFFFFF"))
    }
}
