package com.playfieldportal.core.ui.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.playfieldportal.core.domain.model.AudioChannel
import com.playfieldportal.core.ui.sound.AudioLevels
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import timber.log.Timber

/**
 * One-shot ExoPlayer for presentation audio — the Boot Sequence's and GameBoot's own sounds,
 * neither of which has a MenuSound because they are seconds of presentation audio, not a UI
 * sonification (the same reasoning that keeps `OneShotAudioLayer` off SoundPool in the
 * boot/GameBoot overlays).
 *
 * Started before the presentation's first frame is drawn, so a timeline measured against the
 * sample stays in sync with it, and owned here rather than by an overlay so the drawing side can
 * never release the player mid-clip.
 *
 * **URI in, nothing else.** There used to be a UiMediaSlot form for auditioning Boot Sound from
 * the settings screen; that slot is retired — a presentation's audio now travels inside the
 * presentation — so the only entry point is an explicit URI with its own cap.
 *
 * Singleton: a player that died with its screen could be cut off mid-note by a screen flip, and
 * GameBoot's playback must outlive the launch call that started it. One player for the app's
 * lifetime, reused across plays, released on app teardown with every other Hilt singleton.
 */
@Singleton
class UiMediaAudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val levels: AudioLevels,
) {
    // Owns its scope: the player outlives every screen, and so must the collector that keeps its
    // volume current.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var player: ExoPlayer? = null

    // The channel the CURRENT clip belongs to, and its resolved gain. Collected rather than read
    // once at play(): a Boot or GameBoot preview runs for seconds, and dragging its level while
    // it plays has to be audible or the slider reads as broken.
    private var channelJob: Job? = null
    private var gain: Float = 1f

    private val _isPlaying = MutableStateFlow(false)

    /** True while a clip is sounding — screens may surface a stop affordance from it. */
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /**
     * Starts (or restarts) an explicit [uri], clipped at [clipEndMs] and scaled by [channel]'s
     * level. Neither presentation's sound is a user-assignable slot, so there is no UiMediaSlot to
     * carry the cap, the channel or the log [label] — the caller supplies all three.
     */
    fun play(uri: String, clipEndMs: Long, label: String, channel: AudioChannel) {
        stop()
        channelJob = levels.gainFor(channel)
            .onEach { resolved ->
                gain = resolved
                player?.volume = resolved
            }
            .launchIn(scope)
        (player ?: ExoPlayer.Builder(context).build().also { player = it }).apply {
            addListener(listener)
            setMediaItem(
                MediaItem.Builder()
                    .setUri(uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setEndPositionMs(clipEndMs)
                            .build()
                    )
                    .build()
            )
            repeatMode = Player.REPEAT_MODE_OFF
            volume = gain
            playWhenReady = true
            prepare()
        }
        _isPlaying.value = true
        Timber.d("$label audio playing: $uri")
    }

    /** Stops playback and releases the underlying player. Safe to call repeatedly. */
    fun stop() {
        _isPlaying.value = false
        channelJob?.cancel()
        channelJob = null
        player?.run {
            removeListener(listener)
            release()
        }
        player = null
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                _isPlaying.value = false
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Timber.w(error, "UI-media audio playback failed")
            _isPlaying.value = false
        }
    }
}