package com.playfieldportal.feature.xmb.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs

/**
 * C16 task 6.6 — the crop editor's moving picture.
 *
 * ICON1 and VIDEO are cropped as video: [ArtworkStudioViewModel.beginCrop] extracts one still
 * frame to frame against and keeps the clip in `cropVideoSourcePath`, and Apply re-encodes with
 * Media3 Transformer using the same crop rect. A still frame tells you almost nothing about where
 * the action sits in the clip, so for those two kinds the editor plays instead of freezing.
 *
 * The clip here is a **local temp file**, already whole — so unlike `StudioVideoTilePreview` this
 * needs no download fallback for non-seekable ScreenScraper streams. What it does need is two
 * surfaces (the full-screen canvas and the inset), and an ExoPlayer renders to one surface at a
 * time — hence two players over one file, kept together by [SyncClipTo].
 */

/**
 * A muted, looping player over the local clip at [path], released when the editor closes.
 *
 * Returns null once playback has failed, so the caller can fall back to the still frame rather
 * than leave a black rectangle where the artwork should be.
 */
@Composable
fun rememberCropClipPlayer(path: String): androidx.media3.exoplayer.ExoPlayer? {
    val context = LocalContext.current
    var failed by remember(path) { mutableStateOf(false) }
    val player = remember(path) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true)
                .build()
            volume = 0f
            setMediaItem(
                androidx.media3.common.MediaItem.fromUri(
                    android.net.Uri.fromFile(java.io.File(path))
                )
            )
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                timber.log.Timber.w(error, "Crop editor clip playback failed")
                failed = true
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    return player.takeIf { !failed }
}

/**
 * Nudges [follower] back onto [leader]'s position when the two drift apart.
 *
 * Two decoders over one file start together and then wander, and two views of the same clip
 * showing different moments reads as a bug rather than as a preview. A half-second tick is far
 * cheaper than the seek it usually avoids, and [DRIFT_TOLERANCE_MS] is wide enough that a seek
 * fires rarely — seeking every tick would stutter the very motion this is here to show.
 */
@Composable
fun SyncClipTo(
    leader: androidx.media3.exoplayer.ExoPlayer?,
    follower: androidx.media3.exoplayer.ExoPlayer?,
) {
    if (leader == null || follower == null) return
    LaunchedEffect(leader, follower) {
        while (true) {
            kotlinx.coroutines.delay(SYNC_TICK_MS)
            val drift = leader.currentPosition - follower.currentPosition
            if (abs(drift) > DRIFT_TOLERANCE_MS) follower.seekTo(leader.currentPosition)
        }
    }
}

private const val SYNC_TICK_MS = 500L
private const val DRIFT_TOLERANCE_MS = 150L

/**
 * The video itself, stretched to fill whatever box [modifier] gives it.
 *
 * Callers size that box to the clip's own display aspect — the crop editor derives it from
 * `cropSrcW`/`cropSrcH`, which come off a frame of this very clip — so "stretch to fill" and
 * "preserve aspect" are the same thing here, and no matrix is needed.
 */
@Composable
fun CropVideoSurface(
    player: androidx.media3.exoplayer.ExoPlayer,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { ctx -> android.view.TextureView(ctx).also(player::setVideoTextureView) },
        update = { view -> player.setVideoTextureView(view) },
        modifier = modifier,
    )
}
