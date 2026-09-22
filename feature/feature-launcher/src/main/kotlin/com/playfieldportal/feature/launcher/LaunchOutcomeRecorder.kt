package com.playfieldportal.feature.launcher

import com.playfieldportal.core.data.database.dao.LaunchOutcomeDao
import com.playfieldportal.core.data.database.entity.LaunchOutcomeEntity
import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationKind
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.domain.repository.NotificationRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists and reads launch outcomes ([launch_outcomes], core-data). The DAO stays a dumb log
 * table; this recorder is where the launcher's typed verdicts ([LaunchOutcomeStatus], [LaunchSource])
 * map to stored strings and back. Suspend throughout: every accessor may touch Room.
 */
@Singleton
class LaunchOutcomeRecorder @Inject constructor(
    private val dao: LaunchOutcomeDao,
    @ProfileIoDispatcher private val io: CoroutineDispatcher,
    // A launch that failed is a fact worth keeping, and this recorder is already the one place
    // every verdict passes through. Posting here rather than at the dispatcher's three call sites
    // means no future failure path can forget to.
    private val notificationRepository: NotificationRepository,
) {

    suspend fun record(outcome: LaunchOutcome) = withContext(io) {
        dao.insert(outcome.toEntity())
        Timber.i(
            "Launch outcome recorded: gameId=${outcome.gameId}, status=${outcome.status.name}, " +
                "emulator=${outcome.emulatorName ?: "none"}, reason=${outcome.failureReason ?: "-"}"
        )
        postFailureNotification(outcome)
    }

    /**
     * One row per game, not per attempt: the source key is the game, so retrying a stubborn title
     * four times leaves one fresh unread entry rather than four identical ones.
     *
     * Only the two genuine failures post. A successful launch is not news, and the launcher
     * already has its own recovery sheet for the moment a failure happens; this is the record
     * the user finds afterwards, when the sheet is long gone.
     */
    private suspend fun postFailureNotification(outcome: LaunchOutcome) {
        val reason = when (outcome.status) {
            LaunchOutcomeStatus.INTENT_FAILED -> outcome.failureReason ?: "The emulator could not be started"
            LaunchOutcomeStatus.NEVER_FOREGROUNDED ->
                outcome.failureReason ?: "The emulator never came to the foreground"
            else -> return
        }
        notificationRepository.post(
            kind = NotificationKind.LAUNCH,
            severity = NotificationSeverity.ERROR,
            title = "Launch failed: ${outcome.gameTitle}",
            body = reason,
            sourceKey = "launch:${outcome.gameId}",
            action = NotificationAction.OpenGame(outcome.gameId),
        )
    }

    suspend fun recentForGame(gameId: Long, limit: Int): List<LaunchOutcome> = withContext(io) {
        dao.recentForGame(gameId, limit).map { it.toModel() }
    }

    suspend fun recentForPlatform(platformId: String, limit: Int): List<LaunchOutcome> = withContext(io) {
        dao.recentForPlatform(platformId, limit).map { it.toModel() }
    }

    private fun LaunchOutcome.toEntity() = LaunchOutcomeEntity(
        gameId       = gameId,
        gameTitle    = gameTitle,
        platformId   = platformId,
        emulatorId   = emulatorId,
        emulatorName = emulatorName,
        corePath     = corePath,
        coreName     = coreName,
        source       = source?.name,
        outcome      = status.name,
        failureReason = failureReason,
        launchedAtMs = launchedAtMs,
        returnedAtMs = returnedAtMs,
    )

    private fun LaunchOutcomeEntity.toModel() = LaunchOutcome(
        gameId        = gameId,
        gameTitle     = gameTitle,
        platformId    = platformId,
        emulatorId    = emulatorId,
        emulatorName  = emulatorName,
        corePath      = corePath,
        coreName      = coreName,
        source        = source?.let { runCatching { LaunchSource.valueOf(it) }.getOrNull() },
        status        = runCatching { LaunchOutcomeStatus.valueOf(outcome) }.getOrDefault(
            LaunchOutcomeStatus.INTENT_FAILED
        ),
        failureReason = failureReason,
        launchedAtMs  = launchedAtMs,
        returnedAtMs  = returnedAtMs,
    )
}
