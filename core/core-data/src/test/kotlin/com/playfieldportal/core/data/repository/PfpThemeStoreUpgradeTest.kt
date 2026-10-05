package com.playfieldportal.core.data.repository

import android.content.Context
import android.graphics.Bitmap
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.themekit.PfpThemeCodec
import com.playfieldportal.themekit.PfpThemeManifest
import com.playfieldportal.themekit.ThemeFixtures
import com.playfieldportal.themekit.ThemeMotion
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.playfieldportal.themekit.consoleArt
import com.playfieldportal.themekit.slotIcons

/** Older-format detection and in-place upgrade of saved themes (plan TS-14). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PfpThemeStoreUpgradeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir get() = File(context.filesDir, "pfpthemes")

    @Before
    fun setUp() {
        runBlocking { context.pfpDataStore.edit { it.clear() } }
        dir.deleteRecursively()
        File(context.filesDir, "wallpaper").deleteRecursively()
        File(context.filesDir, "theme-media").deleteRecursively()
        File(context.filesDir, "theme-icons").deleteRecursively()
    }

    private fun store() = PfpThemeStore(context, mediaProbe = { _, _ -> MediaFacts("video/mp4", 1920, 1080, 5_000) })

    private fun place(id: String, bytes: ByteArray): File =
        File(dir.apply { mkdirs() }, "$id.pfptheme").apply { writeBytes(bytes) }

    private fun readBack(id: String) = requireNotNull(PfpThemeCodec.readDetailed(File(dir, "$id.pfptheme"))).bundle

    /** A hand-written pre-v4 manifest: the codec always stamps the current version, so it cannot make one. */
    private fun oldManifest(schema: Int, name: String) =
        """{"manifest":"pfptheme","schemaVersion":$schema,"name":"$name","accentColor":"#0055AA","iconColor":"auto","waveStyle":"animated","source":{"type":"user-created"},"created":"2026-07-07"}"""
            .toByteArray()

    private fun text(m: ThemeMotion) = ByteArrayOutputStream().also { m.copyTo(it) }.toByteArray()

    @Test
    fun `scan reports the schema version of each saved theme`() {
        place("v1", ThemeFixtures.v1())
        place("v2", ThemeFixtures.v2())
        place("v3", ThemeFixtures.v3())
        place("v4", ThemeFixtures.v4())

        val versions = store().themes.value.associate { it.id to it.schemaVersion }

        assertEquals(mapOf("v1" to 1, "v2" to 2, "v3" to 3, "v4" to 4), versions)
    }

    @Test
    fun `a v3 theme upgrades to v4 and keeps its wallpaper icons sysicons and motion`() = runTest {
        place("t", ThemeFixtures.v3())
        val store = store()

        assertTrue(store.upgradeInPlace("t"))

        val bundle = readBack("t")
        assertEquals(PfpThemeManifest.SCHEMA_VERSION, bundle.manifest.schemaVersion)
        assertEquals("Golden V3", bundle.manifest.name)
        assertEquals("2026-08-01", bundle.manifest.created)
        assertNotNull(bundle.manifest.updated)
        assertEquals("#EEEEEE", bundle.manifest.textColor)
        assertContentEquals(ThemeFixtures.WALLPAPER, bundle.wallpaper)
        assertContentEquals(ThemeFixtures.PREVIEW, bundle.preview)
        assertEquals(setOf("catbar_games", "status_bluetooth"), bundle.slotIcons.keys)
        assertEquals(setOf("psx"), bundle.consoleArt.keys)
        assertContentEquals(ThemeFixtures.MOTION_MP4, text(requireNotNull(bundle.motion)))
        assertEquals(PfpThemeManifest.SCHEMA_VERSION, store.themes.value.single().schemaVersion)
    }

    @Test
    fun `upgrade keeps manifest extras passthrough entries and media`() = runTest {
        place("f", ThemeFixtures.future())
        val withMedia = ThemeFixtures.zip(
            "manifest.json" to oldManifest(3, "Media"),
            "sounds/sound_scroll.ogg" to ThemeFixtures.SOUND_OGG,
            "boot.mp4" to ThemeFixtures.BOOT_MP4,
        )
        place("m", withMedia)
        val store = store()

        assertTrue(store.upgradeInPlace("f"))
        assertTrue(store.upgradeInPlace("m"))

        val future = readBack("f")
        assertEquals(PfpThemeManifest.SCHEMA_VERSION, future.manifest.schemaVersion)
        assertEquals(setOf("someFutureField", "anotherFutureKey"), future.manifestExtras.keys)
        assertTrue(future.passthrough.map { it.name }.containsAll(listOf("extras/thing.bin", "readme.txt")))
        val blob = future.passthrough.first { it.name == "extras/thing.bin" }
        assertContentEquals(ThemeFixtures.FUTURE_BLOB, ByteArrayOutputStream().also { blob.copyTo(it) }.toByteArray())
        val media = readBack("m").media
        assertEquals(setOf("sound_scroll", "boot_video"), media.keys)
        assertContentEquals(ThemeFixtures.SOUND_OGG, text(requireNotNull(media["sound_scroll"])))
        assertContentEquals(ThemeFixtures.BOOT_MP4, text(requireNotNull(media["boot_video"])))
    }

    @Test
    fun `an already current theme is left byte for byte alone`() = runTest {
        // The newest fixture is v4; one upgrade makes it current (v5), the state under test.
        val file = place("c", ThemeFixtures.v4())
        assertTrue(store().upgradeInPlace("c"))
        val before = file.readBytes()

        assertTrue(store().upgradeInPlace("c"))

        assertContentEquals(before, file.readBytes())
    }

    @Test
    fun `upgrade keeps the saved list order`() = runTest {
        place("old", ThemeFixtures.v3()).setLastModified(1_000_000L)
        place("new", ThemeFixtures.v2()).setLastModified(2_000_000L)
        val store = store()
        assertEquals(listOf("new", "old"), store.themes.value.map { it.id })

        assertTrue(store.upgradeInPlace("old"))

        assertEquals(listOf("new", "old"), store.themes.value.map { it.id })
    }

    @Test
    fun `a failed upgrade leaves the original file intact and no temp behind`() = runTest {
        val garbage = "not a zip at all".toByteArray()
        val file = place("bad", garbage)
        val store = store()

        assertFalse(store.upgradeInPlace("bad"))
        assertFalse(store.upgradeInPlace("missing"))

        assertContentEquals(garbage, file.readBytes())
        assertEquals(listOf("bad.pfptheme"), dir.list().orEmpty().toList())
    }

    @Test
    fun `an existing preview sidecar is kept and a missing one is regenerated from the wallpaper`() = runTest {
        val png = ByteArrayOutputStream().also {
            Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val oldBundle = ThemeFixtures.zip(
            "manifest.json" to oldManifest(2, "Pic"),
            "wallpaper.png" to png,
            "icons/catbar_games.png" to ThemeFixtures.ICON_PNG,
        )
        place("kept", oldBundle)
        place("regen", oldBundle)
        val keptPreview = File(dir, "kept.preview.jpg").apply { writeBytes("SIDECAR".toByteArray()) }
        val store = store()

        assertTrue(store.upgradeInPlace("kept"))
        assertTrue(store.upgradeInPlace("regen"))

        assertContentEquals("SIDECAR".toByteArray(), keptPreview.readBytes())
        assertTrue(File(dir, "regen.preview.jpg").length() > 0)
        assertNotNull(store.themes.value.first { it.id == "regen" }.previewPath)
    }

    @Test
    fun `an un-upgraded v1 theme still applies`() = runTest {
        place("old", ThemeFixtures.v1())
        val store = store()

        assertTrue(store.apply("old"))

        assertEquals("Golden V1", context.pfpDataStore.data.first()[ThemePrefKeys.APPLIED_THEME_NAME])
        assertNull(File(dir, "old.wallpaper.jpg").takeIf { it.exists() })
    }
}
