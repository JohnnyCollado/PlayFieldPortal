package com.playfieldportal.feature.achievements.sync

import com.playfieldportal.core.data.database.dao.SteamOwnedGamesDao
import com.playfieldportal.core.data.database.entity.SteamOwnedGameEntity
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamOwnership
import com.playfieldportal.feature.achievements.provider.steam.SteamOwnedGamesResult
import com.playfieldportal.feature.achievements.provider.steam.SteamRemoteDataSource
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Steam, playtime-first (plan section 4, Task 7). One documented GetOwnedGames call per check;
 * only present matched app ids are compared with the Steam-reported `playtime_forever` saved at
 * their last detail fetch. A new match or a changed playtime gets a player-achievement fetch;
 * nothing ever probes the rest of the owned library.
 *
 * The owned list is still written to `steam_owned_games` (bookmarks preserved) and Local Steam
 * ownership is re-derived from it, which is the classification the retired account importer used
 * to keep fresh. A private or failed answer is never read as an empty library: the cache stays,
 * and the check pauses rather than sweeping installed games one by one.
 *
 * GetOwnedGames' `appids_filter` is an array parameter the current Retrofit definition can't
 * express, so the unfiltered single response is used — still one request.
 */
@Singleton
class SteamCheckStrategy @Inject constructor(
    private val steam: SteamRemoteDataSource,
    private val ownedDao: SteamOwnedGamesDao,
    private val ownership: LocalSteamOwnership,
) : ProviderCheckStrategy {

    override val provider = AchievementProvider.STEAM

    override suspend fun plan(entries: List<TrackedEntry>, trigger: SyncTrigger, now: Long): ProviderCheckPlan {
        if (entries.isEmpty()) return ProviderCheckPlan()
        val playtimeByApp = when (val owned = steam.ownedGames()) {
            is SteamOwnedGamesResult.Success -> {
                ownedDao.replaceOwned(
                    owned.entries.map {
                        SteamOwnedGameEntity(
                            appid = it.appId,
                            name = it.name,
                            playtimeForeverMinutes = it.playtimeForeverMinutes,
                            fetchedAt = now,
                        )
                    },
                )
                try {
                    ownership.refreshAll()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Local Steam ownership refresh failed")
                }
                owned.entries.associate { it.appId to it.playtimeForeverMinutes }
            }
            SteamOwnedGamesResult.MissingCredentials ->
                return ProviderCheckPlan(pause = UpdatePause.Credentials(provider))
            SteamOwnedGamesResult.ProfileNotPublic ->
                return ProviderCheckPlan(pause = UpdatePause.SteamPrivate)
            is SteamOwnedGamesResult.Failed -> return ProviderCheckPlan(
                failed = true,
                pause = if (owned.reason == "network error") UpdatePause.Offline else null,
            )
        }

        val toFetch = mutableSetOf<AchievementIdentity>()
        val unchanged = mutableSetOf<AchievementIdentity>()
        val snapshots = mutableMapOf<AchievementIdentity, String>()
        for (entry in entries) {
            val playtime = playtimeByApp[entry.identity.providerGameId]
            playtime?.let { snapshots[entry.identity] = snapshotOf(it) }
            when {
                entry.isNew -> toFetch += entry.identity
                // Not in the owned list (family share, delisted, hidden): nothing to compare.
                playtime == null -> Unit
                entry.snapshot != null ->
                    if (entry.snapshot == snapshotOf(playtime)) unchanged += entry.identity else toFetch += entry.identity
                // No snapshot yet (a migrated library): the old import's bookmark is the baseline.
                ownedDao.syncedPlaytime(entry.identity.providerGameId) == playtime -> unchanged += entry.identity
                else -> toFetch += entry.identity
            }
        }
        return ProviderCheckPlan(toFetch = toFetch, unchanged = unchanged, snapshots = snapshots)
    }

    companion object {
        /** The comparison snapshot for a Steam-reported playtime, in minutes. */
        fun snapshotOf(playtimeMinutes: Long): String = "steam:v1:$playtimeMinutes"
    }
}
