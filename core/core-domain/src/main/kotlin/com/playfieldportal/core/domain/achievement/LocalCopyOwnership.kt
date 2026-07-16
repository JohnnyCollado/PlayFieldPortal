package com.playfieldportal.core.domain.achievement

/**
 * Owned-vs-local classification of a LOCAL_STEAM copy, derived from the Steam owned-games cache
 * at scan time (docs/local-steam-achievements-plan.md section 5). Absence (null) means UNKNOWN —
 * the cache was never populated — and the UI stays silent about ownership. NOT_IN_LIBRARY is
 * deliberately neutral wording: family sharing, alternate accounts, and unplayed free games all
 * look unowned, so the signal can never prove piracy.
 */
enum class LocalCopyOwnership {
    OWNED,
    NOT_IN_LIBRARY;

    companion object {
        fun fromName(name: String?): LocalCopyOwnership? =
            entries.firstOrNull { it.name == name }
    }
}
