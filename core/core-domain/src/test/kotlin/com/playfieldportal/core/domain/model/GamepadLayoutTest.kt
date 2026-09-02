package com.playfieldportal.core.domain.model

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 — one action per physical button.
 *
 * The app used to carry four names for two secondary actions (`BUTTON_Y` +
 * `LONG_PRESS` both opened the context menu; `BUTTON_X` + `CHANGE_SORT` +
 * `OPEN_TASK_TRAY` all cycled sort). Worse, [DEFAULT_BINDINGS] and the
 * settings-driven rebuild disagreed about which of those names the X and Y
 * keycodes emitted, so a button changed meaning the first time the user opened
 * controller settings — silently killing the App Drawer's search toggle.
 *
 * These tests pin the collapsed vocabulary and, above all, that the default
 * bindings and the STANDARD layout rebuild are the *same table*.
 */
class GamepadLayoutTest {

    private val faceKeys = listOf(
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_BUTTON_Y,
    )

    private fun layout(confirmBack: ConfirmBackLayout, xy: XYLayout) =
        gamepadMappingsFor(confirmBack, xy)

    private val standard = layout(ConfirmBackLayout.STANDARD, XYLayout.STANDARD)

    // ── The regression that started this ─────────────────────────────────────

    @Test
    fun `default bindings equal the STANDARD layout rebuild`() {
        // A fresh install and a user who has merely opened controller settings
        // must be running the identical table. This is the invariant that broke.
        for (key in faceKeys) {
            assertEquals(
                "keycode $key means something different before and after a settings write",
                GamepadMappings().actionFor(key),
                standard.actionFor(key),
            )
        }
    }

    @Test
    fun `default bindings equal the STANDARD rebuild for every bound keycode`() {
        val defaults = GamepadMappings()
        val everyKey = (defaults.bindings.map { it.keyCode } + standard.bindings.map { it.keyCode })
            .distinct()
        for (key in everyKey) {
            assertEquals(
                "keycode $key diverges between defaults and the STANDARD rebuild",
                defaults.actionFor(key),
                standard.actionFor(key),
            )
        }
    }

    // ── Collapsed vocabulary ────────────────────────────────────────────────

    @Test
    fun `the retired action names are gone`() {
        val names = GamepadAction.entries.map { it.name }
        for (retired in listOf("BUTTON_X", "BUTTON_Y", "LONG_PRESS", "OPEN_TASK_TRAY")) {
            assertTrue(
                "$retired still exists — two names for one action lets the footer contradict the pad",
                retired !in names,
            )
        }
    }

    @Test
    fun `each layout binds each face button exactly once`() {
        for (confirmBack in ConfirmBackLayout.entries) {
            for (xy in XYLayout.entries) {
                val mappings = layout(confirmBack, xy)
                for (key in faceKeys) {
                    val bound = mappings.bindings.filter { it.keyCode == key }
                    assertEquals("$confirmBack/$xy binds keycode $key ${bound.size} times", 1, bound.size)
                }
            }
        }
    }

    @Test
    fun `the four face actions are distinct in every layout`() {
        for (confirmBack in ConfirmBackLayout.entries) {
            for (xy in XYLayout.entries) {
                val mappings = layout(confirmBack, xy)
                val actions = faceKeys.map { mappings.actionFor(it) }
                assertEquals("$confirmBack/$xy doubles up a face action", 4, actions.toSet().size)
            }
        }
    }

    // ── Confirm / Back ──────────────────────────────────────────────────────

    @Test
    fun `STANDARD puts confirm on the south face`() {
        assertEquals(GamepadAction.SELECT, standard.actionFor(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(GamepadAction.BACK, standard.actionFor(KeyEvent.KEYCODE_BUTTON_B))
    }

    @Test
    fun `REVERSED moves confirm to the east face and nothing else`() {
        val reversed = layout(ConfirmBackLayout.REVERSED, XYLayout.STANDARD)
        assertEquals(GamepadAction.BACK, reversed.actionFor(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(GamepadAction.SELECT, reversed.actionFor(KeyEvent.KEYCODE_BUTTON_B))
        // The X/Y pair must not move when only Confirm/Back is reversed.
        assertEquals(
            standard.actionFor(KeyEvent.KEYCODE_BUTTON_X),
            reversed.actionFor(KeyEvent.KEYCODE_BUTTON_X),
        )
        assertEquals(
            standard.actionFor(KeyEvent.KEYCODE_BUTTON_Y),
            reversed.actionFor(KeyEvent.KEYCODE_BUTTON_Y),
        )
    }

    // ── X / Y ───────────────────────────────────────────────────────────────

    @Test
    fun `STANDARD puts the context menu on the north face and sort on the west`() {
        assertEquals(GamepadAction.OPEN_CONTEXT_MENU, standard.actionFor(KeyEvent.KEYCODE_BUTTON_Y))
        assertEquals(GamepadAction.CHANGE_SORT, standard.actionFor(KeyEvent.KEYCODE_BUTTON_X))
    }

    @Test
    fun `SWAPPED exchanges the two secondary actions and nothing else`() {
        val swapped = layout(ConfirmBackLayout.STANDARD, XYLayout.SWAPPED)
        assertEquals(GamepadAction.CHANGE_SORT, swapped.actionFor(KeyEvent.KEYCODE_BUTTON_Y))
        assertEquals(GamepadAction.OPEN_CONTEXT_MENU, swapped.actionFor(KeyEvent.KEYCODE_BUTTON_X))
        assertEquals(
            standard.actionFor(KeyEvent.KEYCODE_BUTTON_A),
            swapped.actionFor(KeyEvent.KEYCODE_BUTTON_A),
        )
        assertEquals(
            standard.actionFor(KeyEvent.KEYCODE_BUTTON_B),
            swapped.actionFor(KeyEvent.KEYCODE_BUTTON_B),
        )
    }

    // ── Rebuild hygiene ─────────────────────────────────────────────────────

    @Test
    fun `rebuilding is idempotent`() {
        // The repository rebuilds the whole table on every write rather than
        // mutating entries, so toggling a setting twice must land exactly back.
        assertEquals(standard.bindings, layout(ConfirmBackLayout.STANDARD, XYLayout.STANDARD).bindings)
    }

    @Test
    fun `every layout preserves the non-face aliases`() {
        // Enter, hardware Back and D-pad centre are how a keyboard or a TV remote
        // drives the UI. An earlier per-action remap path stripped these.
        val aliases = listOf(
            KeyEvent.KEYCODE_ENTER to GamepadAction.SELECT,
            KeyEvent.KEYCODE_DPAD_CENTER to GamepadAction.SELECT,
            KeyEvent.KEYCODE_BACK to GamepadAction.BACK,
            KeyEvent.KEYCODE_DPAD_UP to GamepadAction.NAVIGATE_UP,
            KeyEvent.KEYCODE_BUTTON_L1 to GamepadAction.PREV_CATEGORY,
            KeyEvent.KEYCODE_BUTTON_R1 to GamepadAction.NEXT_CATEGORY,
        )
        for (confirmBack in ConfirmBackLayout.entries) {
            for (xy in XYLayout.entries) {
                val mappings = layout(confirmBack, xy)
                for ((key, action) in aliases) {
                    assertEquals("$confirmBack/$xy dropped alias $key", action, mappings.actionFor(key))
                }
            }
        }
    }

    @Test
    fun `every action a layout can produce is reachable`() {
        val produced = buildSet {
            for (confirmBack in ConfirmBackLayout.entries) {
                for (xy in XYLayout.entries) {
                    addAll(layout(confirmBack, xy).bindings.map { it.action })
                }
            }
        }
        // LONG_PRESS used to be keyless and touch-only; after the collapse the
        // context-menu action must be reachable from the pad in every layout.
        assertTrue(GamepadAction.OPEN_CONTEXT_MENU in produced)
        assertTrue(GamepadAction.CHANGE_SORT in produced)
        assertTrue(GamepadAction.SELECT in produced)
        assertTrue(GamepadAction.BACK in produced)
    }

    // ── Persisted-name migration ────────────────────────────────────────────

    @Test
    fun `legacy persisted action names migrate instead of resetting the user`() {
        // Saved mappings are JSON holding enum names. Without an explicit
        // migration the renamed constants fail to parse and the whole table
        // silently falls back to defaults, discarding the user's layout.
        assertEquals(GamepadAction.OPEN_CONTEXT_MENU, gamepadActionFromPersistedName("BUTTON_Y"))
        assertEquals(GamepadAction.OPEN_CONTEXT_MENU, gamepadActionFromPersistedName("LONG_PRESS"))
        assertEquals(GamepadAction.CHANGE_SORT, gamepadActionFromPersistedName("BUTTON_X"))
        assertEquals(GamepadAction.CHANGE_SORT, gamepadActionFromPersistedName("OPEN_TASK_TRAY"))
    }

    @Test
    fun `current action names still round-trip`() {
        for (action in GamepadAction.entries) {
            assertEquals(action, gamepadActionFromPersistedName(action.name))
        }
    }

    @Test
    fun `an unrecognised persisted name is rejected rather than guessed`() {
        assertNull(gamepadActionFromPersistedName("NOT_AN_ACTION"))
    }

    // ── Labels ──────────────────────────────────────────────────────────────

    @Test
    fun `every action has a display label`() {
        for (action in GamepadAction.entries) {
            assertNotNull(action.displayLabel())
        }
    }

    @Test
    fun `no label still advertises the removed task tray`() {
        // The task tray feature was deleted; the settings copy still promised it.
        val copy = GamepadAction.entries.joinToString(" ") { it.displayLabel() } +
            ConfirmBackLayout.entries.joinToString(" ") { it.displayLabel() } +
            XYLayout.entries.joinToString(" ") { it.displayLabel() }
        assertTrue("UI copy still references the removed Task Tray", !copy.contains("Task Tray", ignoreCase = true))
    }
}
