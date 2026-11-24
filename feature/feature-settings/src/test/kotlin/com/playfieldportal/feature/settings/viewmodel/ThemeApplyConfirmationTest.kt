package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.core.domain.text.UiText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Picking a saved theme asks before anything changes. The question also carries what applying it
 * would replace (the user's own media and icons that would hide the theme's) and switch on (Boot /
 * GameBoot for the theme's clips), so one answer covers everything.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeApplyConfirmationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun UiText.text(): String = resolve(context)

    private fun List<UiText>.texts(): List<String> = map { it.text() }

    private fun confirm(
        themeMedia: Set<String> = emptySet(),
        userMedia: Set<UiMediaSlot> = emptySet(),
        themeIcons: Set<String> = emptySet(),
        userIcons: Set<String> = emptySet(),
        gameBootOn: Boolean = true,
        bootOn: Boolean = true,
        lockScreen: Boolean = false,
    ) = ThemeApplyConfirmation.of(
        themeId = "t1",
        themeName = "Midgar",
        themeMedia = themeMedia.mapNotNullTo(HashSet()) { UiMediaSlot.fromKey(it) },
        userMedia = userMedia,
        themeIcons = themeIcons,
        userIcons = userIcons,
        gameBootEnabled = gameBootOn,
        bootEnabled = bootOn,
        themeHasLockScreen = lockScreen,
    )

    @Test
    fun `a theme that changes nothing of yours is a plain Apply or Cancel`() {
        val c = confirm(themeMedia = setOf("boot_video", "sound_scroll"), userMedia = setOf(UiMediaSlot.SOUND_BACK))
        assertEquals("Apply \"Midgar\"?", c.title.text())
        assertEquals("This changes the launcher's colors, icons and background.", c.message.text())
        assertEquals("Apply", c.applyLabel.text())
        assertEquals("Cancel", c.cancelLabel.text())
        assertEquals(emptyList<String>(), c.options.texts())
        assertFalse(c.hasChoices)
    }

    @Test
    fun `media you set that the theme also supplies is offered for replacement`() {
        val c = confirm(
            themeMedia = setOf("boot_video", "sound_scroll", "gameboot_video"),
            userMedia = setOf(UiMediaSlot.BOOT_VIDEO, UiMediaSlot.SOUND_SCROLL, UiMediaSlot.SOUND_ERROR),
        )
        assertEquals(listOf(UiMediaSlot.SOUND_SCROLL, UiMediaSlot.BOOT_VIDEO), c.replace)
        assertEquals(listOf("Use the Theme's", "Keep Mine"), c.options.texts())
        assertEquals("Apply", c.applyLabel.text())
        assertTrue(c.message.text().startsWith("This changes the launcher's colors, icons and background."), c.message.text())
        assertTrue(c.message.text().contains("Boot Video") && c.message.text().contains("Navigation sound"), c.message.text())
        assertFalse(c.message.text().contains("Error"), "a slot the theme does not supply is never touched")
    }

    @Test
    fun `icons you set that the theme also supplies are offered for replacement, in slot order`() {
        val c = confirm(
            themeIcons = setOf("item_umd", "catbar_games", "sysicon_psx"),
            userIcons = setOf("sysicon_psx", "catbar_games", "catbar_music"),
        )
        assertEquals(listOf("catbar_games", "sysicon_psx"), c.replaceIcons)
        assertTrue(c.message.text().contains("Games") && c.message.text().contains("PlayStation"), c.message.text())
        assertFalse(c.message.text().contains("Music"), "an icon the theme does not supply is never touched")
        assertEquals(listOf("Use the Theme's", "Keep Mine"), c.options.texts())
    }

    @Test
    fun `a theme clip with its presentation off offers to turn it on`() {
        val c = confirm(themeMedia = setOf("gameboot_video", "boot_video"), gameBootOn = false, bootOn = false)
        assertTrue(c.turnOnGameBoot && c.turnOnBoot)
        assertTrue(c.replace.isEmpty())
        assertEquals(listOf("Turn On", "Leave Off"), c.options.texts())
        assertTrue(c.message.text().contains("Game Boot is off") && c.message.text().contains("Boot Sequence is off"), c.message.text())
    }

    @Test
    fun `a presentation that is off only matters when the theme has its clip`() {
        assertFalse(confirm(themeMedia = setOf("sound_scroll"), bootOn = false, gameBootOn = false).hasChoices)
    }

    @Test
    fun `a theme's lock screen image is offered separately, never folded into Apply`() {
        val c = confirm(lockScreen = true)
        assertTrue(c.offersLockScreen)
        // It changes the device, not the launcher: the apply question itself stays plain.
        assertFalse(c.hasChoices)
        assertTrue(c.message.text().contains("lock screen"), c.message.text())
        assertFalse(confirm().offersLockScreen)
    }

    @Test
    fun `replacements and switches ride one answer`() {
        val c = confirm(themeMedia = setOf("gameboot_video"), userMedia = setOf(UiMediaSlot.GAMEBOOT_VIDEO), gameBootOn = false)
        assertEquals(listOf(UiMediaSlot.GAMEBOOT_VIDEO), c.replace)
        assertTrue(c.turnOnGameBoot)
        // One answer: "Use the Theme's" both replaces and switches on.
        assertEquals(listOf("Use the Theme's", "Keep Mine"), c.options.texts())
        assertEquals(0, ThemeApplyConfirmation.USE_THEMES)
    }
}
