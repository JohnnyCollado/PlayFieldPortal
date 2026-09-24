package com.playfieldportal.core.ui.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.playfieldportal.core.domain.model.AudioChannel
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.core.ui.media.UiMediaPaths
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The launcher's background music — an assigned clip ([UiMediaSlot.AMBIENCE_AUDIO]) looping under
 * the XMB while the user browses.
 *
 * **The first continuous sound this app has ever made.** Everything else is a one-shot: a menu
 * tick, a boot chime, a five-second GameBoot sting. That difference is the whole design. A
 * one-shot needs an on/off switch and can read its level at the instant it fires; a loop needs a
 * level it *follows*, has to yield to other audio, and must never outlive the screen it belongs
 * to.
 *
 * **Four gates, all of which must hold for a sound to come out** ([reevaluate]):
 *
 *  1. **Assigned** — a file exists for the slot. No assignment means ambience is off; there is no
 *     separate enable flag, because a switch and an assignment that both mean "off" drift apart.
 *  2. **Audible** — resolved gain is above zero. Master at 0 is the mute.
 *  3. **Foreground** — the launcher is on screen. The player is RELEASED, never paused: a paused
 *     ExoPlayer still holds a codec, a surface and buffers, and every game launch backgrounds us
 *     (the same reasoning MotionWallpaperBackground already applies to video).
 *  4. **Not suppressed** — nothing louder owns the room. See [setSuppressed].
 *
 * Plus a start condition: [setBootFinished] holds ambience until the boot sequence is done, so the
 * chime plays and THEN the music starts, rather than the two landing on top of each other.
 *
 * **Audio focus is handled properly** ([focusListener]), because a loop that ignores it does not
 * degrade gracefully — it talks over the user's Spotify indefinitely. Focus is requested on start
 * and abandoned on every stop.
 */
@Singleton
class AmbienceController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uiMedia: UiMediaPaths,
    private val levels: AudioLevels,
) {
    // Main.immediate: every gate write arrives from the main thread (lifecycle, ViewModel, UI),
    // and ExoPlayer must be touched from the thread that built it. Confinement by construction.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var player: ExoPlayer? = null

    // ── The gates ────────────────────────────────────────────────────────────
    private val foreground = MutableStateFlow(false)
    private val bootFinished = MutableStateFlow(false)
    private val suppressors = MutableStateFlow<Set<String>>(emptySet())

    // Latest resolved values, kept so reevaluate() can read them synchronously.
    private var assignedPath: String? = null
    private var gain: Float = 0f

    // Transient focus loss pauses rather than stops, so GAIN can resume the same player.
    private var duckedByFocus = false
    private var pausedByFocus = false

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val focusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // We handle transient loss ourselves rather than letting the system pause us, so the
            // duck level is ours to choose and resume is ours to decide.
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
    }

    init {
        // The assignment can change under us (an import, a clear, a restored backup), and the
        // stamp is exactly the signal for that — the same one MenuSoundPlayer reloads on.
        uiMedia.stamp
            .distinctUntilChanged()
            .onEach {
                assignedPath = withContext(Dispatchers.IO) {
                    runCatching { uiMedia.pathFor(UiMediaSlot.AMBIENCE_AUDIO) }.getOrNull()
                }
                // A changed FILE needs a restart, not a re-evaluation: the running player is still
                // holding the old one.
                restartIfPlaying()
                reevaluate()
            }
            .launchIn(scope)

        // Collected, not snapshotted. This is the requirement that makes the volume screen feel
        // real: dragging the Ambience slider has to be audible while the loop is playing, which a
        // player that read its level once at start could never do.
        levels.gainFor(AudioChannel.AMBIENCE)
            .distinctUntilChanged()
            .onEach { resolved ->
                gain = resolved
                applyVolume()
                reevaluate()
            }
            .launchIn(scope)

        combine(foreground, bootFinished, suppressors) { fg, boot, sup -> Triple(fg, boot, sup) }
            .distinctUntilChanged()
            .onEach { reevaluate() }
            .launchIn(scope)
    }

    // ── Gate inputs ──────────────────────────────────────────────────────────

    /** The launcher came to the foreground (MainActivity.onResume). */
    fun onHostResumed() { foreground.value = true }

    /**
     * The launcher left the foreground (MainActivity.onStop) — in practice, a game launched.
     * Releases the player rather than pausing it; see the class KDoc.
     */
    fun onHostStopped() { foreground.value = false }

    /**
     * The boot sequence is over (or was never going to run), so ambience may start. Chime first,
     * then music — the order a console does it in.
     */
    fun setBootFinished(finished: Boolean) { bootFinished.value = finished }

    /**
     * Something louder wants the room. [owner] names the suppressor so two of them cannot
     * un-suppress each other: the music player stopping must not resume ambience while a video is
     * still playing.
     *
     * **This is pushed IN from feature-xmb rather than observed from here.** core-ui cannot see
     * MusicPlayerController or VideoPlayerScreen without a dependency cycle, so the arbitration
     * runs in the direction the modules already point.
     *
     * Audio focus does NOT cover this case: focus is granted per-application, so our own music
     * player taking it would never make our own ambience yield. Same UID, no signal.
     */
    fun setSuppressed(owner: String, suppressed: Boolean) {
        suppressors.value =
            if (suppressed) suppressors.value + owner else suppressors.value - owner
    }

    // ── The decision ─────────────────────────────────────────────────────────

    private fun shouldPlay(): Boolean =
        assignedPath != null &&
            gain > 0f &&
            foreground.value &&
            bootFinished.value &&
            suppressors.value.isEmpty()

    private fun reevaluate() {
        if (shouldPlay()) start() else stop()
    }

    private fun start() {
        val path = assignedPath ?: return
        if (player != null) {
            // Already running — a focus pause is the only state that needs lifting here.
            if (pausedByFocus) {
                pausedByFocus = false
                player?.playWhenReady = true
            }
            return
        }
        if (!requestFocus()) {
            Timber.d("Ambience not starting — audio focus refused")
            return
        }
        player = ExoPlayer.Builder(context).build().apply {
            addListener(listener)
            setMediaItem(MediaItem.fromUri(path))
            // A background loops by definition — the same call MotionWallpaperBackground makes.
            // Seamlessness is the container's business: OGG loops clean, MP3 clicks at the seam
            // because of encoder delay, and accepting both is a decision (see UiMediaLimits).
            repeatMode = Player.REPEAT_MODE_ALL
            volume = effectiveVolume()
            playWhenReady = true
            prepare()
        }
        Timber.d("Ambience playing: %s", path)
    }

    private fun stop() {
        val existing = player ?: return
        player = null
        pausedByFocus = false
        duckedByFocus = false
        runCatching {
            existing.removeListener(listener)
            existing.release()
        }
        abandonFocus()
    }

    private fun restartIfPlaying() {
        if (player != null) stop()
    }

    // ── Volume ───────────────────────────────────────────────────────────────

    // Ducking multiplies the user's own level rather than replacing it, so a quiet ambience ducks
    // to quieter still instead of jumping up to the duck level.
    private fun effectiveVolume(): Float = if (duckedByFocus) gain * DUCK_MULTIPLIER else gain

    private fun applyVolume() {
        player?.volume = effectiveVolume()
    }

    // ── Audio focus ──────────────────────────────────────────────────────────

    private fun requestFocus(): Boolean =
        runCatching { audioManager.requestAudioFocus(focusRequest) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private fun abandonFocus() {
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        scope.launch {
            when (change) {
                // Permanent: another app owns audio now. Stop and give the request back — holding
                // it would be claiming a room we have left.
                AudioManager.AUDIOFOCUS_LOSS -> stop()

                // Transient: keep the request and the player, resume on GAIN.
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    pausedByFocus = true
                    player?.playWhenReady = false
                }

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    duckedByFocus = true
                    applyVolume()
                }

                AudioManager.AUDIOFOCUS_GAIN -> {
                    duckedByFocus = false
                    applyVolume()
                    if (pausedByFocus) {
                        pausedByFocus = false
                        // Only resume if the other four gates still agree — the user may have
                        // launched a game during the phone call we just yielded to.
                        if (shouldPlay()) player?.playWhenReady = true
                    }
                }
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            // A bad file must not leave a dead player holding focus forever. Stop; the next stamp
            // bump (a re-import) is what gets ambience going again.
            Timber.w(error, "Ambience playback failed — stopping")
            stop()
        }
    }

    companion object {
        /** How far a duckable focus loss pulls ambience down, as a factor of the user's level. */
        private const val DUCK_MULTIPLIER = 0.25f

        /** Suppressor names — one per owner, so their suppressions cannot cancel each other. */
        const val OWNER_MUSIC = "music"
        const val OWNER_VIDEO = "video"
    }
}
