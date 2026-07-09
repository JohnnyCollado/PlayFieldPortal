package com.playfieldportal.feature.xmb.gamepad

import android.view.InputDevice
import com.playfieldportal.core.data.repository.RemapCoordinator
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.GamepadMappings
import com.playfieldportal.core.domain.model.ScrollSpeed
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

// Dead zone for analog stick — below this magnitude, input is ignored
private const val STICK_DEAD_ZONE = 0.5f

// Stick deflection past this magnitude skips the ramp and repeats at the fast interval
// immediately — full tilt is an explicit "scroll fast" gesture the D-pad can't make.
private const val STICK_FULL_TILT = 0.9f

// Held-navigation repeat tuning: after [initialDelayMs] the action repeats starting at
// [baseIntervalMs], tightening linearly to [fastIntervalMs] over [rampSteps] repeats — short
// holds stay precise, long holds accelerate instead of plodding at one fixed rate.
private data class RepeatTuning(
    val initialDelayMs: Long,
    val baseIntervalMs: Long,
    val fastIntervalMs: Long,
    val rampSteps: Int,
)

private fun ScrollSpeed.tuning(): RepeatTuning = when (this) {
    ScrollSpeed.RELAXED  -> RepeatTuning(initialDelayMs = 350, baseIntervalMs = 130, fastIntervalMs = 80, rampSteps = 6)
    ScrollSpeed.STANDARD -> RepeatTuning(initialDelayMs = 250, baseIntervalMs = 110, fastIntervalMs = 50, rampSteps = 5)
    ScrollSpeed.FAST     -> RepeatTuning(initialDelayMs = 180, baseIntervalMs = 90,  fastIntervalMs = 35, rampSteps = 4)
}

@Singleton
class GamepadInputHandler @Inject constructor(
    private val remapCoordinator: RemapCoordinator,
) {
    private val _actions = MutableSharedFlow<GamepadAction>(extraBufferCapacity = 16)
    val actions: SharedFlow<GamepadAction> = _actions.asSharedFlow()

    // Current live mappings — updated from the repository flow by the ViewModel
    var currentMappings: GamepadMappings = GamepadMappings()

    // Held-scroll speed preference — updated from ControllerLayoutRepository by the ViewModel.
    var scrollSpeed: ScrollSpeed = ScrollSpeed.STANDARD

    // Scope for repeat jobs — set by XMBViewModel on init so repeats survive config changes
    var scope: CoroutineScope? = null

    // When true (settings overlay active), only BACK is intercepted here; everything else
    // falls through to super.dispatchKeyEvent() so Compose handles D-pad focus traversal.
    var bypassToComposeFocus: Boolean = false

    // Repeat job for held directional input
    private var repeatJob: Job? = null
    private var lastStickAction: GamepadAction? = null

    // Live stick deflection while a stick direction is held — read by the repeat loop each step
    // so pushing to full tilt speeds up mid-hold without restarting the repeat. 0 for D-pad holds.
    @Volatile private var stickMagnitude: Float = 0f

    // Push-to-talk (Discord voice): set by XMBViewModel while a call is active with PTT on and a
    // button mapped. When set, the matching keycode holds the mic open (down) / closes it (up)
    // instead of translating to a navigation action. Only reaches us while PFP is foreground.
    var pttKeyCode: Int? = null
    var onPttHold: ((Boolean) -> Unit)? = null

    // Called by MainActivity.dispatchKeyEvent
    fun onKeyEvent(event: KeyEvent): Boolean {
        // During button remapping: capture the raw keyCode before any action translation.
        // This ensures every button — including the one mapped to BACK — can be assigned.
        // Both ACTION_DOWN and ACTION_UP are consumed so nothing leaks into normal handling.
        remapCoordinator.captureNextKey?.let { capture ->
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                remapCoordinator.captureNextKey = null
                capture(event.keyCode)
            }
            return true
        }

        // Push-to-talk takes priority over navigation for its mapped button during a call: hold to
        // open the mic, release to close. Consume both edges (and held repeats) so it never doubles
        // as a nav press.
        pttKeyCode?.let { code ->
            if (event.keyCode == code) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) onPttHold?.invoke(true)
                    KeyEvent.ACTION_UP -> onPttHold?.invoke(false)
                }
                return true
            }
        }

        // Accept any keycode we have a binding for — don't filter by source because
        // Android handhelds (Ayn Thor, Retroid, etc.) sometimes report SOURCE_KEYBOARD
        // for built-in controller buttons even when they're physically a gamepad.
        val action = currentMappings.actionFor(event.keyCode) ?: return false

        // Settings overlay: only BACK is ours — let Compose handle D-pad/select natively
        if (bypassToComposeFocus && action != GamepadAction.BACK) return false

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    // First press — emit immediately
                    emit(action)

                    // Start repeat for navigation actions
                    if (action.isDirectional()) {
                        startRepeat(action)
                    }
                }
                true
            }
            KeyEvent.ACTION_UP -> {
                if (action.isDirectional()) cancelRepeat()
                true
            }
            else -> false
        }
    }

    // Called by MainActivity.onGenericMotionEvent
    fun onMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) {
            return false
        }
        if (event.action != MotionEvent.ACTION_MOVE) return false

        val x = event.getAxisValue(MotionEvent.AXIS_X)
        val y = event.getAxisValue(MotionEvent.AXIS_Y)

        val stickAction = when {
            y < -STICK_DEAD_ZONE          -> GamepadAction.NAVIGATE_UP
            y >  STICK_DEAD_ZONE          -> GamepadAction.NAVIGATE_DOWN
            x < -STICK_DEAD_ZONE          -> GamepadAction.NAVIGATE_LEFT
            x >  STICK_DEAD_ZONE          -> GamepadAction.NAVIGATE_RIGHT
            else                          -> null
        }

        // Track deflection on every event (not just direction changes) so easing into or out of
        // full tilt adjusts the repeat speed of the hold already in progress.
        stickMagnitude = if (stickAction != null) maxOf(abs(x), abs(y)) else 0f

        if (stickAction != lastStickAction) {
            cancelRepeat()
            lastStickAction = stickAction
            if (stickAction != null) {
                emit(stickAction)
                startRepeat(stickAction)
            }
        }

        return stickAction != null
    }

    // Used to inject actions from the ViewModel for button remapping preview
    fun emitAction(action: GamepadAction) = emit(action)

    private fun emit(action: GamepadAction) {
        _actions.tryEmit(action)
        Timber.v("Gamepad action: $action")
    }

    private fun startRepeat(action: GamepadAction) {
        val s = scope ?: return
        startRepeating(action, s)
    }

    // Called by XMBViewModel.init with viewModelScope so repeat jobs survive config changes
    fun startRepeating(action: GamepadAction, s: CoroutineScope) {
        repeatJob?.cancel()
        repeatJob = s.launch {
            val t = scrollSpeed.tuning()
            delay(t.initialDelayMs)
            var step = 0
            while (true) {
                emit(action)
                step++
                // Linear ramp from base to fast over rampSteps; a full-tilt stick jumps straight
                // to the fast interval regardless of how far into the ramp the hold is.
                val ramped =
                    if (step >= t.rampSteps) t.fastIntervalMs
                    else t.baseIntervalMs - (t.baseIntervalMs - t.fastIntervalMs) * step / t.rampSteps
                delay(if (stickMagnitude >= STICK_FULL_TILT) t.fastIntervalMs else ramped)
            }
        }
    }

    fun cancelRepeat() {
        repeatJob?.cancel()
        repeatJob = null
        lastStickAction = null
        stickMagnitude = 0f
    }

    private fun GamepadAction.isDirectional() = this in setOf(
        GamepadAction.NAVIGATE_UP,
        GamepadAction.NAVIGATE_DOWN,
        GamepadAction.NAVIGATE_LEFT,
        GamepadAction.NAVIGATE_RIGHT,
    )
}
