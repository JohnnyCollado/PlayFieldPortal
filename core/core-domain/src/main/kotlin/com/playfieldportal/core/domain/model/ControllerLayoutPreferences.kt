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
    /** Default: Y = Options, X = Sort */
    STANDARD,
    /** Swapped: X = Options, Y = Sort */
    SWAPPED,
}

fun XYLayout.displayLabel(): String = when (this) {
    XYLayout.STANDARD -> "Standard (Y = Options, X = Sort)"
    XYLayout.SWAPPED  -> "Swapped (X = Options, Y = Sort)"
}

// ── Controller display / prompt style ─────────────────────────────────────────

enum class ControllerDisplayType {
    XBOX,
    NINTENDO,
    PLAYSTATION,
}

fun ControllerDisplayType.displayLabel(): String = when (this) {
    ControllerDisplayType.XBOX        -> "Xbox"
    ControllerDisplayType.NINTENDO    -> "Nintendo"
    ControllerDisplayType.PLAYSTATION -> "PlayStation"
}

// ── Held-navigation scroll speed ──────────────────────────────────────────────

/** How fast held D-pad/stick navigation repeats. Affects the repeat ramp, not single presses. */
enum class ScrollSpeed {
    RELAXED,
    STANDARD,
    FAST,
}

fun ScrollSpeed.displayLabel(): String = when (this) {
    ScrollSpeed.RELAXED  -> "Relaxed"
    ScrollSpeed.STANDARD -> "Standard"
    ScrollSpeed.FAST     -> "Fast"
}

// ── Bundled preference snapshot ───────────────────────────────────────────────

data class ControllerLayoutPrefs(
    val confirmBackLayout: ConfirmBackLayout   = ConfirmBackLayout.STANDARD,
    val xyLayout: XYLayout                     = XYLayout.STANDARD,
    val displayType: ControllerDisplayType     = ControllerDisplayType.XBOX,
    val scrollSpeed: ScrollSpeed               = ScrollSpeed.STANDARD,
)
