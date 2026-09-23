package com.playfieldportal.feature.achievements.sync

import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlin.random.Random

/** The app-lifetime scope achievement work runs in, so it outlives the screen that started it. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AchievementSyncScope

/**
 * The one owner of every achievement refresh (plan Task 5): Settings' and Player Status' "Update
 * installed achievements", the XMB hub, the scheduled check, a game page's Refresh and its
 * stale-on-open check, and the Local Steam launch-return check all come through here.
 *
 * - **Scope.** A run reconciles presence first and then considers only present, confirmed
 *   identities; removed games (history) and provider-search previews never enter it, and a
 *   provider with no present identity makes no request.
 * - **Cost.** Each provider's [ProviderCheckStrategy] picks, as cheaply as it can, the identities
 *   that need a full fetch; everything else is recorded as checked-unchanged or left for a later
 *   run. Scheduled runs honour a per-provider [CHECK_WINDOW_MS] window and failure backoff.
 * - **Single flight.** Overlapping update requests share one run, and a fetch for an identity is
 *   performed once no matter how many triggers want it (an explicit Refresh joins a batch's
 *   in-flight fetch of the same game).
 * - **Safety.** A failed, cancelled or credential-less fetch never touches cached coins. Clear all
 *   tracked achievements cancels and joins every running job, bumps a generation so nothing that
 *   was already in flight can write back, clears in one transaction, and pauses scheduled updates
 *   until the user resyncs.
 */
@Singleton
class AchievementSyncCoordinator @Inject constructor(
    private val reconciler: AchievementPresenceReconciler,
    private val strategies: Set<@JvmSuppressWildcards ProviderCheckStrategy>,
    private val fetcher: AchievementDetailFetcher,
    private val writer: AchievementSetWriter,
    private val store: AchievementSyncStore,
    private val credentials: AchievementCredentialsProvider,
    private val reporter: AchievementUpdateReporter,
    private val localReturn: LocalSteamReturnChecker,
    private val clock: AchievementClock,
    @AchievementSyncScope private val scope: CoroutineScope,
) {
    private data class FetchOutcome(val result: ProviderSyncResult, val changed: Boolean)

    // Guards batch/inflight bookkeeping (never held across network work).
    private val stateLock = Mutex()
    // Serializes every write and the clear transaction; checked against [generation].
    private val writeLock = Mutex()

    private var batch: Deferred<AchievementUpdateSummary>? = null
    // Guarded by synchronized(inflight): touched from invokeOnCompletion, which can't suspend.
    private val inflight = mutableMapOf<AchievementIdentity, Deferred<FetchOutcome>>()
    private val progressListeners = mutableListOf<(Int, Int) -> Unit>()
    private val lastReturnCheck = mutableMapOf<Long, Long>()

    @Volatile private var clearing = false
    @Volatile private var generation = 0L

    // ── Update installed achievements ───────────────────────────────────────────

    /**
     * Updates every present, confirmed game. A MANUAL run resumes scheduled updates paused by a
     * clear; an AUTOMATIC run does nothing while they are paused. A request that arrives while a
     * run is active joins it and returns the same summary.
     */
    suspend fun updateInstalled(
        trigger: SyncTrigger,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): AchievementUpdateSummary {
        if (clearing) return BLOCKED
        if (trigger == SyncTrigger.AUTOMATIC && credentials.autoUpdatesPaused()) return BLOCKED
        val run = stateLock.withLock {
            if (clearing) return BLOCKED
            progressListeners += onProgress
            batch?.takeIf { it.isActive } ?: scope.async { runBatch(trigger) }.also { batch = it }
        }
        return try {
            run.await()
        } finally {
            stateLock.withLock { progressListeners -= onProgress }
        }
    }

    /** Stops a running update; progress already saved is kept. */
    fun cancelUpdate() {
        batch?.cancel()
    }

    private suspend fun runBatch(trigger: SyncTrigger): AchievementUpdateSummary {
        if (trigger == SyncTrigger.MANUAL) credentials.setAutoUpdatesPaused(false)
        reporter.started(trigger)
        val now = clock.now()
        val counts = Counts(trigger)
        try {
            val presence = reconciler.reconcile(confirmNew = true)
            val byProvider = presence.entries.groupBy { it.identity.provider }
            counts.total = presence.entries.size
            publishProgress(trigger, 0, counts.total)

            for (strategy in strategies.sortedBy { it.provider.ordinal }) {
                checkProvider(strategy, byProvider[strategy.provider].orEmpty(), trigger, now, counts)
            }
            val summary = counts.summary()
            reporter.finished(trigger, summary, counts.newPauses)
            return summary
        } catch (e: CancellationException) {
            // A clear reports for itself; a user's stop gets the "saved progress kept" result.
            if (!clearing) reporter.finished(trigger, counts.summary().copy(cancelled = true), emptySet())
            throw e
        } catch (e: Exception) {
            // Never let one unexpected error escape the app scope; what was saved stays saved.
            Timber.e(e, "Achievement update failed")
            val summary = counts.summary().let { it.copy(failed = maxOf(1, it.failed + (counts.total - counts.done))) }
            reporter.finished(trigger, summary, counts.newPauses)
            return summary
        }
    }

    private suspend fun checkProvider(
        strategy: ProviderCheckStrategy,
        entries: List<TrackedEntry>,
        trigger: SyncTrigger,
        now: Long,
        counts: Counts,
    ) {
        val provider = strategy.provider
        val state = store.providerState(provider)
        if (entries.isEmpty()) {
            // Nothing present for this provider: no request, but the provider is "checked".
            store.recordProviderCheck(provider, now, null)
            return
        }
        if (trigger == SyncTrigger.AUTOMATIC) {
            val recent = state?.lastCheckedAt?.let { now - it < CHECK_WINDOW_MS } == true
            val backingOff = state?.retryAt?.let { it > now } == true
            if (recent || backingOff) {
                counts.skipped += entries.size
                counts.advance(entries.size)
                return
            }
        }
        val eligible = if (trigger == SyncTrigger.AUTOMATIC) {
            entries.filter { it.retryAt == null || it.retryAt <= now }
        } else {
            entries
        }
        counts.skipped += entries.size - eligible.size
        counts.advance(entries.size - eligible.size)

        val plan = strategy.plan(eligible, trigger, now)
        plan.pause?.let { counts.pause(it, repeat = trigger == SyncTrigger.AUTOMATIC && state?.pausedReason == it.code) }
        if (plan.failed) {
            val retryAt = now + AchievementBackoff.delayMs((state?.failureCount ?: 0) + 1, plan.retryAfterMs)
            store.recordProviderFailure(provider, now, retryAt, plan.pause)
        } else {
            store.recordProviderCheck(provider, now, plan.pause)
        }

        val byIdentity = eligible.associateBy { it.identity }
        for (identity in plan.unchanged) {
            if (identity !in byIdentity) continue
            store.recordUnchanged(identity, now, plan.snapshots[identity])
            counts.unchanged++
            counts.advance(1)
        }
        // New matches first, then changed summaries.
        val toFetch = plan.toFetch.mapNotNull { byIdentity[it] }.sortedByDescending { it.isNew }
        for (entry in toFetch) {
            val reason = if (entry.isNew) FetchReason.NEW_MATCH else FetchReason.ROUTINE
            val outcome = fetchShared(entry.identity, entry.title, reason, plan.snapshots[entry.identity])
            when (val result = outcome.result) {
                is ProviderSyncResult.Success -> if (outcome.changed) counts.updated++ else counts.unchanged++
                ProviderSyncResult.NotFound -> counts.unchanged++
                ProviderSyncResult.MissingCredentials -> {
                    counts.pause(UpdatePause.Credentials(provider), repeat = false)
                    counts.skipped++
                }
                ProviderSyncResult.ProfileNotPublic -> {
                    counts.pause(UpdatePause.SteamPrivate, repeat = false)
                    counts.skipped++
                }
                ProviderSyncResult.NotLinked -> counts.skipped++
                is ProviderSyncResult.Failed -> {
                    Timber.i("Achievement update %s: %s", entry.identity, result.reason)
                    counts.failed++
                }
            }
            counts.advance(1)
        }
        val decided = plan.unchanged.count { it in byIdentity } + toFetch.size
        counts.skipped += eligible.size - decided
        counts.advance(eligible.size - decided)
    }

    // ── Single-game refreshes ───────────────────────────────────────────────────

    /** Explicit "Refresh this game" for a present, confirmed identity. Removed games are refused. */
    suspend fun refreshIdentity(identity: AchievementIdentity, title: String): ProviderSyncResult {
        if (clearing) return CLEARING
        val row = store.identity(identity) ?: return ProviderSyncResult.NotLinked
        if (!row.isPresent) return ProviderSyncResult.NotLinked
        credentials.setAutoUpdatesPaused(false)
        return fetchShared(identity, title.ifBlank { row.title }, FetchReason.EXPLICIT, null).result
    }

    /**
     * Explicit refresh of a library game through its link. The user acting on a present, linked
     * game confirms its match — this is how a single game is resynced after a clear.
     */
    suspend fun refreshGame(gameId: Long): ProviderSyncResult {
        if (clearing) return CLEARING
        val link = store.linksForGame(gameId).firstOrNull { !it.isMissing } ?: return ProviderSyncResult.NotLinked
        val provider = AchievementProvider.fromName(link.provider) ?: return ProviderSyncResult.NotLinked
        val identity = AchievementIdentity(provider, link.providerGameId)
        store.confirm(identity, link.title, clock.now())
        credentials.setAutoUpdatesPaused(false)
        return fetchShared(identity, link.title, FetchReason.EXPLICIT, null).result
    }

    /**
     * The stale-on-open check for the game a user is viewing: at most one fetch per identity per
     * [STALE_AFTER_MS], never for a removed game, never while updates are paused after a clear.
     * Null when nothing was needed.
     */
    suspend fun refreshIfStale(identity: AchievementIdentity, title: String): ProviderSyncResult? {
        if (clearing) return null
        val row = store.identity(identity) ?: return null
        if (!row.isPresent) return null
        val now = clock.now()
        val lastDetailAt = row.lastDetailAt
        val retryAt = row.retryAt
        if (lastDetailAt != null && now - lastDetailAt < STALE_AFTER_MS) return null
        if (retryAt != null && retryAt > now) return null
        if (credentials.autoUpdatesPaused()) return null
        return fetchShared(identity, title.ifBlank { row.title }, FetchReason.STALE_ON_OPEN, null).result
    }

    /** [refreshIfStale] for a library game, resolved through its link. */
    suspend fun refreshGameIfStale(gameId: Long): ProviderSyncResult? {
        val link = store.linksForGame(gameId).firstOrNull { !it.isMissing } ?: return null
        val provider = AchievementProvider.fromName(link.provider) ?: return null
        return refreshIfStale(AchievementIdentity(provider, link.providerGameId), link.title)
    }

    /**
     * Local Steam launch-return check for [gameId]: one local file read, debounced against rapid
     * resumes and serialized with any write in flight.
     */
    suspend fun checkLocalReturn(gameId: Long): LocalReturnOutcome {
        if (clearing || credentials.autoUpdatesPaused()) return LocalReturnOutcome.UNKNOWN
        val now = clock.now()
        val recent = stateLock.withLock {
            val last = lastReturnCheck[gameId]
            if (last != null && now - last < RETURN_DEBOUNCE_MS) true
            else { lastReturnCheck[gameId] = now; false }
        }
        if (recent) return LocalReturnOutcome.UNCHANGED
        val gen = generation
        return writeLock.withLock {
            if (gen != generation || clearing) LocalReturnOutcome.UNKNOWN else localReturn.check(gameId)
        }
    }

    /** True when a scheduled check should run: not paused, and some provider is past its window. */
    suspend fun isAutomaticCheckDue(): Boolean {
        if (credentials.autoUpdatesPaused()) return false
        val now = clock.now()
        return strategies.any { strategy ->
            val state = store.providerState(strategy.provider)
            state == null || (
                now - (state.lastCheckedAt ?: 0L) >= CHECK_WINDOW_MS &&
                    (state.retryAt ?: 0L) <= now
                )
        }
    }

    // ── Clear all tracked achievements ──────────────────────────────────────────

    /**
     * Cancels and joins every achievement job, then removes every PFP achievement record in one
     * transaction and pauses scheduled updates until the user resyncs. Nothing that was in flight
     * can write afterwards. Returns false (and restores the pause state) if the clear failed.
     */
    suspend fun clearAll(): Boolean {
        val wasPaused = credentials.autoUpdatesPaused()
        clearing = true
        try {
            credentials.setAutoUpdatesPaused(true)
            val running: List<Job> = stateLock.withLock {
                listOfNotNull(batch) + synchronized(inflight) { inflight.values.toList() }
            }
            running.forEach { it.cancel() }
            running.joinAll()
            writeLock.withLock {
                generation++
                store.clearAll()
            }
            credentials.clearLastSyncedAt()
            reporter.cleared(true)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Clear all tracked achievements failed")
            credentials.setAutoUpdatesPaused(wasPaused)
            reporter.cleared(false)
            return false
        } finally {
            clearing = false
        }
    }

    // ── Shared fetch ────────────────────────────────────────────────────────────

    private suspend fun fetchShared(
        identity: AchievementIdentity,
        title: String,
        reason: FetchReason,
        snapshot: String?,
    ): FetchOutcome {
        val deferred = synchronized(inflight) {
            inflight[identity]?.takeIf { it.isActive }
                ?: scope.async { fetchAndWrite(identity, title, reason, snapshot) }.also { created ->
                    inflight[identity] = created
                    created.invokeOnCompletion {
                        synchronized(inflight) { if (inflight[identity] === created) inflight.remove(identity) }
                    }
                }
        }
        return deferred.await()
    }

    private suspend fun fetchAndWrite(
        identity: AchievementIdentity,
        title: String,
        reason: FetchReason,
        snapshot: String?,
    ): FetchOutcome {
        val gen = generation
        val result = try {
            fetcher.fetch(identity, reason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Achievement fetch threw for %s", identity)
            ProviderSyncResult.Failed("unexpected error")
        }
        return writeLock.withLock {
            // A clear happened while this fetch was on the wire: its data must not come back.
            if (gen != generation || clearing) throw CancellationException("achievement records were cleared")
            val now = clock.now()
            when (result) {
                is ProviderSyncResult.Success -> {
                    val changed = writer.write(identity, title, result.coins)
                    store.recordDetail(identity, now, snapshot)
                    FetchOutcome(result, changed)
                }
                ProviderSyncResult.NotFound -> {
                    store.recordNoSet(identity, now)
                    FetchOutcome(result, changed = false)
                }
                is ProviderSyncResult.Failed -> {
                    val failures = (store.identity(identity)?.failureCount ?: 0) + 1
                    store.recordFailure(identity, now, now + AchievementBackoff.delayMs(failures, null))
                    FetchOutcome(result, changed = false)
                }
                // Credentials / privacy / link problems are not this game's fault: no backoff.
                else -> FetchOutcome(result, changed = false)
            }
        }
    }

    private suspend fun publishProgress(trigger: SyncTrigger, done: Int, total: Int) {
        reporter.progress(trigger, done, total)
        val listeners = stateLock.withLock { progressListeners.toList() }
        listeners.forEach { it(done, total) }
    }

    private inner class Counts(val trigger: SyncTrigger) {
        var total = 0
        var done = 0
        var updated = 0
        var unchanged = 0
        var skipped = 0
        var failed = 0
        val pauses = mutableSetOf<UpdatePause>()
        val newPauses = mutableSetOf<UpdatePause>()

        fun pause(pause: UpdatePause, repeat: Boolean) {
            pauses += pause
            if (!repeat) newPauses += pause
        }

        suspend fun advance(by: Int) {
            if (by <= 0) return
            done += by
            publishProgress(trigger, done.coerceAtMost(total), total)
        }

        fun summary() = AchievementUpdateSummary(
            total = total,
            checked = updated + unchanged + failed,
            updated = updated,
            unchanged = unchanged,
            skipped = skipped,
            failed = failed,
            pauses = pauses.toSet(),
        )
    }

    companion object {
        /** A provider is checked automatically at most once per this window (24 hours). */
        const val CHECK_WINDOW_MS = 24L * 60 * 60 * 1_000
        /** A viewed game's detail older than this (24 hours) is refreshed on open. */
        const val STALE_AFTER_MS = 24L * 60 * 60 * 1_000
        /** Repeated resumes within this window check a returned game only once. */
        const val RETURN_DEBOUNCE_MS = 10_000L

        private val BLOCKED = AchievementUpdateSummary(blocked = true)
        private val CLEARING = ProviderSyncResult.Failed("achievement records are being cleared")
    }
}

/**
 * Exponential backoff with jitter for failed checks: 15 minutes doubling per consecutive failure,
 * capped at 24 hours, ±20% jitter, never shorter than a server's own retry hint.
 */
object AchievementBackoff {
    private const val BASE_MS = 15L * 60 * 1_000
    private const val MAX_MS = 24L * 60 * 60 * 1_000

    fun delayMs(failures: Int, retryAfterMs: Long?, random: Random = Random.Default): Long {
        val exponent = (failures - 1).coerceIn(0, 10)
        val base = (BASE_MS shl exponent).coerceAtMost(MAX_MS)
        val jittered = (base * (0.8 + random.nextDouble() * 0.4)).toLong()
        return maxOf(jittered, retryAfterMs ?: 0L)
    }
}
