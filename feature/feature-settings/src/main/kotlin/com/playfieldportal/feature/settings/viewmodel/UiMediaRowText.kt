package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.domain.model.UiMediaKind
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.core.domain.text.UiText
import com.playfieldportal.core.domain.text.labelRes
import com.playfieldportal.feature.settings.R

/**
 * The words around UI media in Settings, in one place: a row's value, a video row's cap, and the
 * tray report for clips a theme could not install. Caps are read from the slot's shared
 * UiMediaLimits spec — the same numbers the Studio exports against and the install gate enforces.
 */
object UiMediaRowText {

    /**
     * A media row's value: the user's own pick ([userName], else [fallback]) wins, then the applied
     * theme's file, then the built-in default.
     */
    fun label(userName: String?, userAssigned: Boolean, themeSupplied: Boolean, fallback: UiText): UiText = when {
        userAssigned -> userName?.let { UiText.raw(it) } ?: fallback
        themeSupplied -> UiText.res(R.string.setd_from_theme)
        else -> UiText.res(R.string.setd_pfp_default)
    }

    /** "(MP4 or WebM, up to 15 seconds)" for a video slot, from its own cap. */
    fun videoCap(slot: UiMediaSlot): UiText =
        UiText.plural(R.plurals.setd_video_cap, (slot.limits.hardMaxMs / 1000).toInt())

    /** The tray body for theme media the install gate refused, or null when nothing was refused. */
    fun droppedReport(dropped: Map<String, String>): UiText? {
        if (dropped.isEmpty()) return null
        val lines = dropped.map { (key, reason) ->
            UiText.res(
                R.string.setd_dropped_item,
                UiMediaSlot.fromKey(key)?.let(::slotName) ?: UiText.raw(key),
                UiText.raw(reason),
            )
        }
        return UiText.Joined(listOf<UiText>(UiText.res(R.string.setd_dropped_header)) + lines, "\n")
    }

    /** Settings' own name for [slot]: the Display rows say "Boot Video", the Sound rows name the cue. */
    fun slotName(slot: UiMediaSlot): UiText = when (slot) {
        UiMediaSlot.BOOT_VIDEO -> UiText.res(R.string.setd_slot_boot_video)
        UiMediaSlot.GAMEBOOT_VIDEO -> UiText.res(R.string.setd_slot_gameboot_video)
        else -> if (slot.kind == UiMediaKind.SOUND) {
            UiText.res(R.string.setd_slot_sound_name, UiText.res(slot.labelRes))
        } else {
            UiText.res(slot.labelRes)
        }
    }
}
