package com.playfieldportal.feature.achievements.session

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.data.database.dao.ProviderGameLinkDao
import com.playfieldportal.core.domain.achievement.AchievementProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session boundaries for the in-game achievement watcher. The launcher is the session oracle:
 * [onGameLaunched] fires from the launch path (the Play action / direct launch), and returning
 * to the launcher always resumes MainActivity, which calls [onLauncherResumed].
 *
 * Only LOCAL_STEAM-linked games start a watch, and only when the user enabled popups AND the
 * "Draw over other apps" grant is present — everything else is a silent no-op. Provider seams for
 * RetroAchievements/Steam pollers plug in here later.
 */
@Singleton
class AchievementSessionController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentials: AchievementCredentialsProvider,
    private val linkDao: ProviderGameLinkDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun onGameLaunched(gameId: Long) {
        scope.launch {
            if (!credentials.achievementPopupsEnabled()) return@launch
            val link = linkDao.getForGame(gameId) ?: return@launch
            if (link.provider != AchievementProvider.LOCAL_STEAM.name) return@launch
            if (!Settings.canDrawOverlays(context)) {
                Timber.i("Achievement popups enabled but overlay grant missing — not watching")
                return@launch
            }
            val intent = Intent(context, AchievementWatchService::class.java)
                .putExtra(AchievementWatchService.EXTRA_APP_ID, link.providerGameId)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Timber.w(it, "Could not start achievement watch") }
        }
    }

    /** Back on the launcher — the session is over; stopping is safe even when nothing runs. */
    fun onLauncherResumed() {
        runCatching { context.stopService(Intent(context, AchievementWatchService::class.java)) }
    }
}
