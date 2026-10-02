package com.playfieldportal.core.ui.components

import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.sound.MenuSound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The focus rules every host of the shared modals applies from its own `handleGamepadAction`, so
 * the two modals move the same way wherever they appear: the field sits above a Cancel / Confirm
 * row, a disabled Confirm is never reachable, and a destructive confirm opens on Cancel.
 */
class PfpModalNavTest {

    private fun move(
        from: PfpModalFocus,
        action: GamepadAction,
        hasField: Boolean = true,
        confirmEnabled: Boolean = true,
    ) = PfpModalNav.move(from, action, hasField, confirmEnabled)

    @Test
    fun `a standard confirm opens on the confirm button and a destructive one on cancel`() {
        assertEquals(PfpModalFocus.CONFIRM, PfpModalNav.initialConfirmFocus(destructive = false))
        assertEquals(PfpModalFocus.CANCEL, PfpModalNav.initialConfirmFocus(destructive = true))
    }

    // ── The choice modal: up / down pick the option, left / right pick the button ──

    @Test
    fun `up and down step through a choice modal's options without wrapping`() {
        assertEquals(1, PfpModalNav.moveChoice(0, GamepadAction.NAVIGATE_DOWN, optionCount = 2))
        assertEquals(1, PfpModalNav.moveChoice(1, GamepadAction.NAVIGATE_DOWN, optionCount = 2))
        assertEquals(0, PfpModalNav.moveChoice(1, GamepadAction.NAVIGATE_UP, optionCount = 2))
        assertEquals(0, PfpModalNav.moveChoice(0, GamepadAction.NAVIGATE_UP, optionCount = 2))
    }

    @Test
    fun `only up and down change a choice modal's option`() {
        assertEquals(1, PfpModalNav.moveChoice(1, GamepadAction.NAVIGATE_LEFT, optionCount = 3))
        assertEquals(1, PfpModalNav.moveChoice(1, GamepadAction.SELECT, optionCount = 3))
    }

    @Test
    fun `left and right move between cancel and confirm`() {
        assertEquals(PfpModalFocus.CONFIRM, move(PfpModalFocus.CANCEL, GamepadAction.NAVIGATE_RIGHT))
        assertEquals(PfpModalFocus.CANCEL, move(PfpModalFocus.CONFIRM, GamepadAction.NAVIGATE_LEFT))
        // Already at the edge: stay put rather than wrap.
        assertEquals(PfpModalFocus.CANCEL, move(PfpModalFocus.CANCEL, GamepadAction.NAVIGATE_LEFT))
        assertEquals(PfpModalFocus.CONFIRM, move(PfpModalFocus.CONFIRM, GamepadAction.NAVIGATE_RIGHT))
    }

    @Test
    fun `down leaves the field for confirm and up returns to it`() {
        assertEquals(PfpModalFocus.CONFIRM, move(PfpModalFocus.FIELD, GamepadAction.NAVIGATE_DOWN))
        assertEquals(PfpModalFocus.FIELD, move(PfpModalFocus.CONFIRM, GamepadAction.NAVIGATE_UP))
        assertEquals(PfpModalFocus.FIELD, move(PfpModalFocus.CANCEL, GamepadAction.NAVIGATE_UP))
    }

    @Test
    fun `a disabled confirm is never reachable`() {
        assertEquals(
            PfpModalFocus.CANCEL,
            move(PfpModalFocus.CANCEL, GamepadAction.NAVIGATE_RIGHT, confirmEnabled = false),
        )
        assertEquals(
            PfpModalFocus.CANCEL,
            move(PfpModalFocus.FIELD, GamepadAction.NAVIGATE_DOWN, confirmEnabled = false),
        )
    }

    @Test
    fun `without a field up does nothing`() {
        assertEquals(
            PfpModalFocus.CONFIRM,
            move(PfpModalFocus.CONFIRM, GamepadAction.NAVIGATE_UP, hasField = false),
        )
    }

    @Test
    fun `left and right inside the field stay in the field`() {
        // The text cursor owns them while typing.
        assertEquals(PfpModalFocus.FIELD, move(PfpModalFocus.FIELD, GamepadAction.NAVIGATE_LEFT))
        assertEquals(PfpModalFocus.FIELD, move(PfpModalFocus.FIELD, GamepadAction.NAVIGATE_RIGHT))
    }

    @Test
    fun `actions that are not directions never move focus`() {
        for (action in listOf(GamepadAction.SELECT, GamepadAction.BACK, GamepadAction.OPEN_CONTEXT_MENU)) {
            assertEquals(PfpModalFocus.CANCEL, move(PfpModalFocus.CANCEL, action))
        }
    }

    @Test
    fun `focus on confirm falls back to cancel when confirm becomes disabled`() {
        assertEquals(PfpModalFocus.CANCEL, PfpModalNav.coerce(PfpModalFocus.CONFIRM, confirmEnabled = false))
        assertEquals(PfpModalFocus.CONFIRM, PfpModalNav.coerce(PfpModalFocus.CONFIRM, confirmEnabled = true))
        assertEquals(PfpModalFocus.FIELD, PfpModalNav.coerce(PfpModalFocus.FIELD, confirmEnabled = false))
    }

    // ── A host's whole interceptor: one call per press ──────────────────────────

    private class Outcome {
        val sounds = mutableListOf<MenuSound>()
        var focus: PfpModalFocus? = null
        var confirms = 0
        var cancels = 0
    }

    private fun press(
        action: GamepadAction,
        focus: PfpModalFocus,
        hasField: Boolean = false,
        confirmEnabled: Boolean = true,
    ): Outcome {
        val outcome = Outcome()
        PfpModalNav.handle(
            action = action,
            focus = focus,
            hasField = hasField,
            confirmEnabled = confirmEnabled,
            sounds = { outcome.sounds += it },
            onFocusChange = { outcome.focus = it },
            onConfirm = { outcome.confirms++ },
            onCancel = { outcome.cancels++ },
        )
        return outcome
    }

    @Test
    fun `a move that lands somewhere new reports the focus and ticks`() {
        val outcome = press(GamepadAction.NAVIGATE_LEFT, PfpModalFocus.CONFIRM)

        assertEquals(PfpModalFocus.CANCEL, outcome.focus)
        assertEquals(listOf(MenuSound.SCROLL), outcome.sounds)
    }

    @Test
    fun `a move against the edge does nothing and is silent`() {
        val outcome = press(GamepadAction.NAVIGATE_LEFT, PfpModalFocus.CANCEL)

        assertEquals(null, outcome.focus)
        assertTrue(outcome.sounds.isEmpty())
    }

    @Test
    fun `select on confirm commits and select on cancel backs out`() {
        val confirmed = press(GamepadAction.SELECT, PfpModalFocus.CONFIRM)
        assertEquals(1 to 0, confirmed.confirms to confirmed.cancels)
        assertEquals(listOf(MenuSound.CONFIRM), confirmed.sounds)

        val cancelled = press(GamepadAction.SELECT, PfpModalFocus.CANCEL)
        assertEquals(0 to 1, cancelled.confirms to cancelled.cancels)
        assertEquals(listOf(MenuSound.BACK), cancelled.sounds)
    }

    @Test
    fun `select on a destructive confirm's opening focus cancels and never confirms`() {
        val outcome = press(GamepadAction.SELECT, PfpModalNav.initialConfirmFocus(destructive = true))

        assertEquals(0 to 1, outcome.confirms to outcome.cancels)
        assertEquals(listOf(MenuSound.BACK), outcome.sounds)
    }

    @Test
    fun `select on a disabled confirm does nothing`() {
        val outcome = press(GamepadAction.SELECT, PfpModalFocus.CONFIRM, confirmEnabled = false)

        assertEquals(0 to 0, outcome.confirms to outcome.cancels)
        assertTrue(outcome.sounds.isEmpty())
    }

    @Test
    fun `select in the field does nothing`() {
        // The keyboard is up; its own Done key is the way to save from the field.
        val outcome = press(GamepadAction.SELECT, PfpModalFocus.FIELD, hasField = true)

        assertEquals(0 to 0, outcome.confirms to outcome.cancels)
    }

    @Test
    fun `back cancels from anywhere`() {
        for (focus in PfpModalFocus.entries) {
            val outcome = press(GamepadAction.BACK, focus, hasField = true)
            assertEquals(0 to 1, outcome.confirms to outcome.cancels)
            assertEquals(listOf(MenuSound.BACK), outcome.sounds)
        }
    }

    @Test
    fun `a notice is dismissed by select or back and swallows everything else`() {
        for (action in GamepadAction.entries) {
            val sounds = mutableListOf<MenuSound>()
            var dismissals = 0

            PfpModalNav.handleNotice(action, sounds = { sounds += it }, onDismiss = { dismissals++ })

            val dismisses = action == GamepadAction.SELECT || action == GamepadAction.BACK
            assertEquals(if (dismisses) 1 else 0, dismissals)
            // Nothing was committed, so dismissing is a back whichever button did it.
            assertEquals(if (dismisses) listOf(MenuSound.BACK) else emptyList(), sounds)
        }
    }

    @Test
    fun `text entry can be confirmed only with a real name, no error, and within the limit`() {
        assertTrue(PfpModalNav.textEntryConfirmEnabled("Survival Horror", error = null, maxLength = 40))
        assertTrue(PfpModalNav.textEntryConfirmEnabled("Survival Horror", error = null, maxLength = null))
        assertFalse(PfpModalNav.textEntryConfirmEnabled("", error = null, maxLength = 40))
        assertFalse(PfpModalNav.textEntryConfirmEnabled("   ", error = null, maxLength = 40))
        assertFalse(PfpModalNav.textEntryConfirmEnabled("Favorites", error = "Already exists.", maxLength = 40))
        assertFalse(PfpModalNav.textEntryConfirmEnabled("Too long", error = null, maxLength = 4))
    }

    @Test
    fun `a field that may be cleared can be confirmed blank, but still not with an error`() {
        // Rename Shortcut and Edit Title treat an empty field as "back to the default".
        assertTrue(PfpModalNav.textEntryConfirmEnabled("", error = null, maxLength = null, allowBlank = true))
        assertTrue(PfpModalNav.textEntryConfirmEnabled("  ", error = null, maxLength = null, allowBlank = true))
        assertFalse(PfpModalNav.textEntryConfirmEnabled("", error = "Nope.", maxLength = null, allowBlank = true))
    }
}
