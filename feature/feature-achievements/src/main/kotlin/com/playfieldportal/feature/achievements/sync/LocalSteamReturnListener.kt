package com.playfieldportal.feature.achievements.sync

import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.feature.launcher.GameSessionReturnListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges a confirmed return from a game PFP launched to the Local Steam progress check. Only
 * Windows games can carry a LOCAL_STEAM identity, so every other return is ignored up front; the
 * coordinator does the rest (debounce, serialization, the local-only read).
 */
@Singleton
class LocalSteamReturnListener @Inject constructor(
    private val coordinator: AchievementSyncCoordinator,
    @AchievementSyncScope private val scope: CoroutineScope,
) : GameSessionReturnListener {

    override fun onGameSessionReturned(game: Game) {
        if (game.platformId != "windows") return
        scope.launch {
            val outcome = runCatching { coordinator.checkLocalReturn(game.id) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull()
            Timber.i("Local Steam return check for %s: %s", game.id, outcome)
        }
    }
}
