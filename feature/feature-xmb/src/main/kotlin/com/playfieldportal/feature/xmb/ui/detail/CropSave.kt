package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.feature.artwork.store.ImageFormat

/** How the Artwork Studio saves an applied crop. Pure, so the choice is testable. */
enum class CropSave {
    /** ICON1's video snap: re-encoded to the frame. */
    REENCODE_VIDEO,

    /** A still: cropped into a PNG, as always. */
    BAKE,

    /** An animated image: kept whole and framed while drawing, so it keeps playing. */
    AT_DRAW;

    companion object {
        /** [header] is the first bytes (≥ 21) of the image being cropped. */
        fun of(isVideo: Boolean, header: ByteArray): CropSave = when {
            isVideo -> REENCODE_VIDEO
            ImageFormat.isAnimated(header) -> AT_DRAW
            else -> BAKE
        }
    }
}
