package com.playfieldportal.core.ui.sound

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.View
import dagger.hilt.android.qualifiers.ApplicationContext
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import timber.log.Timber

/**
 * Optional haptic feedback for menu steps (Settings ▸ Controller ▸ Haptic feedback).
 *
 * Hooked into [MenuSoundPlayer.play] because every navigation step and confirmation already
 * passes through it, so no screen needs its own call. The decision of WHAT to buzz and WHEN is
 * the pure [hapticCue] mapping and [HapticGate]; [HapticFeedbackPlayer] only delivers it.
 */
enum class HapticCue { NONE, TICK, CONFIRM }

/**
 * The haptic a menu cue earns. A step through the UI (move, descend, category change, an options
 * panel opening, backing out) is a light tick; a committed action is the firmer confirm tick.
 * [MenuSound.ERROR] and [MenuSound.NOTIFICATION] stay silent to the hand: neither is a step the
 * user just took, and a notification can land while the controller is on a table.
 */
val MenuSound.hapticCue: HapticCue
    get() = when (this) {
        MenuSound.SCROLL,
        MenuSound.SYSTEM_BROWSE,
        MenuSound.SELECT,
        MenuSound.OPTIONS,
        MenuSound.BACK,
        -> HapticCue.TICK
        MenuSound.CONFIRM -> HapticCue.CONFIRM
        MenuSound.ERROR,
        MenuSound.NOTIFICATION,
        -> HapticCue.NONE
    }

/**
 * Decides whether a cue buzzes now: never while the preference is off, never for a cue with no
 * haptic, and at most one pulse per [minIntervalMs] so a held direction (which repeats the step
 * cue many times a second) does not turn into a continuous buzz. Time is passed in so the rule is
 * testable without a clock. A refused cue does not restart the interval.
 */
class HapticGate(private val minIntervalMs: Long = MIN_INTERVAL_MS) {
    private var lastPulseMs: Long? = null

    @Synchronized
    fun cueFor(sound: MenuSound, enabled: Boolean, nowMs: Long): HapticCue? {
        if (!enabled) return null
        val cue = sound.hapticCue
        if (cue == HapticCue.NONE) return null
        val last = lastPulseMs
        if (last != null && nowMs - last < minIntervalMs) return null
        lastPulseMs = nowMs
        return cue
    }

    companion object {
        const val MIN_INTERVAL_MS = 60L
    }
}

/**
 * Read-only view of the haptic preference for components in core-ui. Same seam as [AudioLevels]:
 * the store lives in core-data, which depends on this module, so the interface is here and the
 * Hilt binding is over there.
 */
interface HapticPreference {
    val enabled: Flow<Boolean>
}

/**
 * Delivers a [HapticCue] on the device and on a controller that reports a vibrator.
 *
 * **Device:** `View.performHapticFeedback` on the host view the app registers through
 * [attachView]. Unlike `Vibrator.vibrate` it needs no `VIBRATE` permission (the app does not hold
 * one), and it respects the system's own touch-feedback setting.
 *
 * **Controller:** the first gamepad whose `InputDevice.getVibratorManager()` lists a vibrator
 * (API 31+), pulsed with the predefined TICK / CLICK effects, falling back to a short one-shot
 * where the motor does not support predefined effects. Input-device vibrators are not gated by
 * the `VIBRATE` permission.
 *
 * Every platform call is wrapped: a haptic that fails must never break navigation.
 */
@Singleton
class HapticFeedbackPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    preference: HapticPreference,
) {
    private val gate = HapticGate()

    @Volatile
    private var enabled = false

    private var hostView: WeakReference<View>? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        preference.enabled.onEach { enabled = it }.launchIn(scope)
    }

    fun attachView(view: View) {
        hostView = WeakReference(view)
    }

    fun detachView(view: View) {
        if (hostView?.get() === view) hostView = null
    }

    /** Called for every menu cue; cheap when the preference is off. */
    fun onSound(sound: MenuSound) {
        val cue = gate.cueFor(sound, enabled, SystemClock.uptimeMillis()) ?: return
        runCatching { pulseDevice(cue) }.onFailure { Timber.d(it, "device haptic failed") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { pulseController(cue) }.onFailure { Timber.d(it, "controller haptic failed") }
        }
    }

    private fun pulseDevice(cue: HapticCue) {
        val view = hostView?.get() ?: return
        val constant = when (cue) {
            HapticCue.TICK -> HapticFeedbackConstants.CLOCK_TICK
            HapticCue.CONFIRM ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                else HapticFeedbackConstants.KEYBOARD_TAP
            HapticCue.NONE -> return
        }
        view.performHapticFeedback(constant)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun pulseController(cue: HapticCue) {
        val effectId = when (cue) {
            HapticCue.TICK -> VibrationEffect.EFFECT_TICK
            HapticCue.CONFIRM -> VibrationEffect.EFFECT_CLICK
            HapticCue.NONE -> return
        }
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            val padLike = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK
            if (device.sources and padLike == 0) continue
            val manager = device.vibratorManager
            if (manager.vibratorIds.isEmpty()) continue
            val vibrator: Vibrator = manager.defaultVibrator
            val predefinedOk = vibrator.areEffectsSupported(effectId)
                .firstOrNull() != Vibrator.VIBRATION_EFFECT_SUPPORT_NO
            val effect = if (predefinedOk) {
                VibrationEffect.createPredefined(effectId)
            } else {
                val ms = if (cue == HapticCue.CONFIRM) 30L else 12L
                VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            vibrator.vibrate(effect)
            return   // the first controller that can buzz; several connected pads would just fight
        }
    }
}
