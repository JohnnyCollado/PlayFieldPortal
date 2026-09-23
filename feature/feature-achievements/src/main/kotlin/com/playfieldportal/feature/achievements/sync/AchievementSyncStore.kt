package com.playfieldportal.feature.achievements.sync

import com.playfieldportal.core.data.database.dao.AchievementTrackingDao
import com.playfieldportal.core.data.database.dao.LinkPresenceRow
import com.playfieldportal.core.data.database.entity.AchievementProviderSyncStateEntity
import com.playfieldportal.core.data.database.entity.AchievementTrackedIdentityEntity
import com.playfieldportal.core.domain.achievement.AchievementProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The coordinator's and planners' view of the sync bookkeeping, in domain terms: per-identity
 * check/detail/failure bookmarks, per-provider schedule state and the RA cohort rotation. A thin
 * seam over [AchievementTrackingDao] so the sync logic is testable without a database.
 */
@Singleton
class AchievementSyncStore @Inject constructor(
    private val dao: AchievementTrackingDao,
) {
    suspend fun identity(identity: AchievementIdentity): AchievementTrackedIdentityEntity? =
        dao.getIdentity(identity.provider.name, identity.providerGameId)

    suspend fun confirm(identity: AchievementIdentity, title: String, now: Long) =
        dao.confirm(identity.provider.name, identity.providerGameId, title, now)

    /** [gameId]'s provider links with the game's presence, first provider alphabetically first. */
    suspend fun linksForGame(gameId: Long): List<LinkPresenceRow> = dao.linkPresenceForGame(gameId)

    /** The present LOCAL_STEAM identity [gameId] links to, or null. */
    suspend fun localSteamIdentityForGame(gameId: Long): AchievementTrackedIdentityEntity? =
        dao.presentLocalSteamIdentityForGame(gameId)

    suspend fun recordUnchanged(identity: AchievementIdentity, now: Long, snapshot: String?) =
        dao.recordCheck(identity.provider.name, identity.providerGameId, now, snapshot)

    /** A check that found no achievement set: checked, nothing to store. */
    suspend fun recordNoSet(identity: AchievementIdentity, now: Long) =
        dao.recordCheck(identity.provider.name, identity.providerGameId, now, null)

    suspend fun recordDetail(identity: AchievementIdentity, now: Long, snapshot: String?) =
        dao.recordDetail(identity.provider.name, identity.providerGameId, now, snapshot)

    suspend fun recordFailure(identity: AchievementIdentity, now: Long, retryAt: Long) {
        // `now` is implied by retryAt; kept in the signature so callers state when it failed.
        dao.recordFailure(identity.provider.name, identity.providerGameId, retryAt)
    }

    suspend fun providerState(provider: AchievementProvider): AchievementProviderSyncStateEntity? =
        dao.getProviderState(provider.name)

    /** A provider check completed (possibly with a non-transient pause such as missing credentials). */
    suspend fun recordProviderCheck(provider: AchievementProvider, now: Long, pause: UpdatePause?) {
        val state = providerState(provider) ?: AchievementProviderSyncStateEntity(provider = provider.name)
        dao.upsertProviderState(
            state.copy(lastCheckedAt = now, retryAt = null, failureCount = 0, pausedReason = pause?.code),
        )
    }

    /** A transient provider-level failure: keep the last successful check time, back off. */
    suspend fun recordProviderFailure(provider: AchievementProvider, now: Long, retryAt: Long, pause: UpdatePause?) {
        val state = providerState(provider) ?: AchievementProviderSyncStateEntity(provider = provider.name)
        dao.upsertProviderState(
            state.copy(retryAt = retryAt, failureCount = state.failureCount + 1, pausedReason = pause?.code),
        )
    }

    /** RetroAchievements finished [day]'s summary cohort; [next] is tomorrow's. */
    suspend fun recordCohort(provider: AchievementProvider, next: Int, day: Long) {
        val state = providerState(provider) ?: AchievementProviderSyncStateEntity(provider = provider.name)
        dao.upsertProviderState(state.copy(cohortNext = next, cohortLastDay = day))
    }

    /** Clear all tracked achievements — one transaction. */
    suspend fun clearAll() = dao.clearAllAchievementRecords()
}
