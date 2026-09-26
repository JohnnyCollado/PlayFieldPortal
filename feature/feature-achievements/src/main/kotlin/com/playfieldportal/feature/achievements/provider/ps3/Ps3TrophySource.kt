package com.playfieldportal.feature.achievements.provider.ps3

import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.api.SyncedCoin
import com.playfieldportal.feature.achievements.provider.RemoteAchievementSource
import com.playfieldportal.feature.achievements.provider.vita.TropUsrParser
import com.playfieldportal.core.data.database.dao.ProviderGameLinkDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The PS3_TROPHY provider: a PS3 game's trophies read entirely from ARMSX3's local files —
 * definitions and icons from `TROPCONF.SFM`/`TROP*.PNG`, earned state and timestamps from the
 * big-endian `TROPUSR.DAT` (see [Ps3TrophyDiscovery]). Fully offline: like VITA_TROPHY and unlike
 * STEAM/LOCAL_STEAM there is no web schema and no key, so a fetch never reports MissingCredentials.
 *
 * The provider game id is the **base** trophy set's NPCOMMID — the first id the game's own
 * `TROPDIR` lists. A game with DLC trophy subsets shows one merged coin list, so each fetch
 * re-reads `TROPDIR` through [Ps3TropDirReader] and merges every set it declares: coin ids are
 * namespaced `"<npCommId>:<trophyId>"` so two subsets numbering from 0 never collide, and newly
 * installed DLC simply makes the list longer on the next sync with no invalidation logic.
 *
 * PS3's Platinum is a real trophy, so it maps to [ShibaTier.PLATINUM] (the crown), unlike Steam
 * where it's minted locally. Rarity has no source here, so every coin uses
 * [SyncedCoin.RARITY_UNAVAILABLE].
 */
@Singleton
class Ps3TrophySource @Inject constructor(
    private val discovery: Ps3TrophyDiscovery,
    private val tropDirReader: Ps3TropDirReader,
    private val linkDao: ProviderGameLinkDao,
    private val gameRepository: GameRepository,
) : RemoteAchievementSource {

    override suspend fun fetch(providerGameId: String): ProviderSyncResult {
        // TROPDIR is the subset grouping authority, so it is re-read every fetch (a handful of
        // 2,048-byte sectors) rather than persisted — that is what makes new DLC appear by itself.
        val declared = subsetsOf(providerGameId)
        val sets = discovery.loadMerged(declared)
        if (sets.isEmpty()) {
            return when (discovery.checkSet(providerGameId)) {
                Ps3TrophyDiscovery.Failure.NotConfigured ->
                    ProviderSyncResult.Failed("no PS3 data folder set — pick your ARMSX3 PS3 folder in the library")
                Ps3TrophyDiscovery.Failure.NoTrophyFolder ->
                    ProviderSyncResult.Failed("the granted PS3 folder has no dev_hdd0 trophy data")
                // Declared by the disc but never registered: the game creates the folder the first
                // time it runs, so this is "play it once", not "no achievements".
                Ps3TrophyDiscovery.Failure.SetNotPresent ->
                    ProviderSyncResult.Failed("play $providerGameId once in ARMSX3 to start tracking its trophies")
                null -> ProviderSyncResult.Failed("PS3 trophy set $providerGameId couldn't be read")
            }
        }

        val coins = Ps3TrophySets.merge(sets).map { t ->
            SyncedCoin(
                providerAchievementId = t.coinId,
                title = t.name,
                description = t.detail,
                tier = tierOf(t.grade),
                globalRarity = SyncedCoin.RARITY_UNAVAILABLE,
                iconUrl = t.iconUri,
                isHidden = t.hidden,
                isEarned = t.unlocked,
                // PS3 has no hardcore/softcore split — mastery mirrors the unlock, like Vita.
                earnedHardcore = t.unlocked,
                earnedAt = t.unlockedAtEpochMillis,
            )
        }
        if (coins.isEmpty()) return ProviderSyncResult.NotFound
        return ProviderSyncResult.Success(providerGameId, coins)
    }

    /**
     * The sets this game declares, base first. Falls back to the linked id alone when the disc
     * can't be re-read (an image that has since been removed, or an encrypted dump linked by title)
     * — a game already linked keeps tracking its base set rather than going dark.
     */
    private suspend fun subsetsOf(baseNpCommId: String): List<String> {
        val gameId = linkDao.getByProvider(AchievementProvider.PS3_TROPHY.name)
            .firstOrNull { it.providerGameId.equals(baseNpCommId, ignoreCase = true) }
            ?.gameId ?: return listOf(baseNpCommId)
        val game = gameRepository.getById(gameId) ?: return listOf(baseNpCommId)
        val declared = (tropDirReader.npCommIdsFor(game) as? Ps3TropDirReader.Result.Found)?.npCommIds
            ?: return listOf(baseNpCommId)
        // The link's identity is the base set: keep it first even if the disc order ever drifts.
        return listOf(baseNpCommId) + declared.filterNot { it.equals(baseNpCommId, ignoreCase = true) }
    }

    private fun tierOf(grade: TropUsrParser.Grade): ShibaTier = when (grade) {
        TropUsrParser.Grade.PLATINUM -> ShibaTier.PLATINUM
        TropUsrParser.Grade.GOLD -> ShibaTier.GOLD
        TropUsrParser.Grade.SILVER -> ShibaTier.SILVER
        TropUsrParser.Grade.BRONZE -> ShibaTier.BRONZE
        TropUsrParser.Grade.UNKNOWN -> ShibaTier.BRONZE
    }
}
