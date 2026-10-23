package com.playfieldportal.core.domain.model

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The physical Select button opens the focused category's menu — the same menu a long-press on a
 * category icon opens, which a controller had no way to reach. It used to duplicate X (sort).
 */
class SelectButtonBindingTest {

    @Test
    fun `Select is bound to the category menu by default`() {
        assertEquals(GamepadAction.CATEGORY_MENU, GamepadMappings().actionFor(KeyEvent.KEYCODE_BUTTON_SELECT))
    }

    @Test
    fun `every layout keeps Select on the category menu and X or Y on sort`() {
        ConfirmBackLayout.entries.forEach { cb ->
            XYLayout.entries.forEach { xy ->
                val m = gamepadMappingsFor(cb, xy)
                assertEquals(GamepadAction.CATEGORY_MENU, m.actionFor(KeyEvent.KEYCODE_BUTTON_SELECT))
                assertEquals(1, listOf(KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y).count { m.actionFor(it) == GamepadAction.CHANGE_SORT })
            }
        }
    }

    @Test
    fun `a table saved before the change gets the new Select binding and nothing else changes`() {
        val saved = GamepadMappings(
            DEFAULT_BINDINGS.map {
                if (it.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) it.copy(action = GamepadAction.CHANGE_SORT) else it
            },
        )

        val upgraded = saved.withCurrentSelectBinding()

        assertEquals(GamepadAction.CATEGORY_MENU, upgraded.actionFor(KeyEvent.KEYCODE_BUTTON_SELECT))
        assertEquals(saved.bindings.filter { it.keyCode != KeyEvent.KEYCODE_BUTTON_SELECT },
            upgraded.bindings.filter { it.keyCode != KeyEvent.KEYCODE_BUTTON_SELECT })
    }

    @Test
    fun `the action has a readable name`() {
        assertEquals("Category Menu", GamepadAction.CATEGORY_MENU.displayLabel())
    }
}
