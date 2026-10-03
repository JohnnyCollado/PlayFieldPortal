package com.playfieldportal.themekit

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TS-07: the five menu sounds, ambience, boot and GameBoot travel in the bundle as streamed entries. */
class ThemeMediaCodecTest {

    private val fx = ThemeFixtures

    private val manifestJson =
        """{"manifest":"pfptheme","name":"M","accentColor":"#000000"}""".toByteArray()

    private fun raw(vararg entries: Pair<String, ByteArray>): ByteArray =
        fx.zip("manifest.json" to manifestJson, *entries)

    private fun bytesOf(m: ThemeMotion): ByteArray =
        ByteArrayOutputStream().also { m.copyTo(it) }.toByteArray()

    private fun zipNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) { names += e.name; e = z.nextEntry }
        }
        return names
    }

    private fun payload(key: String) = "payload-$key".toByteArray()

    private val allMedia: Map<String, ThemeMotion> = mapOf(
        "sound_scroll" to ThemeMotion.ofBytes(payload("sound_scroll"), "ogg"),
        "sound_back" to ThemeMotion.ofBytes(payload("sound_back"), "wav"),
        "sound_confirm" to ThemeMotion.ofBytes(payload("sound_confirm"), "mp3"),
        "sound_error" to ThemeMotion.ofBytes(payload("sound_error"), "m4a"),
        "sound_notification" to ThemeMotion.ofBytes(payload("sound_notification"), "ogg"),
        "ambience_audio" to ThemeMotion.ofBytes(payload("ambience_audio"), "mp3"),
        "boot_video" to ThemeMotion.ofBytes(payload("boot_video"), "mp4"),
        "gameboot_video" to ThemeMotion.ofBytes(payload("gameboot_video"), "webm"),
    )

    private fun bundleWith(media: Map<String, ThemeMotion>) = PfpThemeBundle(
        manifest = PfpThemeManifest(name = "M", accentColor = "#000000"),
        wallpaper = null,
        preview = null,
        media = media,
    )

    // -- registry ------------------------------------------------------------------------

    @Test
    fun `registry holds the eight media keys with their entry names`() {
        assertEquals(8, ThemeMediaSlots.KEYS.size)
        assertEquals("sounds/sound_scroll.ogg", ThemeMediaSlots.entryName("sound_scroll", "ogg"))
        assertEquals("ambience.mp3", ThemeMediaSlots.entryName("ambience_audio", "mp3"))
        assertEquals("boot.mp4", ThemeMediaSlots.entryName("boot_video", "mp4"))
        assertEquals("gameboot.webm", ThemeMediaSlots.entryName("gameboot_video", "webm"))
    }

    @Test
    fun `entryName refuses unknown keys and wrong containers`() {
        assertNull(ThemeMediaSlots.entryName("sound_launch", "mp3"))
        assertNull(ThemeMediaSlots.entryName("boot_video", "gif"))
        assertNull(ThemeMediaSlots.entryName("sound_back", "mp4"))
        assertNull(ThemeMediaSlots.entryName("../x", "mp3"))
    }

    @Test
    fun `byte caps follow the plan`() {
        assertEquals(8L * 1024 * 1024, UiMediaLimits.THEME_SOUND_MAX_BYTES)
        assertEquals(32L * 1024 * 1024, UiMediaLimits.THEME_AMBIENCE_MAX_BYTES)
        assertEquals(UiMediaLimits.THEME_SOUND_MAX_BYTES, ThemeMediaSlots.slot("sound_error")!!.maxBytes)
        assertEquals(UiMediaLimits.THEME_AMBIENCE_MAX_BYTES, ThemeMediaSlots.slot("ambience_audio")!!.maxBytes)
        assertEquals(UiMediaLimits.VIDEO_MAX_BYTES, ThemeMediaSlots.slot("boot_video")!!.maxBytes)
        assertEquals(UiMediaLimits.VIDEO_MAX_BYTES, ThemeMediaSlots.slot("gameboot_video")!!.maxBytes)
    }

    // -- round trip ----------------------------------------------------------------------

    @Test
    fun `all eight media entries round-trip with their content`() {
        val written = PfpThemeCodec.write(bundleWith(allMedia))
        assertTrue(
            zipNames(written).containsAll(
                listOf(
                    "sounds/sound_scroll.ogg", "sounds/sound_back.wav", "sounds/sound_confirm.mp3",
                    "sounds/sound_error.m4a", "sounds/sound_notification.ogg",
                    "ambience.mp3", "boot.mp4", "gameboot.webm",
                ),
            ),
            zipNames(written).toString(),
        )

        val read = assertNotNull(PfpThemeCodec.read(written))
        assertEquals(allMedia.keys, read.media.keys)
        for ((key, m) in allMedia) {
            assertEquals(m.extension, read.media.getValue(key).extension)
            assertContentEquals(payload(key), bytesOf(read.media.getValue(key)), key)
        }
        assertTrue(read.passthrough.isEmpty(), "media must not fall through to passthrough")
        assertEquals(read, assertNotNull(PfpThemeCodec.read(PfpThemeCodec.write(read))))
    }

    @Test
    fun `reading media opens the source only when it is copied`() {
        val bytes = PfpThemeCodec.write(bundleWith(allMedia))
        var opens = 0
        val source = { opens++; ByteArrayInputStream(bytes) }

        val bundle = assertNotNull(
            PfpThemeCodec.read(ByteArrayInputStream(bytes), null) { name ->
                PfpThemeCodec.passthroughFrom(source, name)
            },
        )
        assertEquals(8, bundle.media.size)
        assertEquals(0, opens, "reading the bundle must not reopen the source")
        assertContentEquals(payload("boot_video"), bytesOf(bundle.media.getValue("boot_video")))
        assertEquals(1, opens)
    }

    @Test
    fun `plain stream read reports no media bytes`() {
        val bytes = PfpThemeCodec.write(bundleWith(allMedia))
        val bundle = assertNotNull(PfpThemeCodec.read(ByteArrayInputStream(bytes)))
        assertTrue(bundle.media.isEmpty())
    }

    @Test
    fun `media take part in bundle equality by key and extension`() {
        val a = bundleWith(mapOf("boot_video" to ThemeMotion.ofBytes(byteArrayOf(1), "mp4")))
        val b = bundleWith(mapOf("boot_video" to ThemeMotion.ofBytes(byteArrayOf(2), "mp4")))
        val c = bundleWith(mapOf("boot_video" to ThemeMotion.ofBytes(byteArrayOf(1), "webm")))
        assertEquals(a, b)
        assertFalse(a == c)
        assertFalse(a == bundleWith(emptyMap()))
    }

    // -- gating --------------------------------------------------------------------------

    @Test
    fun `wrong extension is dropped and reported, not passed through`() {
        val result = assertNotNull(
            PfpThemeCodec.readDetailed(
                raw(
                    "boot.gif" to byteArrayOf(1),
                    "sounds/sound_back.mp4" to byteArrayOf(2),
                    "ambience.png" to byteArrayOf(3),
                    "gameboot.mp3" to byteArrayOf(4),
                ),
            ),
        )
        assertTrue(result.bundle.media.isEmpty())
        assertTrue(result.bundle.passthrough.isEmpty())
        val dropped = result.diagnostics.dropped.associate { it.name to it.reason }
        assertEquals(
            setOf("boot.gif", "sounds/sound_back.mp4", "ambience.png", "gameboot.mp3"),
            dropped.keys,
        )
        assertTrue(dropped.values.all { it == DropReason.UNSUPPORTED_MEDIA })
    }

    @Test
    fun `retired or unknown sound key becomes passthrough, not media`() {
        val bytes = raw("sounds/sound_launch.mp3" to byteArrayOf(9), "sounds/boot_audio.ogg" to byteArrayOf(8))
        val result = assertNotNull(PfpThemeCodec.readDetailed(bytes))
        assertTrue(result.bundle.media.isEmpty())
        assertEquals(
            setOf("sounds/sound_launch.mp3", "sounds/boot_audio.ogg"),
            result.bundle.passthrough.map { it.name }.toSet(),
        )
        assertTrue(result.diagnostics.dropped.isEmpty())
        val rewritten = PfpThemeCodec.write(result.bundle)
        assertTrue(zipNames(rewritten).contains("sounds/sound_launch.mp3"))
    }

    @Test
    fun `second entry for one slot is a duplicate`() {
        val result = assertNotNull(
            PfpThemeCodec.readDetailed(raw("boot.mp4" to byteArrayOf(1), "boot.webm" to byteArrayOf(2))),
        )
        assertEquals(setOf("boot_video"), result.bundle.media.keys)
        assertEquals(
            listOf(DroppedEntry("boot.webm", DropReason.DUPLICATE)),
            result.diagnostics.dropped,
        )
    }

    @Test
    fun `over-cap media are dropped, at-cap are kept`() {
        val atCap = ByteArray(UiMediaLimits.THEME_SOUND_MAX_BYTES.toInt())
        val overCap = ByteArray(UiMediaLimits.THEME_SOUND_MAX_BYTES.toInt() + 1)
        val result = assertNotNull(
            PfpThemeCodec.readDetailed(
                raw("sounds/sound_back.ogg" to atCap, "sounds/sound_error.ogg" to overCap),
            ),
        )
        assertEquals(setOf("sound_back"), result.bundle.media.keys)
        assertEquals(
            listOf(DroppedEntry("sounds/sound_error.ogg", DropReason.OVER_CAP)),
            result.diagnostics.dropped,
        )
    }

    @Test
    fun `writer skips media with an unknown key or wrong container`() {
        val bundle = bundleWith(
            mapOf(
                "boot_video" to ThemeMotion.ofBytes(byteArrayOf(1), "gif"),
                "sound_launch" to ThemeMotion.ofBytes(byteArrayOf(2), "mp3"),
                "sound_back" to ThemeMotion.ofBytes(byteArrayOf(3), "mp4"),
                "gameboot_video" to ThemeMotion.ofBytes(byteArrayOf(4), "MP4"),
            ),
        )
        val names = zipNames(PfpThemeCodec.write(bundle))
        assertEquals(listOf("manifest.json", "gameboot.mp4"), names)
    }

    @Test
    fun `passthrough cannot shadow a media entry name`() {
        val bundle = bundleWith(allMedia.filterKeys { it == "boot_video" }).copy(
            passthrough = listOf(
                PassthroughEntry.ofBytes("boot.mp4", byteArrayOf(7)),
                PassthroughEntry.ofBytes("boot.gif", byteArrayOf(7)),
            ),
        )
        val names = zipNames(PfpThemeCodec.write(bundle))
        assertEquals(1, names.count { it == "boot.mp4" })
        assertFalse(names.contains("boot.gif"))
    }

    // -- older readers -------------------------------------------------------------------

    @Test
    fun `the v3-era reader ignores every media entry`() {
        val bundle = bundleWith(allMedia).copy(
            icons = mapOf("catbar_games" to ThemeImage(fx.ICON_PNG, "png")),
            motion = ThemeMotion.ofBytes(fx.MOTION_MP4, "mp4"),
        )
        val frozen = assertNotNull(V3EraReader.read(PfpThemeCodec.write(bundle)))
        assertEquals(mapOf("catbar_games" to "png"), frozen.icons)
        assertEquals("mp4", frozen.motionExtension)
        assertTrue(frozen.sysicons.isEmpty())
        assertNull(frozen.wallpaper)
    }

    // -- upgrade report ------------------------------------------------------------------

    @Test
    fun `upgrade report lists media as kept and keeps them through upgrade`() {
        val read = assertNotNull(PfpThemeCodec.readDetailed(PfpThemeCodec.write(bundleWith(allMedia))))
        val report = ThemeUpgrade.report(read.bundle, read.diagnostics)
        assertTrue(report.kept.any { it.contains("5 menu sounds") }, report.kept.toString())
        assertTrue(report.kept.any { it.contains("Ambience") }, report.kept.toString())
        assertTrue(report.kept.any { it.contains("Boot animation") }, report.kept.toString())
        assertTrue(report.kept.any { it.contains("GameBoot animation") }, report.kept.toString())
        assertEquals(allMedia.keys, ThemeUpgrade.upgrade(read.bundle, "2026-10-02").media.keys)
    }

    @Test
    fun `upgrade report names dropped media under cant-recover`() {
        val read = assertNotNull(PfpThemeCodec.readDetailed(raw("boot.gif" to byteArrayOf(1))))
        val report = ThemeUpgrade.report(read.bundle, read.diagnostics)
        assertTrue(report.cantRecover.any { it.startsWith("boot.gif") }, report.cantRecover.toString())
    }
}
