package com.playfieldportal.feature.xmb.gamepad

import android.view.KeyEvent
import android.view.MotionEvent
import app.cash.turbine.test
import com.playfieldportal.core.data.repository.RemapCoordinator
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.GamepadBinding
import com.playfieldportal.core.domain.model.GamepadMappings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GamepadInputHandlerTest {

    private lateinit var handler: GamepadInputHandler

    @Before
    fun setUp() {
        handler = GamepadInputHandler(RemapCoordinator())
    }

    // ── Key events ───────────────────────────────────────────────────────

    @Test
    fun `onKeyEvent emits SELECT for BUTTON_A down`() = runTest {
        handler.actions.test {
            assertTrue(handler.onKeyEvent(keyEvent(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN)))
            assertEquals(GamepadAction.SELECT, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onKeyEvent emits BACK for BUTTON_B down`() = runTest {
        handler.actions.test {
            handler.onKeyEvent(keyEvent(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.ACTION_DOWN))
            assertEquals(GamepadAction.BACK, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onKeyEvent emits NAVIGATE_UP for DPAD_UP`() = runTest {
        handler.actions.test {
            handler.onKeyEvent(keyEvent(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN))
            assertEquals(GamepadAction.NAVIGATE_UP, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onKeyEvent emits NAVIGATE_DOWN for DPAD_DOWN`() = runTest {
        handler.actions.test {
            handler.onKeyEvent(keyEvent(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN))
            assertEquals(GamepadAction.NAVIGATE_DOWN, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onKeyEvent emits NAVIGATE_LEFT for DPAD_LEFT`() = runTest {
        handler.actions.test {
            handler.onKeyEvent(keyEvent(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN))
            assertEquals(GamepadAction.NAVIGATE_LEFT, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onKeyEvent emits NAVIGATE_RIGHT for DPAD_RIGHT`() = runTest {
        handler.actions.test {
            handler.onKeyEvent(keyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN))
            assertEquals(GamepadAction.NAVIGATE_RIGHT, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onKeyEvent returns false for unmapped key`() {
        assertFalse(handler.onKeyEvent(keyEvent(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN)))
    }

    @Test
    fun `ACTION_UP does not emit an action`() = runTest {
        handler.actions.test {
            handler.onKeyEvent(keyEvent(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN))
            awaitItem() // consume DOWN emission
            handler.onKeyEvent(keyEvent(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_UP))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Motion events (analog stick) ──────────────────────────────────────

    @Test
    fun `analog stick below dead zone does not emit`() = runTest {
        handler.actions.test {
            handler.onMotionEvent(motionEvent(axisX = 0.3f, axisY = 0.0f))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `analog stick right emits NAVIGATE_RIGHT`() = runTest {
        handler.actions.test {
            handler.onMotionEvent(motionEvent(axisX = 0.8f, axisY = 0.0f))
            assertEquals(GamepadAction.NAVIGATE_RIGHT, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `analog stick left emits NAVIGATE_LEFT`() = runTest {
        handler.actions.test {
            handler.onMotionEvent(motionEvent(axisX = -0.8f, axisY = 0.0f))
            assertEquals(GamepadAction.NAVIGATE_LEFT, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `analog stick up emits NAVIGATE_UP`() = runTest {
        handler.actions.test {
            handler.onMotionEvent(motionEvent(axisX = 0.0f, axisY = -0.8f))
            assertEquals(GamepadAction.NAVIGATE_UP, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `analog stick down emits NAVIGATE_DOWN`() = runTest {
        handler.actions.test {
            handler.onMotionEvent(motionEvent(axisX = 0.0f, axisY = 0.8f))
            assertEquals(GamepadAction.NAVIGATE_DOWN, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Custom mappings ───────────────────────────────────────────────────

    @Test
    fun `remapped binding overrides default action`() = runTest {
        handler.currentMappings = GamepadMappings(
            bindings = listOf(GamepadBinding(KeyEvent.KEYCODE_BUTTON_A, GamepadAction.BACK))
        )
        handler.actions.test {
            handler.onKeyEvent(keyEvent(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN))
            assertEquals(GamepadAction.BACK, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun keyEvent(keyCode: Int, action: Int): KeyEvent {
        val event = mockk<KeyEvent>(relaxed = true)
        every { event.action }      returns action
        every { event.keyCode }     returns keyCode
        every { event.repeatCount } returns 0
        return event
    }

    private fun motionEvent(axisX: Float, axisY: Float): MotionEvent {
        val event = mockk<MotionEvent>(relaxed = true)
        every { event.getAxisValue(MotionEvent.AXIS_X) } returns axisX
        every { event.getAxisValue(MotionEvent.AXIS_Y) } returns axisY
        every { event.source } returns android.view.InputDevice.SOURCE_JOYSTICK
        return event
    }
}
