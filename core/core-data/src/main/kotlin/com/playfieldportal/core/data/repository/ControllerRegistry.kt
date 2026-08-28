package com.playfieldportal.core.data.repository

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import com.playfieldportal.core.domain.model.ControllerDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Authoritative tracking of connected controller-capable devices.
 *
 * The Android [InputManager] listener only adapts platform device add/remove/change events into
 * the [onDeviceAdded] / [onDeviceRemoved] state transitions — the state logic itself is plain and
 * unit-testable. The input bridge (GamepadInputHandler) calls [markActive] on every consumed
 * key/motion event so [lastActiveController] reflects the most recently used device without ever
 * locking input to one controller.
 */
@Singleton
class ControllerRegistry @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val _connectedControllers = MutableStateFlow<List<ControllerDevice>>(emptyList())
    val connectedControllers: StateFlow<List<ControllerDevice>> = _connectedControllers.asStateFlow()

    private val _lastActiveController = MutableStateFlow<ControllerDevice?>(null)
    val lastActiveController: StateFlow<ControllerDevice?> = _lastActiveController.asStateFlow()

    init {
        // Guarded so JVM unit tests (which construct the registry directly) never touch platform
        // services. In production this registers once for the app lifetime.
        runCatching {
            val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
                ?: return@runCatching
            inputManager.registerInputDeviceListener(
                object : InputManager.InputDeviceListener {
                    override fun onInputDeviceAdded(deviceId: Int) = refreshDevice(deviceId)
                    override fun onInputDeviceRemoved(deviceId: Int) = removeDevice(deviceId)
                    override fun onInputDeviceChanged(deviceId: Int) = refreshDevice(deviceId)
                },
                Handler(Looper.getMainLooper()),
            )
        }
    }

    /** Called by the input bridge on every consumed key/motion event. */
    fun markActive(deviceId: Int) {
        val device = _connectedControllers.value.firstOrNull { it.deviceId == deviceId } ?: return
        if (_lastActiveController.value?.deviceId != deviceId) {
            _lastActiveController.value = device
            Timber.v("Controller registry: active device ${device.name}")
        }
    }

    /** Adds or refreshes a device snapshot. Re-adding an existing id keeps the original connect time. */
    fun onDeviceAdded(device: ControllerDevice) {
        _connectedControllers.update { current ->
            if (current.any { it.deviceId == device.deviceId }) current else current + device
        }
    }

    fun onDeviceRemoved(deviceId: Int) {
        _connectedControllers.update { list -> list.filterNot { it.deviceId == deviceId } }
        if (_lastActiveController.value?.deviceId == deviceId) _lastActiveController.value = null
    }

    private fun refreshDevice(deviceId: Int) {
        val snapshot = snapshotDevice(deviceId) ?: return
        onDeviceAdded(snapshot)
    }

    private fun removeDevice(deviceId: Int) = onDeviceRemoved(deviceId)

    private fun snapshotDevice(deviceId: Int): ControllerDevice? {
        val device = InputDevice.getDevice(deviceId) ?: return null
        val sources = device.sources
        return ControllerDevice(
            deviceId = device.id,
            name = device.name ?: "Controller",
            vendorId = device.vendorId,
            productId = device.productId,
            isGamepad = sources and InputDevice.SOURCE_GAMEPAD != 0,
            isJoystick = sources and InputDevice.SOURCE_JOYSTICK != 0,
            connectedAtEpochMs = System.currentTimeMillis(),
        )
    }
}
