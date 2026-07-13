package com.playfieldportal.feature.achievements.match

import com.playfieldportal.core.data.database.dao.ProviderGameLinkDao
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.AchievementRepository
import com.playfieldportal.feature.achievements.api.RetroAchievementsApi
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** A game the auto-matcher couldn't link, with a plain reason for the report. */
data class UnmatchedGame(
    val gameId: Long,
    val title: String,
    val platformId: String,
    val reason: String,
)

/** Outcome of an auto-match run: what got linked and what didn't. */
data class MatchReport(
    val matched: Int,
    val unmatched: List<UnmatchedGame>,
)

/**
 * Batch-links unlinked games to their achievement provider: Steam PC games by title, and
 * RetroAchievements ROMs by content hash (cartridge systems). Anything it can't resolve is
 * returned in the report for the user to link by hand. See docs/shiba-coins-achievements-plan.md.
 */
@Singleton
class AchievementAutoMatcher @Inject constructor(
    private val gameRepository: GameRepository,
    private val linkDao: ProviderGameLinkDao,
    private val retroApi: RetroAchievementsApi,
    private val repository: AchievementRepository,
    private val romReader: RomBytesReader,
) {
    private sealed interface Outcome {
        data object Matched : Outcome
        data class Unmatched(val reason: String) : Outcome
    }

    /** Matches every unlinked game; [onProgress] reports (done, total). */
    suspend fun matchUnlinked(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): MatchReport {
        val unlinked = gameRepository.observeGamesOnly().first()
            .filter { linkDao.getForGame(it.id) == null }

        var matched = 0
        val unmatched = mutableListOf<UnmatchedGame>()
        Timber.d("auto-match: %d unlinked of %d games", unlinked.size, unlinked.size)
        unlinked.forEachIndexed { index, game ->
            onProgress(index, unlinked.size)
            val outcome = matchOne(game)
            Timber.d("auto-match [%s] %s -> %s", game.platformId, game.displayTitle, outcome)
            when (outcome) {
                Outcome.Matched -> matched++
                is Outcome.Unmatched -> unmatched += UnmatchedGame(game.id, game.displayTitle, game.platformId, outcome.reason)
            }
        }
        onProgress(unlinked.size, unlinked.size)
        return MatchReport(matched, unmatched)
    }

    private suspend fun matchOne(game: Game): Outcome {
        // Steam PC titles resolve by name.
        if (game.platformId == "windows") {
            return if (repository.resolveSteamLink(game.id, game.displayTitle) != null) Outcome.Matched
            else Outcome.Unmatched("no Steam title match")
        }
        // RetroAchievements ROMs resolve by content hash (cartridge systems only for now).
        val consoleId = RaConsole.idFor(game.platformId)
        if (consoleId == null || !RaRomHasher.isSupported(game.platformId)) {
            return Outcome.Unmatched("unsupported system")
        }
        val bytes = romReader.read(game) ?: return Outcome.Unmatched("couldn't read ROM")
        val hash = RaRomHasher.hash(game.platformId, bytes) ?: return Outcome.Unmatched("unsupported system")
        val raGameId = retroApi.gameIdForHash(consoleId, hash) ?: return Outcome.Unmatched("no RetroAchievements match")
        repository.linkManually(game.id, AchievementProvider.RETRO_ACHIEVEMENTS, raGameId)
        return Outcome.Matched
    }
}
