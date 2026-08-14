package com.playfieldportal.core.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.themekit.PfpThemeBundle
import com.playfieldportal.themekit.PfpThemeCodec
import com.playfieldportal.themekit.PfpThemeManifest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour of the launcher-side `.pfptheme` library — the import and apply paths a shared
 * theme travels through. Guards the wave-only regression: a theme authored with just an accent
 * (no wallpaper) is a valid bundle and must import and apply, reverting to the live wave
 * background rather than being rejected as "not a valid .pfptheme file".
 *
 * Coverage note: the "wallpaper bytes present but undecodable -> reject" branch of importBundle
 * is not exercised here. Robolectric's BitmapFactory shadow returns a placeholder bitmap for
 * arbitrary bytes instead of failing, so a decode failure can't be simulated deterministically
 * on the JVM; that branch is better covered by an instrumented (androidTest) run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PfpThemeStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearState() {
        // The prefs DataStore and the on-disk library persist within the test JVM; wipe both so
        // each case starts from the stock look with an empty library.
        runBlocking { context.pfpDataStore.edit { it.clear() } }
        File(context.filesDir, "pfpthemes").deleteRecursively()
        File(context.filesDir, "wallpaper").deleteRecursively()
    }

    @Test
    fun `wave-only bundle with no wallpaper imports successfully`() = runTest {
        val store = PfpThemeStore(context)

        val saved = store.importBundle(register(bundleBytes(name = "Red", accent = "#FF0000")))

        assertNotNull(saved, "wave-only theme should import, not be rejected")
        assertEquals("Red", saved.name)
        assertEquals(0xFFFF0000L, saved.accentArgb)
        assertNull(saved.previewPath, "no preview supplied -> no thumbnail sidecar")
        assertTrue(pfpThemeFile(saved.id).isFile, "the bundle is stored verbatim on disk")
        assertTrue(!wallpaperSidecar(saved.id).isFile, "wave-only theme leaves no wallpaper sidecar")
    }

    @Test
    fun `wave-only bundle with a preview writes a preview sidecar`() = runTest {
        val store = PfpThemeStore(context)

        val saved = requireNotNull(
            store.importBundle(register(bundleBytes("Red", "#FF0000", preview = pngBytes()))),
        )

        assertNotNull(saved.previewPath, "a supplied preview becomes the list thumbnail")
        assertTrue(File(saved.previewPath!!).isFile)
    }

    @Test
    fun `bundle with a wallpaper still imports`() = runTest {
        val store = PfpThemeStore(context)

        val saved = requireNotNull(
            store.importBundle(
                register(bundleBytes("Blue", "#0000FF", wallpaper = pngBytes(), preview = pngBytes())),
            ),
        )

        assertEquals("Blue", saved.name)
        assertTrue(wallpaperSidecar(saved.id).isFile, "a wallpaper theme extracts its wallpaper sidecar")
    }

    @Test
    fun `non-bundle bytes are rejected as invalid`() = runTest {
        val store = PfpThemeStore(context)

        assertNull(store.importBundle(register("not a zip".toByteArray())))
    }

    @Test
    fun `applying a wave-only theme clears a previous wallpaper and sets the accent`() = runTest {
        val store = PfpThemeStore(context)
        // Stand in for a previously-applied wallpaper theme.
        context.pfpDataStore.edit { it[KEY_CUSTOM_WALLPAPER] = "/old/wallpaper.jpg" }
        val saved = requireNotNull(store.importBundle(register(bundleBytes("Red", "#FF0000"))))

        assertTrue(store.apply(saved.id))

        val prefs = context.pfpDataStore.data.first()
        assertNull(prefs[KEY_CUSTOM_WALLPAPER], "wave-only apply reverts to the live wave background")
        assertEquals(0xFFFF0000L, prefs[KEY_ACCENT_OVERRIDE], "the theme's accent is applied")
    }

    @Test
    fun `applying a wallpaper theme sets the custom wallpaper pref`() = runTest {
        val store = PfpThemeStore(context)
        val saved = requireNotNull(
            store.importBundle(
                register(bundleBytes("Blue", "#0000FF", wallpaper = pngBytes(), preview = pngBytes())),
            ),
        )

        assertTrue(store.apply(saved.id))

        val path = context.pfpDataStore.data.first()[KEY_CUSTOM_WALLPAPER]
        assertNotNull(path, "a wallpaper theme sets the custom-wallpaper pref")
        assertTrue(File(path).isFile, "the pref points at the copied wallpaper file")
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Serializes a `.pfptheme` bundle exactly as Theme Studio / share export would. */
    private fun bundleBytes(
        name: String,
        accent: String,
        wallpaper: ByteArray? = null,
        preview: ByteArray? = null,
    ): ByteArray = PfpThemeCodec.write(
        PfpThemeBundle(
            manifest = PfpThemeManifest(name = name, accentColor = accent),
            wallpaper = wallpaper,
            preview = preview,
        ),
    )

    private fun pngBytes(width: Int = 64, height: Int = 64): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** Exposes [bytes] to the store through the SAF ContentResolver, as a picked file would arrive. */
    private fun register(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://test/${System.nanoTime()}.pfptheme")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        return uri
    }

    private fun pfpThemeFile(id: String) = File(File(context.filesDir, "pfpthemes"), "$id.pfptheme")
    private fun wallpaperSidecar(id: String) = File(File(context.filesDir, "pfpthemes"), "$id.wallpaper.jpg")

    private companion object {
        // Mirror PfpThemeStore's private cascade-pref keys by their string contract.
        val KEY_CUSTOM_WALLPAPER = stringPreferencesKey("display_custom_wallpaper")
        val KEY_ACCENT_OVERRIDE = longPreferencesKey("theme_accent_override")
    }
}
