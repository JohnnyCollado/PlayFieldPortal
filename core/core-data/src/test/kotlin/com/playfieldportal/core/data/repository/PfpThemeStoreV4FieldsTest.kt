package com.playfieldportal.core.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.themekit.PfpThemeBundle
import com.playfieldportal.themekit.PfpThemeCodec
import com.playfieldportal.themekit.MotionCrop
import com.playfieldportal.themekit.PfpThemeManifest
import com.playfieldportal.themekit.ThemeLegibility
import com.playfieldportal.themekit.ThemeMotion
import java.io.ByteArrayInputStream
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
 * Format v4 manifest-field fidelity through apply / saveCurrentLook (plan TS-10, decision A1):
 * the exact wave (bug a: reduced+static used to collapse to static), and the legibility /
 * exact-colour fields, which are Display settings the user owns — written only when the theme
 * carries them, never cleared by a theme that is silent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PfpThemeStoreV4FieldsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        runBlocking { context.pfpDataStore.edit { it.clear() } }
        File(context.filesDir, "pfpthemes").deleteRecursively()
    }

    @Test
    fun `reduced static survives saveCurrentLook then apply`() = runTest {
        val store = PfpThemeStore(context, PERMISSIVE_PROBE)
        context.pfpDataStore.edit { it[KEY_WAVE] = "REDUCED_STATIC" }

        val saved = requireNotNull(store.saveCurrentLook("Wave"))
        val manifest = requireNotNull(PfpThemeCodec.readManifest(File(context.filesDir, "pfpthemes/${saved.id}.pfptheme")))
        assertEquals(PfpThemeManifest.WAVE_STATIC, manifest.waveStyle)
        assertEquals(PfpThemeManifest.WAVE_REDUCED_STATIC, manifest.waveStyleV4)

        context.pfpDataStore.edit { it[KEY_WAVE] = "ANIMATED" }
        assertTrue(store.apply(saved.id))
        assertEquals("REDUCED_STATIC", context.pfpDataStore.data.first()[KEY_WAVE])
    }

    @Test
    fun `legacy-only wave values still apply`() = runTest {
        val store = PfpThemeStore(context, PERMISSIVE_PROBE)
        val saved = requireNotNull(store.importBundle(register(bundle(waveStyle = PfpThemeManifest.WAVE_REDUCED))))
        assertTrue(store.apply(saved.id))
        assertEquals("REDUCED", context.pfpDataStore.data.first()[KEY_WAVE])
    }

    @Test
    fun `a v3 bundle without legibility leaves the user's device prefs untouched`() = runTest {
        val store = PfpThemeStore(context, PERMISSIVE_PROBE)
        context.pfpDataStore.edit {
            it[KEY_ICON_LEG] = "CONTOUR_AUTO"
            it[KEY_TEXT_LEG] = "PLATE"
            it[KEY_SOLID] = true
            it[KEY_EXACT] = true
        }

        val saved = requireNotNull(store.importBundle(register(bundle())))
        assertTrue(store.apply(saved.id))

        val prefs = context.pfpDataStore.data.first()
        assertEquals("CONTOUR_AUTO", prefs[KEY_ICON_LEG])
        assertEquals("PLATE", prefs[KEY_TEXT_LEG])
        assertEquals(true, prefs[KEY_SOLID])
        assertEquals(true, prefs[KEY_EXACT])
    }

    @Test
    fun `a v4 bundle with legibility writes all four fields`() = runTest {
        val store = PfpThemeStore(context, PERMISSIVE_PROBE)
        val saved = requireNotNull(
            store.importBundle(
                register(
                    bundle(
                        textColorExact = true,
                        legibility = ThemeLegibility(text = "outline", icon = "contour_dark", solidUnfocusedIcons = true),
                    ),
                ),
            ),
        )
        assertTrue(store.apply(saved.id))

        val prefs = context.pfpDataStore.data.first()
        assertEquals("OUTLINE", prefs[KEY_TEXT_LEG])
        assertEquals("CONTOUR_DARK", prefs[KEY_ICON_LEG])
        assertEquals(true, prefs[KEY_SOLID])
        assertEquals(true, prefs[KEY_EXACT])
    }

    @Test
    fun `an unknown legibility enum string leaves that pref untouched`() = runTest {
        val store = PfpThemeStore(context, PERMISSIVE_PROBE)
        context.pfpDataStore.edit {
            it[KEY_ICON_LEG] = "CONTOUR_AUTO"
            it[KEY_TEXT_LEG] = "PLATE"
        }
        val saved = requireNotNull(
            store.importBundle(
                register(bundle(legibility = ThemeLegibility(text = "sparkle", icon = "glitter", solidUnfocusedIcons = false))),
            ),
        )
        assertTrue(store.apply(saved.id))

        val prefs = context.pfpDataStore.data.first()
        assertEquals("PLATE", prefs[KEY_TEXT_LEG])
        assertEquals("CONTOUR_AUTO", prefs[KEY_ICON_LEG])
        assertEquals(false, prefs[KEY_SOLID])
    }

    @Test
    fun `saveCurrentLook captures the four fields and stamps updated`() = runTest {
        val store = PfpThemeStore(context, PERMISSIVE_PROBE)
        context.pfpDataStore.edit {
            it[KEY_ICON_LEG] = "OFFSET_SHADOW"
            it[KEY_TEXT_LEG] = "SHADOW"
            it[KEY_SOLID] = true
            it[KEY_EXACT] = true
        }
        val saved = requireNotNull(store.saveCurrentLook("Look"))
        val manifest = requireNotNull(PfpThemeCodec.readManifest(File(context.filesDir, "pfpthemes/${saved.id}.pfptheme")))

        assertEquals(true, manifest.textColorExact)
        assertEquals(ThemeLegibility(text = "shadow", icon = "offset_shadow", solidUnfocusedIcons = true), manifest.legibility)
        assertTrue(manifest.author.isNullOrBlank())
        assertTrue(manifest.description.isNullOrBlank())
        assertNotNull(manifest.created)
        assertEquals(manifest.created, manifest.updated)
    }

    @Test
    fun `saveCurrentLook with untouched prefs writes the defaults not nothing`() = runTest {
        val store = PfpThemeStore(context, PERMISSIVE_PROBE)
        val saved = requireNotNull(store.saveCurrentLook("Defaults"))
        val manifest = requireNotNull(PfpThemeCodec.readManifest(File(context.filesDir, "pfpthemes/${saved.id}.pfptheme")))

        assertEquals(false, manifest.textColorExact)
        assertEquals(ThemeLegibility(text = "auto", icon = "none", solidUnfocusedIcons = false), manifest.legibility)
        assertEquals(PfpThemeManifest.WAVE_ANIMATED, manifest.waveStyleV4)
        assertNull(manifest.subTextColor, "no Sub Font Colour set means the theme says nothing")
    }

    @Test
    fun `saveCurrentLook captures the sub text colour`() = runTest {
        val store = PfpThemeStore(context, PERMISSIVE_PROBE)
        context.pfpDataStore.edit { it[KEY_SUB_TEXT] = 0xFF88CCFFL }
        val saved = requireNotNull(store.saveCurrentLook("Sub"))
        val manifest = requireNotNull(PfpThemeCodec.readManifest(File(context.filesDir, "pfpthemes/${saved.id}.pfptheme")))

        assertEquals("#88CCFF", manifest.subTextColor)
    }

    // ── motion crop (plan TS-11) ──

    @Test
    fun `applying a bundle with a crop and mp4 motion sets the crop pref as compact json`() = runTest {
        val store = PfpThemeStore(context, PERMISSIVE_PROBE)
        val saved = requireNotNull(store.importBundle(register(motionBundle("mp4", MotionCrop(0.1f, 0f, 0.8f, 1f)))))
        assertTrue(store.apply(saved.id))

        assertEquals("""{"x":0.1,"y":0.0,"w":0.8,"h":1.0}""", context.pfpDataStore.data.first()[KEY_CROP])
    }

    @Test
    fun `applying a bundle without a crop removes a previous theme's crop`() = runTest {
        val store = PfpThemeStore(context, PERMISSIVE_PROBE)
        context.pfpDataStore.edit { it[KEY_CROP] = """{"x":0.1,"y":0.0,"w":0.8,"h":1.0}""" }
        val saved = requireNotNull(store.importBundle(register(motionBundle("mp4", null))))
        assertTrue(store.apply(saved.id))

        assertNull(context.pfpDataStore.data.first()[KEY_CROP])
    }

    @Test
    fun `a crop on gif motion is ignored and any stale crop is removed`() = runTest {
        val store = PfpThemeStore(context, PERMISSIVE_PROBE)
        context.pfpDataStore.edit { it[KEY_CROP] = """{"x":0.1,"y":0.0,"w":0.8,"h":1.0}""" }
        val saved = requireNotNull(store.importBundle(register(motionBundle("gif", MotionCrop(0.1f, 0f, 0.8f, 1f)))))
        assertTrue(store.apply(saved.id))

        assertNull(context.pfpDataStore.data.first()[KEY_CROP])
    }

    @Test
    fun `a crop with no motion entry is not written`() = runTest {
        val store = PfpThemeStore(context, PERMISSIVE_PROBE)
        val saved = requireNotNull(
            store.importBundle(
                register(
                    PfpThemeCodec.write(
                        PfpThemeBundle(
                            manifest = PfpThemeManifest(name = "NoMotion", accentColor = "#0055AA", motionCrop = MotionCrop(0.1f, 0f, 0.8f, 1f)),
                            wallpaper = null,
                            preview = null,
                        ),
                    ),
                ),
            ),
        )
        assertTrue(store.apply(saved.id))

        assertNull(context.pfpDataStore.data.first()[KEY_CROP])
    }

    @Test
    fun `resetApplied removes the crop pref`() = runTest {
        val store = PfpThemeStore(context, PERMISSIVE_PROBE)
        context.pfpDataStore.edit { it[KEY_CROP] = """{"x":0.1,"y":0.0,"w":0.8,"h":1.0}""" }
        store.resetApplied()

        assertNull(context.pfpDataStore.data.first()[KEY_CROP])
    }

    @Test
    fun `saveCurrentLook exports the crop beside mp4 motion`() = runTest {
        val store = PfpThemeStore(context, PERMISSIVE_PROBE)
        val video = File(context.filesDir, "wallpaper/mine.mp4").apply { parentFile?.mkdirs(); writeBytes("ftypmp42".toByteArray() + ByteArray(12)) }
        context.pfpDataStore.edit {
            it[KEY_MOTION] = video.absolutePath
            it[KEY_CROP] = """{"x":0.1,"y":0.0,"w":0.8,"h":1.0}"""
        }
        val saved = requireNotNull(store.saveCurrentLook("Crop"))
        val manifest = requireNotNull(PfpThemeCodec.readManifest(File(context.filesDir, "pfpthemes/${saved.id}.pfptheme")))

        assertEquals(MotionCrop(0.1f, 0f, 0.8f, 1f), manifest.motionCrop)
    }

    @Test
    fun `saveCurrentLook omits the crop for gif motion and for no motion`() = runTest {
        val store = PfpThemeStore(context, PERMISSIVE_PROBE)
        val gif = File(context.filesDir, "wallpaper/mine.gif").apply { parentFile?.mkdirs(); writeBytes("GIF89a".toByteArray()) }
        context.pfpDataStore.edit {
            it[KEY_MOTION] = gif.absolutePath
            it[KEY_CROP] = """{"x":0.1,"y":0.0,"w":0.8,"h":1.0}"""
        }
        val withGif = requireNotNull(store.saveCurrentLook("Gif"))
        assertNull(requireNotNull(PfpThemeCodec.readManifest(File(context.filesDir, "pfpthemes/${withGif.id}.pfptheme"))).motionCrop)

        context.pfpDataStore.edit { it.remove(KEY_MOTION) }
        val none = requireNotNull(store.saveCurrentLook("None"))
        assertNull(requireNotNull(PfpThemeCodec.readManifest(File(context.filesDir, "pfpthemes/${none.id}.pfptheme"))).motionCrop)
    }

    @Test
    fun `a malformed crop pref is treated as absent on export`() = runTest {
        val store = PfpThemeStore(context, PERMISSIVE_PROBE)
        val video = File(context.filesDir, "wallpaper/mine.mp4").apply { parentFile?.mkdirs(); writeBytes("ftypmp42".toByteArray() + ByteArray(12)) }
        context.pfpDataStore.edit {
            it[KEY_MOTION] = video.absolutePath
            it[KEY_CROP] = "not json"
        }
        val saved = requireNotNull(store.saveCurrentLook("Bad"))
        assertNull(requireNotNull(PfpThemeCodec.readManifest(File(context.filesDir, "pfpthemes/${saved.id}.pfptheme"))).motionCrop)
    }

    private fun motionBundle(ext: String, crop: MotionCrop?): ByteArray =
        PfpThemeCodec.write(
            PfpThemeBundle(
                manifest = PfpThemeManifest(name = "Motion", accentColor = "#0055AA", motionCrop = crop),
                wallpaper = null,
                preview = null,
                motion = ThemeMotion.ofBytes("ftypmp42".toByteArray() + ByteArray(12) { it.toByte() }, ext),
            ),
        )

    private fun bundle(
        waveStyle: String = PfpThemeManifest.WAVE_ANIMATED,
        textColorExact: Boolean? = null,
        legibility: ThemeLegibility? = null,
    ): ByteArray =
        PfpThemeCodec.write(
            PfpThemeBundle(
                manifest = PfpThemeManifest(
                    name = "V4 Theme",
                    accentColor = "#0055AA",
                    waveStyle = waveStyle,
                    textColorExact = textColorExact,
                    legibility = legibility,
                ),
                wallpaper = null,
                preview = null,
            ),
        )

    private fun register(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://test/${System.nanoTime()}.pfptheme")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        return uri
    }

    private companion object {
        /** Robolectric cannot probe media; these tests use placeholder video bytes and only assert plumbing. */
        val PERMISSIVE_PROBE: MediaProbe = { _, mime -> MediaFacts(mime, 1920, 1080, 1_000L) }
        val KEY_WAVE = ThemePrefKeys.WAVE_STYLE
        val KEY_ICON_LEG = ThemePrefKeys.ICON_LEGIBILITY
        val KEY_TEXT_LEG = ThemePrefKeys.TEXT_LEGIBILITY
        val KEY_SOLID = ThemePrefKeys.SOLID_UNFOCUSED_ICONS
        val KEY_EXACT = ThemePrefKeys.TEXT_COLOR_EXACT
        val KEY_SUB_TEXT = ThemePrefKeys.SUB_TEXT_COLOR
        val KEY_CROP = ThemePrefKeys.MOTION_CROP
        val KEY_MOTION = ThemePrefKeys.MOTION_WALLPAPER
    }
}
