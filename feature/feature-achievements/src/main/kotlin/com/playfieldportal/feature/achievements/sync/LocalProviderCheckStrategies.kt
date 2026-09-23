package com.playfieldportal.feature.achievements.sync

import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.feature.achievements.provider.localsteam.LocalEarnedRead
import com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LOCAL_STEAM: the local progress file IS the cheap change signal. Each present folder's earned
 * state is fingerprinted and compared with the stored one; only a changed (or new) game is rebuilt,
 * and with cached metadata that rebuild is local too. An unreadable file is left unchecked rather
 * than treated as "nothing earned". Steam account playtime is never involved.
 */
@Singleton
class LocalSteamCheckStrategy @Inject constructor(
    private val source: LocalSteamSource,
) : ProviderCheckStrategy {

    override val provider = AchievementProvider.LOCAL_STEAM

    override suspend fun plan(entries: List<TrackedEntry>, trigger: SyncTrigger, now: Long): ProviderCheckPlan {
        if (entries.isEmpty()) return ProviderCheckPlan()
        val toFetch = mutableSetOf<AchievementIdentity>()
        val unchanged = mutableSetOf<AchievementIdentity>()
        val snapshots = mutableMapOf<AchievementIdentity, String>()
        for (entry in entries) {
            val read = source.readEarned(entry.identity.providerGameId) as? LocalEarnedRead.Read
            read?.let { snapshots[entry.identity] = it.fingerprint }
            when {
                entry.isNew -> toFetch += entry.identity
                read == null -> Unit
                read.fingerprint == entry.snapshot -> unchanged += entry.identity
                else -> toFetch += entry.identity
            }
        }
        return ProviderCheckPlan(toFetch = toFetch, unchanged = unchanged, snapshots = snapshots)
    }
}

/**
 * VITA_TROPHY (Vita3K's local trophy files) has no cheap change signal, so present matched games
 * are re-read on a conservative interval — [INTERVAL_MS] on scheduled runs, every time on a manual
 * update. The reads are local; the writer reports whether anything actually changed.
 */
@Singleton
class VitaTrophyCheckStrategy @Inject constructor() : ProviderCheckStrategy {

    override val provider = AchievementProvider.VITA_TROPHY

    override suspend fun plan(entries: List<TrackedEntry>, trigger: SyncTrigger, now: Long): ProviderCheckPlan {
        val due = entries.filter { entry ->
            trigger == SyncTrigger.MANUAL || entry.isNew ||
                entry.lastCheckedAt == null || now - entry.lastCheckedAt >= INTERVAL_MS
        }
        return ProviderCheckPlan(toFetch = due.mapTo(mutableSetOf()) { it.identity })
    }

    companion object {
        const val INTERVAL_MS = 3L * 24 * 60 * 60 * 1_000
    }
}
