package com.playfieldportal.feature.launcher

import android.content.Context
import com.playfieldportal.core.data.repository.GameBootPreferences
import com.playfieldportal.core.data.repository.UiMediaStore
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.core.ui.media.UiMediaAudioPlayer
import com.playfieldportal.core.ui.media.gameBootDefaultAudioUri
import com.playfieldportal.core.ui.media.resolveGameBootAudio
import com.playfieldportal.themekit.UiMediaLimits
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * eight seconds, not their game.
 *
 * **One GameBoot, one switch, one asset.** GameBoot is either on or off ([GameBootPreferences]),
 * and when it is on the user either keeps the built-in five-second sequence or replaces the whole
 * thing with a clip of their own — there is no separate sound to assign, which is why
 * [resolveGameBootAudio] has only two branches: the built-in sound plays under the built-in
 * sequence, and a custom clip is left to its own track.
 *
 * **The launch waits for the whole presentation.** [awaitPresentation] does not return until the
 * overlay reports back, and the built-in sequence runs its full five seconds in every case — the
 * motion budget drops its motion, not its length — so the emulator never takes the screen partway
 * through. The audio is started here rather than by the overlay only so it begins before the first
 * frame is drawn and stays in sync with a timeline that was measured against it; the overlay is
 * draw-only and can never release the player mid-clip.
 */
@Singleton
class GameBootGate @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: GameBootPreferences,
    private val uiMedia: UiMediaStore,
    private val audioPlayer: UiMediaAudioPlayer,
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
     * Runs the GameBoot presentation when it is switched on, and returns immediately when it is
     * not — in which case nothing plays and the ordinary Launch Sound handles the launch (subject
     * to the Menu Sounds mute).
     *
     * When on, this suspends until the presentation finishes, is skipped, or times out. A second
     * request while one is already on screen is dropped, not queued.
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
            val customVideo = uiMedia.pathFor(UiMediaSlot.GAMEBOOT_VIDEO)
            customVideo to resolveGameBootAudio(
                customVideoPath = customVideo,
                defaultUri = gameBootDefaultAudioUri(context),
            )
        }
        // Started before the request is raised so the sound is already going when the first
        // frame lands — the sequence's timeline is measured against this sample. The overlay only
        // draws, so it can never release the player mid-clip. A custom clip resolves to null here
        // and keeps its own track.
        audio?.let {
            audioPlayer.play(uri = it, clipEndMs = UiMediaLimits.GAMEBOOT_SEQUENCE_MS, label = "gameboot")
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
        /**
         * Hard cap on the whole presentation, sized from the LONGEST one a user can produce: a
         * 10 s custom clip ([UiMediaLimits.GAMEBOOT_CLIP_MAX_MS]) plus the overlay's fade and a
         * slow first frame. Deliberately longer than either of GameBootOverlay's caps so the
         * overlay normally resolves itself and this watchdog stays the last resort.
         *
         * The built-in sequence is bounded far more tightly by the overlay's own sequence cap;
         * this number exists for the clip path, which is the only one that can genuinely stall.
         */
        const val TIMEOUT_MS = 13_000L
    }
}
