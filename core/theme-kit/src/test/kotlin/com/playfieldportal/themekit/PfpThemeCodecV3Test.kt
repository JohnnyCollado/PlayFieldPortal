package com.playfieldportal.themekit

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Schema v3 of the `.pfptheme` bundle (additive on v2):
 *
 * - `icons/<key>.png` widens to `icons/<key>.{png,gif}` — animated icons round-trip.
 * - `sysicons/<platformId>.{png,gif}` — console art, gated by CustomizableIcons (NOT
 *   IconSlots, so the desktop Studio's slot list is unaffected until it opts in).
 * - `motion.<mp4|webm|gif>` — motion wallpaper travels in the bundle.
 *
 * The forward-compatibility contract is load-bearing: unknown entries are ignored, unknown
 * manifest keys ignored, and a bundle that trips a limit is "not a .pfptheme", not a crash.
 */
class PfpThemeCodecV3Test {

    private val manifest = PfpThemeManifest(name = "V3 Pink", accentColor = "#FF72B1")

    // ── write + read round-trips ──────────────────────────────────────────────

    @Test
    fun `v3 round-trips gif icons and sysicons and motion`() {
        val bundle = PfpThemeBundle(
            manifest = manifest,
            wallpaper = null,
            preview = null,
            icons = mapOf(
                "catbar_games" to ThemeImage(gifBytes(), "gif"),
                "catbar_music" to ThemeImage(pngV3Bytes(), "png"),
            ),
            sysicons = mapOf(
                "psx" to ThemeImage(pngV3Bytes(), "png"),
                "nes" to ThemeImage(gifBytes(), "gif"),
            ),
            motion = ThemeMotion.ofBytes(mp4Bytes(), "mp4"),
        )

        val decoded = assertNotNull(PfpThemeCodec.read(PfpThemeCodec.write(bundle)))

        assertEquals(bundle.manifest, decoded.manifest)
        assertEquals(setOf("catbar_games", "catbar_music"), decoded.icons.keys)
        assertEquals("gif", decoded.icons["catbar_games"]?.extension)
        assertTrue(decoded.icons["catbar_games"]!!.bytes.contentEquals(gifBytes()))
        assertEquals(setOf("psx", "nes"), decoded.sysicons.keys)
        assertEquals("mp4", decoded.motion?.extension)
        // The motion entry is never held as bytes — it streams on demand, so the round-trip is
        // asserted by draining it rather than by reading a `bytes` property that no longer exists.
        assertTrue(decoded.motion!!.drain().contentEquals(mp4Bytes()))
    }

    @Test
    fun `write sorts bundle entries for byte-deterministic output`() {
        fun build(icons: Map<String, ThemeImage>) = PfpThemeCodec.write(
            PfpThemeBundle(
                manifest = manifest,
                wallpaper = null,
                preview = null,
                icons = icons,
                sysicons = mapOf("nes" to ThemeImage(gifBytes(), "gif"), "psx" to ThemeImage(pngV3Bytes(), "png")),
            ),
        )

        val a = build(linkedMapOf("status_bluetooth" to ThemeImage(gifBytes(), "gif"), "catbar_games" to ThemeImage(gifBytes(), "gif")))
        val b = build(linkedMapOf("catbar_games" to ThemeImage(gifBytes(), "gif"), "status_bluetooth" to ThemeImage(gifBytes(), "gif")))
        // Identical themes built with different insertion orders must be byte-identical.
        assertTrue(a.contentEquals(b), "same entries in different insertion order must produce identical bytes")
    }

    @Test
    fun `write still drops unregistered icon keys but accepts console keys`() {
        val written = PfpThemeCodec.write(
            PfpThemeBundle(
                manifest = manifest,
                wallpaper = null,
                preview = null,
                icons = mapOf(
                    "not_a_slot" to ThemeImage(pngV3Bytes(), "png"),
                    "catbar_games" to ThemeImage(pngV3Bytes(), "png"),
                ),
            ),
        )
        val decoded = assertNotNull(PfpThemeCodec.read(written))
        assertEquals(setOf("catbar_games"), decoded.icons.keys)
    }

    // ── entry-name gating ─────────────────────────────────────────────────────

    @Test
    fun `read rejects sysicon keys that are not registered console slots`() {
        val hostile = zip(
            "manifest.json" to jsonManifest(),
            "sysicons/../evil.png" to ByteArray(4),
            "sysicon_default.png" to ByteArray(4), // fallback art, never a slot
            "sysicons/not_a_platform.png" to ByteArray(4),
            "sysicons/psx.png" to ByteArray(4),
        )
        val decoded = assertNotNull(PfpThemeCodec.read(hostile))
        assertEquals(setOf("psx"), decoded.sysicons.keys, "only registered console keys survive read")
    }

    @Test
    fun `read rejects sysicons entries outside the accepted extensions`() {
        val hostile = zip(
            "manifest.json" to jsonManifest(),
            "sysicons/psx.bmp" to ByteArray(4),
            "sysicons/psx.png" to pngV3Bytes(),
        )
        val decoded = assertNotNull(PfpThemeCodec.read(hostile))
        assertEquals(setOf("psx"), decoded.sysicons.keys)
    }

    /** Streams a [ThemeMotion] into memory so a test can assert its content. */
    private fun ThemeMotion.drain(): ByteArray =
        java.io.ByteArrayOutputStream().also { copyTo(it) }.toByteArray()

    @Test
    fun `read accepts only the known motion extensions`() {
        val withMotion = zip("manifest.json" to jsonManifest(), "motion.webm" to mp4Bytes())
        assertEquals("webm", assertNotNull(PfpThemeCodec.read(withMotion)).motion?.extension)

        val hostile = zip("manifest.json" to jsonManifest(), "motion.exe" to mp4Bytes())
        val decoded = assertNotNull(PfpThemeCodec.read(hostile))
        assertNull(decoded.motion, "an unknown motion extension is not a motion entry")
    }

    @Test
    fun `read keeps IconSlots gating on the icons directory`() {
        val hostile = zip(
            "manifest.json" to jsonManifest(),
            "icons/catbar_games.gif" to gifBytes(),
            "icons/not_a_slot.png" to pngV3Bytes(),
            "icons/../../evil.gif" to gifBytes(),
            "sysicon_snes.png" to pngV3Bytes(), // right key, wrong directory — not an icon entry
        )
        val decoded = assertNotNull(PfpThemeCodec.read(hostile))
        assertEquals(setOf("catbar_games"), decoded.icons.keys)
        assertTrue(decoded.sysicons.isEmpty(), "sysicon keys live under sysicons/, not icons/")
    }

    // ── limits ────────────────────────────────────────────────────────────────

    @Test
    fun `limits are raised for v3`() {
        // A motion wallpaper is capped at 60 MB by MotionLimits, so the per-entry
        // cap must clear it; 98 icons + sysicons + manifest + wallpaper + motion + preview
        // clears 128 entries.
        assertEquals(256, PfpThemeCodec.BUNDLE_LIMITS.maxEntries)
        assertEquals(64L * 1024 * 1024, PfpThemeCodec.BUNDLE_LIMITS.maxEntryBytes)
        assertEquals(256L * 1024 * 1024, PfpThemeCodec.BUNDLE_LIMITS.maxTotalBytes)
        assertEquals((8 * 1024 * 1024).toLong(), PfpThemeCodec.MAX_ICON_BYTES.toLong())
    }

    @Test
    fun `oversized icon entries are dropped on read, not fatal`() {
        val big: ByteArray = ByteArray(PfpThemeCodec.MAX_ICON_BYTES + 1)
        val bundle = zip(
            "manifest.json" to jsonManifest(),
            "icons/catbar_games.png" to big,
            "icons/catbar_music.png" to pngV3Bytes(),
        )
        val decoded = assertNotNull(PfpThemeCodec.read(bundle), "an over-cap icon is dropped, the bundle still reads")
        assertEquals(setOf("catbar_music"), decoded.icons.keys)
    }

    // ── forward compatibility ─────────────────────────────────────────────────

    @Test
    fun `v2-shaped bundle still reads unchanged`() {
        // Hand-built v2 zip: icons only under the .png-only naming, no sysicons/motion.
        val v2 = zip(
            "manifest.json" to """{"manifest":"pfptheme","schemaVersion":2,"name":"Legacy","accentColor":"#FF0000"}""".toByteArray(),
            "icons/catbar_games.png" to pngV3Bytes(),
        )
        val decoded = assertNotNull(PfpThemeCodec.read(v2))
        assertEquals(2, decoded.manifest.schemaVersion)
        assertEquals(setOf("catbar_games"), decoded.icons.keys)
        assertEquals("png", decoded.icons["catbar_games"]?.extension)
        assertTrue(decoded.sysicons.isEmpty())
        assertNull(decoded.motion)
    }

    @Test
    fun `unknown manifest keys and unknown zip entries are ignored`() {
        val future = zip(
            "manifest.json" to """{"manifest":"pfptheme","schemaVersion":4,"name":"Future","accentColor":"#FF0000","hologram":"true"}""".toByteArray(),
            "sounds/cursor.at3" to ByteArray(4),
        )
        val decoded = assertNotNull(PfpThemeCodec.read(future))
        assertEquals(4, decoded.manifest.schemaVersion, "version is informational — readers never gate on it")
    }

    @Test
    fun `manifest without schemaVersion still reads (older writer)`() {
        val v1 = zip("manifest.json" to """{"manifest":"pfptheme","name":"Ancient","accentColor":"#FF0000"}""".toByteArray())
        val decoded = assertNotNull(PfpThemeCodec.read(v1))
        assertEquals("Ancient", decoded.manifest.name)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Proves the manifest read stops at the first entry rather than streaming the archive.
     *
     * The archive here carries more entries than [PfpThemeCodec.BUNDLE_LIMITS] allows, so a full
     * read is refused outright. readManifest still succeeds — which it can only do by never
     * looking past `manifest.json`. That early exit is the whole point: the library listing calls
     * this once per saved theme, and reading to the end would mean streaming every motion
     * wallpaper on disk to recover a name.
     */
    @Test
    fun `readManifest stops at the manifest and ignores the rest of the archive`() {
        val entries = arrayOf("manifest.json" to jsonManifest()) +
            Array(PfpThemeCodec.BUNDLE_LIMITS.maxEntries + 8) { i -> "filler/$i.png" to pngV3Bytes() }
        val file = tempBundle(zip(*entries))

        assertNull(PfpThemeCodec.read(file), "a full read is refused: too many entries")
        assertEquals("V3 Pink", assertNotNull(PfpThemeCodec.readManifest(file)).name)
    }

    @Test
    fun `read from a file streams the motion entry back out`() {
        val file = tempBundle(
            PfpThemeCodec.write(
                PfpThemeBundle(
                    manifest = manifest,
                    wallpaper = null,
                    preview = null,
                    motion = ThemeMotion.ofBytes(mp4Bytes(), "mp4"),
                ),
            ),
        )

        val decoded = assertNotNull(PfpThemeCodec.read(file))

        assertEquals("mp4", decoded.motion?.extension)
        assertTrue(decoded.motion!!.drain().contentEquals(mp4Bytes()), "streams from the file")
        // Streaming is repeatable: apply() may extract the same bundle more than once.
        assertTrue(decoded.motion!!.drain().contentEquals(mp4Bytes()), "and can be streamed again")
    }

    @Test
    fun `a bundle read from a bare stream reports no motion`() {
        val bytes = PfpThemeCodec.write(
            PfpThemeBundle(manifest, null, null, motion = ThemeMotion.ofBytes(mp4Bytes(), "mp4")),
        )

        // Documented one-pass limitation: nothing can stream the entry back, so it is not offered.
        assertNull(PfpThemeCodec.read(bytes.inputStream()).let { assertNotNull(it).motion })
    }

    private fun tempBundle(bytes: ByteArray): java.io.File =
        java.io.File.createTempFile("bundle", ".pfptheme").apply { deleteOnExit(); writeBytes(bytes) }

    private fun jsonManifest(): ByteArray =
        """{"manifest":"pfptheme","schemaVersion":3,"name":"V3 Pink","accentColor":"#FF72B1"}""".toByteArray()

    /** A minimal but structurally valid GIF89a header — enough for the codec, which treats bytes as opaque. */
    private fun gifBytes(): ByteArray =
        "GIF89a".toByteArray() + ByteArray(16) { it.toByte() }

    private fun pngV3Bytes(): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(8) { it.toByte() }

    private fun mp4Bytes(): ByteArray =
        "ftypmp42".toByteArray() + ByteArray(12) { it.toByte() }

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
