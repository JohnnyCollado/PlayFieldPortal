package com.playfieldportal.feature.xmb.viewmodel

import android.content.Context
import com.playfieldportal.core.domain.model.BackgroundTaskInfo
import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationDetail
import com.playfieldportal.core.domain.model.PfpErrorCode
import com.playfieldportal.feature.artwork.api.classifyScrapeFailureText
import com.playfieldportal.core.domain.model.TaskKind
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.core.ui.notification.BackgroundTaskCenter
import com.playfieldportal.feature.artwork.api.ARTWORK_PAUSED_TEXT
import com.playfieldportal.feature.artwork.api.ArtworkRepository
import com.playfieldportal.feature.xmb.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject

/**
 * Fetch Artwork from the XMB game menu.
 *
 * Progress is a RUNNING row in the notification panel; the outcome settles into the tray only,
 * and tapping it opens the game. Game Detail runs the same repository call but reports inline,
 * because the user is looking at the game while it runs.
 */
class GameArtworkFetchRunner @Inject constructor(
    private val artworkRepository: ArtworkRepository,
    private val gameRepository: GameRepository,
    private val backgroundTasks: BackgroundTaskCenter,
    @ApplicationContext private val context: Context,
) {
    /** Returns true when new artwork was written, so the caller can reload the visible list. */
    suspend fun run(gameId: Long): Boolean {
        val taskId = "fetch_artwork_$gameId"
        // This game's menu fetch is already on the panel; a second press would only replace its row.
        if (backgroundTasks.running.value.any { it.id == taskId }) return false
        val game = gameRepository.getById(gameId) ?: return false
        val action = NotificationAction.OpenGame(gameId)

        backgroundTasks.start(
            BackgroundTaskInfo(
                id = taskId,
                label = context.getString(R.string.xmbm_fetchart_running, game.displayTitle),
                kind = TaskKind.ARTWORK,
            )
        )
        val result = try {
            artworkRepository.refetchArtworkForGame(gameId) { source, asset ->
                // One game has no counts; total 0 keeps the bar indeterminate.
                backgroundTasks.progress(taskId, 0, 0, "$source · $asset")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Fetch Artwork failed for gameId=$gameId")
            backgroundTasks.fail(
                taskId, context.getString(R.string.xmbm_fetchart_failed), action,
                NotificationDetail.notes(PfpErrorCode.AR_9001, summary = e.message,
                    diagnostic = e.stackTraceToString().take(4_000)),
                context.getString(R.string.xmbm_fetchart_failed_title, game.displayTitle),
            )
            return false
        }

        when {
            // Game Detail is fetching this game already and reports its own result.
            result.alreadyRunning -> backgroundTasks.cancel(taskId)
            result.paused         -> backgroundTasks.fail(
                taskId, ARTWORK_PAUSED_TEXT.resolve(context), NotificationAction.OpenSettingsScreen("settings_artwork_import"),
                NotificationDetail.notes(PfpErrorCode.AR_1001, summary = ARTWORK_PAUSED_TEXT.resolve(context)),
                context.getString(R.string.xmbm_fetchart_paused_title, game.displayTitle),
            )
            result.success       -> backgroundTasks.complete(
                taskId, null, action, null, context.getString(R.string.xmbm_fetchart_done_title, game.displayTitle),
            )
            else                  -> backgroundTasks.fail(
                taskId, result.errorMessage?.resolve(context) ?: context.getString(R.string.xmbm_fetchart_failed), action,
                NotificationDetail.notes(
                    classifyScrapeFailureText(result.errorMessage),
                    summary = result.errorMessage?.resolve(context),
                ),
                context.getString(R.string.xmbm_fetchart_failed_title, game.displayTitle),
            )
        }
        return result.success
    }
}
