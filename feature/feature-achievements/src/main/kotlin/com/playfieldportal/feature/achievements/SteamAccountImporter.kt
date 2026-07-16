package com.playfieldportal.feature.achievements

import com.playfieldportal.core.data.database.dao.AccountAchievementDao
import com.playfieldportal.core.data.database.dao.AccountAchievementSetDao
import com.playfieldportal.core.data.database.dao.ProviderGameLinkDao
import com.playfieldportal.core.data.database.dao.SteamOwnedGamesDao
import com.playfieldportal.core.data.database.entity.SteamNoAchievementsEntity
import com.playfieldportal.core.data.database.entity.SteamOwnedGameEntity
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.provider.steam.SteamOwnedGamesResult
import com.playfieldportal.feature.achievements.provider.steam.SteamRemoteDataSource
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a [SteamAccountImporter.import] run, for the settings report. */
data class SteamImportResult(
    val total: Int,
    val imported: Int,
    val noCoins: Int,
    val noProgress: Int,
    val failed: Int,
    val missingCredentials: Boolean,
    val profileNotPublic: Boolean,
)

/**
 * Imports the Steam account's achievement history with the probe-filter-fetch pipeline
 * (docs/account-achievements-plan.md Phase 3): one GetOwnedGames call refreshes the owned cache,
 * then each candidate syncs through the shared account path. The schema-first fetch makes a
 * no-achievements game cost one call, remembered in the memo so it is never probed again.
 *
 * Candidates are owned games that are not memo'd and whose playtime differs from the bookmarked
 * synced playtime — which makes the first run walk everything, an interrupted run resume where
 * it stopped, and a routine re-run touch only games played since (the cheap incremental path).
 * Zero-earned sets are kept only when a library game links to them; account-only entries need
 * actual progress, matching the RA import.
 */
@Singleton
class SteamAccountImporter @Inject constructor(
    private val steamSource: SteamRemoteDataSource,
    private val ownedDao: SteamOwnedGamesDao,
    private val setDao: AccountAchievementSetDao,
    private val coinDao: AccountAchievementDao,
    private val linkDao: ProviderGameLinkDao,
    private val repository: AchievementRepository,
    private val localSteamOwnership: com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamOwnership,
) {
    suspend fun import(onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }): SteamImportResult {
        when (val owned = steamSource.ownedGames()) {
            is SteamOwnedGamesResult.Success -> {
                val now = System.currentTimeMillis()
                ownedDao.replaceOwned(owned.entries.map {
                    SteamOwnedGameEntity(
                        appid = it.appId,
                        name = it.name,
                        playtimeForeverMinutes = it.playtimeForeverMinutes,
                        fetchedAt = now,
                    )
                })
            }
            SteamOwnedGamesResult.MissingCredentials -> return emptyResult(missingCredentials = true)
            SteamOwnedGamesResult.ProfileNotPublic -> return emptyResult(profileNotPublic = true)
            is SteamOwnedGamesResult.Failed -> return emptyResult(failed = 1)
        }

        // The fresh owned list upgrades earlier UNKNOWN classifications without a re-scan
        // (docs/local-steam-achievements-plan.md Phase 2).
        runCatching { localSteamOwnership.refreshAll() }

        val memo = ownedDao.noAchievementAppids().toHashSet()
        val candidates = ownedDao.getAll()
            .filter { it.appid !in memo && it.syncedPlaytimeMinutes != it.playtimeForeverMinutes }

        var imported = 0
        var noCoins = 0
        var noProgress = 0
        var failed = 0
        var missingCredentials = false
        var profileNotPublic = false
        for ((index, game) in candidates.withIndex()) {
            onProgress(index, candidates.size)
            when (val result = repository.syncAccountEntry(STEAM, game.appid, game.name)) {
                is ProviderSyncResult.Success -> {
                    if (hasProgress(result)) imported++
                    else { discardUnlessLibraryLinked(game.appid); noProgress++ }
                    ownedDao.markSynced(game.appid)
                }
                ProviderSyncResult.NotFound -> {
                    ownedDao.rememberNoAchievements(
                        SteamNoAchievementsEntity(game.appid, System.currentTimeMillis()),
                    )
                    ownedDao.markSynced(game.appid)
                    noCoins++
                }
                ProviderSyncResult.MissingCredentials -> { missingCredentials = true; break }
                ProviderSyncResult.ProfileNotPublic -> { profileNotPublic = true; break }
                else -> failed++
            }
        }
        onProgress(candidates.size, candidates.size)
        return SteamImportResult(
            total = candidates.size,
            imported = imported,
            noCoins = noCoins,
            noProgress = noProgress,
            failed = failed,
            missingCredentials = missingCredentials,
            profileNotPublic = profileNotPublic,
        )
    }

    private fun hasProgress(result: ProviderSyncResult.Success): Boolean =
        result.coins.any { it.isEarned }

    // A zero-earned set stays only when a library game links to it (a linked game at 0% is
    // normal library behavior); an account-only entry without progress is removed again.
    private suspend fun discardUnlessLibraryLinked(appId: String) {
        if (linkDao.linkExistsFor(STEAM.name, appId)) return
        coinDao.deleteForSet(STEAM.name, appId)
        setDao.deleteSet(STEAM.name, appId)
    }

    private fun emptyResult(
        failed: Int = 0,
        missingCredentials: Boolean = false,
        profileNotPublic: Boolean = false,
    ) = SteamImportResult(
        total = 0, imported = 0, noCoins = 0, noProgress = 0, failed = failed,
        missingCredentials = missingCredentials, profileNotPublic = profileNotPublic,
    )

    private companion object {
        val STEAM = AchievementProvider.STEAM
    }
}
