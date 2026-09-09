package com.playfieldportal.core.ui.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.core.ui.sound.MenuSoundPlayer
import com.playfieldportal.themekit.UiMediaLimits
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * One-shot ExoPlayer audition for [UiMediaSlot.BOOT_AUDIO] — the slot that has no [com.playfieldportal.core.ui.sound.MenuSound]
 * because it is up-to-10-seconds of boot music, not a UI sonification (the same reasoning that
 * keeps `OneShotAudioLayer` off SoundPool in the boot overlay).
 *
 * Plays the user's assignment when one exists, otherwise the bundled default resolved through
 * [UiMediaSlot.bundledDefaultUri] — the same custom-over-default rule [MenuSoundPlayer] applies
 * to the menu sounds, kept here rather than in any feature screen so no caller grows a URI branch.
 *
 * Singleton: settings screens come and go, and a preview player that died with its screen could
 * be cut off mid-note by a screen flip. One player for the app's lifetime, reused across
 * previews, released on app teardown with every other Hilt singleton.
 */
@Singleton
class BootSoundPreviewer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var player: ExoPlayer? = null

    private val _isPlaying = MutableStateFlow(false)

    /** True while a preview is sounding — screens may surface a stop affordance from it. */
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /**
     * Starts (or restarts) the boot sound. [customPath] is the user's assignment from
     * [com.playfieldportal.core.data.repository.UiMediaStore.pathFor], or null to audition the
     * bundled default. The clip is bounded by the slot's own duration ceiling — the second belt
     * behind the import gate, exactly as the boot overlay does it.
     */
    fun play(customPath: String?) {
        val source = customPath
            ?: UiMediaSlot.BOOT_AUDIO.bundledDefaultUri(context.packageName)
            ?: return
        stop()
        val next = (player ?: ExoPlayer.Builder(context).build().also { player = it }).apply {
            addListener(listener)
            setMediaItem(
                MediaItem.Builder()
                    .setUri(source)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setEndPositionMs(UiMediaLimits.BOOT.hardMaxMs)
                            .build()
                    )
                    .build()
            )
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true
            prepare()
        }
        _isPlaying.value = true
        Timber.d("Boot sound preview: ${if (customPath != null) "custom" else "bundled default"}")
    }

    /** Stops playback and releases the underlying player. Safe to call repeatedly. */
    fun stop() {
        _isPlaying.value = false
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
            Timber.w(error, "Boot sound preview failed")
            _isPlaying.value = false
        }
    }
}
