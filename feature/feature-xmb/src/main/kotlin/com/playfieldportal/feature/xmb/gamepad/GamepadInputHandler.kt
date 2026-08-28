package com.playfieldportal.feature.xmb.gamepad

import android.os.SystemClock
import android.view.InputDevice
import com.playfieldportal.core.data.repository.ControllerRegistry
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

// Dead zone for analog stick — below this magnitude, input is ignored. Device-reported flat
// (MotionRange.getFlat) can raise this floor; see stickDirection().
private const val STICK_DEAD_ZONE = 0.5f

// A stick direction engaged past activation stays engaged until deflection falls below
// activation * STICK_RELEASE_FACTOR — hysteresis so noise around the activation edge cannot
// flap press/release/re-press.
private const val STICK_RELEASE_FACTOR = 0.6f

// HAT (D-pad) deflection threshold. HAT axes are usually discrete (-1/0/1) but may be analog.
private const val HAT_DEAD_ZONE = 0.5f

// One physical D-pad press can arrive as KEYCODE_DPAD_* and a HAT/axis deflection within the
// same frame. Same-direction presses from another source inside this window are consumed without
// emitting so navigation never double-steps. Kept well under the fastest repeat interval so a
// held direction never suppresses its own legitimate repeats.
private const val DUPLICATE_WINDOW_MS = 80L

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
    private val registry: ControllerRegistry,
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

    // Last emit time per directional action, for same-source duplicate suppression. Stamped by
    // emit(); read by isDuplicateDirection() before a new edge is emitted.
    private val lastDirectionalEmitAt = mutableMapOf<GamepadAction, Long>()

    // Test seam: injectable clock so duplicate-window tests are deterministic. Production uses
    // the system uptime clock.
    internal var clock: () -> Long = SystemClock::uptimeMillis

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
        registry.markActive(event.deviceId)

        // Settings overlay: only BACK is ours — let Compose handle D-pad/select natively
        if (bypassToComposeFocus && action != GamepadAction.BACK) return false

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    // First press — emit immediately. A directional press from a redundant source
                    // (HAT + DPAD keys on one physical button) is consumed without emitting so
                    // navigation never double-steps.
                    if (action.isDirectional() && isDuplicateDirection(action)) return true

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
        registry.markActive(event.deviceId)

        val x = event.getAxisValue(MotionEvent.AXIS_X)
        val y = event.getAxisValue(MotionEvent.AXIS_Y)
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        val stickAction = stickDirection(x, y, stickFlatFor(event.deviceId))
        val hatAction = hatDirection(hatX, hatY)

        // HAT wins over the stick when both report a direction: HAT is the discrete D-pad, and a
        // stick deflection near a HAT press is usually the same physical gesture leaking onto both.
        val motionAction = hatAction ?: stickAction

        // Track deflection on every event (not just direction changes) so easing into or out of
        // full tilt adjusts the repeat speed of the hold already in progress.
        stickMagnitude = if (motionAction != null) maxOf(abs(x), abs(y), abs(hatX), abs(hatY)) else 0f

        if (motionAction != lastStickAction) {
            cancelRepeat()
            lastStickAction = motionAction
            if (motionAction != null && !isDuplicateDirection(motionAction)) {
                emit(motionAction)
                startRepeat(motionAction)
            }
        }

        return motionAction != null
    }

    // Used to inject actions from the ViewModel for button remapping preview
    fun emitAction(action: GamepadAction) = emit(action)

    private fun emit(action: GamepadAction) {
        if (action.isDirectional()) lastDirectionalEmitAt[action] = clock()
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

    // ── Normalization helpers ──────────────────────────────────────────────────────────────

    /**
     * Left-stick direction with hysteresis. Activation is the device-reported neutral flat (or the
     * [STICK_DEAD_ZONE] floor); a direction engaged past activation stays engaged until deflection
     * falls below the lower release threshold.
     */
    private fun stickDirection(x: Float, y: Float, flat: Float): GamepadAction? {
        val activation = maxOf(STICK_DEAD_ZONE, flat)
        val release = activation * STICK_RELEASE_FACTOR

        val strong = when {
            y < -activation -> GamepadAction.NAVIGATE_UP
            y >  activation -> GamepadAction.NAVIGATE_DOWN
            x < -activation -> GamepadAction.NAVIGATE_LEFT
            x >  activation -> GamepadAction.NAVIGATE_RIGHT
            else -> null
        }
        if (strong != null) return strong

        val engaged = lastStickAction ?: return null
        val stillEngaged = when (engaged) {
            GamepadAction.NAVIGATE_UP -> y < -release
            GamepadAction.NAVIGATE_DOWN -> y > release
            GamepadAction.NAVIGATE_LEFT -> x < -release
            GamepadAction.NAVIGATE_RIGHT -> x > release
            else -> false
        }
        return if (stillEngaged) engaged else null
    }

    private fun hatDirection(hatX: Float, hatY: Float): GamepadAction? = when {
        hatY < -HAT_DEAD_ZONE -> GamepadAction.NAVIGATE_UP
        hatY >  HAT_DEAD_ZONE -> GamepadAction.NAVIGATE_DOWN
        hatX < -HAT_DEAD_ZONE -> GamepadAction.NAVIGATE_LEFT
        hatX >  HAT_DEAD_ZONE -> GamepadAction.NAVIGATE_RIGHT
        else -> null
    }

    /** Device-reported neutral flat for the left stick; 0 when unavailable (JVM tests, odd devices). */
    private fun stickFlatFor(deviceId: Int): Float =
        runCatching { InputDevice.getDevice(deviceId)?.getMotionRange(MotionEvent.AXIS_X)?.flat }
            .getOrNull() ?: 0f

    /**
     * True when the same directional action was emitted from another source inside
     * [DUPLICATE_WINDOW_MS] — a redundant physical representation of one press, not a new intent.
     */
    private fun isDuplicateDirection(action: GamepadAction): Boolean {
        if (!action.isDirectional()) return false
        val now = clock()
        val last = lastDirectionalEmitAt[action]
        return last != null && now - last < DUPLICATE_WINDOW_MS
    }

    private fun GamepadAction.isDirectional() = this in setOf(
        GamepadAction.NAVIGATE_UP,
        GamepadAction.NAVIGATE_DOWN,
        GamepadAction.NAVIGATE_LEFT,
        GamepadAction.NAVIGATE_RIGHT,
    )
}
