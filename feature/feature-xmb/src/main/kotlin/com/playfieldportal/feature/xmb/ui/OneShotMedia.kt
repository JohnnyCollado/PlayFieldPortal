package com.playfieldportal.feature.xmb.ui

import android.graphics.Matrix
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import timber.log.Timber

/**
 * The one-shot presentation players shared by the Boot Sequence and GameBoot overlays.
 *
 * Modelled on [Icon1VideoOverlay], not on MotionWallpaperBackground: these play once and stop.
 * TextureView (not SurfaceView) so the overlay's fade actually composites; `REPEAT_MODE_OFF`;
 * released — never merely paused — on dispose, so the decoder is gone before the emulator or the
 * XMB takes the screen.
 *
 * **Audio is ENABLED here.** That is the one deliberate divergence from this repo's house rule of
 * `setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)` on every player: a boot/GameBoot presentation
 * IS its sound. Do not "fix" it. Everything else that plays video in PFP (icon snaps, motion
 * wallpapers) stays muted.
 */
@Composable
internal fun OneShotVideoLayer(
    path: String,
    clipEndMs: Long,
    onEnded: () -> Unit,
    onFailed: () -> Unit,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
) {
    val context = LocalContext.current
    val currentEnded by rememberUpdatedState(onEnded)
    val currentFailed by rememberUpdatedState(onFailed)
    var videoSize by remember(path) { mutableStateOf<VideoSize?>(null) }

    val player = remember(path) {
        ExoPlayer.Builder(context).build().apply {
            if (muted) volume = 0f
            setMediaItem(
                MediaItem.Builder()
                    .setUri(path)
                    .setClippingConfiguration(
                        // A second belt behind the import gate: even a file that somehow got past
                        // the duration cap cannot hold the screen longer than the slot allows.
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
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) { videoSize = size }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) currentEnded()
            }
            override fun onPlayerError(error: PlaybackException) {
                // A file that is gone or won't decode must not leave a black screen: the caller
                // falls back to the built-in presentation.
                Timber.w(error, "One-shot video failed: $path")
                currentFailed()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { view ->
                player.setVideoTextureView(view)
                view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                    applyFullscreenCenterCrop(v as TextureView, videoSize)
                }
            }
        },
        update = { view -> applyFullscreenCenterCrop(view, videoSize) },
        modifier = modifier,
    )
}

/**
 * A one-shot audio track played alongside (and independently of) [OneShotVideoLayer]. Its own
 * player, so one component failing never stops the other — the four default/custom combinations
 * of video and audio all have to work.
 *
 * Deliberately NOT SoundPool: up to 10 seconds of boot music is not a UI sonification, and it must
 * stay audible with menu sounds muted.
 */
@Composable
internal fun OneShotAudioLayer(
    path: String,
    clipEndMs: Long,
    onFinished: () -> Unit = {},
) {
    val context = LocalContext.current
    val currentFinished by rememberUpdatedState(onFinished)

    val player = remember(path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(
                MediaItem.Builder()
                    .setUri(path)
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
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) currentFinished()
            }
            override fun onPlayerError(error: PlaybackException) {
                Timber.w(error, "One-shot audio failed: $path")
                currentFinished()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
}

/** Fills the screen at the clip's own aspect, centered; the overflow is clipped by the overlay. */
private fun applyFullscreenCenterCrop(view: TextureView, size: VideoSize?) {
    val vw = size?.width?.toFloat() ?: return
    val vh = size.height.toFloat()
    if (vw <= 0f || vh <= 0f || view.width == 0 || view.height == 0) return
    val viewW = view.width.toFloat()
    val viewH = view.height.toFloat()
    val scale = maxOf(viewW / vw, viewH / vh)
    view.setTransform(
        Matrix().apply {
            setScale((vw * scale) / viewW, (vh * scale) / viewH, viewW / 2f, viewH / 2f)
        }
    )
}
