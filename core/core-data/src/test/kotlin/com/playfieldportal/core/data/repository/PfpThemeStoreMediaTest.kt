package com.playfieldportal.core.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.themekit.MotionCrop
import com.playfieldportal.themekit.MotionLimits
import com.playfieldportal.themekit.PfpThemeBundle
import com.playfieldportal.themekit.PfpThemeCodec
import com.playfieldportal.themekit.PfpThemeManifest
import com.playfieldportal.themekit.ThemeMotion
import com.playfieldportal.themekit.WavFixtures
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Apply-side media gate and `theme-media/` extraction (plan TS-12). Robolectric cannot probe media,
 * so every test injects a fake probe; the gate's decisions are what is under test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PfpThemeStoreMediaTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val themeMedia get() = File(context.filesDir, "theme-media")

    @Before
    fun setUp() {
        runBlocking { context.pfpDataStore.edit { it.clear() } }
        File(context.filesDir, "pfpthemes").deleteRecursively()
        File(context.filesDir, "wallpaper").deleteRecursively()
        themeMedia.deleteRecursively()
    }

    private fun audio(durationMs: Long?) = MediaFacts("audio/wav", 0, 0, durationMs)

    private fun video(width: Int = 1920, height: Int = 1080, durationMs: Long? = 5_000) =
        MediaFacts("video/mp4", width, height, durationMs)

    private fun store(probe: MediaProbe) = PfpThemeStore(context, probe)

    @Test
    fun `a valid sound installs under theme-media and bumps the ui media stamp`() = runTest {
        val store = store { _, _ -> audio(200) }
        val saved = requireNotNull(store.importBundle(register(bundle(media = mapOf("sound_scroll" to wav())))))

        assertTrue(store.apply(saved.id))

        assertTrue(File(themeMedia, "sound_scroll.wav").isFile)
        assertNotNull(context.pfpDataStore.data.first()[UiMediaStore.KEY_UI_MEDIA_STAMP])
    }

    @Test
    fun `an over-cap sound is dropped while the rest of the theme still applies`() = runTest {
        // sound_scroll is capped at 0.5 s; sound_back at 1 s.
        val store = store { file, _ -> if (file.name.contains("sound_scroll")) audio(5_000) else audio(300) }
        val saved = requireNotNull(
            store.importBundle(register(bundle(media = mapOf("sound_scroll" to wav(), "sound_back" to wav())))),
        )

        assertTrue(store.apply(saved.id))

        assertFalse(File(themeMedia, "sound_scroll.wav").exists())
        assertTrue(File(themeMedia, "sound_back.wav").isFile)
        assertEquals("Media", context.pfpDataStore.data.first()[KEY_APPLIED_NAME])
        assertNoStaging()
    }

    @Test
    fun `a sound with no readable duration is dropped`() = runTest {
        val store = store { _, _ -> audio(null) }
        val saved = requireNotNull(store.importBundle(register(bundle(media = mapOf("sound_scroll" to wav())))))

        assertTrue(store.apply(saved.id))

        assertFalse(File(themeMedia, "sound_scroll.wav").exists())
    }

    @Test
    fun `a probe that throws drops that entry only`() = runTest {
        val store = store { file, _ -> if (file.name.contains("sound_scroll")) error("native parser blew up") else audio(300) }
        val saved = requireNotNull(
            store.importBundle(register(bundle(media = mapOf("sound_scroll" to wav(), "sound_back" to wav())))),
        )

        assertTrue(store.apply(saved.id))

        assertFalse(File(themeMedia, "sound_scroll.wav").exists())
        assertTrue(File(themeMedia, "sound_back.wav").isFile)
    }

    @Test
    fun `a sound whose probed container is not audio is dropped`() = runTest {
        val store = store { _, _ -> MediaFacts("video/mp4", 640, 360, 200) }
        val saved = requireNotNull(store.importBundle(register(bundle(media = mapOf("sound_scroll" to wav())))))

        assertTrue(store.apply(saved.id))

        assertFalse(File(themeMedia, "sound_scroll.wav").exists())
    }

    @Test
    fun `boot video installs and an over-long one is dropped`() = runTest {
        val ok = store { _, _ -> video(durationMs = 5_000) }
        val good = requireNotNull(ok.importBundle(register(bundle(media = mapOf("boot_video" to mp4())))))
        assertTrue(ok.apply(good.id))
        assertTrue(File(themeMedia, "boot_video.mp4").isFile)

        val long = store { _, _ -> video(durationMs = 11_000) }
        assertTrue(long.apply(good.id))
        assertFalse(File(themeMedia, "boot_video.mp4").exists())
    }

    @Test
    fun `applying a theme without media wipes the previous theme's media`() = runTest {
        val store = store { _, _ -> audio(200) }
        val with = requireNotNull(store.importBundle(register(bundle(media = mapOf("sound_scroll" to wav())))))
        val without = requireNotNull(store.importBundle(register(bundle())))
        assertTrue(store.apply(with.id))
        assertTrue(File(themeMedia, "sound_scroll.wav").isFile)

        assertTrue(store.apply(without.id))

        assertFalse(themeMedia.exists() && themeMedia.listFiles().orEmpty().isNotEmpty())
    }

    @Test
    fun `a valid mp4 motion installs and sets the pref`() = runTest {
        val store = store { _, _ -> video() }
        val saved = requireNotNull(store.importBundle(register(bundle(motion = ThemeMotion.ofBytes(mp4(), "mp4")))))

        assertTrue(store.apply(saved.id))

        val path = context.pfpDataStore.data.first()[KEY_MOTION]
        assertNotNull(path)
        assertTrue(File(path).isFile)
    }

    @Test
    fun `an invalid motion is not installed its crop is removed and the theme still applies`() = runTest {
        val store = store { _, _ -> video(width = 3840, height = 2160) }
        context.pfpDataStore.edit {
            it[KEY_MOTION] = "/stale/previous.mp4"
            it[KEY_CROP] = """{"x":0.1,"y":0.0,"w":0.8,"h":1.0}"""
        }
        val saved = requireNotNull(
            store.importBundle(
                register(bundle(motion = ThemeMotion.ofBytes(mp4(), "mp4"), crop = MotionCrop(0.1f, 0f, 0.8f, 1f))),
            ),
        )

        assertTrue(store.apply(saved.id))

        val prefs = context.pfpDataStore.data.first()
        assertNull(prefs[KEY_MOTION])
        assertNull(prefs[KEY_CROP])
        assertEquals("Media", prefs[KEY_APPLIED_NAME])
        assertEquals(emptyList(), File(context.filesDir, "wallpaper").listFiles().orEmpty().filter { it.extension == "mp4" || it.extension == "part" })
    }

    @Test
    fun `a motion over the byte cap is rejected before the probe runs`() {
        var probed = false
        val installer = ThemeMediaInstaller { _, _ -> probed = true; video() }
        val dest = File(context.filesDir, "wallpaper/motion_big.mp4").apply { parentFile?.mkdirs() }

        val ok = installer.installMotion(ThemeMotion.ofFile(sparseFile(MotionLimits.MAX_BYTES + 1), "mp4"), dest)

        assertFalse(ok)
        assertFalse(probed)
        assertFalse(dest.exists())
        assertNoStaging()
    }

    @Test
    fun `a sound over the theme byte cap is rejected before the probe runs`() {
        var probed = false
        val installer = ThemeMediaInstaller { _, _ -> probed = true; audio(200) }
        val dir = File(context.filesDir, "theme-media")

        val installed = installer.installMedia(
            mapOf("sound_scroll" to ThemeMotion.ofFile(sparseFile(8L * 1024 * 1024 + 1), "wav")),
            dir,
        )

        assertTrue(installed.isEmpty())
        assertFalse(probed)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a float WAV in a theme installs as 16-bit PCM, probed after conversion`() {
        var probedFormatTag = -1
        val installer = ThemeMediaInstaller { file, _ ->
            probedFormatTag = WavFixtures.readPcm16(file).formatTag
            audio(100)
        }
        val dir = File(context.filesDir, "theme-media")
        val src = File(context.cacheDir, "float.wav").apply {
            writeBytes(WavFixtures.sampleWav(3, 32, 2, 44_100, WavFixtures.floats32(0.5f, -0.5f), extraChunks = true))
        }

        val installed = installer.installMedia(mapOf("sound_scroll" to ThemeMotion.ofFile(src, "wav")), dir)

        assertEquals(setOf("sound_scroll"), installed)
        assertEquals(1, probedFormatTag, "the gate sees the PCM conversion, not the float original")
        val pcm = WavFixtures.readPcm16(File(dir, "sound_scroll.wav"))
        assertEquals(1, pcm.formatTag)
        assertContentEquals(shortArrayOf(16384, -16384), pcm.samples)
        assertNoStaging()
    }

    @Test
    fun `resetApplied deletes theme-media and bumps the stamp`() = runTest {
        val store = store { _, _ -> audio(200) }
        val saved = requireNotNull(store.importBundle(register(bundle(media = mapOf("sound_scroll" to wav())))))
        assertTrue(store.apply(saved.id))
        val before = context.pfpDataStore.data.first()[UiMediaStore.KEY_UI_MEDIA_STAMP]
        assertNotNull(before)
        Thread.sleep(5)

        store.resetApplied()

        assertFalse(themeMedia.exists())
        val after = context.pfpDataStore.data.first()[UiMediaStore.KEY_UI_MEDIA_STAMP]
        assertNotNull(after)
        assertTrue(after > before)
    }

    @Test
    fun `apply never touches user ui-media`() = runTest {
        val userFile = File(context.filesDir, UiMediaStore.UI_MEDIA_DIR + "/sound_scroll.wav")
            .apply { parentFile?.mkdirs(); writeBytes(wav()) }
        val store = store { _, _ -> audio(200) }
        val saved = requireNotNull(store.importBundle(register(bundle(media = mapOf("sound_scroll" to wav())))))

        assertTrue(store.apply(saved.id))
        store.resetApplied()

        assertTrue(userFile.isFile)
    }

    @Test
    fun `saveCurrentLook captures user media over theme media and theme media alone`() = runTest {
        val themeDir = themeMedia.apply { mkdirs() }
        File(themeDir, "sound_scroll.wav").writeBytes("THEME-SCROLL".toByteArray())
        File(themeDir, "sound_back.wav").writeBytes("THEME-BACK".toByteArray())
        File(themeDir, "boot_video.mp4").writeBytes("THEME-BOOT".toByteArray())
        val userDir = File(context.filesDir, UiMediaStore.UI_MEDIA_DIR).apply { mkdirs() }
        File(userDir, "sound_scroll.mp3").writeBytes("USER-SCROLL".toByteArray())
        try {
            val saved = requireNotNull(store { _, _ -> audio(200) }.saveCurrentLook("Look"))

            val media = requireNotNull(
                PfpThemeCodec.read(File(context.filesDir, "pfpthemes/${saved.id}.pfptheme")),
            ).media
            fun text(key: String) = requireNotNull(media[key]) { "missing $key" }
                .let { m -> java.io.ByteArrayOutputStream().also { m.copyTo(it) }.toString(Charsets.UTF_8) }
            assertEquals("USER-SCROLL", text("sound_scroll"))
            assertEquals("THEME-BACK", text("sound_back"))
            assertEquals("THEME-BOOT", text("boot_video"))
            assertEquals(setOf("sound_scroll", "sound_back", "boot_video"), media.keys)
        } finally {
            userDir.deleteRecursively()
        }
    }

    @Test
    fun `a 70 MB bundle imports now that the cap is the bundle limit`() = runTest {
        val rnd = Random(7)
        val motionFile = File(context.cacheDir, "big_motion.mp4").apply { writeRandom(this, 58L * 1024 * 1024, rnd) }
        val bootFile = File(context.cacheDir, "big_boot.mp4").apply { writeRandom(this, 12L * 1024 * 1024, rnd) }
        val bundleFile = File(context.cacheDir, "big.pfptheme")
        bundleFile.outputStream().use { out: OutputStream ->
            PfpThemeCodec.write(
                PfpThemeBundle(
                    manifest = PfpThemeManifest(name = "Big", accentColor = "#0055AA"),
                    wallpaper = null,
                    preview = null,
                    motion = ThemeMotion.ofFile(motionFile, "mp4"),
                    media = mapOf("boot_video" to ThemeMotion.ofFile(bootFile, "mp4")),
                ),
                out,
            )
        }
        assertTrue(bundleFile.length() > 64L * 1024 * 1024, "fixture must exceed the old 64 MB cap")
        val uri = Uri.parse("content://test/big.pfptheme")
        shadowOf(context.contentResolver).registerInputStream(uri, FileInputStream(bundleFile))

        val result = PfpThemeStore(context) { _, _ -> video() }.importBundleDetailed(uri)

        assertTrue(result is PfpThemeStore.ImportResult.Success, "was $result")
        bundleFile.delete(); motionFile.delete(); bootFile.delete()
    }

    // ── helpers ──

    private fun assertNoStaging() {
        val leftovers = listOfNotNull(themeMedia, File(context.filesDir, "wallpaper"))
            .flatMap { it.listFiles().orEmpty().toList() }
            .filter { it.name.endsWith(".part") }
        assertEquals(emptyList(), leftovers)
    }

    private fun writeRandom(file: File, bytes: Long, rnd: Random) {
        file.outputStream().use { out ->
            val buf = ByteArray(1 shl 16)
            var left = bytes
            while (left > 0) {
                rnd.nextBytes(buf)
                val n = minOf(left, buf.size.toLong()).toInt()
                out.write(buf, 0, n)
                left -= n
            }
        }
    }

    /** A file of [bytes] zeros without holding it on the heap. */
    private fun sparseFile(bytes: Long): File =
        File(context.cacheDir, "sparse_$bytes.bin").also { f ->
            java.io.RandomAccessFile(f, "rw").use { it.setLength(bytes) }
        }

    private fun wav() = "RIFF".toByteArray() + ByteArray(40) { it.toByte() }

    private fun mp4() = "ftypmp42".toByteArray() + ByteArray(12) { it.toByte() }

    private fun bundle(
        media: Map<String, ByteArray> = emptyMap(),
        motion: ThemeMotion? = null,
        crop: MotionCrop? = null,
    ): ByteArray =
        PfpThemeCodec.write(
            PfpThemeBundle(
                manifest = PfpThemeManifest(name = "Media", accentColor = "#0055AA", motionCrop = crop),
                wallpaper = null,
                preview = null,
                motion = motion,
                media = media.mapValues { (key, bytes) ->
                    ThemeMotion.ofBytes(bytes, if (key.endsWith("_video")) "mp4" else "wav")
                },
            ),
        )

    private fun register(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://test/${System.nanoTime()}.pfptheme")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        return uri
    }

    private companion object {
        val KEY_MOTION = stringPreferencesKey("display_motion_wallpaper")
        val KEY_CROP = stringPreferencesKey("display_motion_crop")
        val KEY_APPLIED_NAME = stringPreferencesKey("theme_applied_name")
    }
}
