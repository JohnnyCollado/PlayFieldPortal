package com.playfieldportal.core.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.themekit.PfpThemeBundle
import com.playfieldportal.themekit.PfpThemeCodec
import com.playfieldportal.themekit.PfpThemeManifest
import com.playfieldportal.themekit.PfpThemeSource
import com.playfieldportal.themekit.ThemeImage
import com.playfieldportal.themekit.ThemeMotion
import com.playfieldportal.themekit.XmbLayoutSpec
import com.playfieldportal.themekit.XmbLayoutSpecCodec
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Schema-v3 behaviour of the theme library: `apply()` writing gif/sysicon/motion entries, and
 * `saveCurrentLook()` flattening the live look (user picks over applied-theme icons) into one
 * bundle — the exact inverse of apply().
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PfpThemeStoreV3Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearState() {
        runBlocking { context.pfpDataStore.edit { it.clear() } }
        File(context.filesDir, "pfpthemes").deleteRecursively()
        File(context.filesDir, "wallpaper").deleteRecursively()
        File(context.filesDir, PfpThemeStore.THEME_ICONS_DIR).deleteRecursively()
        File(context.filesDir, CustomIconStore.CUSTOM_ICONS_DIR).deleteRecursively()
    }

    // ── apply() with v3 bundles ───────────────────────────────────────────────

    @Test
    fun `applying a v3 bundle writes gif sysicon files and sets the motion wallpaper`() = runTest {
        val store = PfpThemeStore(context)
        val saved = requireNotNull(store.importBundle(register(v3BundleBytes())))

        assertTrue(store.apply(saved.id))

        val iconsDir = File(context.filesDir, PfpThemeStore.THEME_ICONS_DIR)
        assertTrue(File(iconsDir, "catbar_games.gif").isFile, "gif icon keeps its extension")
        assertTrue(File(iconsDir, "sysicon_psx.png").isFile, "console art lands under its sysicon_ key")

        val prefs = context.pfpDataStore.data.first()
        val motionPath = prefs[KEY_MOTION_WALLPAPER]
        assertNotNull(motionPath, "a bundle carrying motion sets KEY_MOTION_WALLPAPER")
        assertTrue(File(motionPath!!).isFile, "the motion file was written into the wallpaper dir")
        assertTrue(PfpThemeStore.KEY_THEME_ICONS_STAMP in prefs.asMap(), "the icon stamp is bumped")
    }

    @Test
    fun `applying a v3 bundle still clears a previous motion wallpaper when it carries none`() = runTest {
        val store = PfpThemeStore(context)
        context.pfpDataStore.edit { it[KEY_MOTION_WALLPAPER] = "/old/wallpaper.mp4" }
        // v2-shaped: icons only, no motion entry.
        val saved = requireNotNull(
            store.importBundle(
                register(
                    PfpThemeCodec.write(
                        PfpThemeBundle(
                            manifest = PfpThemeManifest(name = "Legacy", accentColor = "#FF0000"),
                            wallpaper = null,
                            preview = null,
                            icons = mapOf("catbar_games" to ThemeImage(pngBytes(), "png")),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(store.apply(saved.id))

        assertNull(context.pfpDataStore.data.first()[KEY_MOTION_WALLPAPER])
        assertEquals(
            setOf("catbar_games"),
            File(context.filesDir, PfpThemeStore.THEME_ICONS_DIR).listFiles()?.map { it.nameWithoutExtension }?.toSet(),
        )
    }

    @Test
    fun `applying a theme never deletes a user pick`() = runTest {
        val store = PfpThemeStore(context)
        // A user pick lives in custom-icons/, applied themes in theme-icons/ — separate dirs.
        val userDir = File(context.filesDir, CustomIconStore.CUSTOM_ICONS_DIR).apply { mkdirs() }
        File(userDir, "catbar_music.png").writeBytes(pngBytes())
        val saved = requireNotNull(store.importBundle(register(v3BundleBytes())))

        assertTrue(store.apply(saved.id))

        assertTrue(File(userDir, "catbar_music.png").isFile, "the user's pick survives the theme apply")
        assertTrue(File(File(context.filesDir, PfpThemeStore.THEME_ICONS_DIR), "catbar_games.gif").isFile)
    }

    // ── saveCurrentLook() ─────────────────────────────────────────────────────

    @Test
    fun `saveCurrentLook flattens user pick over applied theme icon per slot`() = runTest {
        val store = PfpThemeStore(context)
        val customDir = File(context.filesDir, CustomIconStore.CUSTOM_ICONS_DIR).apply { mkdirs() }
        File(customDir, "catbar_games.gif").writeBytes(gifBytes())
        val themeDir = File(context.filesDir, PfpThemeStore.THEME_ICONS_DIR).apply { mkdirs() }
        File(themeDir, "catbar_games.png").writeBytes(pngBytes())
        File(themeDir, "item_playlist.png").writeBytes(pngBytes())

        val saved = assertNotNull(store.saveCurrentLook("My Look"))
        val bundle = assertNotNull(PfpThemeCodec.read(File(context.filesDir, "pfpthemes/${saved.id}.pfptheme").readBytes()))

        assertEquals("gif", bundle.icons["catbar_games"]?.extension, "the user pick wins, verbatim gif")
        assertEquals(setOf("catbar_games", "item_playlist"), bundle.icons.keys, "theme icon fills the slot the user left alone")
        assertEquals(PfpThemeSource.TYPE_USER_CREATED, bundle.manifest.source?.type)
    }

    @Test
    fun `saveCurrentLook captures wallpaper accent icon color wave style and layout`() = runTest {
        val store = PfpThemeStore(context)
        val wallpaper = File(context.filesDir, "wallpaper").apply { mkdirs() }.resolve("w.jpg")
        wallpaper.writeBytes(pngBytes())
        val layoutJson = XmbLayoutSpecCodec.encode(XmbLayoutSpec(barTopFraction = 0.2f))
        context.pfpDataStore.edit {
            it[KEY_CUSTOM_WALLPAPER] = wallpaper.absolutePath
            it[KEY_ACCENT_OVERRIDE] = 0xFFFF72B1L
            it[KEY_ICON_COLOR] = 0xFF00FF00L
            it[KEY_WAVE_STYLE] = "STATIC"
            it[PfpThemeStore.KEY_THEME_LAYOUT] = layoutJson
        }

        val saved = assertNotNull(store.saveCurrentLook("Full Look"))
        val bundle = assertNotNull(PfpThemeCodec.read(File(context.filesDir, "pfpthemes/${saved.id}.pfptheme").readBytes()))

        assertNotNull(bundle.wallpaper, "the current wallpaper travels")
        assertEquals("#FF72B1", bundle.manifest.accentColor)
        assertEquals("#00FF00", bundle.manifest.iconColor)
        assertEquals(PfpThemeManifest.WAVE_STATIC, bundle.manifest.waveStyle)
        assertEquals(0.2f, assertNotNull(bundle.manifest.layout).barTopFraction)
        assertNotNull(saved.previewPath, "the preview sidecar is derived as usual")
    }

    @Test
    fun `saveCurrentLook excludes the device-specific XmbLayoutAdjust`() = runTest {
        val store = PfpThemeStore(context)
        context.pfpDataStore.edit {
            // Stand-in for the per-screen-bucket adjust map's pref. The portable geometry
            // (KEY_THEME_LAYOUT) is the one that travels; the adjust map never does.
            it[stringPreferencesKey("display_xmb_layout_adjust")] = """{"gameTopFraction":0.5}"""
        }

        val saved = assertNotNull(store.saveCurrentLook("No Adjust"))
        val bundle = assertNotNull(PfpThemeCodec.read(File(context.filesDir, "pfpthemes/${saved.id}.pfptheme").readBytes()))

        assertNull(bundle.manifest.layout, "Adjust XMB Layout scale/offset is device-specific and must not ship")
    }

    @Test
    fun `saveCurrentLook of the stock look yields a wave-only theme`() = runTest {
        val store = PfpThemeStore(context)

        val saved = assertNotNull(store.saveCurrentLook("Stock"))
        val bundle = assertNotNull(PfpThemeCodec.read(File(context.filesDir, "pfpthemes/${saved.id}.pfptheme").readBytes()))

        assertNull(bundle.wallpaper)
        assertTrue(bundle.icons.isEmpty())
        assertEquals(PfpThemeManifest.SCHEMA_VERSION, bundle.manifest.schemaVersion)
    }

    @Test
    fun `saveCurrentLook round-trips through apply`() = runTest {
        val store = PfpThemeStore(context)
        val customDir = File(context.filesDir, CustomIconStore.CUSTOM_ICONS_DIR).apply { mkdirs() }
        File(customDir, "status_bluetooth.png").writeBytes(pngBytes())

        val saved = assertNotNull(store.saveCurrentLook("Round Trip"))
        // Reset the live state to prove apply restores it.
        store.resetApplied()
        File(customDir, "status_bluetooth.png").delete()

        assertTrue(store.apply(saved.id))

        val themeDir = File(context.filesDir, PfpThemeStore.THEME_ICONS_DIR)
        assertTrue(File(themeDir, "status_bluetooth.png").isFile, "the flattened look re-applies as the theme tier")
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** A v3 bundle: a gif icon, a sysicon and a motion wallpaper. */
    private fun v3BundleBytes(): ByteArray = PfpThemeCodec.write(
        PfpThemeBundle(
            manifest = PfpThemeManifest(name = "V3 Theme", accentColor = "#FF0000"),
            wallpaper = null,
            preview = null,
            icons = mapOf("catbar_games" to ThemeImage(gifBytes(), "gif")),
            sysicons = mapOf("psx" to ThemeImage(pngBytes(), "png")),
            motion = ThemeMotion.ofBytes(mp4Bytes(), "mp4"),
        ),
    )

    private fun pngBytes(width: Int = 64, height: Int = 64): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun gifBytes(): ByteArray = "GIF89a".toByteArray() + ByteArray(16) { it.toByte() }
    private fun mp4Bytes(): ByteArray = "ftypmp42".toByteArray() + ByteArray(12) { it.toByte() }

    private fun register(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://test/${System.nanoTime()}.pfptheme")
        org.robolectric.Shadows.shadowOf(context.contentResolver).registerInputStream(uri, java.io.ByteArrayInputStream(bytes))
        return uri
    }

    private companion object {
        val KEY_CUSTOM_WALLPAPER = stringPreferencesKey("display_custom_wallpaper")
        val KEY_MOTION_WALLPAPER = stringPreferencesKey("display_motion_wallpaper")
        val KEY_WAVE_STYLE = stringPreferencesKey("display_wave_style")
        val KEY_ACCENT_OVERRIDE = longPreferencesKey("theme_accent_override")
        val KEY_ICON_COLOR = longPreferencesKey("theme_icon_color")
    }
}
