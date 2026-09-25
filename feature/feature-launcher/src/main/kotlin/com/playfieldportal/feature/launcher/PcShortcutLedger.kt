package com.playfieldportal.feature.launcher

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers which pinned PC shortcuts PFP has already turned into library rows, as the host's
 * `lastChangedTimestamp` at the moment of the import.
 *
 * This is what makes "Add to Desktop" a one-shot hand-off rather than a standing subscription.
 * [PcShortcutImporter.reconcilePinnedShortcuts] sweeps every pin PFP holds on every startup and
 * shortcut change; without a ledger that sweep re-creates a game the user deliberately removed
 * from the library, because the pin itself never goes away (PFP must keep it — `startShortcut`
 * only launches shortcuts the launcher still holds). With it, a handled pin stays handled until
 * the host *republishes* the shortcut, which is precisely what pressing "Add to Desktop" again
 * does — so the user's way back in is re-adding it the same way, or exporting the game into
 * `windows/import/`.
 *
 * Marks are keyed by host package and shortcut id and are never pruned: a mark for a shortcut
 * that no longer exists is a few bytes, and if that shortcut ever comes back it arrives with a
 * newer stamp and re-imports anyway.
 */
@Singleton
class PcShortcutLedger @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * True when this pin has already been imported and has not been republished since —
     * [changedAt] is the shortcut's `lastChangedTimestamp`.
     */
    suspend fun isHandled(hostPackage: String, shortcutId: String, changedAt: Long): Boolean {
        val handledAt = context.pfpDataStore.data.first()[key(hostPackage, shortcutId)] ?: return false
        return changedAt <= handledAt
    }

    /** Records this pin as handled at [changedAt]; a later stamp supersedes an earlier one. */
    suspend fun markHandled(hostPackage: String, shortcutId: String, changedAt: Long) {
        context.pfpDataStore.edit { prefs ->
            val k = key(hostPackage, shortcutId)
            val previous = prefs[k]
            if (previous == null || changedAt > previous) prefs[k] = changedAt
        }
    }

    // NUL separates the two halves so no host/id pair can spell another pair's key.
    private fun key(hostPackage: String, shortcutId: String) =
        longPreferencesKey("$KEY_PREFIX$hostPackage\u0000$shortcutId")

    private companion object {
        const val KEY_PREFIX = "pc_shortcut_handled:"
    }
}
