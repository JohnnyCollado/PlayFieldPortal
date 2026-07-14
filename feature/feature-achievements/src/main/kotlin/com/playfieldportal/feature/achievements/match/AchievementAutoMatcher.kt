package com.playfieldportal.feature.achievements.match

import com.playfieldportal.core.data.database.dao.AchievementMatchNoteDao
import com.playfieldportal.core.data.database.dao.ProviderGameLinkDao
import com.playfieldportal.core.data.database.entity.AchievementMatchNoteEntity
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.AchievementController
import com.playfieldportal.feature.achievements.provider.retro.RaHashResolver
import com.playfieldportal.feature.achievements.provider.steam.SteamShortcut
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
    private val raHashResolver: RaHashResolver,
    private val repository: AchievementController,
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
        // RetroAchievements is hash-only: a game links solely by its ROM/disc content hash, never
        // by title. If the hash isn't a registered RA hash, the game stays untracked.
        val consoleId = RaConsole.idFor(game.platformId)
            ?: return Outcome.Unmatched("RetroAchievements has no achievements for ${platformLabel(game.platformId)}")

        val attempt = attemptHash(game)
        // Log the computed content hash so a known-good title can be checked against RA's "Supported
        // Game Files" list (a mismatch = wrong dump / romhack; a match that stays unlinked = not on RA).
        if (attempt is HashAttempt.Hashed) {
            Timber.d("auto-match hash [%s] %s = %s", game.platformId, game.displayTitle, attempt.hash)
        }
        val raGameId = (attempt as? HashAttempt.Hashed)?.let { raHashResolver.gameIdForHash(consoleId, it.hash) }
            ?: return Outcome.Unmatched(reasonFor(attempt))

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
        // Try each title variant — a user's shortened display override (e.g. "Resonance of Fate")
        // must not hide the full store name ("RESONANCE OF FATE.../4K/HD EDITION") that Steam lists.
        for (title in steamTitleCandidates(game)) {
            if (repository.resolveSteamLink(game.id, title) != null) return Outcome.Matched
        }
        return Outcome.Unmatched("Not found on Steam (no embedded appid, no SteamGridDB or title match)")
    }

    // Full title first (most complete), then any scraped title, then the display override.
    private fun steamTitleCandidates(game: Game): List<String> =
        listOfNotNull(game.title, game.scrapedTitle, game.displayTitle)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    // Computes the RA content hash, naming the failure mode when it can't. Cartridges hash from a
    // full byte read; disc images hash from a seeking reader (they're far too large to load whole).
    private suspend fun attemptHash(game: Game): HashAttempt {
        if (RaRomHasher.isSupported(game.platformId)) {
            // NDS carts run up to 256 MB; hash the header + code + icon via a seeking reader instead
            // of loading the whole ROM into one allocation (OOM). Zipped ROMs can't be seeked, so they
            // take the in-memory path (a zipped 256 MB cart is rare, and unzips to the same size).
            if (game.platformId == "nds" && !game.isZippedRom()) {
                val source = discOpener.openRawSource(game) ?: return HashAttempt.Unreadable
                val hash = source.use { RaRomHasher.hashNds(it) } ?: return HashAttempt.Unreadable
                return HashAttempt.Hashed(hash)
            }
            val bytes = romReader.read(game) ?: return HashAttempt.Unreadable
            val hash = RaRomHasher.hash(game.platformId, bytes) ?: return HashAttempt.Unreadable
            return HashAttempt.Hashed(hash)
        }
        // CHD container: its hunks decompress to the same logical sectors as the uncompressed disc,
        // so it feeds the normal CD hashers (PSX/PS2/Saturn/Sega CD). Intercept before the raw-image
        // openers, which can't parse a compressed container.
        if (game.isChdImage()) {
            val image = discOpener.openChd(game) ?: return HashAttempt.DiscUnreadable
            val hash = image.use { hashCdDiscImage(game.platformId, it) }
            return if (hash == null) HashAttempt.DiscUnidentified else HashAttempt.Hashed(hash)
        }
        if (RaNintendoDiscHasher.isSupported(game.platformId)) {
            val source = discOpener.openRawSource(game) ?: return HashAttempt.DiscUnreadable
            val hash = source.use { RaNintendoDiscHasher.hash(game.platformId, it) }
            return if (hash == null) HashAttempt.DiscUnidentified else HashAttempt.Hashed(hash)
        }
        if (RaSegaDiscHasher.isSupported(game.platformId)) {
            val image = discOpener.openRawCd(game) ?: return HashAttempt.DiscUnreadable
            val hash = image.use { RaSegaDiscHasher.hash(it) }
            return if (hash == null) HashAttempt.DiscUnidentified else HashAttempt.Hashed(hash)
        }
        if (RaDreamcastHasher.isSupported(game.platformId)) {
            val image = discOpener.openGdi(game) ?: return HashAttempt.DiscUnreadable
            val hash = image.use { RaDreamcastHasher.hash(it) }
            return if (hash == null) HashAttempt.DiscUnidentified else HashAttempt.Hashed(hash)
        }
        if (RaDiscHasher.isSupported(game.platformId)) {
            val image = discOpener.open(game) ?: return HashAttempt.DiscUnreadable
            val hash = image.use { RaDiscHasher.hash(game.platformId, it) }
            return if (hash == null) HashAttempt.DiscUnidentified else HashAttempt.Hashed(hash)
        }
        return HashAttempt.NoHasher
    }

    // A CHD's logical sectors feed whichever disc hasher fits the platform — including Dreamcast
    // GD-ROM, whose ISO track the CHD reader anchors via firstTrackSector. GameCube/Wii (raw DVD, not
    // CD frames) aren't covered by the CD reader, so they return null here.
    private fun hashCdDiscImage(platformId: String, image: DiscImage): String? = when {
        RaSegaDiscHasher.isSupported(platformId) -> RaSegaDiscHasher.hash(image)
        RaDreamcastHasher.isSupported(platformId) -> RaDreamcastHasher.hash(image)
        RaDiscHasher.isSupported(platformId) -> RaDiscHasher.hash(platformId, image)
        else -> null
    }

    private fun Game.isChdImage(): Boolean =
        romPath?.endsWith(".chd", ignoreCase = true) == true ||
            romUri?.endsWith(".chd", ignoreCase = true) == true

    private fun Game.isZippedRom(): Boolean =
        romPath?.endsWith(".zip", ignoreCase = true) == true ||
            romUri?.endsWith(".zip", ignoreCase = true) == true

    // Why the ROM/disc didn't hash to a registered RetroAchievements game (RA is hash-only).
    private fun reasonFor(attempt: HashAttempt): String = when (attempt) {
        is HashAttempt.Hashed -> "ROM hash isn't registered on RetroAchievements"
        HashAttempt.Unreadable -> "Couldn't read the ROM file"
        HashAttempt.DiscUnreadable -> "Unsupported disc image (e.g. NKit, CHD, or compressed) — can't hash"
        HashAttempt.DiscUnidentified -> "Couldn't find the disc's boot executable"
        HashAttempt.NoHasher -> "Disc hashing for this system isn't supported yet"
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
