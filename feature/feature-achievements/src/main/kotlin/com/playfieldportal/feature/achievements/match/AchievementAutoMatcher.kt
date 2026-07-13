package com.playfieldportal.feature.achievements.match

import com.playfieldportal.core.data.database.dao.AchievementMatchNoteDao
import com.playfieldportal.core.data.database.dao.ProviderGameLinkDao
import com.playfieldportal.core.data.database.entity.AchievementMatchNoteEntity
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
    private val matchNoteDao: AchievementMatchNoteDao,
    private val retroApi: RetroAchievementsApi,
    private val repository: AchievementRepository,
    private val romReader: RomBytesReader,
    private val discOpener: DiscImageOpener,
    private val steamGridDb: com.playfieldportal.feature.artwork.api.SteamGridDbApi,
) {
    private sealed interface Outcome {
        data object Matched : Outcome
        data class Unmatched(val reason: String) : Outcome
    }

    // How the ROM/disc hashing step turned out, so the failure reason can name the actual cause.
    private sealed interface HashAttempt {
        data class Hashed(val hash: String) : HashAttempt
        data object Unreadable : HashAttempt          // cartridge bytes couldn't be read
        data object DiscUnreadable : HashAttempt      // disc image couldn't be opened (bad/unsupported format)
        data object DiscUnidentified : HashAttempt    // disc opened but its boot executable wasn't found
        data object NoHasher : HashAttempt            // RA console, but this system's format isn't hashed yet
    }

    /** Matches every unlinked game; [onProgress] reports (done, total). */
    suspend fun matchUnlinked(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): MatchReport {
        val unlinked = gameRepository.observeGamesOnly().first()
            .filter { linkDao.getForGame(it.id) == null }

        // Notes are rewritten from scratch: this run's unmatched set is the source of truth.
        matchNoteDao.clear()
        val now = System.currentTimeMillis()

        var matched = 0
        val unmatched = mutableListOf<UnmatchedGame>()
        Timber.d("auto-match: %d unlinked of %d games", unlinked.size, unlinked.size)
        unlinked.forEachIndexed { index, game ->
            onProgress(index, unlinked.size)
            val outcome = matchOne(game)
            Timber.d("auto-match [%s] %s -> %s", game.platformId, game.displayTitle, outcome)
            when (outcome) {
                Outcome.Matched -> matched++
                is Outcome.Unmatched -> {
                    unmatched += UnmatchedGame(game.id, game.displayTitle, game.platformId, outcome.reason)
                    matchNoteDao.upsert(AchievementMatchNoteEntity(game.id, outcome.reason, now))
                }
            }
        }
        onProgress(unlinked.size, unlinked.size)
        return MatchReport(matched, unmatched)
    }

    private suspend fun matchOne(game: Game): Outcome {
        if (game.platformId == "windows") return matchSteam(game)
        // RetroAchievements: prefer an exact ROM-content-hash match; fall back to a normalized
        // title match so a differing regional dump still links (RA coins are per-game).
        val consoleId = RaConsole.idFor(game.platformId)
            ?: return Outcome.Unmatched("RetroAchievements has no achievements for ${platformLabel(game.platformId)}")

        val attempt = attemptHash(game)
        val byHash = (attempt as? HashAttempt.Hashed)?.let { retroApi.gameIdForHash(consoleId, it.hash) }
        val raGameId = byHash ?: retroApi.gameIdForTitle(consoleId, game.displayTitle)
        if (raGameId == null) return Outcome.Unmatched(reasonFor(attempt))

        repository.linkManually(game.id, AchievementProvider.RETRO_ACHIEVEMENTS, raGameId)
        return Outcome.Matched
    }

    // Steam PC games resolve down a ladder: the appid the shortcut already carries (deterministic),
    // then SteamGridDB's platform data (if we have an SGDB id), then the title match.
    private suspend fun matchSteam(game: Game): Outcome {
        SteamShortcut.appIdFrom(game)?.let { appId ->
            repository.linkManually(game.id, AchievementProvider.STEAM, appId)
            return Outcome.Matched
        }
        game.steamGridDbId?.let { sgdbId ->
            steamGridDb.getSteamAppId(sgdbId)?.let { appId ->
                repository.linkManually(game.id, AchievementProvider.STEAM, appId)
                return Outcome.Matched
            }
        }
        return if (repository.resolveSteamLink(game.id, game.displayTitle) != null) Outcome.Matched
        else Outcome.Unmatched("Not found on Steam (no embedded appid, no SteamGridDB or title match)")
    }

    // Computes the RA content hash, naming the failure mode when it can't. Cartridges hash from a
    // full byte read; disc images hash from a seeking reader (they're far too large to load whole).
    private suspend fun attemptHash(game: Game): HashAttempt {
        if (RaRomHasher.isSupported(game.platformId)) {
            val bytes = romReader.read(game) ?: return HashAttempt.Unreadable
            val hash = RaRomHasher.hash(game.platformId, bytes) ?: return HashAttempt.Unreadable
            return HashAttempt.Hashed(hash)
        }
        if (RaDiscHasher.isSupported(game.platformId)) {
            val image = discOpener.open(game) ?: return HashAttempt.DiscUnreadable
            val hash = image.use { RaDiscHasher.hash(game.platformId, it) }
            return if (hash == null) HashAttempt.DiscUnidentified else HashAttempt.Hashed(hash)
        }
        return HashAttempt.NoHasher
    }

    // The reason shown when neither the hash nor the title matched — leads with why the file itself
    // didn't hash, then notes that the title fallback also came up empty.
    private fun reasonFor(attempt: HashAttempt): String = when (attempt) {
        is HashAttempt.Hashed -> "ROM hash isn't registered on RetroAchievements, and no title match"
        HashAttempt.Unreadable -> "Couldn't read the ROM file, and no title match"
        HashAttempt.DiscUnreadable -> "Unsupported disc image (e.g. NKit, CHD, or compressed) — can't hash, and no title match"
        HashAttempt.DiscUnidentified -> "Couldn't find the disc's boot executable, and no title match"
        HashAttempt.NoHasher -> "Disc hashing for this system isn't supported yet, and no title match"
    }

    // Friendly names for the systems RetroAchievements doesn't cover, so the note reads naturally.
    private fun platformLabel(platformId: String): String = when (platformId) {
        "x360" -> "Xbox 360"
        "xbox" -> "Xbox"
        "xboxone" -> "Xbox One"
        "psvita" -> "PS Vita"
        "ps4" -> "PlayStation 4"
        "n3ds" -> "Nintendo 3DS"
        "wiiu" -> "Wii U"
        "switch" -> "Nintendo Switch"
        else -> "this system ($platformId)"
    }
}
