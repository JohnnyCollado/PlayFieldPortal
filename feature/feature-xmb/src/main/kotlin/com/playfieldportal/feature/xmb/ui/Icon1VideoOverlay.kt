package com.playfieldportal.feature.xmb.ui

import android.graphics.Matrix
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer

/**
 * ICON1.PMF revival — plays a game's video snap inside the 144:80 icon slot, over the static
 * ICON0 (which stays composed underneath as the poster frame).
 *
 * Battery discipline (the linger gate, battery/thermal/saver checks, and the "one focused game
 * only" rule live in XMBViewModel — by the time this composes, playback has been approved):
 *  • ONE player per overlay, and only one overlay ever exists (the focused game's).
 *  • Audio is disabled at the track level — the audio track is never selected, never decoded.
 *  • A hard clip at [MAX_PLAY_MS] stops the decoder itself; when it ends we fade back to the
 *    static ICON0 and do NOT loop (a PMF that ran its course).
 *  • Released (not paused) the moment focus moves — DisposableEffect onDispose.
 *
 * Rendering: TextureView (not SurfaceView) so the fade-in/out alpha actually composites, with
 * a center-crop matrix so the (usually 4:3) snap fills the 144:80 tile like ICON1 did.
 */
@Composable
fun Icon1VideoOverlay(
    videoUri: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var firstFrameRendered by remember(videoUri) { mutableStateOf(false) }
    var ended by remember(videoUri) { mutableStateOf(false) }
    var videoSize by remember(videoUri) { mutableStateOf<VideoSize?>(null) }

    val player = remember(videoUri) {
        ExoPlayer.Builder(context).build().apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            volume = 0f
            setMediaItem(
                MediaItem.Builder()
                    .setUri(videoUri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setEndPositionMs(MAX_PLAY_MS)
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
            override fun onRenderedFirstFrame() { firstFrameRendered = true }
            override fun onVideoSizeChanged(size: VideoSize) { videoSize = size }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) ended = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Static ICON0 shows until the first frame lands (no flicker); fade back when the clip ends.
    val alpha by animateFloatAsState(
        targetValue = if (firstFrameRendered && !ended) 1f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "icon1Fade",
    )

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { view ->
                player.setVideoTextureView(view)
                view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                    applyCenterCrop(v as TextureView, videoSize)
                }
            }
        },
        update = { view -> applyCenterCrop(view, videoSize) },
        modifier = modifier.graphicsLayer { this.alpha = alpha },
    )
}

// TextureView stretches the frame to its bounds by default; this rescales so the snap fills
// the tile at its own aspect, centered — overflow is clipped by the caller's tile shape.
private fun applyCenterCrop(view: TextureView, size: VideoSize?) {
    val vw = size?.width?.toFloat() ?: return
    val vh = size.height.toFloat()
    if (vw <= 0f || vh <= 0f || view.width == 0 || view.height == 0) return
    val viewW = view.width.toFloat()
    val viewH = view.height.toFloat()
    val scale = maxOf(viewW / vw, viewH / vh)
    val matrix = Matrix().apply {
        setScale((vw * scale) / viewW, (vh * scale) / viewH, viewW / 2f, viewH / 2f)
    }
    view.setTransform(matrix)
}

// 60 s hard cap — "only play the first 60 seconds", enforced in the media pipeline itself.
private const val MAX_PLAY_MS = 60_000L
