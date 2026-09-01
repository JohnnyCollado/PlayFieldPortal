package com.playfieldportal.core.ui.icons

import androidx.annotation.DrawableRes
import com.playfieldportal.core.domain.model.ControllerDisplayType
import com.playfieldportal.core.ui.R

// ── Idle context-menu hint button glyph ──────────────────────────────────────
//
// The idle hint (a small "Options" pill over the XMB) shows the face button that
// triggers the context menu under the user's chosen controller display style.
// The trigger action is GamepadAction.BUTTON_Y (physical top face button); each
// style labels that physical button differently:
//
//   PLAYSTATION → △ Triangle
//   XBOX        → Y
//   NINTENDO    → X    (Nintendo uses X as its Y button — same physical button,
//                        different silkscreen label)
//   GENERIC     → Y    (sensible default)
//
// Nintendo reuses the Xbox X art (no dedicated Nintendo asset pack ships), per
// the user's decision that XB art is acceptable since "they basically use the
// same keys".

/**
 * The drawable resource id of the button glyph the idle context-menu hint should
 * show for this [ControllerDisplayType]. Pure so it is unit-testable without Compose.
 */
@DrawableRes
fun ControllerDisplayType.contextHintButtonDrawable(): Int = when (this) {
    ControllerDisplayType.PLAYSTATION -> R.drawable.btn_hint_triangle
    ControllerDisplayType.XBOX       -> R.drawable.btn_hint_y
    ControllerDisplayType.NINTENDO   -> R.drawable.btn_hint_x
    ControllerDisplayType.GENERIC    -> R.drawable.btn_hint_y
}
