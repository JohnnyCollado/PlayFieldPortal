package com.playfieldportal.core.ui.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.core.ui.sound.MenuSoundPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * One-shot ExoPlayer for presentation audio — [UiMediaSlot.BOOT_AUDIO] and the GameBoot
 * sequence's own sound, neither of which has a [MenuSound] because they are seconds of
 * presentation audio, not a UI sonification (the same reasoning that keeps `OneShotAudioLayer`
 * off SoundPool in the boot/GameBoot overlays).
 *
 * Two jobs share this one singleton:
 *
 *  1. **Auditioning** from the settings screens (its original, Boot-Sound-only role) — play the
 *     slot's assignment, or its bundled default, on demand: [play] with a [UiMediaSlot].
 *  2. **Presentation playback** for the GameBoot sequence — started before the first frame is
 *     drawn so a timeline measured against the sample stays in sync with it, and owned here
 *     rather than by the overlay so the drawing side can never release the player mid-clip.
 *     GameBoot has no slot of its own, so it uses [play] with an explicit URI.
 *
 * The slot form plays the user's assignment when one exists, otherwise the bundled default
 * resolved through [UiMediaSlot.bundledDefaultUri] — the same custom-over-default rule
 * [MenuSoundPlayer] applies to the menu sounds, kept here rather than in any feature screen so no
 * caller grows a URI branch.
 *
 * Singleton: settings screens come and go, and a preview player that died with its screen could
 * be cut off mid-note by a screen flip — and GameBoot's playback must outlive the launch call
 * that started it. One player for the app's lifetime, reused across plays, released on app
 * teardown with every other Hilt singleton.
 */
@Singleton
class UiMediaAudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var player: ExoPlayer? = null

    private val _isPlaying = MutableStateFlow(false)

    /** True while a clip is sounding — screens may surface a stop affordance from it. */
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /**
     * Starts (or restarts) [slot]'s audio. [customPath] is the user's assignment from
     * [com.playfieldportal.core.data.repository.UiMediaStore.pathFor], or null to play the slot's
     * bundled default (and a no-op for a slot with none). The clip is bounded by the slot's own
     * duration ceiling — the second belt behind the import gate, exactly as the boot overlay does
     * it.
     */
    fun play(slot: UiMediaSlot = UiMediaSlot.BOOT_AUDIO, customPath: String?) {
        val source = customPath
            ?: slot.bundledDefaultUri(context.packageName)
            ?: return
        play(uri = source, clipEndMs = slot.limits.hardMaxMs, label = slot.key)
    }

    /**
     * Starts (or restarts) an explicit [uri], clipped at [clipEndMs]. The form GameBoot uses:
     * its sound is not a user-assignable slot, so there is no [UiMediaSlot] to carry the cap or
     * the log [label].
     */
    fun play(uri: String, clipEndMs: Long, label: String) {
        stop()
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
            playWhenReady = true
            prepare()
        }
        _isPlaying.value = true
        Timber.d("$label audio playing: $uri")
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
            Timber.w(error, "UI-media audio playback failed")
            _isPlaying.value = false
        }
    }
}