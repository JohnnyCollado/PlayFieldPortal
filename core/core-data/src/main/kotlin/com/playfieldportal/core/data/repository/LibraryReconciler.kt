package com.playfieldportal.core.data.repository

import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

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
 *
 * The same guard holds per folder: a game is only flagged missing when the survey saw some other
 * ROM in its folder. Android's storage provider answers a folder it cannot read with an empty
 * listing rather than an error, so a one-folder-per-disc game (Parasite Eve II) looked deleted
 * while the rest of the console was seen. See reports/2026-10-22-parasite-eve-missing.md. The cost:
 * deleting a game's whole folder leaves its row visible until the launch reports the file gone.
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
        if (present == null || !isTrustedSurvey(romPaths, present, scanErrored)) {
            return Result(0, 0, skipped = true)
        }

        val seen = romPaths.filter { it in present }
        val gone = gonePaths(romPaths, present)
        val withheld = romPaths.size - seen.size - gone.size
        if (withheld > 0) Timber.w("Library reconcile — $withheld game(s) left as they were: their folder listed no ROMs")

        if (seen.isNotEmpty()) gameRepository.markSeen(seen, now)
        if (gone.isNotEmpty()) gameRepository.markMissing(gone)

        return Result(markedSeen = seen.size, markedMissing = gone.size, skipped = false)
    }

    companion object {
        /**
         * Whether [present] can be trusted to say which of [romPaths] are gone: the scan didn't
         * error, the survey ran, and it isn't empty against a non-empty library (the half-mounted
         * guard — that is a mount problem, not a real mass deletion).
         */
        fun isTrustedSurvey(romPaths: List<String>, present: Set<String>?, scanErrored: Boolean): Boolean =
            !scanErrored && present != null && !(present.isEmpty() && romPaths.isNotEmpty())

        /**
         * [dbGames] carrying the missing flags [reconcile] writes for this survey, for a caller that
         * must decide on them before they are stored. An untrustworthy survey returns the rows
         * unchanged, exactly as [reconcile] then touches nothing.
         */
        fun withSurveyedFlags(dbGames: List<Game>, present: Set<String>?, scanErrored: Boolean): List<Game> {
            if (present == null || !isTrustedSurvey(dbGames.mapNotNull { it.romPath }, present, scanErrored)) {
                return dbGames
            }
            val gone = gonePaths(dbGames.mapNotNull { it.romPath }, present).toHashSet()
            return dbGames.map { game ->
                val path = game.romPath ?: return@map game
                val missing = when (path) {
                    in present -> false
                    in gone -> true
                    else -> return@map game   // withheld: its folder listed no ROMs
                }
                if (game.isMissing == missing) game else game.copy(isMissing = missing)
            }
        }

        /**
         * The [romPaths] a trusted [present] set says are gone: absent from it, in a folder where
         * the survey saw at least one other ROM. A folder with nothing seen is withheld, as the
         * whole-console guard in [isTrustedSurvey] withholds an empty survey.
         */
        fun gonePaths(romPaths: List<String>, present: Set<String>): List<String> {
            val seenFolders = present.mapTo(HashSet(), ::folderOf)
            return romPaths.filter { it !in present && folderOf(it) in seenFolders }
        }

        // Both separators, as in DiscCompanionSuppressor, so Windows-style test paths group too.
        private fun folderOf(path: String) = path.substringBeforeLast('/').substringBeforeLast('\\')
    }
}
