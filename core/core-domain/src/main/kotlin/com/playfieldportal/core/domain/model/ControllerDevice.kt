package com.playfieldportal.core.domain.model

/**
 * A connected controller-capable input device, as seen by the app.
 *
 * Kept Android-free so any consumer (diagnostics, Settings, per-device profiles later) can
 * depend on it without knowing how the platform enumerates devices.
 */
data class ControllerDevice(
    val deviceId: Int,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    /** True when the device exposes gamepad-style controls (face buttons, D-pad…). */
    val isGamepad: Boolean,
    /** True when the device exposes analog joystick axes. */
    val isJoystick: Boolean,
    /** Wall-clock time the device was first observed, for diagnostics/ordering. */
    val connectedAtEpochMs: Long,
)
