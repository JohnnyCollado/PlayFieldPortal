package com.playfieldportal.core.domain.model

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 — the inverse lookup that footers are built on.
 *
 * Behaviour reads keycode → action. Chrome needs the opposite: given an action,
 * which physical button currently performs it? Deriving prompts from the same
 * [GamepadMappings] the input handler consumes is what makes footer drift
 * structurally impossible, rather than a thing to remember.
 */
class ControllerIconLookupTest {

    private fun mappings(confirmBack: ConfirmBackLayout, xy: XYLayout) =
        gamepadMappingsFor(confirmBack, xy)

    private val standard = mappings(ConfirmBackLayout.STANDARD, XYLayout.STANDARD)

    // ── Keycode → physical position ─────────────────────────────────────────

    @Test
    fun `face keycodes map to face positions`() {
        assertEquals(ControllerIcon.FACE_SOUTH, KeyEvent.KEYCODE_BUTTON_A.toControllerIcon())
        assertEquals(ControllerIcon.FACE_EAST, KeyEvent.KEYCODE_BUTTON_B.toControllerIcon())
        assertEquals(ControllerIcon.FACE_WEST, KeyEvent.KEYCODE_BUTTON_X.toControllerIcon())
        assertEquals(ControllerIcon.FACE_NORTH, KeyEvent.KEYCODE_BUTTON_Y.toControllerIcon())
    }

    @Test
    fun `shoulder and trigger keycodes map to their positions`() {
        assertEquals(ControllerIcon.BUMPER_LEFT, KeyEvent.KEYCODE_BUTTON_L1.toControllerIcon())
        assertEquals(ControllerIcon.BUMPER_RIGHT, KeyEvent.KEYCODE_BUTTON_R1.toControllerIcon())
        assertEquals(ControllerIcon.TRIGGER_LEFT, KeyEvent.KEYCODE_BUTTON_L2.toControllerIcon())
        assertEquals(ControllerIcon.TRIGGER_RIGHT, KeyEvent.KEYCODE_BUTTON_R2.toControllerIcon())
    }

    @Test
    fun `dpad, stick-click and utility keycodes map to their positions`() {
        assertEquals(ControllerIcon.DPAD_UP, KeyEvent.KEYCODE_DPAD_UP.toControllerIcon())
        assertEquals(ControllerIcon.DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN.toControllerIcon())
        assertEquals(ControllerIcon.DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT.toControllerIcon())
        assertEquals(ControllerIcon.DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT.toControllerIcon())
        assertEquals(ControllerIcon.STICK_LEFT_CLICK, KeyEvent.KEYCODE_BUTTON_THUMBL.toControllerIcon())
        assertEquals(ControllerIcon.STICK_RIGHT_CLICK, KeyEvent.KEYCODE_BUTTON_THUMBR.toControllerIcon())
        assertEquals(ControllerIcon.START, KeyEvent.KEYCODE_BUTTON_START.toControllerIcon())
        assertEquals(ControllerIcon.SELECT, KeyEvent.KEYCODE_BUTTON_SELECT.toControllerIcon())
    }

    @Test
    fun `keys that are not on a gamepad have no position`() {
        // Enter, hardware Back and D-pad centre drive the UI but are not buttons
        // a prompt can point at. They must never leak into a footer.
        assertNull(KeyEvent.KEYCODE_ENTER.toControllerIcon())
        assertNull(KeyEvent.KEYCODE_BACK.toControllerIcon())
        assertNull(KeyEvent.KEYCODE_DPAD_CENTER.toControllerIcon())
        assertNull(KeyEvent.KEYCODE_SPACE.toControllerIcon())
    }

    // ── Action → position, per layout ───────────────────────────────────────

    @Test
    fun `confirm and back follow the Confirm-Back setting`() {
        assertEquals(ControllerIcon.FACE_SOUTH, standard.iconFor(GamepadAction.SELECT))
        assertEquals(ControllerIcon.FACE_EAST, standard.iconFor(GamepadAction.BACK))

        val reversed = mappings(ConfirmBackLayout.REVERSED, XYLayout.STANDARD)
        assertEquals(ControllerIcon.FACE_EAST, reversed.iconFor(GamepadAction.SELECT))
        assertEquals(ControllerIcon.FACE_SOUTH, reversed.iconFor(GamepadAction.BACK))
    }

    @Test
    fun `the context menu prompt follows the X-Y setting`() {
        // This is the ContextMenuHint bug: it hardcoded FACE_NORTH, which is
        // wrong the moment the user swaps X and Y.
        assertEquals(ControllerIcon.FACE_NORTH, standard.iconFor(GamepadAction.OPEN_CONTEXT_MENU))

        val swapped = mappings(ConfirmBackLayout.STANDARD, XYLayout.SWAPPED)
        assertEquals(ControllerIcon.FACE_WEST, swapped.iconFor(GamepadAction.OPEN_CONTEXT_MENU))
    }

    @Test
    fun `sort follows the X-Y setting`() {
        assertEquals(ControllerIcon.FACE_WEST, standard.iconFor(GamepadAction.CHANGE_SORT))

        val swapped = mappings(ConfirmBackLayout.STANDARD, XYLayout.SWAPPED)
        assertEquals(ControllerIcon.FACE_NORTH, swapped.iconFor(GamepadAction.CHANGE_SORT))
    }

    @Test
    fun `the two settings compose independently`() {
        val both = mappings(ConfirmBackLayout.REVERSED, XYLayout.SWAPPED)
        assertEquals(ControllerIcon.FACE_EAST, both.iconFor(GamepadAction.SELECT))
        assertEquals(ControllerIcon.FACE_SOUTH, both.iconFor(GamepadAction.BACK))
        assertEquals(ControllerIcon.FACE_WEST, both.iconFor(GamepadAction.OPEN_CONTEXT_MENU))
        assertEquals(ControllerIcon.FACE_NORTH, both.iconFor(GamepadAction.CHANGE_SORT))
    }

    @Test
    fun `category shoulders are fixed regardless of layout`() {
        for (confirmBack in ConfirmBackLayout.entries) {
            for (xy in XYLayout.entries) {
                val m = mappings(confirmBack, xy)
                assertEquals(ControllerIcon.BUMPER_LEFT, m.iconFor(GamepadAction.PREV_CATEGORY))
                assertEquals(ControllerIcon.BUMPER_RIGHT, m.iconFor(GamepadAction.NEXT_CATEGORY))
            }
        }
    }

    // ── Alias handling: the subtle failure ──────────────────────────────────

    @Test
    fun `SELECT resolves to a face button, never to one of its keyboard aliases`() {
        // SELECT is bound three times (BUTTON_A, ENTER, DPAD_CENTER). A naive
        // firstOrNull over the binding list can hand the footer a D-pad glyph
        // for "Launch" depending purely on list order.
        for (confirmBack in ConfirmBackLayout.entries) {
            for (xy in XYLayout.entries) {
                val icon = mappings(confirmBack, xy).iconFor(GamepadAction.SELECT)
                assertTrue(
                    "SELECT resolved to $icon, which is not a face button",
                    icon == ControllerIcon.FACE_SOUTH || icon == ControllerIcon.FACE_EAST,
                )
            }
        }
    }

    @Test
    fun `BACK resolves to a face button, never to the hardware Back key`() {
        for (confirmBack in ConfirmBackLayout.entries) {
            for (xy in XYLayout.entries) {
                val icon = mappings(confirmBack, xy).iconFor(GamepadAction.BACK)
                assertTrue(
                    "BACK resolved to $icon, which is not a face button",
                    icon == ControllerIcon.FACE_SOUTH || icon == ControllerIcon.FACE_EAST,
                )
            }
        }
    }

    @Test
    fun `a resolved icon is always reachable from the action it came from`() {
        // Round-trip: whatever position we show, pressing it must dispatch the
        // action the label promised.
        for (confirmBack in ConfirmBackLayout.entries) {
            for (xy in XYLayout.entries) {
                val m = mappings(confirmBack, xy)
                for (action in GamepadAction.entries) {
                    val icon = m.iconFor(action) ?: continue
                    val keys = m.bindings
                        .filter { it.keyCode.toControllerIcon() == icon }
                        .map { it.action }
                    assertTrue(
                        "$action renders as $icon but that button dispatches $keys",
                        action in keys,
                    )
                }
            }
        }
    }

    // ── Degradation ─────────────────────────────────────────────────────────

    @Test
    fun `an unbound action has no prompt rather than a wrong one`() {
        val stripped = GamepadMappings(
            standard.bindings.filterNot { it.action == GamepadAction.CHANGE_SORT },
        )
        assertNull(stripped.iconFor(GamepadAction.CHANGE_SORT))
        // Everything else still resolves.
        assertNotNull(stripped.iconFor(GamepadAction.SELECT))
    }

    @Test
    fun `an action bound only to a keyboard alias has no prompt`() {
        val keyboardOnly = GamepadMappings(
            listOf(GamepadBinding(KeyEvent.KEYCODE_ENTER, GamepadAction.SELECT)),
        )
        assertNull(keyboardOnly.iconFor(GamepadAction.SELECT))
    }

    // ── Multi-input prompts ─────────────────────────────────────────────────

    @Test
    fun `a multi-input prompt keeps the order it was asked for`() {
        // "◀▶ Seek" must not come out as "▶◀".
        assertEquals(
            listOf(ControllerIcon.DPAD_LEFT, ControllerIcon.DPAD_RIGHT),
            standard.iconsFor(listOf(GamepadAction.NAVIGATE_LEFT, GamepadAction.NAVIGATE_RIGHT)),
        )
        assertEquals(
            listOf(ControllerIcon.DPAD_RIGHT, ControllerIcon.DPAD_LEFT),
            standard.iconsFor(listOf(GamepadAction.NAVIGATE_RIGHT, GamepadAction.NAVIGATE_LEFT)),
        )
    }

    @Test
    fun `an unbound member drops out and the rest of the prompt survives`() {
        val stripped = GamepadMappings(
            standard.bindings.filterNot { it.action == GamepadAction.NEXT_CATEGORY },
        )
        assertEquals(
            listOf(ControllerIcon.BUMPER_LEFT),
            stripped.iconsFor(listOf(GamepadAction.PREV_CATEGORY, GamepadAction.NEXT_CATEGORY)),
        )
    }

    @Test
    fun `a prompt whose every member is unbound resolves to nothing`() {
        // The caller renders nothing at all rather than a label with no glyph.
        val keyboardOnly = GamepadMappings(
            listOf(GamepadBinding(KeyEvent.KEYCODE_ENTER, GamepadAction.SELECT)),
        )
        assertTrue(keyboardOnly.iconsFor(listOf(GamepadAction.SELECT)).isEmpty())
    }

    @Test
    fun `two actions on one button draw that button once`() {
        // A remap can land both members of a pair on the same physical button.
        // The same glyph twice reads as a broken prompt, not as a pair.
        val collapsed = GamepadMappings(
            listOf(
                GamepadBinding(KeyEvent.KEYCODE_BUTTON_A, GamepadAction.SELECT),
                GamepadBinding(KeyEvent.KEYCODE_BUTTON_A, GamepadAction.BACK),
            ),
        )
        assertEquals(
            listOf(ControllerIcon.FACE_SOUTH),
            collapsed.iconsFor(listOf(GamepadAction.SELECT, GamepadAction.BACK)),
        )
    }

    @Test
    fun `each member agrees with the single-action lookup`() {
        for (confirmBack in ConfirmBackLayout.entries) {
            for (xy in XYLayout.entries) {
                val m = mappings(confirmBack, xy)
                val actions = GamepadAction.entries.toList()
                assertEquals(
                    actions.mapNotNull { m.iconFor(it) }.distinct(),
                    m.iconsFor(actions),
                )
            }
        }
    }

    // ── The media footers, end to end ───────────────────────────────────────

    @Test
    fun `the video transport bar resolves under every layout combination`() {
        // The Music/Video/Photo footers were hardcoded Xbox letters; they are
        // now action-driven, so every entry must resolve under every layout.
        val transport = listOf(
            listOf(GamepadAction.SELECT),
            listOf(GamepadAction.NAVIGATE_LEFT, GamepadAction.NAVIGATE_RIGHT),
            listOf(GamepadAction.PREV_CATEGORY, GamepadAction.NEXT_CATEGORY),
            listOf(GamepadAction.OPEN_CONTEXT_MENU),
        )
        for (confirmBack in ConfirmBackLayout.entries) {
            for (xy in XYLayout.entries) {
                val m = mappings(confirmBack, xy)
                for (prompt in transport) {
                    assertEquals(
                        "$prompt lost a glyph under $confirmBack/$xy",
                        prompt.size,
                        m.iconsFor(prompt).size,
                    )
                }
            }
        }
    }

    @Test
    fun `the track picker Add prompt sits on Start under every layout`() {
        // HOME is the picker's confirm ("Start to add"); the layout settings
        // touch only the face buttons and must never move it.
        for (confirmBack in ConfirmBackLayout.entries) {
            for (xy in XYLayout.entries) {
                assertEquals(
                    ControllerIcon.START,
                    mappings(confirmBack, xy).iconFor(GamepadAction.HOME),
                )
            }
        }
    }

    // ── The App Drawer footer, end to end ───────────────────────────────────

    @Test
    fun `the drawer command bar resolves under every layout combination`() {
        val commandBar = listOf(
            GamepadAction.PREV_CATEGORY,
            GamepadAction.NEXT_CATEGORY,
            GamepadAction.BACK,
            GamepadAction.SELECT,
            GamepadAction.OPEN_CONTEXT_MENU,
            GamepadAction.CHANGE_SORT,
        )
        for (confirmBack in ConfirmBackLayout.entries) {
            for (xy in XYLayout.entries) {
                val m = mappings(confirmBack, xy)
                val icons = commandBar.map { action ->
                    requireNotNull(m.iconFor(action)) { "$action has no prompt under $confirmBack/$xy" }
                }
                assertEquals(
                    "$confirmBack/$xy draws the same glyph twice in the command bar",
                    commandBar.size,
                    icons.toSet().size,
                )
            }
        }
    }
}
