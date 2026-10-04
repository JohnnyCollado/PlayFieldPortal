package com.playfieldportal.core.ui.icons

import androidx.compose.ui.graphics.ImageBitmap

/**
 * A replaceable XMB icon: either a decoded still, or an animated GIF kept as its first frame
 * plus the file path Coil animates.
 *
 * `firstFrame` exists on BOTH arms so the unfocused case is free: an unfocused [Animated]
 * icon draws the exact same single-bitmap path a [Still] does (same matte, same cost), and
 * the decoder only starts when Animated Images lets the icon play (see CustomIconSurface). A
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

