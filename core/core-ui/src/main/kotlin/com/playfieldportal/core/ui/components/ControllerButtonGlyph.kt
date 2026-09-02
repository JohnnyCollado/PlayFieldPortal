package com.playfieldportal.core.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.playfieldportal.core.domain.model.ControllerDisplayType
import com.playfieldportal.core.domain.model.ControllerIcon
import com.playfieldportal.core.ui.R

// ── Shared controller button glyph ───────────────────────────────────────────
//
// Single source of truth for every location that displays a controller button:
// the wizard footer, the App Drawer command bar, the XMB idle context-menu
// hint, and any future help-prompt row. The icon is chosen by the user's
// controller display family (Settings ▸ Controller ▸ Type), which flows
// reactively from ControllerLayoutRepository.prefs through
// XMBUiState.controllerDisplayType.
//
// Callers request a *physical position* (ControllerIcon), never a printed
// letter. A letter is not a stable key: "B" is the east face button on Xbox and
// the south face button on a Switch. Position is the only family-agnostic
// identity, so the resolver owns the per-family art and UI code stays
// controller-agnostic.
//
// Art provenance (see Controller_Helper_Icon_Mapping.md):
//   PLAYSTATION → PS5 pack,          Buttons Solid/White/128w (DualSense)
//   XBOX        → Xbox Series pack,  Buttons Solid/White/128w
//   NINTENDO    → Switch 2 pack,     Buttons Solid/White/128w, Pro D-Pad art
//
// All three are the same treatment at the same size, so a prompt row reads as one set. The
// PlayStation glyphs were previously the PS4 Premium pack — 480px and a different look, which
// stood out beside the flat white Xbox/Switch art.
//
// Nintendo has its own art for every core input, so no family ever borrows
// another family's letters — their physical A/B and X/Y positions are reversed.

/**
 * The drawable for [family]'s art of this position, or `null` when the input
 * does not exist on that hardware (a touchpad on an Xbox pad, GameChat on a
 * DualSense). Pure, so the whole mapping is unit-testable without Compose.
 *
 * Returning `null` rather than throwing matters: a prompt row that happens to
 * reference a family-exclusive input must degrade, not crash the screen.
 */
@DrawableRes
fun ControllerIcon.drawableForOrNull(family: ControllerDisplayType): Int? =
    when (family) {
        ControllerDisplayType.PLAYSTATION -> psTable
        ControllerDisplayType.NINTENDO -> nsTable
        ControllerDisplayType.XBOX -> xbTable
    }[this]

/**
 * The label [family] silkscreens on this position ("A", "○", "ZL"), used as the
 * text fallback when no art exists. `null` when the hardware has no such input.
 */
fun ControllerIcon.printedLabelFor(family: ControllerDisplayType): String? =
    when (family) {
        ControllerDisplayType.PLAYSTATION -> psLabels
        ControllerDisplayType.NINTENDO -> nsLabels
        ControllerDisplayType.XBOX -> xbLabels
    }[this]

private val psTable = mapOf(
    ControllerIcon.FACE_SOUTH to R.drawable.ctl_ps_face_south,
    ControllerIcon.FACE_EAST to R.drawable.ctl_ps_face_east,
    ControllerIcon.FACE_WEST to R.drawable.ctl_ps_face_west,
    ControllerIcon.FACE_NORTH to R.drawable.ctl_ps_face_north,
    ControllerIcon.DPAD_UP to R.drawable.ctl_ps_dpad_up,
    ControllerIcon.DPAD_DOWN to R.drawable.ctl_ps_dpad_down,
    ControllerIcon.DPAD_LEFT to R.drawable.ctl_ps_dpad_left,
    ControllerIcon.DPAD_RIGHT to R.drawable.ctl_ps_dpad_right,
    ControllerIcon.DPAD_ALL to R.drawable.ctl_ps_dpad_all,
    ControllerIcon.BUMPER_LEFT to R.drawable.ctl_ps_bumper_left,
    ControllerIcon.BUMPER_RIGHT to R.drawable.ctl_ps_bumper_right,
    ControllerIcon.TRIGGER_LEFT to R.drawable.ctl_ps_trigger_left,
    ControllerIcon.TRIGGER_RIGHT to R.drawable.ctl_ps_trigger_right,
    ControllerIcon.STICK_LEFT to R.drawable.ctl_ps_stick_left,
    ControllerIcon.STICK_RIGHT to R.drawable.ctl_ps_stick_right,
    ControllerIcon.STICK_LEFT_CLICK to R.drawable.ctl_ps_stick_left_click,
    ControllerIcon.STICK_RIGHT_CLICK to R.drawable.ctl_ps_stick_right_click,
    ControllerIcon.START to R.drawable.ctl_ps_start,
    // SELECT and SHARE stay distinct concepts even though the DualSense prints
    // one Create button that serves both roles — the art repeats, the IDs do not.
    ControllerIcon.SELECT to R.drawable.ctl_ps_select,
    ControllerIcon.SYSTEM to R.drawable.ctl_ps_system,
    ControllerIcon.SHARE to R.drawable.ctl_ps_share,
    ControllerIcon.TOUCHPAD to R.drawable.ctl_ps_touchpad,
    ControllerIcon.TOUCHPAD_LEFT to R.drawable.ctl_ps_touchpad_left,
    ControllerIcon.TOUCHPAD_RIGHT to R.drawable.ctl_ps_touchpad_right,
)

private val xbTable = mapOf(
    ControllerIcon.FACE_SOUTH to R.drawable.ctl_xb_face_south,
    ControllerIcon.FACE_EAST to R.drawable.ctl_xb_face_east,
    ControllerIcon.FACE_WEST to R.drawable.ctl_xb_face_west,
    ControllerIcon.FACE_NORTH to R.drawable.ctl_xb_face_north,
    ControllerIcon.DPAD_UP to R.drawable.ctl_xb_dpad_up,
    ControllerIcon.DPAD_DOWN to R.drawable.ctl_xb_dpad_down,
    ControllerIcon.DPAD_LEFT to R.drawable.ctl_xb_dpad_left,
    ControllerIcon.DPAD_RIGHT to R.drawable.ctl_xb_dpad_right,
    ControllerIcon.DPAD_ALL to R.drawable.ctl_xb_dpad_all,
    ControllerIcon.BUMPER_LEFT to R.drawable.ctl_xb_bumper_left,
    ControllerIcon.BUMPER_RIGHT to R.drawable.ctl_xb_bumper_right,
    ControllerIcon.TRIGGER_LEFT to R.drawable.ctl_xb_trigger_left,
    ControllerIcon.TRIGGER_RIGHT to R.drawable.ctl_xb_trigger_right,
    ControllerIcon.STICK_LEFT to R.drawable.ctl_xb_stick_left,
    ControllerIcon.STICK_RIGHT to R.drawable.ctl_xb_stick_right,
    ControllerIcon.STICK_LEFT_CLICK to R.drawable.ctl_xb_stick_left_click,
    ControllerIcon.STICK_RIGHT_CLICK to R.drawable.ctl_xb_stick_right_click,
    ControllerIcon.START to R.drawable.ctl_xb_start,
    ControllerIcon.SELECT to R.drawable.ctl_xb_select,
    ControllerIcon.SYSTEM to R.drawable.ctl_xb_system,
    ControllerIcon.SHARE to R.drawable.ctl_xb_share,
)

private val nsTable = mapOf(
    ControllerIcon.FACE_SOUTH to R.drawable.ctl_ns_face_south,
    ControllerIcon.FACE_EAST to R.drawable.ctl_ns_face_east,
    ControllerIcon.FACE_WEST to R.drawable.ctl_ns_face_west,
    ControllerIcon.FACE_NORTH to R.drawable.ctl_ns_face_north,
    ControllerIcon.DPAD_UP to R.drawable.ctl_ns_dpad_up,
    ControllerIcon.DPAD_DOWN to R.drawable.ctl_ns_dpad_down,
    ControllerIcon.DPAD_LEFT to R.drawable.ctl_ns_dpad_left,
    ControllerIcon.DPAD_RIGHT to R.drawable.ctl_ns_dpad_right,
    ControllerIcon.DPAD_ALL to R.drawable.ctl_ns_dpad_all,
    ControllerIcon.BUMPER_LEFT to R.drawable.ctl_ns_bumper_left,
    ControllerIcon.BUMPER_RIGHT to R.drawable.ctl_ns_bumper_right,
    ControllerIcon.TRIGGER_LEFT to R.drawable.ctl_ns_trigger_left,
    ControllerIcon.TRIGGER_RIGHT to R.drawable.ctl_ns_trigger_right,
    ControllerIcon.STICK_LEFT to R.drawable.ctl_ns_stick_left,
    ControllerIcon.STICK_RIGHT to R.drawable.ctl_ns_stick_right,
    ControllerIcon.STICK_LEFT_CLICK to R.drawable.ctl_ns_stick_left_click,
    ControllerIcon.STICK_RIGHT_CLICK to R.drawable.ctl_ns_stick_right_click,
    ControllerIcon.START to R.drawable.ctl_ns_start,
    ControllerIcon.SELECT to R.drawable.ctl_ns_select,
    ControllerIcon.SYSTEM to R.drawable.ctl_ns_system,
    ControllerIcon.SHARE to R.drawable.ctl_ns_share,
    ControllerIcon.GAME_CHAT to R.drawable.ctl_ns_game_chat,
    ControllerIcon.CAMERA to R.drawable.ctl_ns_camera,
    ControllerIcon.PADDLE_LEFT to R.drawable.ctl_ns_paddle_left,
    ControllerIcon.PADDLE_RIGHT to R.drawable.ctl_ns_paddle_right,
    ControllerIcon.JOYCON_SL to R.drawable.ctl_ns_joycon_sl,
    ControllerIcon.JOYCON_SR to R.drawable.ctl_ns_joycon_sr,
)

// ── Printed labels (text fallback + TalkBack) ────────────────────────────────

private val psLabels = mapOf(
    ControllerIcon.FACE_SOUTH to "Cross", ControllerIcon.FACE_EAST to "Circle",
    ControllerIcon.FACE_WEST to "Square", ControllerIcon.FACE_NORTH to "Triangle",
    ControllerIcon.BUMPER_LEFT to "L1", ControllerIcon.BUMPER_RIGHT to "R1",
    ControllerIcon.TRIGGER_LEFT to "L2", ControllerIcon.TRIGGER_RIGHT to "R2",
    ControllerIcon.STICK_LEFT_CLICK to "L3", ControllerIcon.STICK_RIGHT_CLICK to "R3",
    ControllerIcon.START to "Options", ControllerIcon.SELECT to "Create",
    ControllerIcon.SYSTEM to "PS", ControllerIcon.SHARE to "Create",
    ControllerIcon.TOUCHPAD to "Touchpad",
    ControllerIcon.TOUCHPAD_LEFT to "Touchpad left",
    ControllerIcon.TOUCHPAD_RIGHT to "Touchpad right",
)

private val xbLabels = mapOf(
    ControllerIcon.FACE_SOUTH to "A", ControllerIcon.FACE_EAST to "B",
    ControllerIcon.FACE_WEST to "X", ControllerIcon.FACE_NORTH to "Y",
    ControllerIcon.BUMPER_LEFT to "LB", ControllerIcon.BUMPER_RIGHT to "RB",
    ControllerIcon.TRIGGER_LEFT to "LT", ControllerIcon.TRIGGER_RIGHT to "RT",
    ControllerIcon.STICK_LEFT_CLICK to "LS", ControllerIcon.STICK_RIGHT_CLICK to "RS",
    ControllerIcon.START to "Menu", ControllerIcon.SELECT to "View",
    ControllerIcon.SYSTEM to "Xbox", ControllerIcon.SHARE to "Share",
)

private val nsLabels = mapOf(
    // Nintendo's A/B and X/Y are mirrored from Xbox — south is B, east is A.
    ControllerIcon.FACE_SOUTH to "B", ControllerIcon.FACE_EAST to "A",
    ControllerIcon.FACE_WEST to "Y", ControllerIcon.FACE_NORTH to "X",
    ControllerIcon.BUMPER_LEFT to "L", ControllerIcon.BUMPER_RIGHT to "R",
    ControllerIcon.TRIGGER_LEFT to "ZL", ControllerIcon.TRIGGER_RIGHT to "ZR",
    // Nintendo prints no L3/R3 marking; name the press for the text fallback.
    ControllerIcon.STICK_LEFT_CLICK to "L Stick", ControllerIcon.STICK_RIGHT_CLICK to "R Stick",
    ControllerIcon.START to "Plus", ControllerIcon.SELECT to "Minus",
    ControllerIcon.SYSTEM to "Home", ControllerIcon.SHARE to "Capture",
    ControllerIcon.GAME_CHAT to "C", ControllerIcon.CAMERA to "Camera",
    ControllerIcon.PADDLE_LEFT to "GL", ControllerIcon.PADDLE_RIGHT to "GR",
    ControllerIcon.JOYCON_SL to "SL", ControllerIcon.JOYCON_SR to "SR",
)

// ── Rendering ────────────────────────────────────────────────────────────────

/**
 * Renders the [family] art for a physical controller position.
 *
 * Falls back per the icon-mapping contract: art, else the family's printed
 * label as text, else nothing. Glyphs are normalized to [size]: every family's
 * art is 128px today, but sizing here is what guarantees a row stays aligned if
 * a future pack ships at another resolution — as the 480px PlayStation set that
 * these replaced did.
 *
 * The glyph is decorative: callers pair it with an action label that carries
 * the meaning, so semantics are cleared here to avoid a doubled announcement.
 */
@Composable
fun ControllerIconGlyph(
    icon: ControllerIcon,
    family: ControllerDisplayType,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    val drawable = icon.drawableForOrNull(family)
    if (drawable != null) {
        Image(
            painter = painterResource(drawable),
            contentDescription = null,
            modifier = modifier.size(size),
        )
        return
    }
    val label = icon.printedLabelFor(family) ?: return
    Text(
        text = label,
        style = LocalTextStyle.current,
        modifier = modifier.clearAndSetSemantics { },
    )
}
