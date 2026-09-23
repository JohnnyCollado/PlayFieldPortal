package com.playfieldportal.feature.achievements.sync

import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.feature.achievements.provider.retro.RaProgressSnapshot
import com.playfieldportal.feature.achievements.provider.retro.RaRemoteDataSource
import com.playfieldportal.feature.achievements.provider.retro.RaSummaryResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RetroAchievements, summary-first (plan section 4, Task 6).
 *
 * Each check makes one GetUserRecentlyPlayedGames request (up to [RECENT_COUNT]) and compares the
 * present matched games it lists. Every present id also belongs to one of [COHORTS] stable daily
 * cohorts; the first check of a new day summary-checks the next cohort with GetUserProgress in
 * URL-safe batches. A missed day resumes with the next cohort only — never a burst — so every
 * present id is summary-checked once per seven active days. Full detail is requested (by the
 * coordinator) only for a new match or a changed summary; summaries compare softcore and hardcore
 * counts and scores and the achievable totals.
 *
 * For 1,000 installed RA ids: about 20 targeted summary requests per seven active days, plus one
 * recent-games request per active day.
 */
@Singleton
class RaCheckStrategy @Inject constructor(
    private val remote: RaRemoteDataSource,
    private val store: AchievementSyncStore,
) : ProviderCheckStrategy {

    override val provider = AchievementProvider.RETRO_ACHIEVEMENTS

    override suspend fun plan(entries: List<TrackedEntry>, trigger: SyncTrigger, now: Long): ProviderCheckPlan {
        if (entries.isEmpty()) return ProviderCheckPlan()
        val byId = entries.associateBy { it.identity.providerGameId }
        val toFetch = entries.filter { it.isNew }.mapTo(mutableSetOf()) { it.identity }
        val unchanged = mutableSetOf<AchievementIdentity>()
        val snapshots = mutableMapOf<AchievementIdentity, String>()

        fun compare(id: String, snapshot: RaProgressSnapshot) {
            val entry = byId[id] ?: return            // not a present matched game: ignore
            if (entry.isNew) return                    // fetched in full regardless
            val encoded = snapshot.encode()
            snapshots[entry.identity] = encoded
            val same = entry.snapshot?.let { it == encoded }
                // No snapshot yet (a migrated library): the cached set is the baseline.
                ?: (entry.storedEarned?.toLong() == snapshot.numAchieved &&
                    entry.storedTotal?.toLong() == snapshot.numPossible)
            if (same) unchanged += entry.identity else toFetch += entry.identity
        }

        when (val recent = remote.recentlyPlayed(RECENT_COUNT)) {
            is RaSummaryResult.Success -> recent.byGameId.forEach { (id, s) -> compare(id, s) }
            RaSummaryResult.MissingCredentials ->
                return ProviderCheckPlan(pause = UpdatePause.Credentials(provider))
            is RaSummaryResult.Failed -> return failurePlan(recent)
            RaSummaryResult.UriTooLong -> return ProviderCheckPlan(failed = true)
        }
        val coveredByRecent = snapshots.keys.map { it.providerGameId }.toHashSet()

        val today = now / DAY_MS
        val state = store.providerState(provider)
        if (state?.cohortLastDay != today) {
            val cohort = (state?.cohortNext ?: 0).mod(COHORTS)
            val ids = entries
                .filter { !it.isNew && it.identity.providerGameId !in coveredByRecent }
                .map { it.identity.providerGameId }
                .filter { it.toLongOrNull() != null && cohortOf(it) == cohort }
            var complete = true
            for (batch in batches(ids)) {
                when (val result = summaries(batch)) {
                    is RaSummaryResult.Success -> result.byGameId.forEach { (id, s) -> compare(id, s) }
                    RaSummaryResult.MissingCredentials ->
                        return ProviderCheckPlan(pause = UpdatePause.Credentials(provider))
                    else -> complete = false          // these ids stay unchecked until next time
                }
            }
            if (complete) store.recordCohort(provider, (cohort + 1).mod(COHORTS), today)
        }

        return ProviderCheckPlan(toFetch = toFetch, unchanged = unchanged, snapshots = snapshots)
    }

    // One batch, halved and retried while the server says the URI is too long.
    private suspend fun summaries(batch: List<String>): RaSummaryResult {
        return when (val result = remote.userProgress(batch)) {
            RaSummaryResult.UriTooLong -> {
                if (batch.size <= 1) return RaSummaryResult.Failed("request too long")
                val merged = mutableMapOf<String, RaProgressSnapshot>()
                for (half in batch.chunked((batch.size + 1) / 2)) {
                    when (val part = summaries(half)) {
                        is RaSummaryResult.Success -> merged += part.byGameId
                        else -> return part
                    }
                }
                RaSummaryResult.Success(merged)
            }
            else -> result
        }
    }

    // Batches of at most MAX_BATCH_IDS ids whose comma-joined length stays URL-safe.
    private fun batches(ids: List<String>): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var chars = 0
        for (id in ids) {
            if (current.size == MAX_BATCH_IDS || chars + id.length + 1 > MAX_ID_CHARS) {
                out += current
                current = mutableListOf()
                chars = 0
            }
            current += id
            chars += id.length + 1
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    private fun failurePlan(failure: RaSummaryResult.Failed) = ProviderCheckPlan(
        failed = true,
        pause = if (failure.offline) UpdatePause.Offline else null,
        retryAfterMs = failure.retryAfterMs,
    )

    companion object {
        /** Documented GetUserRecentlyPlayedGames maximum. */
        const val RECENT_COUNT = 50
        const val COHORTS = 7
        const val MAX_BATCH_IDS = 50
        /** Comma-joined id characters per request; well inside common 2,000-character URL limits. */
        const val MAX_ID_CHARS = 1_200
        private const val DAY_MS = 24L * 60 * 60 * 1_000

        /** Stable cohort of an RA game id. */
        fun cohortOf(gameId: String): Int = (gameId.toLongOrNull() ?: gameId.hashCode().toLong()).mod(COHORTS)
    }
}
