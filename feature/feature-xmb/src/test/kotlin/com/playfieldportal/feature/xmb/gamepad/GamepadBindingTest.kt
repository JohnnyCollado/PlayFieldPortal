package com.playfieldportal.feature.xmb.gamepad

import android.view.KeyEvent
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.GamepadBinding
import com.playfieldportal.core.domain.model.GamepadMappings
import com.playfieldportal.core.domain.model.displayLabel
import com.playfieldportal.core.domain.model.keycodeDisplayName
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GamepadBindingTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `GamepadMappings default bindings are not empty`() {
        val defaults = GamepadMappings()
        assert(defaults.bindings.isNotEmpty())
    }

    @Test
    fun `default bindings contain SELECT for BUTTON_A`() {
        val defaults = GamepadMappings()
        val binding = defaults.bindings.firstOrNull { it.keyCode == KeyEvent.KEYCODE_BUTTON_A }
        assertNotNull("Expected a binding for BUTTON_A", binding)
        assertEquals(GamepadAction.SELECT, binding!!.action)
    }

    @Test
    fun `default bindings contain BACK for BUTTON_B`() {
        val defaults = GamepadMappings()
        val binding = defaults.bindings.firstOrNull { it.keyCode == KeyEvent.KEYCODE_BUTTON_B }
        assertNotNull(binding)
        assertEquals(GamepadAction.BACK, binding!!.action)
    }

    @Test
    fun `GamepadMappings round-trips through JSON`() {
        val original = GamepadMappings()
        val encoded  = json.encodeToString(GamepadMappings.serializer(), original)
        val decoded  = json.decodeFromString(GamepadMappings.serializer(), encoded)
        assertEquals(original.bindings.size, decoded.bindings.size)
        original.bindings.forEachIndexed { i, binding ->
            assertEquals(binding.keyCode, decoded.bindings[i].keyCode)
            assertEquals(binding.action,  decoded.bindings[i].action)
        }
    }

    @Test
    fun `displayLabel returns non-blank string for every action`() {
        GamepadAction.values().forEach { action ->
            assert(action.displayLabel().isNotBlank()) {
                "displayLabel() was blank for $action"
            }
        }
    }

    @Test
    fun `keycodeDisplayName returns non-blank for common keycodes`() {
        val commonCodes = listOf(
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
        )
        commonCodes.forEach { code ->
            assert(code.keycodeDisplayName().isNotBlank()) {
                "keycodeDisplayName() was blank for keyCode $code"
            }
        }
    }
}
