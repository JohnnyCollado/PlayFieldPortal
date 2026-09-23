package com.playfieldportal.feature.achievements.sync

import com.playfieldportal.core.domain.achievement.AchievementProvider

/** Wall-clock source for sync bookkeeping (injectable in tests). */
fun interface AchievementClock {
    fun now(): Long
}

/** One provider game — the unit of tracking, fetching and deduplication. */
data class AchievementIdentity(
    val provider: AchievementProvider,
    val providerGameId: String,
)

/** Who asked for an update. Automatic runs obey the 24-hour window and stay quiet in the tray. */
enum class SyncTrigger { MANUAL, AUTOMATIC }

/** Why a full-detail fetch happens, which also decides whether provider metadata is renewed. */
enum class FetchReason { ROUTINE, NEW_MATCH, EXPLICIT, STALE_ON_OPEN }

/**
 * A present, confirmed identity as the planners see it: its sync bookmarks plus the cached set's
 * coin counts (null when no set is stored), which serve as a baseline before any snapshot exists.
 */
data class TrackedEntry(
    val identity: AchievementIdentity,
    val title: String,
    val lastCheckedAt: Long?,
    val lastDetailAt: Long?,
    val retryAt: Long?,
    val snapshot: String?,
    val storedEarned: Int?,
    val storedTotal: Int?,
) {
    /** Never fetched in full: a new match, first in line and fetched without a summary check. */
    val isNew: Boolean get() = lastDetailAt == null
}

/** The reconciler's answer: every present confirmed identity, and how many it just confirmed. */
data class PresenceResult(
    val entries: List<TrackedEntry>,
    val newlyConfirmed: Int,
)

/**
 * An actionable reason a provider could not be checked. [code] is persisted per provider so a
 * scheduled repeat of the same condition is not reported again.
 */
sealed interface UpdatePause {
    val code: String

    data object Offline : UpdatePause {
        override val code = "offline"
    }

    data class Credentials(val provider: AchievementProvider) : UpdatePause {
        override val code = "credentials_${provider.name}"
    }

    data object SteamPrivate : UpdatePause {
        override val code = "steam_private"
    }
}

/**
 * A provider strategy's decision for one check: which identities need a full-detail fetch, which
 * were summary-checked and found unchanged, the comparison snapshot to store for each, and any
 * provider-level pause or transient failure. Entries in neither set were not checked this time.
 */
data class ProviderCheckPlan(
    val toFetch: Set<AchievementIdentity> = emptySet(),
    val unchanged: Set<AchievementIdentity> = emptySet(),
    val snapshots: Map<AchievementIdentity, String> = emptyMap(),
    val pause: UpdatePause? = null,
    val failed: Boolean = false,
    val retryAfterMs: Long? = null,
)

/**
 * How one provider finds out, as cheaply as it can, which present games changed. Implementations
 * must make no request at all for an empty [entries] list.
 */
interface ProviderCheckStrategy {
    val provider: AchievementProvider
    suspend fun plan(entries: List<TrackedEntry>, trigger: SyncTrigger, now: Long): ProviderCheckPlan
}

/**
 * Outcome of one update run. [checked] = [updated] + [unchanged] + [failed]; [skipped] entries
 * were not due this time. [blocked] means the run never started (paused, or a clear in progress).
 */
data class AchievementUpdateSummary(
    val total: Int = 0,
    val checked: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val pauses: Set<UpdatePause> = emptySet(),
    val cancelled: Boolean = false,
    val blocked: Boolean = false,
)

/** What the return-from-game check did for one launched LOCAL_STEAM game. */
enum class LocalReturnOutcome { NOT_LOCAL_STEAM, UNKNOWN, UNCHANGED, UPDATED, FAILED }
