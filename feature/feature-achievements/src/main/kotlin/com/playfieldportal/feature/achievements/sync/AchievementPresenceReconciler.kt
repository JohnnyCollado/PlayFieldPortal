package com.playfieldportal.feature.achievements.sync

import com.playfieldportal.core.data.database.dao.AchievementTrackingDao
import com.playfieldportal.core.data.database.dao.TrackedEntryRow
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamDiscovery
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reconciles the confirmed-local-match ledger with what is on this device right now.
 *
 * Present = a provider link whose game is not flagged missing (a LOCAL_STEAM link additionally
 * needs its emu folder to be discovered), plus every discovered Local Steam folder, linked or not.
 * A present identity is confirmed (new) or marked present again (a reinstall reconnects to its one
 * history row); a ledger identity no longer present is only marked not-present — its cached coins
 * and history stay. Nothing is ever inferred from a bare achievement set.
 *
 * [reconcile] with `confirmNew = false` (updates paused after Clear all tracked achievements)
 * refreshes presence of identities already in the ledger but admits no new ones.
 */
@Singleton
class AchievementPresenceReconciler @Inject constructor(
    private val dao: AchievementTrackingDao,
    private val localSteamDiscovery: LocalSteamDiscovery,
    private val clock: AchievementClock,
) {
    suspend fun reconcile(confirmNew: Boolean): PresenceResult {
        val now = clock.now()
        // A failed scan is "unknown", not "every folder vanished": LOCAL_STEAM presence then stays
        // as it was instead of flipping every local game to history.
        val folders = try {
            localSteamDiscovery.scan()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Local Steam discovery failed during presence reconcile")
            null
        }
        val folderAppIds = folders?.map { it.appId }?.toHashSet()

        // Insertion-ordered so the first local copy names a shared identity.
        val present = LinkedHashMap<AchievementIdentity, String>()
        for (link in dao.linksWithPresence()) {
            if (link.isMissing) continue
            val provider = AchievementProvider.fromName(link.provider) ?: continue
            if (provider == AchievementProvider.LOCAL_STEAM &&
                (folderAppIds == null || link.providerGameId !in folderAppIds)
            ) continue
            present.putIfAbsent(AchievementIdentity(provider, link.providerGameId), link.title)
        }
        folders?.forEach { folder ->
            present.putIfAbsent(AchievementIdentity(AchievementProvider.LOCAL_STEAM, folder.appId), folder.folderName)
        }

        val ledger = dao.getAllIdentities().associateBy {
            it.provider to it.providerGameId
        }
        var newlyConfirmed = 0
        for ((identity, title) in present) {
            val key = identity.provider.name to identity.providerGameId
            if (key in ledger) {
                dao.markPresent(identity.provider.name, identity.providerGameId, now)
            } else if (confirmNew) {
                dao.confirm(identity.provider.name, identity.providerGameId, title, now)
                newlyConfirmed++
            }
        }
        val presentKeys = present.keys.map { it.provider.name to it.providerGameId }.toHashSet()
        for (row in ledger.values) {
            if (!row.isPresent) continue
            if ((row.provider to row.providerGameId) in presentKeys) continue
            if (row.provider == AchievementProvider.LOCAL_STEAM.name && folders == null) continue
            dao.markAbsent(row.provider, row.providerGameId)
        }

        val entries = dao.presentEntries().mapNotNull { it.toEntry() }
        return PresenceResult(entries, newlyConfirmed)
    }
}

internal fun TrackedEntryRow.toEntry(): TrackedEntry? {
    val p = AchievementProvider.fromName(provider) ?: return null
    return TrackedEntry(
        identity = AchievementIdentity(p, providerGameId),
        title = title,
        lastCheckedAt = lastCheckedAt,
        lastDetailAt = lastDetailAt,
        retryAt = retryAt,
        snapshot = summarySnapshot,
        storedEarned = storedEarned,
        storedTotal = storedTotal,
    )
}
