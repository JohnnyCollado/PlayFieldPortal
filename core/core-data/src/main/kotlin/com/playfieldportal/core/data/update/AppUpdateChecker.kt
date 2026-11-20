package com.playfieldportal.core.data.update

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.domain.model.appupdate.AppRelease
import com.playfieldportal.core.domain.model.appupdate.AppReleaseParser
import com.playfieldportal.core.domain.model.appupdate.SemVer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** How an update check ended. */
enum class AppUpdateCheckResult {
    UpToDate,
    UpdateAvailable,
    /** GitHub could not be reached, or answered with an error (rate limit included). */
    Offline,
    /** GitHub answered, but not with a release list. */
    InvalidResponse,
    /** An automatic check that was not due yet. Nothing was fetched or recorded. */
    Skipped,
}

/** What is known about releases right now: the last list fetched, held against the installed build. */
data class AppUpdateSnapshot(
    val installed: SemVer?,
    /** Newest first. Empty before the first successful check. */
    val releases: List<AppRelease>,
    /** Epoch millis of the last check that got a release list, 0 when none has. */
    val lastCheckAt: Long,
) {
    /** The newest release, when it is newer than what is installed. */
    val available: AppRelease?
        get() {
            val latest = releases.firstOrNull() ?: return null
            val latestVersion = latest.semVer ?: return null
            // An unreadable installed version cannot be compared, so nothing is offered.
            val current = installed ?: return null
            return latest.takeIf { latestVersion > current }
        }
}

/**
 * Checks GitHub for a newer release of the app and remembers the answer.
 *
 * The release list is cached so the System Update screen, its changelog and the reminder all work
 * from the last answer without a request of their own; automatic checks are limited to one a day
 * because GitHub allows an anonymous address only 60 requests an hour. "Available" is recomputed
 * against the running build every time, so it clears by itself once the update is installed.
 */
@Singleton
class AppUpdateChecker internal constructor(
    private val context: Context,
    private val downloader: AppUpdateDownloader,
    private val clock: () -> Long,
    private val installedVersionName: () -> String?,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        downloader: AppUpdateDownloader,
    ) : this(
        context, downloader, System::currentTimeMillis,
        { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() },
    )

    private val mutex = Mutex()

    val snapshot: Flow<AppUpdateSnapshot> = context.pfpDataStore.data.map { prefs ->
        AppUpdateSnapshot(
            installed = installedVersionName()?.let { SemVer.fromVersionName(it) },
            releases = decodeReleases(prefs[KEY_RELEASES]),
            lastCheckAt = prefs[KEY_LAST_CHECK_AT] ?: 0L,
        )
    }

    /** Whether a found update is posted to the notification panel. Defaults to on. */
    val notificationsEnabled: Flow<Boolean> = context.pfpDataStore.data.map { it[KEY_NOTIFY] ?: true }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.pfpDataStore.edit { prefs ->
            prefs[KEY_NOTIFY] = enabled
            // Off, then on again, reminds at once instead of waiting out a day nobody was counting.
            if (!enabled) prefs.remove(KEY_LAST_REMINDED_AT)
        }
    }

    /**
     * Runs one check. An automatic one ([manual] false) returns [AppUpdateCheckResult.Skipped] when
     * the last successful check was under a day ago. A failure keeps the cached releases and does
     * not start the day's wait. Cancellation propagates.
     */
    suspend fun check(manual: Boolean): AppUpdateCheckResult = mutex.withLock {
        if (!manual && !isDue()) return AppUpdateCheckResult.Skipped
        val text = when (val fetched = downloader.fetchReleases()) {
            is ReleaseListFetch.Text -> fetched.text
            is ReleaseListFetch.Failure -> {
                Timber.i("App update check failed: %s", fetched.reason)
                return AppUpdateCheckResult.Offline
            }
        }
        val releases = AppReleaseParser.parse(text) ?: run {
            Timber.w("App update check: the response was not a release list")
            return AppUpdateCheckResult.InvalidResponse
        }
        context.pfpDataStore.edit { prefs ->
            prefs[KEY_RELEASES] = JSON.encodeToString(RELEASES, releases.take(MAX_CACHED_RELEASES))
            prefs[KEY_LAST_CHECK_AT] = clock()
        }
        val available = snapshot.first().available
        Timber.i("App update check: latest=%s available=%s", releases.firstOrNull()?.version, available?.version)
        if (available != null) AppUpdateCheckResult.UpdateAvailable else AppUpdateCheckResult.UpToDate
    }

    /**
     * The update to remind the user about now, or null. Returning one records it, so the next call
     * stays quiet until a day has passed or a newer release appears.
     */
    suspend fun takeReminder(): AppRelease? = mutex.withLock {
        val available = snapshot.first().available ?: return null
        var due = false
        context.pfpDataStore.edit { prefs ->
            if (prefs[KEY_NOTIFY] == false) return@edit
            val sameVersion = prefs[KEY_LAST_REMINDED_VERSION] == available.version
            val elapsed = clock() - (prefs[KEY_LAST_REMINDED_AT] ?: Long.MIN_VALUE / 2)
            // A reminder stamped in the future (clock set back) counts as due.
            due = !sameVersion || elapsed !in 0 until REMIND_INTERVAL_MS
            if (due) {
                prefs[KEY_LAST_REMINDED_VERSION] = available.version
                prefs[KEY_LAST_REMINDED_AT] = clock()
            }
        }
        available.takeIf { due }
    }

    /**
     * The running version, once it has caught up with an update that was reminded about; null
     * otherwise. Returning it forgets the reminder, so the caller hears about the install once and
     * can overwrite the "is available" row it posted.
     */
    suspend fun takeInstalledReminder(): String? = mutex.withLock {
        val installed = installedVersionName()?.let { SemVer.fromVersionName(it) } ?: return null
        var caughtUp = false
        context.pfpDataStore.edit { prefs ->
            val reminded = prefs[KEY_LAST_REMINDED_VERSION]?.let { SemVer.parseTag(it) } ?: return@edit
            if (installed < reminded) return@edit
            caughtUp = true
            prefs.remove(KEY_LAST_REMINDED_VERSION)
            prefs.remove(KEY_LAST_REMINDED_AT)
        }
        installed.toString().takeIf { caughtUp }
    }

    private suspend fun isDue(): Boolean {
        val elapsed = clock() - (context.pfpDataStore.data.first()[KEY_LAST_CHECK_AT] ?: 0L)
        // A last check in the future (clock set back) counts as due rather than blocking for days.
        return elapsed !in 0 until CHECK_INTERVAL_MS
    }

    private fun decodeReleases(text: String?): List<AppRelease> = text?.let {
        try {
            JSON.decodeFromString(RELEASES, it)
        } catch (_: IllegalArgumentException) {
            null
        }
    } ?: emptyList()

    companion object {
        // Not added to BackupManager: a cached release list and reminder stamps mean nothing on another install.
        private val KEY_RELEASES = stringPreferencesKey("app_update_releases")
        private val KEY_LAST_CHECK_AT = longPreferencesKey("app_update_last_check_at")
        // The one user choice here, so this one IS backed up (BackupManager names it by string).
        private val KEY_NOTIFY = booleanPreferencesKey("app_update_notify")
        private val KEY_LAST_REMINDED_VERSION = stringPreferencesKey("app_update_last_reminded_version")
        private val KEY_LAST_REMINDED_AT = longPreferencesKey("app_update_last_reminded_at")

        const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        const val REMIND_INTERVAL_MS = 24L * 60 * 60 * 1000
        private const val MAX_CACHED_RELEASES = 30
        private val JSON = Json { ignoreUnknownKeys = true }
        private val RELEASES = ListSerializer(AppRelease.serializer())
    }
}
