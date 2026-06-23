package com.playfieldportal.core.domain.model

// ── Confirm / Back button layout ──────────────────────────────────────────────

enum class ConfirmBackLayout {
    /** Default: A / Cross = Confirm,  B / Circle = Back  */
    STANDARD,
    /** Reversed: B / Circle = Confirm, A / Cross = Back */
    REVERSED,
}

fun ConfirmBackLayout.displayLabel(): String = when (this) {
    ConfirmBackLayout.STANDARD -> "Standard (A = Confirm, B = Back)"
    ConfirmBackLayout.REVERSED -> "Reversed (B = Confirm, A = Back)"
}

// ── Secondary button (X / Y) layout ───────────────────────────────────────────

enum class XYLayout {
    /** Default: X = Task Tray, Y = Options */
    STANDARD,
    /** Swapped: X = Options, Y = Task Tray */
    SWAPPED,
}

fun XYLayout.displayLabel(): String = when (this) {
    XYLayout.STANDARD -> "Standard (X = Task Tray, Y = Options)"
    XYLayout.SWAPPED  -> "Swapped (X = Options, Y = Task Tray)"
}

// ── Controller display / prompt style ─────────────────────────────────────────

enum class ControllerDisplayType {
    XBOX,
    NINTENDO,
    PLAYSTATION,
    GENERIC,
}

fun ControllerDisplayType.displayLabel(): String = when (this) {
    ControllerDisplayType.XBOX        -> "Xbox"
    ControllerDisplayType.NINTENDO    -> "Nintendo"
    ControllerDisplayType.PLAYSTATION -> "PlayStation"
    ControllerDisplayType.GENERIC     -> "Generic"
}

// ── Bundled preference snapshot ───────────────────────────────────────────────

data class ControllerLayoutPrefs(
    val confirmBackLayout: ConfirmBackLayout   = ConfirmBackLayout.STANDARD,
    val xyLayout: XYLayout                     = XYLayout.STANDARD,
    val displayType: ControllerDisplayType     = ControllerDisplayType.XBOX,
)
