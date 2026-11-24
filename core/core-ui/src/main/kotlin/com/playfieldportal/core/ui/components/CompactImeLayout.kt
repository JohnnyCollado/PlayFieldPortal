package com.playfieldportal.core.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ── Modals over the system keyboard ──────────────────────────────────────────
//
// PFP draws edge-to-edge, so the system keyboard is an overlay: the window neither pans nor
// resizes, and the only sign of it is the IME inset. On a phone in landscape it leaves ~150dp —
// less than a full modal card — so the text-entry modal squashed its field flat and the colour
// picker sat under the keyboard. Below [CompactImeThreshold] both collapse to one strip resting on
// the keyboard: field plus Cancel / Confirm, so a touch user can always finish.

/** The least room above the system keyboard a full modal card fits in. */
internal val CompactImeThreshold = 260.dp

/** Below this much room above a keyboard, the keyboard layout drops its title too: field and buttons. */
internal val TightImeThreshold = 150.dp

/**
 * Whether a modal collapses to its keyboard layout. Either keyboard counts: PFP's reserves its own
 * room, but on a 468dp-tall handheld that room (about 140dp) is still short of a full card.
 */
internal fun useCompactImeLayout(keyboardOpen: Boolean, spaceAboveKeyboard: Dp): Boolean =
    keyboardOpen && spaceAboveKeyboard < CompactImeThreshold

/** Test seam: a fixed system keyboard height, since Robolectric reports no IME inset. */
internal val LocalSystemKeyboardHeightOverride = staticCompositionLocalOf<Dp?> { null }

/** The system keyboard's height over this window; 0 while it is closed. */
@Composable
internal fun systemKeyboardHeight(): Dp =
    LocalSystemKeyboardHeightOverride.current
        ?: with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() }
