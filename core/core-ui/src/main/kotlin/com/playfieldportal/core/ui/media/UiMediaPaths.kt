package com.playfieldportal.core.ui.media

import com.playfieldportal.core.domain.model.UiMediaSlot
import kotlinx.coroutines.flow.Flow

/**
 * Read-only view of the user's UI-media assignments for components living in core-ui.
 *
 * The concrete store ([com.playfieldportal.core.data.repository.UiMediaStore]) lives in core-data,
 * which depends on core-ui — so core-ui cannot see it without a Gradle cycle. This interface is
 * the seam, bound by a Hilt module in core-data (the same pattern as CustomIconCacheEvictor, in
 * the opposite direction). Tests substitute a fake directly.
 */
interface UiMediaPaths {

    /**
     * Absolute path of the user's file for [slot], or null when the slot is on the PFP default.
     * Cheap (one directory listing) but still file IO — call it off the main thread.
     */
    fun pathFor(slot: UiMediaSlot): String?

    /**
     * Bumps on every import/clear of any slot, so observers reload what [pathFor] would now
     * return. Without it, a replaced sound keeps playing the old sample for the process's
     * lifetime — the exact bug the custom-icons stamp exists to prevent.
     */
    val stamp: Flow<Long>

    /**
     * The user's `sound_menu_enabled` pref (default true). Observed here — not by whichever
     * ViewModel happens to be alive — so the mute flag can never silently die with its host.
     */
    val menuSoundsEnabled: Flow<Boolean>
}
