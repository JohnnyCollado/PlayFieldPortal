package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.domain.model.UiMediaSlot
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * After a theme applies, its clips and sounds only play where the user has not assigned their own
 * (the user tier wins), and its GameBoot / boot clips only play while those presentations are on.
 * The prompt offers exactly the changes that would make the theme's media play — nothing when
 * there is nothing to change.
 */
class ThemeMediaPromptTest {

    private fun prompt(
        installed: Set<String>,
        userAssigned: Set<UiMediaSlot> = emptySet(),
        gameBootOn: Boolean = true,
        bootOn: Boolean = true,
        installedIcons: Set<String> = emptySet(),
        userIcons: Set<String> = emptySet(),
    ) = ThemeMediaPrompt.of(installed, userAssigned, gameBootOn, bootOn, installedIcons, userIcons)

    @Test
    fun `nothing to ask when the theme's media already plays`() {
        assertNull(prompt(installed = emptySet()))
        assertNull(prompt(installed = setOf("boot_video", "sound_scroll"), userAssigned = setOf(UiMediaSlot.SOUND_BACK)))
    }

    @Test
    fun `slots the user assigned and the theme also supplies are offered for replacement`() {
        val p = prompt(
            installed = setOf("boot_video", "sound_scroll", "gameboot_video"),
            userAssigned = setOf(UiMediaSlot.BOOT_VIDEO, UiMediaSlot.SOUND_SCROLL, UiMediaSlot.SOUND_ERROR),
        )!!
        assertEquals(listOf(UiMediaSlot.SOUND_SCROLL, UiMediaSlot.BOOT_VIDEO), p.replace)
        assertFalse(p.turnOnGameBoot)
        assertEquals("Use Theme's", p.confirmLabel)
        assertEquals("Keep Mine", p.cancelLabel)
        assertTrue(p.message.contains("Boot Video") && p.message.contains("Navigation sound"), p.message)
        assertFalse(p.message.contains("Error"), "a slot the theme does not supply is never touched")
    }

    @Test
    fun `a theme GameBoot clip with GameBoot off offers to turn it on`() {
        val p = prompt(installed = setOf("gameboot_video"), gameBootOn = false)!!
        assertTrue(p.turnOnGameBoot)
        assertTrue(p.replace.isEmpty())
        assertEquals("Turn On", p.confirmLabel)
        assertEquals("Not Now", p.cancelLabel)
        assertTrue(p.message.contains("GameBoot is off"), p.message)
    }

    @Test
    fun `a theme boot clip with the boot sequence off offers to turn it on`() {
        val p = prompt(installed = setOf("boot_video"), bootOn = false)!!
        assertTrue(p.turnOnBoot)
        assertTrue(p.message.contains("Boot Sequence is off"), p.message)
        assertNull(prompt(installed = setOf("sound_scroll"), bootOn = false, gameBootOn = false))
    }

    @Test
    fun `icons the user set and the theme also supplies are offered for replacement, in slot order`() {
        val p = prompt(
            installed = emptySet(),
            installedIcons = setOf("item_umd", "catbar_games", "sysicon_psx"),
            userIcons = setOf("sysicon_psx", "catbar_games", "catbar_music"),
        )!!
        assertEquals(listOf("catbar_games", "sysicon_psx"), p.replaceIcons)
        assertTrue(p.replace.isEmpty())
        assertEquals("Use This Theme's Icons?", p.title)
        assertEquals("Use Theme's", p.confirmLabel)
        assertEquals("Keep Mine", p.cancelLabel)
        assertTrue(p.message.contains("Games") && p.message.contains("PlayStation"), p.message)
        assertFalse(p.message.contains("Music"), "an icon the theme does not supply is never touched")
    }

    @Test
    fun `theme icons the user has not overridden ask nothing`() {
        assertNull(prompt(installed = emptySet(), installedIcons = setOf("catbar_games"), userIcons = setOf("catbar_music")))
    }

    @Test
    fun `icons and media in one prompt name both`() {
        val p = prompt(
            installed = setOf("sound_scroll"),
            userAssigned = setOf(UiMediaSlot.SOUND_SCROLL),
            installedIcons = setOf("catbar_games"),
            userIcons = setOf("catbar_games"),
        )!!
        assertEquals("Use This Theme's Icons and Media?", p.title)
        assertTrue(p.message.contains("Navigation sound") && p.message.contains("Games"), p.message)
    }

    @Test
    fun `one prompt carries both the replacements and the switches`() {
        val p = prompt(
            installed = setOf("gameboot_video"),
            userAssigned = setOf(UiMediaSlot.GAMEBOOT_VIDEO),
            gameBootOn = false,
        )!!
        assertEquals(listOf(UiMediaSlot.GAMEBOOT_VIDEO), p.replace)
        assertTrue(p.turnOnGameBoot)
        assertEquals("Use Theme's", p.confirmLabel)
    }
}
