package com.playfieldportal.core.ui.icons

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap

/**
 * A replaceable XMB icon: either a decoded still, or an animated GIF kept as its first frame
 * plus the file path Coil animates.
 *
 * `firstFrame` exists on BOTH arms so the unfocused case is free: an unfocused [Animated]
 * icon draws the exact same single-bitmap path a [Still] does (same matte, same cost), and
 * the decoder only starts when [LocalIconAnimating] turns on for the focused item. A
 * single-frame GIF is stored as a [Still] — no decoder is ever started for it.
 */
sealed interface CustomIcon {
    val firstFrame: ImageBitmap

    /** A still image, already decoded and dimension-capped at import. */
    data class Still(override val firstFrame: ImageBitmap) : CustomIcon

    /**
     * An animated GIF: [firstFrame] renders everywhere except the focused item, which
     * streams [path] through the global Coil loader (AnimatedImageDecoder). [path] is the
     * absolute file path as stored — also the key Coil caches it under, which is why
     * replacing a GIF must evict that path from the image cache.
     */
    data class Animated(val path: String, override val firstFrame: ImageBitmap) : CustomIcon
}

/**
 * The user's per-slot picks (`custom-icons/`), slot key → icon. Provided by XMBShell;
 * empty when nothing has been customized. Precedence at every render site is
 * `LocalCustomIcons` > `LocalXmbIconOverrides` > built-in — user picks win over the applied
 * theme's icons and survive theme switches.
 */
val LocalCustomIcons = staticCompositionLocalOf<Map<String, CustomIcon>> { emptyMap() }

/**
 * True only for the row/column currently focused — the single gate on GIF playback (decision:
 * only the focused icon animates; everything else shows frame 1, one decoder at a time).
 * Provided per container where selection is already known (XMBCategoryBar, XMBItemList), ANDed
 * with "animation allowed at all" (battery saver, blocking overlays) by the provider.
 */
val LocalIconAnimating = staticCompositionLocalOf { false }
