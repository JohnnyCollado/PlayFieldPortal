package com.playfieldportal.feature.achievements.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.data.database.dao.AccountAchievementDao
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamDiscovery
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Per-session Local Steam unlock watcher. Started by [AchievementSessionController] when a
 * LOCAL_STEAM-linked game launches (popups enabled + overlay grant present), stopped when the
 * user returns to the launcher.
 *
 * The loop reads the GSE emulator's progress file (the same file sync reads) every few seconds
 * and diffs the earned set against a session baseline: each new unlock pops the overlay banner
 * and marks the coin earned in Room, so the Shiba wallet ticks live. The file is a few KB and
 * local, so the poll is effectively free; everything is best-effort and can never crash a
 * session. A safety timeout stops the service if the stop signal never arrives.
 */
@AndroidEntryPoint
class AchievementWatchService : Service() {

    @Inject lateinit var discovery: LocalSteamDiscovery
    @Inject lateinit var coinDao: AccountAchievementDao
    @Inject lateinit var popup: AchievementPopupOverlay
    @Inject lateinit var credentials: AchievementCredentialsProvider

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val appId = intent?.getStringExtra(EXTRA_APP_ID)
        if (appId.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        watch(appId)
        return START_NOT_STICKY
    }

    private fun watch(appId: String) {
        scope.launch {
            Timber.i("Achievement watch started for appid $appId")
            var game = discovery.findByAppId(appId)

            // Coin metadata for the popup (title/description/tier), keyed by Steam apiname.
            // Absent rows (game never synced) fall back to the raw apiname.
            val metaByName = runCatching {
                coinDao.getForSet(AchievementProvider.LOCAL_STEAM.name, appId).associateBy { it.providerAchievementId }
            }.getOrDefault(emptyMap())

            // The unlock signal is an entry's "earned" flag becoming true. A popup fires ONLY
            // for a flip observed during this session, and never twice for the same achievement
            // — a per-game shown-set persisted in DataStore survives app restarts, so replays,
            // re-baselining, or file rewrites can never re-show one.
            var shown = runCatching { credentials.achievementPopupsShown(appId) }.getOrDefault(emptySet())

            // Baseline earned-flags before the session:
            //  - initial read succeeds → those flags are the baseline;
            //  - the file exists but the read failed → baseline is UNKNOWN; the first successful
            //    read becomes the baseline (suppressed), so history never replays as popups;
            //  - no progress file at start → nothing was ever earned (GSE creates the file on the
            //    first unlock), so an empty baseline is correct and the first earns ARE new.
            val fileExistedAtStart = game?.achievementsUri != null
            var baseline: Map<String, Boolean>? = readEarnedOrNull(game)
                ?: if (fileExistedAtStart) null else emptyMap()
            // Change gate: one cheap metadata query (mtime+size) per tick; the stream open,
            // read, and parse only run when the stamp moved. Keeps per-tick cost to a single
            // binder call — a game is in the foreground, and sustained SAF churn from a
            // foreground-service-priority process is exactly what caused input lag.
            var lastStamp: Pair<Long, Long>? = game?.achievementsUri?.let { stampOf(it) }
            val deadline = System.currentTimeMillis() + SESSION_TIMEOUT_MS
            var tick = 0

            while (isActive && System.currentTimeMillis() < deadline) {
                delay(POLL_MS)
                tick++
                // A game that has never unlocked anything has no progress file yet — GSE creates
                // it on the first unlock. Re-resolving is a FULL SAF tree walk, so it runs
                // sparingly, and never again once the file is found.
                if (game?.achievementsUri == null) {
                    if (tick % REDISCOVER_EVERY == 0) {
                        game = discovery.findByAppId(appId)
                        lastStamp = null   // newly appeared file must be read
                    }
                    if (game?.achievementsUri == null) continue
                }
                val stamp = game?.achievementsUri?.let { stampOf(it) }
                if (stamp != null && stamp == lastStamp) continue   // unchanged — skip the read
                val now = readEarnedOrNull(game) ?: continue
                lastStamp = stamp
                val base = baseline
                if (base == null) {
                    baseline = now   // late baseline (pre-existing file): history stays silent
                    continue
                }
                // earned false -> true flips: earned now, and at baseline it was false or the
                // entry didn't exist yet (a fresh file's first unlock IS new).
                val fresh = now.filter { (name, earned) -> earned && base[name] != true && name !in shown }.keys
                baseline = base + now
                if (fresh.isEmpty()) continue
                shown = shown + fresh
                runCatching { credentials.addAchievementPopupsShown(appId, fresh) }
                for (name in fresh) {
                    val meta = metaByName[name]
                    popup.enqueue(
                        AchievementPopup(
                            title = meta?.title ?: name,
                            description = meta?.description.orEmpty(),
                            tier = meta?.tier ?: "BRONZE",
                        ),
                    )
                    // Mark it earned in Room so the wallet/standings tick live; the next full
                    // sync reconciles rarity and timestamps authoritatively.
                    if (meta != null && !meta.isEarned) {
                        runCatching {
                            coinDao.upsertAll(listOf(meta.copy(isEarned = true, earnedAt = System.currentTimeMillis())))
                        }
                    }
                    Timber.i("Achievement unlocked (local steam $appId): $name")
                }
            }
            stopSelf()
        }
    }

    // The file's (last-modified, size) stamp via a single metadata query — the cheap per-tick
    // change probe. Null on any failure, which callers treat as "read to be safe".
    private fun stampOf(uri: android.net.Uri): Pair<Long, Long>? = runCatching {
        contentResolver.query(
            uri,
            arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                android.provider.DocumentsContract.Document.COLUMN_SIZE,
            ),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val mtime = if (c.isNull(0)) 0L else c.getLong(0)
            val size = if (c.isNull(1)) 0L else c.getLong(1)
            mtime to size
        }
    }.getOrNull()

    // Per-achievement earned flags. Null = "could not read" (no file yet, or a transient SAF
    // failure) — callers must treat that differently from an empty map, or history replays.
    private suspend fun readEarnedOrNull(game: com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamGame?): Map<String, Boolean>? {
        val uri = game?.achievementsUri ?: return null
        return runCatching {
            discovery.readProgress(uri).associate { it.apiName to it.earned }
        }.getOrNull()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Achievement popups", NotificationManager.IMPORTANCE_MIN),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle("Watching for achievements")
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        popup.clear()
        scope.cancel()
        Timber.i("Achievement watch stopped")
        super.onDestroy()
    }

    companion object {
        const val EXTRA_APP_ID = "app_id"
        private const val CHANNEL_ID = "achievement_watch"
        private const val NOTIFICATION_ID = 4711
        private const val POLL_MS = 5_000L
        private const val REDISCOVER_EVERY = 12        // ticks (60 s) between full-tree re-resolves
        private const val SESSION_TIMEOUT_MS = 6L * 60 * 60 * 1000
    }
}
