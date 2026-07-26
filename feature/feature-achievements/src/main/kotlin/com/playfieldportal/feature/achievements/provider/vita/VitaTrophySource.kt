package com.playfieldportal.feature.achievements.provider.vita

import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.api.SyncedCoin
import com.playfieldportal.feature.achievements.provider.RemoteAchievementSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The VITA_TROPHY provider: a game's trophies read entirely from Vita3K's local files — definitions
 * and icons from `TROP.SFM`/`TROP*.PNG`, earned state and timestamps from `TROPUSR.DAT` (see
 * [VitaTrophyDiscovery]). Fully offline: unlike STEAM/LOCAL_STEAM there is no web schema and no key,
 * so a fetch never reports MissingCredentials.
 *
 * The provider game id is the trophy set's NPCOMMID (e.g. `NPWR02979_00`). Vita's Platinum is a real
 * trophy, so it maps to [ShibaTier.PLATINUM] (the crown), unlike Steam where it's minted locally.
 * Rarity has no source here, so every coin uses [SyncedCoin.RARITY_UNAVAILABLE].
 */
@Singleton
class VitaTrophySource @Inject constructor(
    private val discovery: VitaTrophyDiscovery,
) : RemoteAchievementSource {

    override suspend fun fetch(providerGameId: String): ProviderSyncResult {
        val set = discovery.loadSet(providerGameId)
            ?: return ProviderSyncResult.Failed("no Vita trophy data for $providerGameId (grant the ux0 folder)")
        if (set.trophies.isEmpty()) return ProviderSyncResult.NotFound

        val coins = set.trophies.map { t ->
            SyncedCoin(
                providerAchievementId = t.id.toString(),
                title = t.name,
                description = t.detail,
                tier = tierOf(t.grade),
                globalRarity = SyncedCoin.RARITY_UNAVAILABLE,
                iconUrl = t.iconUri,
                isHidden = t.hidden,
                isEarned = t.unlocked,
                // Vita has no hardcore/softcore split — mastery mirrors the unlock, like Steam.
                earnedHardcore = t.unlocked,
                earnedAt = t.unlockedAtEpochSec?.times(1_000),   // stored as millis
            )
        }
        return ProviderSyncResult.Success(providerGameId, coins)
    }

    private fun tierOf(grade: TropUsrParser.Grade): ShibaTier = when (grade) {
        TropUsrParser.Grade.PLATINUM -> ShibaTier.PLATINUM
        TropUsrParser.Grade.GOLD -> ShibaTier.GOLD
        TropUsrParser.Grade.SILVER -> ShibaTier.SILVER
        TropUsrParser.Grade.BRONZE -> ShibaTier.BRONZE
        TropUsrParser.Grade.UNKNOWN -> ShibaTier.BRONZE
    }
}
