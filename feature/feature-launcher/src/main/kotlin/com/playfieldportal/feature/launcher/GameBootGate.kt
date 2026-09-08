package com.playfieldportal.feature.launcher

import com.playfieldportal.core.data.repository.GameBootPreferences
import com.playfieldportal.core.data.repository.UiMediaStore
import com.playfieldportal.core.domain.model.UiMediaSlot
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/** What the shell needs to draw one GameBoot presentation. */
data class GameBootRequest(
    val gameTitle: String,
    val videoPath: String? = null,
    val audioPath: String? = null,
)

/**
 * The seam between "the launch is going to happen" and "the emulator has the screen".
 *
 * [LaunchDispatcher.launch] awaits this immediately before `startActivity`, so the presentation
 * only ever plays for a launch that has already passed every preflight — a game that cannot start
 * never shows a GameBoot. The XMB shell observes [active] and composes the overlay; when the
 * overlay finishes (or is skipped) it calls [onPresentationFinished] and the launch proceeds.
 *
 * **The user can never be trapped here.** [awaitPresentation] is bounded by [TIMEOUT_MS] and a
 * timeout PROCEEDS with the launch rather than throwing — a stuck presentation costs the user
 * seven seconds, not their game.
 */
@Singleton
class GameBootGate @Inject constructor(
    private val preferences: GameBootPreferences,
    private val uiMedia: UiMediaStore,
) {
    private val _active = MutableStateFlow<GameBootRequest?>(null)

    /** Non-null while a presentation should be on screen. */
    val active: StateFlow<GameBootRequest?> = _active.asStateFlow()

    // Completed by the overlay (natural end, skip, or its own error). Replaced per presentation.
    @Volatile
    private var completion: CompletableDeferred<Unit>? = null

    /** True while a presentation is running — used to drop a duplicate launch request. */
    val isActive: Boolean get() = _active.value != null

    /**
     * Suspends until the presentation finishes, is skipped, or times out. A no-op — returning
     * immediately — when GameBoot is disabled, or when one is already on screen (a second launch
     * request must be dropped, not queued).
     */
    suspend fun awaitPresentation(gameTitle: String) {
        if (!preferences.gameBootEnabledFlow.first()) return
        if (isActive) {
            Timber.d("GameBoot already presenting — ignoring a second request for $gameTitle")
            return
        }
        val done = CompletableDeferred<Unit>()
        completion = done
        val (video, audio) = withContext(Dispatchers.IO) {
            uiMedia.pathFor(UiMediaSlot.GAMEBOOT_VIDEO) to uiMedia.pathFor(UiMediaSlot.GAMEBOOT_AUDIO)
        }
        _active.value = GameBootRequest(gameTitle = gameTitle, videoPath = video, audioPath = audio)
        try {
            withTimeout(TIMEOUT_MS) { done.await() }
        } catch (_: TimeoutCancellationException) {
            // Deliberately swallowed: the launch continues. Rule 13 — never trap the user on the
            // transition screen because a player stalled.
            Timber.w("GameBoot watchdog fired after ${TIMEOUT_MS}ms — launching anyway")
        } finally {
            clear()
        }
    }

    /** The overlay reports its presentation is over (ended, skipped, or failed). */
    fun onPresentationFinished() {
        completion?.complete(Unit)
    }

    private fun clear() {
        completion = null
        _active.value = null
    }

    companion object {
        /** Hard cap on the whole presentation — the 5 s media cap plus room for the fade. */
        const val TIMEOUT_MS = 7_000L
    }
}
