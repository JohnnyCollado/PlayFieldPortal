package com.playfieldportal.core.ui.keyboard

/** What started an edit. */
enum class InputSource { CONTROLLER, TOUCH }

/** Which keyboard an edit gets. */
enum class TextInputMode { VIRTUAL, SYSTEM }

/**
 * PFP's keyboard only for a controller-started edit with the setting on; touch, or the setting
 * off, keeps today's system keyboard (Virtual Keyboard plan section 1).
 */
fun resolveTextInputMode(source: InputSource, virtualKeyboardEnabled: Boolean): TextInputMode =
    if (source == InputSource.CONTROLLER && virtualKeyboardEnabled) TextInputMode.VIRTUAL else TextInputMode.SYSTEM
