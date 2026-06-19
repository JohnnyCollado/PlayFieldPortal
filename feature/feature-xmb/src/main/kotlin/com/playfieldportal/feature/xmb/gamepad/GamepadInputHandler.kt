package com.playfieldportal.feature.xmb.gamepad

import android.view.InputDevice
import com.playfieldportal.core.data.repository.ControllerMappingRepository
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.GamepadMappings
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

// Delay before D-pad/stick navigation starts repeating when held (ms)
private const val REPEAT_INITIAL_DELAY_MS = 400L

// Interval between repeat firings while held (ms)
private const val REPEAT_RATE_MS = 120L

// Dead zone for analog stick — below this magnitude, input is ignored
private const val STICK_DEAD_ZONE = 0.5f

@Singleton
class GamepadInputHandler @Inject constructor(
    private val mappingRepository: ControllerMappingRepository,
) {
    private val _actions = MutableSharedFlow<GamepadAction>(extraBufferCapacity = 16)
    val actions: SharedFlow<GamepadAction> = _actions.asSharedFlow()

    // Current live mappings — updated from the repository flow by the ViewModel
    var currentMappings: GamepadMappings = GamepadMappings()

    // Scope for repeat jobs — set by XMBViewModel on init so repeats survive config changes
    var scope: CoroutineScope? = null

    // When true (settings overlay active), only BACK is intercepted here; everything else
    // falls through to super.dispatchKeyEvent() so Compose handles D-pad focus traversal.
    var bypassToComposeFocus: Boolean = false

    // Repeat job for held directional input
    private var repeatJob: Job? = null
    private var lastStickAction: GamepadAction? = null

    // Called by MainActivity.dispatchKeyEvent
    fun onKeyEvent(event: KeyEvent): Boolean {
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
        repeatJob?.cancel()
        val s = scope ?: return
        repeatJob = s.launch {
            delay(REPEAT_INITIAL_DELAY_MS)
            while (true) {
                emit(action)
                delay(REPEAT_RATE_MS)
            }
        }
    }

    // Called by XMBViewModel.init with viewModelScope so repeat jobs survive config changes
    fun startRepeating(action: GamepadAction, s: CoroutineScope) {
        repeatJob?.cancel()
        repeatJob = s.launch {
            delay(REPEAT_INITIAL_DELAY_MS)
            while (true) {
                emit(action)
                delay(REPEAT_RATE_MS)
            }
        }
    }

    fun cancelRepeat() {
        repeatJob?.cancel()
        repeatJob = null
        lastStickAction = null
    }

    private fun GamepadAction.isDirectional() = this in setOf(
        GamepadAction.NAVIGATE_UP,
        GamepadAction.NAVIGATE_DOWN,
        GamepadAction.NAVIGATE_LEFT,
        GamepadAction.NAVIGATE_RIGHT,
    )
}
