package com.playfieldportal.feature.settings.ui

import com.playfieldportal.core.data.repository.PfpThemeStore
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.sound.MenuSoundSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Themes > My Themes: a saved theme is deleted only after a confirm. The menu's last row is the red
 * "Delete Theme"; activating it only asks, and the delete runs on Confirm alone.
 */
class SavedThemeMenuTest {

    private val theme = PfpThemeStore.SavedTheme("t1", "Summer Trip", 0xFFFF7070L, null)
    private val calls = mutableListOf<String>()

    private fun rows() = savedThemeMenuRows(
        theme,
        onShare = { calls += "share:$it" },
        onRequestRename = { calls += "rename:${it.id}" },
        onRequestDelete = { calls += "ask:${it.id}" },
    )

    // Applying is X on the card, so the menu does not repeat it.
    @Test
    fun `the menu is Share, Rename Theme then Delete Theme, with no Apply`() {
        assertEquals(listOf("Share", "Rename Theme", "Delete Theme"), rows().map { it.label })
    }

    @Test
    fun `Rename Theme only asks for a name`() {
        rows()[1].action()

        assertEquals(listOf("rename:t1"), calls)
    }

    @Test
    fun `the rename entry starts from the current name and commits the typed one`() {
        var renamed: String? = null
        val spec = renameThemeSpec(theme, onConfirm = { renamed = it }, onCancel = {})

        assertEquals("Summer Trip", spec.initial)
        assertEquals("Rename Theme", spec.title)
        spec.onConfirm("Winter")
        assertEquals("Winter", renamed)
    }

    // ── The shared Settings item menu (SettingsItemMenuState) ─────────────

    private val played = mutableListOf<MenuSound>()
    private val ran = mutableListOf<String>()

    private fun menu(): SettingsItemMenuState {
        val state = SettingsItemMenuState(MenuSoundSink { played += it })
        state.show(
            "Menu",
            listOf(
                SettingsMenuItem("One") { ran += "one" },
                SettingsMenuItem("Two") { ran += "two" },
            ),
        )
        played.clear()
        return state
    }

    @Test
    fun `a closed menu consumes nothing`() {
        val state = SettingsItemMenuState(MenuSoundSink { played += it })

        assertFalse(state.intercept(GamepadAction.SELECT))
        assertFalse(state.open)
    }

    @Test
    fun `an open menu swallows every press, and the cursor clamps without wrapping`() {
        val state = menu()

        assertTrue(state.intercept(GamepadAction.NAVIGATE_LEFT))
        assertTrue(state.intercept(GamepadAction.NAVIGATE_UP))
        assertEquals(0, state.index)
        assertTrue(state.intercept(GamepadAction.NAVIGATE_DOWN))
        assertEquals(1, state.index)
        assertTrue(state.intercept(GamepadAction.NAVIGATE_DOWN))
        assertEquals(1, state.index)
        assertEquals(listOf(MenuSound.SCROLL), played)
    }

    @Test
    fun `SELECT runs the row, closes the menu and plays the commit cue`() {
        val state = menu()
        state.intercept(GamepadAction.NAVIGATE_DOWN)
        played.clear()

        assertTrue(state.intercept(GamepadAction.SELECT))

        assertEquals(listOf("two"), ran)
        assertFalse(state.open)
        assertEquals(listOf(MenuSound.CONFIRM), played)
    }

    @Test
    fun `Back and Triangle close without running anything`() {
        listOf(GamepadAction.BACK, GamepadAction.OPEN_CONTEXT_MENU).forEach { press ->
            val state = menu()

            assertTrue(state.intercept(press))

            assertFalse(state.open)
            assertEquals(listOf(MenuSound.BACK), played)
            played.clear()
        }
        assertTrue(ran.isEmpty())
    }

    @Test
    fun `the menu ends with a destructive Delete Theme row`() {
        val last = rows().last()

        assertEquals("Delete Theme", last.label)
        assertTrue(last.destructive)
        assertTrue(rows().dropLast(1).none { it.destructive })
    }

    @Test
    fun `activating Delete Theme only asks, it does not delete`() {
        rows().last().action()

        assertEquals(listOf("ask:t1"), calls)
    }

    @Test
    fun `the confirm is destructive, opens on cancel and names the theme`() {
        val spec = deleteThemeConfirmSpec(theme, onConfirm = {}, onCancel = {})

        assertTrue(spec.destructive)
        assertTrue(spec.openOnCancel)
        assertTrue(spec.message.contains("Summer Trip"))
    }

    @Test
    fun `the delete runs on Confirm and not on Cancel`() {
        var deleted = 0
        var cancelled = 0
        val spec = deleteThemeConfirmSpec(theme, onConfirm = { deleted++ }, onCancel = { cancelled++ })

        spec.onCancel()
        assertEquals(0 to 1, deleted to cancelled)

        spec.onConfirm()
        assertEquals(1 to 1, deleted to cancelled)
    }
}
