package com.playfieldportal.core.data.repository

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.repository.ThemeTiers.Tier
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.core.ui.icons.CustomIcon
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two tiers of themeable assets and the one rule between them: the user's own pick beats the
 * applied theme's, which beats the built-in. Icons (`custom-icons/` over `theme-icons/`) and media
 * (`ui-media/` over `theme-media/`) follow the same rule, with the same file rules in both tiers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ThemeTiersTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var tiers: ThemeTiers

    @Before
    fun setUp() {
        tiers = ThemeTiers(context.filesDir)
        for (tier in Tier.entries) {
            tiers.iconDir(tier).deleteRecursively()
            tiers.mediaDir(tier).deleteRecursively()
        }
    }

    // ── icons ────────────────────────────────────────────────────────────────

    @Test
    fun `the user's icon beats the theme's, which beats the built-in`() {
        val user = icon(Tier.USER, "catbar_games", "png")
        val theme = icon(Tier.THEME, "catbar_games", "png")
        icon(Tier.THEME, "catbar_music", "gif")

        assertEquals(user, tiers.resolveIconFile("catbar_games"))
        tiers.clearIcons(Tier.USER)
        assertEquals(theme, tiers.resolveIconFile("catbar_games"))
        assertNotNull(tiers.resolveIconFile("catbar_music"))
        assertNull(tiers.resolveIconFile("catbar_video"), "neither tier: the built-in draws")
    }

    @Test
    fun `shadowed icons are the theme's keys the user also set`() {
        icon(Tier.USER, "catbar_games", "png")
        icon(Tier.USER, "catbar_music", "png")
        icon(Tier.THEME, "catbar_games", "png")
        icon(Tier.THEME, "sysicon_psx", "png")

        assertEquals(setOf("catbar_games"), tiers.shadowedIcons())
    }

    @Test
    fun `both tiers ignore stray files and unregistered keys`() {
        for (tier in Tier.entries) {
            icon(tier, "catbar_games", "png")
            File(tiers.iconDir(tier), "notes.txt").writeText("x")
            File(tiers.iconDir(tier), "not_a_slot.png").writeBytes(png())
            File(tiers.iconDir(tier), "catbar_games.tmp").writeText("x")
        }
        assertEquals(setOf("catbar_games"), tiers.iconKeys(Tier.USER))
        assertEquals(setOf("catbar_games"), tiers.iconKeys(Tier.THEME))
    }

    @Test
    fun `both tiers load with the same rules`() = runTest {
        for (tier in Tier.entries) {
            icon(tier, "catbar_games", "png")
            File(tiers.iconDir(tier), "junk.png").writeBytes(png())
        }
        val user = tiers.loadIcons(Tier.USER)
        val theme = tiers.loadIcons(Tier.THEME)
        assertEquals(setOf("catbar_games"), user.keys)
        assertEquals(user.keys, theme.keys)
        assertIs<CustomIcon.Still>(user.getValue("catbar_games"))
        assertIs<CustomIcon.Still>(theme.getValue("catbar_games"))
        assertEquals(
            user.getValue("catbar_games").firstFrame.width,
            theme.getValue("catbar_games").firstFrame.width,
            "one decode cap for both tiers",
        )
    }

    @Test
    fun `a user category image only ever lives in the user tier`() {
        icon(Tier.USER, "usercat_custom_x_1", "png")
        assertEquals(setOf("usercat_custom_x_1"), tiers.iconKeys(Tier.USER))
    }

    @Test
    fun `clearing a tier leaves the other and reports whether anything went`() {
        icon(Tier.USER, "catbar_games", "png")
        icon(Tier.THEME, "catbar_games", "png")

        assertTrue(tiers.clearIcons(Tier.THEME))
        assertFalse(tiers.clearIcons(Tier.THEME), "nothing left to clear")
        assertEquals(setOf("catbar_games"), tiers.iconKeys(Tier.USER))
    }

    // ── media ────────────────────────────────────────────────────────────────

    @Test
    fun `the user's clip beats the theme's, which beats the built-in`() {
        val user = media(Tier.USER, "boot_video", "mp4")
        val theme = media(Tier.THEME, "boot_video", "webm")
        media(Tier.THEME, "sound_scroll", "wav")

        assertEquals(user, tiers.resolveMedia(UiMediaSlot.BOOT_VIDEO))
        tiers.clearMedia(Tier.USER)
        assertEquals(theme, tiers.resolveMedia(UiMediaSlot.BOOT_VIDEO))
        assertNotNull(tiers.resolveMedia(UiMediaSlot.SOUND_SCROLL))
        assertNull(tiers.resolveMedia(UiMediaSlot.SOUND_BACK))
    }

    @Test
    fun `both media tiers take the same extensions`() {
        for (tier in Tier.entries) {
            media(tier, "sound_back", "ogg")
            File(tiers.mediaDir(tier), "sound_scroll.exe").writeText("x")
            File(tiers.mediaDir(tier), "staging_123.mp3").writeText("x")
        }
        assertEquals(setOf(UiMediaSlot.SOUND_BACK), tiers.mediaSlots(Tier.USER))
        assertEquals(setOf(UiMediaSlot.SOUND_BACK), tiers.mediaSlots(Tier.THEME))
        for (tier in Tier.entries) assertNull(tiers.mediaFile(tier, UiMediaSlot.SOUND_SCROLL.key))
    }

    @Test
    fun `shadowed media are the theme's slots the user also assigned`() {
        media(Tier.USER, "boot_video", "mp4")
        media(Tier.USER, "sound_error", "wav")
        media(Tier.THEME, "boot_video", "mp4")
        media(Tier.THEME, "sound_scroll", "wav")

        assertEquals(setOf(UiMediaSlot.BOOT_VIDEO), tiers.shadowedMedia())
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun icon(tier: Tier, key: String, ext: String): File =
        File(tiers.iconDir(tier).apply { mkdirs() }, "$key.$ext").apply { writeBytes(png()) }

    private fun media(tier: Tier, key: String, ext: String): File =
        File(tiers.mediaDir(tier).apply { mkdirs() }, "$key.$ext").apply { writeText("media") }

    private fun png(width: Int = 640, height: Int = 640): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}
