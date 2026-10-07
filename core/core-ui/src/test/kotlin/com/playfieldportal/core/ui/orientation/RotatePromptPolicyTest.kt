package com.playfieldportal.core.ui.orientation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.ScreenOrientationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #21: Landscape keeps today's sensorLandscape lock; Follow Device lets the window turn
 * portrait (so automations such as Tasker see the rotation) and covers PFP with a rotate prompt.
 */
class RotatePromptPolicyTest {

    @Test
    fun `landscape keeps the sensor landscape lock`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            requestedOrientationFor(ScreenOrientationMode.LANDSCAPE),
        )
    }

    @Test
    fun `follow device honours the system rotation lock`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
            requestedOrientationFor(ScreenOrientationMode.FOLLOW_DEVICE),
        )
    }

    @Test
    fun `the prompt shows only for follow device in portrait`() {
        assertTrue(showRotatePrompt(ScreenOrientationMode.FOLLOW_DEVICE, Configuration.ORIENTATION_PORTRAIT))
        assertFalse(showRotatePrompt(ScreenOrientationMode.FOLLOW_DEVICE, Configuration.ORIENTATION_LANDSCAPE))
        assertFalse(showRotatePrompt(ScreenOrientationMode.LANDSCAPE, Configuration.ORIENTATION_PORTRAIT))
        assertFalse(showRotatePrompt(ScreenOrientationMode.FOLLOW_DEVICE, Configuration.ORIENTATION_UNDEFINED))
    }

    @Test
    fun `while the prompt is up Confirm switches launcher and every other bound button is swallowed`() {
        assertEquals(RotatePromptKey.SWITCH_LAUNCHER, rotatePromptKey(GamepadAction.SELECT, systemKey = false))
        assertEquals(RotatePromptKey.SWALLOW, rotatePromptKey(GamepadAction.BACK, systemKey = true))
        assertEquals(RotatePromptKey.SWALLOW, rotatePromptKey(GamepadAction.NAVIGATE_UP, systemKey = false))
        assertEquals(RotatePromptKey.SWALLOW, rotatePromptKey(GamepadAction.HOME, systemKey = false))
    }

    @Test
    fun `unbound system keys pass through, so volume still works`() {
        assertEquals(RotatePromptKey.PASS, rotatePromptKey(null, systemKey = true))
    }

    @Test
    fun `unbound ordinary keys are dropped, so a keyboard cannot type into the hidden UI`() {
        assertEquals(RotatePromptKey.IGNORE, rotatePromptKey(null, systemKey = false))
    }

    @Test
    fun `only a bound button counts as controller input on the prompt`() {
        assertTrue(RotatePromptKey.SWITCH_LAUNCHER.isControllerInput)
        assertTrue(rotatePromptKey(GamepadAction.BACK, systemKey = false).isControllerInput)
        assertFalse(rotatePromptKey(null, systemKey = true).isControllerInput)
        assertFalse(rotatePromptKey(null, systemKey = false).isControllerInput)
    }
}
