package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.domain.model.UiMediaSlot
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Picking a saved theme asks before anything changes. The question also carries what applying it
 * would replace (the user's own media and icons that would hide the theme's) and switch on (Boot /
 * GameBoot for the theme's clips), so one answer covers everything.
 */
class ThemeApplyConfirmationTest {

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
        assertEquals("Apply \"Midgar\"?", c.title)
        assertEquals("This changes the launcher's colors, icons and background.", c.message)
        assertEquals("Apply", c.applyLabel)
        assertEquals("Cancel", c.cancelLabel)
        assertEquals(emptyList<String>(), c.options)
        assertFalse(c.hasChoices)
    }

    @Test
    fun `media you set that the theme also supplies is offered for replacement`() {
        val c = confirm(
            themeMedia = setOf("boot_video", "sound_scroll", "gameboot_video"),
            userMedia = setOf(UiMediaSlot.BOOT_VIDEO, UiMediaSlot.SOUND_SCROLL, UiMediaSlot.SOUND_ERROR),
        )
        assertEquals(listOf(UiMediaSlot.SOUND_SCROLL, UiMediaSlot.BOOT_VIDEO), c.replace)
        assertEquals(listOf("Use the Theme's", "Keep Mine"), c.options)
        assertEquals("Apply", c.applyLabel)
        assertTrue(c.message.startsWith("This changes the launcher's colors, icons and background."), c.message)
        assertTrue(c.message.contains("Boot Video") && c.message.contains("Navigation sound"), c.message)
        assertFalse(c.message.contains("Error"), "a slot the theme does not supply is never touched")
    }

    @Test
    fun `icons you set that the theme also supplies are offered for replacement, in slot order`() {
        val c = confirm(
            themeIcons = setOf("item_umd", "catbar_games", "sysicon_psx"),
            userIcons = setOf("sysicon_psx", "catbar_games", "catbar_music"),
        )
        assertEquals(listOf("catbar_games", "sysicon_psx"), c.replaceIcons)
        assertTrue(c.message.contains("Games") && c.message.contains("PlayStation"), c.message)
        assertFalse(c.message.contains("Music"), "an icon the theme does not supply is never touched")
        assertEquals(listOf("Use the Theme's", "Keep Mine"), c.options)
    }

    @Test
    fun `a theme clip with its presentation off offers to turn it on`() {
        val c = confirm(themeMedia = setOf("gameboot_video", "boot_video"), gameBootOn = false, bootOn = false)
        assertTrue(c.turnOnGameBoot && c.turnOnBoot)
        assertTrue(c.replace.isEmpty())
        assertEquals(listOf("Turn On", "Leave Off"), c.options)
        assertTrue(c.message.contains("GameBoot is off") && c.message.contains("Boot Sequence is off"), c.message)
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
        assertTrue(c.message.contains("lock screen"), c.message)
        assertFalse(confirm().offersLockScreen)
    }

    @Test
    fun `replacements and switches ride one answer`() {
        val c = confirm(themeMedia = setOf("gameboot_video"), userMedia = setOf(UiMediaSlot.GAMEBOOT_VIDEO), gameBootOn = false)
        assertEquals(listOf(UiMediaSlot.GAMEBOOT_VIDEO), c.replace)
        assertTrue(c.turnOnGameBoot)
        // One answer: "Use the Theme's" both replaces and switches on.
        assertEquals(listOf("Use the Theme's", "Keep Mine"), c.options)
        assertEquals(0, ThemeApplyConfirmation.USE_THEMES)
    }
}
