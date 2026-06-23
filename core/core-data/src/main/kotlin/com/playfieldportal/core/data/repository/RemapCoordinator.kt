package com.playfieldportal.core.data.repository

import javax.inject.Inject
import javax.inject.Singleton

// Shared singleton that bridges GamepadInputHandler (feature-xmb) and
// ControllerSettingsViewModel (feature-settings) without a direct module dependency.
//
// While a remap is pending, captureNextKey is non-null. GamepadInputHandler checks it first
// in onKeyEvent: if set, the raw keyCode is forwarded to the callback and the event is
// consumed (no GamepadAction is emitted). The callback is cleared after one call so normal
// input resumes immediately after assignment.
@Singleton
class RemapCoordinator @Inject constructor() {
    var captureNextKey: ((keyCode: Int) -> Unit)? = null
}
