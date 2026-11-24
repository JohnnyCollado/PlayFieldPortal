package com.playfieldportal.feature.xmb.ui

import com.playfieldportal.core.domain.text.UiText
import com.playfieldportal.feature.xmb.R

/**
 * The tray row for a boot or GameBoot clip that would not play. The overlay falls back to the
 * built-in presentation either way; this is what stops that fallback being silent. Keyed per
 * presentation, so a clip that fails on every launch is one row, refreshed, not a pile.
 */
data class PresentationClipFailure(val id: String, val label: UiText, val message: UiText) {
    companion object {
        fun of(gameBoot: Boolean): PresentationClipFailure {
            return PresentationClipFailure(
                id = if (gameBoot) "gameboot_clip_failed" else "boot_clip_failed",
                label = UiText.res(if (gameBoot) R.string.xmbu_clipfail_gameboot_label else R.string.xmbu_clipfail_boot_label),
                message = UiText.res(if (gameBoot) R.string.xmbu_clipfail_gameboot_message else R.string.xmbu_clipfail_boot_message),
            )
        }
    }
}
