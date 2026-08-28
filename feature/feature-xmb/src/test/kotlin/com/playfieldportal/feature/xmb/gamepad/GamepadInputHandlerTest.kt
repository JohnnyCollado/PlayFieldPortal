package com.playfieldportal.feature.xmb.gamepad

import android.view.KeyEvent
import android.view.MotionEvent
import app.cash.turbine.test
import com.playfieldportal.core.data.repository.ControllerRegistry
import com.playfieldportal.core.data.repository.RemapCoordinator
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.GamepadBinding
import com.playfieldportal.core.domain.model.GamepadMappings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GamepadInputHandlerTest {

    private lateinit var handler: GamepadInputHandler
    private val remapCoordinator = RemapCoordinator()

    @Before
    fun setUp() {
        handler = GamepadInputHandler(remapCoordinator, ControllerRegistry(mockk(relaxed = true)))
        // SystemClock.uptimeMillis is an Android stub on the JVM; tests override with a stable clock
        // (dedupe tests swap in a mutable one to control the duplicate window).
        handler.clock = { 0L }
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

    @Test
    fun `held key repeat does not re-emit`() = runTest {
        handler.actions.test {
            handler.onKeyEvent(keyEvent(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN))
            assertEquals(GamepadAction.NAVIGATE_DOWN, awaitItem())
            // repeatCount > 0 is an OS-level repeat — the handler's own repeat loop owns repeats.
            val repeat = keyEvent(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN, repeatCount = 1)
            assertTrue(handler.onKeyEvent(repeat))
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

    @Test
    fun `stick returning to neutral emits nothing and releases`() = runTest {
        handler.actions.test {
            assertTrue(handler.onMotionEvent(motionEvent(axisX = 0.8f, axisY = 0.0f)))
            assertEquals(GamepadAction.NAVIGATE_RIGHT, awaitItem())
            assertFalse(handler.onMotionEvent(motionEvent(axisX = 0.0f, axisY = 0.0f)))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Stick hysteresis ──────────────────────────────────────────────────

    @Test
    fun `stick stays engaged above the release threshold`() = runTest {
        handler.actions.test {
            assertTrue(handler.onMotionEvent(motionEvent(axisX = 0.6f, axisY = 0.0f)))
            assertEquals(GamepadAction.NAVIGATE_RIGHT, awaitItem())
            // Below activation (0.5) but above release (0.3): still held — no release, no re-press.
            assertTrue(handler.onMotionEvent(motionEvent(axisX = 0.4f, axisY = 0.0f)))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stick below the release threshold disengages`() = runTest {
        handler.actions.test {
            handler.onMotionEvent(motionEvent(axisX = 0.6f, axisY = 0.0f))
            assertEquals(GamepadAction.NAVIGATE_RIGHT, awaitItem())
            assertFalse(handler.onMotionEvent(motionEvent(axisX = 0.2f, axisY = 0.0f)))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stick below activation does not re-engage after release`() = runTest {
        handler.actions.test {
            handler.onMotionEvent(motionEvent(axisX = 0.6f, axisY = 0.0f))
            assertEquals(GamepadAction.NAVIGATE_RIGHT, awaitItem())
            handler.onMotionEvent(motionEvent(axisX = 0.2f, axisY = 0.0f)) // release
            handler.onMotionEvent(motionEvent(axisX = 0.4f, axisY = 0.0f)) // below activation
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── HAT (D-pad) axes ──────────────────────────────────────────────────

    @Test
    fun `HAT right emits NAVIGATE_RIGHT`() = runTest {
        handler.actions.test {
            assertTrue(handler.onMotionEvent(motionEvent(hatX = 1f, hatY = 0f)))
            assertEquals(GamepadAction.NAVIGATE_RIGHT, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `HAT up emits NAVIGATE_UP`() = runTest {
        handler.actions.test {
            handler.onMotionEvent(motionEvent(hatX = 0f, hatY = -1f))
            assertEquals(GamepadAction.NAVIGATE_UP, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `HAT release emits nothing`() = runTest {
        handler.actions.test {
            handler.onMotionEvent(motionEvent(hatX = 1f, hatY = 0f))
            assertEquals(GamepadAction.NAVIGATE_RIGHT, awaitItem())
            handler.onMotionEvent(motionEvent(hatX = 0f, hatY = 0f))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Duplicate-direction mitigation ────────────────────────────────────

    @Test
    fun `DPAD press matching a held stick direction inside the window is suppressed`() = runTest {
        var now = 0L
        handler.clock = { now }
        handler.actions.test {
            handler.onMotionEvent(motionEvent(axisX = 0.8f, axisY = 0.0f))
            assertEquals(GamepadAction.NAVIGATE_RIGHT, awaitItem())
            now = 50
            // Same physical direction reported by a second source: consumed, not emitted.
            assertTrue(handler.onKeyEvent(keyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN)))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `same direction from a new source after the window is accepted`() = runTest {
        var now = 0L
        handler.clock = { now }
        handler.actions.test {
            handler.onMotionEvent(motionEvent(axisX = 0.8f, axisY = 0.0f))
            assertEquals(GamepadAction.NAVIGATE_RIGHT, awaitItem())
            now = 200
            handler.onMotionEvent(motionEvent(axisX = 0.0f, axisY = 0.0f)) // release stick
            expectNoEvents()
            now = 250
            // Past the duplicate window: a genuine new press.
            assertTrue(handler.onKeyEvent(keyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN)))
            assertEquals(GamepadAction.NAVIGATE_RIGHT, awaitItem())
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

    // ── Settings handoff ──────────────────────────────────────────────────

    @Test
    fun `bypassToComposeFocus lets non-BACK fall through but keeps BACK`() = runTest {
        handler.bypassToComposeFocus = true
        handler.actions.test {
            assertFalse(handler.onKeyEvent(keyEvent(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN)))
            assertTrue(handler.onKeyEvent(keyEvent(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.ACTION_DOWN)))
            assertEquals(GamepadAction.BACK, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Capture mode ──────────────────────────────────────────────────────

    @Test
    fun `capture mode consumes the key without emitting its mapped action`() = runTest {
        handler.actions.test {
            var captured: Int? = null
            remapCoordinator.captureNextKey = { captured = it }
            assertTrue(handler.onKeyEvent(keyEvent(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN)))
            assertEquals(KeyEvent.KEYCODE_BUTTON_A, captured)
            assertNull(remapCoordinator.captureNextKey)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun keyEvent(keyCode: Int, action: Int, repeatCount: Int = 0): KeyEvent {
        val event = mockk<KeyEvent>(relaxed = true)
        every { event.action }      returns action
        every { event.keyCode }     returns keyCode
        every { event.repeatCount } returns repeatCount
        return event
    }

    private fun motionEvent(
        axisX: Float = 0f,
        axisY: Float = 0f,
        hatX: Float = 0f,
        hatY: Float = 0f,
    ): MotionEvent {
        val event = mockk<MotionEvent>(relaxed = true)
        // onMotionEvent only processes ACTION_MOVE joystick events; a relaxed mock defaults action
        // to 0 (ACTION_DOWN), which the handler ignores — stub it so the stick logic actually runs.
        every { event.action } returns MotionEvent.ACTION_MOVE
        every { event.getAxisValue(MotionEvent.AXIS_X) } returns axisX
        every { event.getAxisValue(MotionEvent.AXIS_Y) } returns axisY
        every { event.getAxisValue(MotionEvent.AXIS_HAT_X) } returns hatX
        every { event.getAxisValue(MotionEvent.AXIS_HAT_Y) } returns hatY
        every { event.source } returns android.view.InputDevice.SOURCE_JOYSTICK
        return event
    }
}
