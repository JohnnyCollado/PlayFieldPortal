package com.playfieldportal.core.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.themekit.PfpThemeBundle
import com.playfieldportal.themekit.PfpThemeCodec
import com.playfieldportal.themekit.PfpThemeManifest
import com.playfieldportal.themekit.PtfIcons
import com.playfieldportal.themekit.TestFixtures
import com.playfieldportal.themekit.TestFixtures.gimRecord
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `.ptf` import: wallpaper, the direct-fit icons, the icon tint and the accent land in a saved
 * library theme. Applying is the caller's job (it goes through the apply confirmation), so the
 * import itself never touches the live look.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PtfThemeImporterTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = PfpThemeStore(context)
    private val importer = PtfThemeImporter(context, store)

    private val orange = 0xFFE07020.toInt()
    private val grey = 0xFFB0B0B0.toInt()
    private val wallpaperBlue = 0xFF2050D0.toInt()

    @Before
    fun clearState() {
        runBlocking { context.pfpDataStore.edit { it.clear() } }
        File(context.filesDir, "pfpthemes").deleteRecursively()
        File(context.filesDir, PfpThemeStore.THEME_ICONS_DIR).deleteRecursively()
    }

    private fun gim(argb: Int) = TestFixtures.buildGim(48, 48, swizzle = true) { _, _ -> argb }

    /** A full theme (groups 2, 3 and 4) of solid [iconArgb] icons over a blue wallpaper. */
    private fun ptf(iconArgb: Int): ByteArray = ptfOf { gim(iconArgb) }

    private fun ptfOf(icon: () -> ByteArray): ByteArray =
        TestFixtures.buildPtfGroups(
            name = "Sunset",
            firmware = "6.20",
            groups = mapOf(
                2 to (0..7).map { gimRecord(it, icon()) },
                3 to (0..9).map { gimRecord(it, icon()) },
                4 to (0..3).map { gimRecord(it, icon()) },
            ),
            wallpaperBmp = TestFixtures.buildBmp(48, 27) { _, _ -> wallpaperBlue },
        )

    private fun register(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://test/${System.nanoTime()}.ptf")
        org.robolectric.Shadows.shadowOf(context.contentResolver).registerInputStream(uri, java.io.ByteArrayInputStream(bytes))
        return uri
    }

    private fun bundleOf(id: String): PfpThemeBundle =
        assertNotNull(PfpThemeCodec.read(File(context.filesDir, "pfpthemes/$id.pfptheme")))

    private fun hex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)

    @Test
    fun `a full theme carries every direct-fit icon into the saved bundle`() = runTest {
        val result = assertIs<PtfThemeImporter.Result.Success>(importer.import(register(ptf(orange))))
        val expected = PtfIcons.DIRECT.values.flatten().toSet()
        assertEquals(expected.size, result.iconCount)
        val bundle = bundleOf(result.themeId)
        assertEquals(expected, bundle.icons.keys)
        bundle.icons.values.forEach { assertEquals("png", it.extension) }
        assertNotNull(bundle.wallpaper)
    }

    @Test
    fun `a strong vivid icon colour becomes both the icon tint and the accent`() = runTest {
        val result = assertIs<PtfThemeImporter.Result.Success>(importer.import(register(ptf(orange))))
        val manifest = bundleOf(result.themeId).manifest
        assertEquals(hex(orange), manifest.iconColor)
        assertEquals(hex(orange), manifest.accentColor)
        assertEquals(100, result.tintScore)
    }

    @Test
    fun `strong grey icons tint the built-ins but leave the wallpaper accent`() = runTest {
        val result = assertIs<PtfThemeImporter.Result.Success>(importer.import(register(ptf(grey))))
        val manifest = bundleOf(result.themeId).manifest
        assertEquals(hex(grey), manifest.iconColor)
        assertTrue(manifest.accentColor != hex(grey), "a grey tint is not a hue the menus can use")
        assertTrue(manifest.accentColor.isNotEmpty(), "the wallpaper accent stays")
    }

    @Test
    fun `multicolour icons keep the automatic icon colour`() = runTest {
        val palette = intArrayOf(0xFFE02020.toInt(), 0xFF20C040.toInt(), 0xFF2040E0.toInt(), 0xFFF0E020.toInt())
        // Every icon is four equal quadrants, so no single colour explains more than a quarter.
        val quadrants = { TestFixtures.buildGim(48, 48, swizzle = true) { x, y -> palette[(x / 24) * 2 + y / 24] } }
        val result = assertIs<PtfThemeImporter.Result.Success>(importer.import(register(ptfOf(quadrants))))
        assertEquals(PfpThemeManifest.ICON_COLOR_AUTO, bundleOf(result.themeId).manifest.iconColor)
        assertTrue(assertNotNull(result.tintScore) < 35)
    }

    @Test
    fun `a wallpaper-only theme imports as before`() = runTest {
        val bmp = TestFixtures.buildBmp(48, 27) { _, _ -> wallpaperBlue }
        val result = assertIs<PtfThemeImporter.Result.Success>(
            importer.import(register(TestFixtures.buildPtf("Plain", "5.00", bmp))),
        )
        assertEquals(0, result.iconCount)
        assertNull(result.tintScore)
        val bundle = bundleOf(result.themeId)
        assertTrue(bundle.icons.isEmpty())
        assertEquals(PfpThemeManifest.ICON_COLOR_AUTO, bundle.manifest.iconColor)
    }

    @Test
    fun `an undecodable icon record is skipped, the import still succeeds`() = runTest {
        val bytes = TestFixtures.buildPtfGroups(
            name = "Half",
            firmware = "5.00",
            groups = mapOf(3 to listOf(TestFixtures.opaqueRecord(2, ByteArray(40) { 3 }), gimRecord(4, gim(orange)))),
            wallpaperBmp = TestFixtures.buildBmp(48, 27) { _, _ -> wallpaperBlue },
        )
        val result = assertIs<PtfThemeImporter.Result.Success>(importer.import(register(bytes)))
        assertEquals(setOf("item_umd"), bundleOf(result.themeId).icons.keys)
        assertEquals(1, result.iconCount)
    }

    @Test
    fun `importing saves to the library without applying`() = runTest {
        val result = assertIs<PtfThemeImporter.Result.Success>(importer.import(register(ptf(orange))))
        assertTrue(store.themes.value.any { it.id == result.themeId })
        assertNull(context.pfpDataStore.data.first()[ThemePrefKeys.APPLIED_THEME_NAME])
        assertTrue(ThemeTiers(context.filesDir).iconKeys(ThemeTiers.Tier.THEME).isEmpty())
    }

    @Test
    fun `applying the imported theme installs its icons and tint`() = runTest {
        val result = assertIs<PtfThemeImporter.Result.Success>(importer.import(register(ptf(orange))))
        assertTrue(store.apply(result.themeId))
        assertEquals(PtfIcons.DIRECT.values.flatten().toSet(), ThemeTiers(context.filesDir).iconKeys(ThemeTiers.Tier.THEME))
        assertEquals(orange.toLong() and 0xFFFFFFFFL, context.pfpDataStore.data.first()[ThemePrefKeys.ICON_COLOR])
    }

    @Test
    fun `body images the direct map leaves are kept as ptf icons`() = runTest {
        val bytes = TestFixtures.buildPtfGroups(
            name = "Extras",
            firmware = "6.20",
            groups = mapOf(
                2 to listOf(gimRecord(5, gim(orange)), gimRecord(6, gim(orange))),
                3 to listOf(gimRecord(8, gim(orange)), gimRecord(9, gim(orange))),
            ),
            wallpaperBmp = TestFixtures.buildBmp(48, 27) { _, _ -> wallpaperBlue },
        )
        val result = assertIs<PtfThemeImporter.Result.Success>(importer.import(register(bytes)))
        val kept = bundleOf(result.themeId).ptfIcons
        assertEquals(setOf(PtfIcons.SlotRef(2, 5), PtfIcons.SlotRef(3, 8)), kept.keys, "TV and Game sharing; not the odd 3/9, not DIRECT 2/6")
        kept.values.forEach { assertEquals("png", it.extension) }
        assertEquals(PtfIcons.DIRECT.getValue(PtfIcons.SlotRef(2, 6)).size, result.iconCount, "iconCount still counts only the slots filled")
    }

    @Test
    fun `a corrupt icon group still imports the wallpaper with no extras`() = runTest {
        val bytes = TestFixtures.buildPtfGroups(
            name = "Broken",
            firmware = "5.00",
            groups = mapOf(2 to listOf(TestFixtures.opaqueRecord(5, ByteArray(40) { 3 }))),
            wallpaperBmp = TestFixtures.buildBmp(48, 27) { _, _ -> wallpaperBlue },
        )
        val result = assertIs<PtfThemeImporter.Result.Success>(importer.import(register(bytes)))
        assertTrue(bundleOf(result.themeId).ptfIcons.isEmpty())
        assertNotNull(bundleOf(result.themeId).wallpaper)
    }

    @Test
    fun `cxmb and garbage are still refused`() = runTest {
        val bmp = TestFixtures.buildBmp(8, 4) { _, _ -> wallpaperBlue }
        val cxmb = TestFixtures.buildPtf("C", "6.60", bmp) + "/vsh/resource/x".toByteArray()
        assertIs<PtfThemeImporter.Result.CxmbNotSupported>(importer.import(register(cxmb)))
        assertIs<PtfThemeImporter.Result.Failed>(importer.import(register(ByteArray(600) { 9 })))
    }
}
