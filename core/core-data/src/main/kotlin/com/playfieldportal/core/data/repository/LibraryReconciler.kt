package com.playfieldportal.core.data.repository

import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies the missing-ROM write policy after a scan of one console's roots.
 *
 * A game whose file was seen is marked present (missing flag cleared, last_seen_at stamped); a game
 * whose file is gone is marked missing — never deleted, so its favorite/play-stats/artwork survive
 * and it reappears in every view once the file returns. Real deletion is only ever the user's
 * explicit "Remove permanently" action, never this path.
 *
 * Removals are applied ONLY when the survey is trustworthy: the scan didn't error, produced a
 * non-null present-set, and that set isn't suspiciously empty against a non-empty library (a
 * half-mounted card must never mass-flag the whole console as missing).
 */
@Singleton
class LibraryReconciler @Inject constructor(
    private val gameRepository: GameRepository,
) {
    /** [skipped] is true when the survey was untrustworthy and nothing was touched. */
    data class Result(val markedSeen: Int, val markedMissing: Int, val skipped: Boolean)

    /**
     * @param dbGames the console's current library rows.
     * @param present the union of on-disk ROM paths the scan surveyed; null if any source couldn't
     *   survey (unmounted card, permission loss) — the caller must pass null, not an empty set, in
     *   that case.
     * @param scanErrored true if any source reported an error during the scan.
     */
    suspend fun reconcile(
        dbGames: List<Game>,
        present: Set<String>?,
        scanErrored: Boolean,
        now: Long = System.currentTimeMillis(),
    ): Result {
        val romPaths = dbGames.mapNotNull { it.romPath }

        // Untrustworthy survey → touch nothing.
        if (scanErrored || present == null) return Result(0, 0, skipped = true)

        // Half-mounted guard: an empty survey against a non-empty library is a mount problem, not a
        // real mass deletion. Bail rather than flag everything missing.
        if (present.isEmpty() && romPaths.isNotEmpty()) return Result(0, 0, skipped = true)

        val seen = romPaths.filter { it in present }
        val gone = romPaths.filterNot { it in present }

        if (seen.isNotEmpty()) gameRepository.markSeen(seen, now)
        if (gone.isNotEmpty()) gameRepository.markMissing(gone)

        return Result(markedSeen = seen.size, markedMissing = gone.size, skipped = false)
    }
}
