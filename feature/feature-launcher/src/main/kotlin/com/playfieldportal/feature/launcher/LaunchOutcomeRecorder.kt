package com.playfieldportal.feature.launcher

import com.playfieldportal.core.data.database.dao.LaunchOutcomeDao
import com.playfieldportal.core.data.database.entity.LaunchOutcomeEntity
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
) {

    suspend fun record(outcome: LaunchOutcome) = withContext(io) {
        dao.insert(outcome.toEntity())
        Timber.i(
            "Launch outcome recorded: gameId=${outcome.gameId}, status=${outcome.status.name}, " +
                "emulator=${outcome.emulatorName ?: "none"}, reason=${outcome.failureReason ?: "-"}"
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
