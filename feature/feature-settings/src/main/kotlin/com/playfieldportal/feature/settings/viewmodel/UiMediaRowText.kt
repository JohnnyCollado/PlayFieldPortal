package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.domain.model.UiMediaKind
import com.playfieldportal.core.domain.model.UiMediaSlot

/**
 * The words around UI media in Settings, in one place: a row's value, a video row's cap, and the
 * tray report for clips a theme could not install. Caps are read from the slot's shared
 * UiMediaLimits spec — the same numbers the Studio exports against and the install gate enforces.
 */
object UiMediaRowText {

    const val FROM_THEME = "From theme"

    /**
     * A media row's value: the user's own pick ([userName], else [fallback]) wins, then the applied
     * theme's file, then the built-in default.
     */
    fun label(userName: String?, userAssigned: Boolean, themeSupplied: Boolean, fallback: String): String = when {
        userAssigned -> userName ?: fallback
        themeSupplied -> FROM_THEME
        else -> PFP_DEFAULT_LABEL
    }

    /** "(MP4 or WebM, up to 15 seconds)" for a video slot, from its own cap. */
    fun videoCap(slot: UiMediaSlot): String = "(MP4 or WebM, up to ${slot.limits.hardMaxMs / 1000} seconds)"

    /** The tray body for theme media the install gate refused, or null when nothing was refused. */
    fun droppedReport(dropped: Map<String, String>): String? {
        if (dropped.isEmpty()) return null
        return buildString {
            append("Not installed from this theme:")
            for ((key, reason) in dropped) {
                append("\n").append(UiMediaSlot.fromKey(key)?.let(::slotName) ?: key).append(" — ").append(reason)
            }
        }
    }

    /** Settings' own name for [slot]: the Display rows say "Boot Video", the Sound rows name the cue. */
    fun slotName(slot: UiMediaSlot): String = when (slot) {
        UiMediaSlot.BOOT_VIDEO -> "Boot Video"
        UiMediaSlot.GAMEBOOT_VIDEO -> "GameBoot Video"
        else -> if (slot.kind == UiMediaKind.SOUND) "${slot.displayName} sound" else slot.displayName
    }
}
