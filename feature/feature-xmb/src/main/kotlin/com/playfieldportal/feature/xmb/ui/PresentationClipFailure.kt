package com.playfieldportal.feature.xmb.ui

/**
 * The tray row for a boot or GameBoot clip that would not play. The overlay falls back to the
 * built-in presentation either way; this is what stops that fallback being silent. Keyed per
 * presentation, so a clip that fails on every launch is one row, refreshed, not a pile.
 */
data class PresentationClipFailure(val id: String, val label: String, val message: String) {
    companion object {
        fun of(gameBoot: Boolean): PresentationClipFailure {
            val name = if (gameBoot) "GameBoot" else "Boot Sequence"
            val clip = if (gameBoot) "GameBoot video" else "boot video"
            return PresentationClipFailure(
                id = if (gameBoot) "gameboot_clip_failed" else "boot_clip_failed",
                label = name,
                message = "Your $clip couldn't play on this device, so the built-in sequence played instead. " +
                    "Re-export the theme from the Theme Studio (it converts the video), or pick another clip " +
                    "in Display settings.",
            )
        }
    }
}
