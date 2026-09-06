package com.playfieldportal.core.ui.motion

import android.graphics.Matrix
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import coil3.gif.MovieDrawable
import coil3.gif.repeatCount
import coil3.request.ImageRequest
import timber.log.Timber

/**
 * The motion-wallpaper layer: the poster still rendered underneath, with the looping video
 * (or animated GIF/WebP) composited above it once its first frame lands.
 *
 * Power discipline — this composable exists to implement one rule:
 *
 * > When the wave would be frozen, the motion wallpaper is not merely paused — no decoder exists.
 *
 *  • [decision] POSTER is honored by NOT composing this composable at all (the caller switches
 *    branches), so a frozen background holds neither a player nor a codec. The poster parameter
 *    is still rendered here for the PLAY paths, where it sits under the video until the first
 *    frame arrives (no black flash) and remains if decoding fails.
 *  • GIF/animated WebP never construct a player at all: they are a *separate composable*
 *    ([AnimatedImageSurface]), because ExoPlayer has no GIF extractor and a player built on one
 *    fails to sniff, logs a `Source error`, and holds a codec that can never render a frame.
 *  • ONE player, constructed per [motionPath] inside [MotionVideoSurface] — and only for real
 *    video — released — never paused — in [DisposableEffect.onDispose]. A paused ExoPlayer still
 *    holds a codec instance, a surface, and buffers.
 *  • Audio never decoded: the audio track is disabled at the track-selection level AND the
 *    volume is muted (the same two-belt approach as the ICON1 overlay). A wallpaper with sound
 *    would also fight the music player.
 *  • Loops forever (REPEAT_MODE_ALL) — a background loops by definition, which is why the
 *    import gate caps duration at 60 s.
 *  • TextureView, not SurfaceView: the layer sits under the whole Compose tree and must
 *    composite with the fade-in (a SurfaceView behind the window needs a punched-through hole
 *    in an opaque window and breaks the crossfade).
 *  • The app-visible gate is [rememberAppVisible], folded into the decision upstream, so
 *    backgrounding the launcher (every game launch) lands in POSTER and releases the player.
 */
@Composable
fun MotionWallpaperBackground(
    posterPath: String,
    motionPath: String,
    decision: MotionWallpaperPolicy.Decision,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Poster always composed underneath: shows until the first video frame lands, and is
        // simply what remains whenever the decoder goes away (freeze, failure, backgrounding).
        // The request pins repeatCount(1): for stills it is a no-op, and if a poster path ever
        // pointed at an animated container the poster would hold its first frame rather than
        // silently run a second CPU decoder behind the animation layer above.
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(posterPath)
                .repeatCount(1)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        when (formatOf(motionPath)) {
            MotionFormat.ANIMATED_IMAGE -> AnimatedImageSurface(motionPath)
            MotionFormat.VIDEO -> MotionVideoSurface(motionPath, decision)
        }
    }
}

/**
 * The looping video surface. Separated from the poster so its player state (remember keys,
 * listeners, dispose) lives in the smallest possible restart scope. Only real video reaches
 * this composable — animated images are routed to [AnimatedImageSurface] by the caller's
 * format switch.
 */
@Composable
private fun MotionVideoSurface(motionPath: String, decision: MotionWallpaperPolicy.Decision) {
    val context = LocalContext.current
    var firstFrameRendered by remember(motionPath) { mutableStateOf(false) }
    var videoSize by remember(motionPath) { mutableStateOf<VideoSize?>(null) }

    val player = remember(motionPath) {
        ExoPlayer.Builder(context).build().apply {
            // Audio is never selected and never decoded; volume stays 0f on principle.
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            volume = 0f
            setMediaItem(MediaItem.fromUri(motionPath))
            // A background loops by definition. (Icon1VideoOverlay deliberately does NOT loop;
            // this is the one place the rule diverges — and the reason the 60 s cap matters.)
            repeatMode = Player.REPEAT_MODE_ALL
            // REDUCED halves perceived motion (no frame-rate knob exists); the caller raises
            // the scrim to match the wave's dimming. POSTER never reaches this composable.
            setPlaybackSpeed(if (decision == MotionWallpaperPolicy.Decision.PLAY_REDUCED) 0.5f else 1f)
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrameRendered = true
                Timber.tag(TAG).d("motion first frame rendered (%s)", motionPath)
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                videoSize = size
            }
        }
        player.addListener(listener)
        onDispose {
            // Released, not paused — the mechanism that implements the governing rule.
            player.removeListener(listener)
            player.release()
        }
    }

    // Fade the video in over the poster on the first frame — the same pattern Icon1VideoOverlay
    // uses. When the decision returns to POSTER the player is released and the poster is what
    // remains: a hard cut is CORRECT there (an overlay just opened or the device just started
    // conserving — an animated exit would be the one thing still animating).
    val alpha by animateFloatAsState(
        targetValue = if (firstFrameRendered) 1f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "motionWallpaperFade",
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
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha },
    )
}

/**
 * The GIF/animated-WebP surface: a second AsyncImage over the poster loads the animated file
 * itself, decoded by Coil's AnimatedImageDecoder (registered on the app-wide ImageLoader in
 * feature-artwork). A sibling of [MotionVideoSurface], not a branch inside it — the split makes
 * "a GIF wallpaper constructs no ExoPlayer" structural: no code path even builds the player for
 * an animated image.
 *
 * Coil decodes animated images honoring the file's OWN repeat metadata by default — a GIF whose
 * loop flag is absent (or 0) renders its first frame and stops, i.e. an animated image with
 * repeatCount 1 is visually a still. The request therefore carries an explicit infinite repeat
 * count (forcing a fresh animated decode that actually loops — the count rides the memory-cache
 * key, so it is a distinct decode from any still request of the same file). The caller only
 * routes non-POSTER decisions into this background, so the request is unconditional. (REDUCED is
 * a no-op here: there is no frame-rate knob; the video's 0.5f playback-speed analogue would be
 * arbitrary.) If the animated decode fails outright, this layer renders nothing and the poster
 * beneath is simply what shows — the same degradation a corrupt video gets.
 */
@Composable
private fun AnimatedImageSurface(motionPath: String) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(motionPath)
            .repeatCount(MovieDrawable.REPEAT_INFINITE)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}

private const val TAG = "MotionWallpaper"

// TextureView stretches the frame to its bounds; this rescales to center-crop so the video fills
// the screen at its own aspect — matching the ContentScale.Crop poster underneath it, so the
// fade-in never visibly distorts the picture the poster established. (Same approach as
// Icon1VideoOverlay.)
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

/**
 * Folds the app's ON_START..ON_STOP window into the motion decision. The [covered]/[throttled]
 * inputs come from XMBShell's existing pipeline, but nothing there reacts to the app being
 * backgrounded — the composition survives ON_STOP, and a launcher is backgrounded constantly
 * (every game launch). Missing this would leave a decoder running behind the emulator.
 *
 * Uses the PLATFORM LocalLifecycleOwner: this Compose BOM doesn't provide the
 * androidx.lifecycle.compose variant (see XMBShell's note on the same workaround).
 */
@Composable
fun rememberAppVisible(): Boolean {
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var appVisible by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> appVisible = true
                Lifecycle.Event.ON_STOP -> appVisible = false
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return appVisible
}
