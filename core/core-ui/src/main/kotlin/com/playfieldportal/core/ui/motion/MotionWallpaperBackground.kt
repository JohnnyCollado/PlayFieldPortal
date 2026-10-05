package com.playfieldportal.core.ui.motion

import android.view.SurfaceView
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
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
import com.playfieldportal.themekit.MotionCrop
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * The motion-wallpaper layer: the poster still, and the looping video (or animated GIF/WebP) that
 * takes over from it once its first frame lands.
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
 *  • SurfaceView, not TextureView: decoded frames go straight to the system compositor instead
 *    of being drawn through the app's RenderThread every frame — most of a video theme's
 *    on-screen cost. The surface sits behind the window and shows through the hole the view
 *    punches in it, so everything else draws over it as before. Two consequences:
 *    - The poster is drawn ABOVE the video (it would be punched away beneath it, leaving black
 *      until the first frame) and fades OUT when the first frame lands — the same crossfade,
 *      run the other way.
 *    - A SurfaceView takes no transform matrix, so the crop is applied by sizing and placing the
 *      view itself ([motionSurfaceRect]) and letting the screen edges clip it. This relies on the
 *      wallpaper filling the screen, which both callers do.
 *  • The app-in-front gate is [rememberAppInFront], folded into the decision upstream, so
 *    backgrounding the launcher (every game launch) lands in POSTER and releases the player.
 */
@Composable
fun MotionWallpaperBackground(
    posterPath: String,
    motionPath: String,
    decision: MotionWallpaperPolicy.Decision,
    modifier: Modifier = Modifier,
    motionCrop: MotionCrop? = null,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (formatOf(motionPath)) {
            MotionFormat.ANIMATED_IMAGE -> {
                // Poster underneath: shows until the animation decodes, and remains if it fails.
                Poster(posterPath, Modifier.fillMaxSize())
                AnimatedImageSurface(motionPath)
            }
            // The video surface owns its poster: it has to sit ABOVE the video (see the KDoc).
            MotionFormat.VIDEO ->
                MotionVideoSurface(posterPath, motionPath, decision, cropForMotionPath(motionPath, motionCrop))
        }
    }
}

/**
 * The poster still. The request pins repeatCount(1): for stills it is a no-op, and if a poster path
 * ever pointed at an animated container the poster would hold its first frame rather than silently
 * run a second CPU decoder alongside the motion layer.
 */
@Composable
private fun Poster(posterPath: String, modifier: Modifier) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(posterPath)
            .repeatCount(1)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

/**
 * The looping video surface. Separated from the poster so its player state (remember keys,
 * listeners, dispose) lives in the smallest possible restart scope. Only real video reaches
 * this composable — animated images are routed to [AnimatedImageSurface] by the caller's
 * format switch.
 */
@Composable
private fun MotionVideoSurface(
    posterPath: String,
    motionPath: String,
    decision: MotionWallpaperPolicy.Decision,
    crop: MotionCrop?,
) {
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

    // The poster fades OUT over the video on the first frame (it is drawn above the surface; see
    // the KDoc). When the decision returns to POSTER the player is released and the caller shows
    // the poster: a hard cut is CORRECT there (an overlay just opened or the device just started
    // conserving — an animated exit would be the one thing still animating).
    val posterAlpha by animateFloatAsState(
        targetValue = if (firstFrameRendered) 0f else 1f,
        animationSpec = tween(durationMillis = 400),
        label = "motionWallpaperPosterFade",
    )

    AndroidView(
        factory = { ctx -> SurfaceView(ctx).also(player::setVideoSurfaceView) },
        onRelease = { view -> player.clearVideoSurfaceView(view) },
        modifier = Modifier.motionSurfacePlacement(videoSize, crop),
    )
    // Gone entirely once it has faded, so nothing is drawn over the video for the rest of its life.
    if (posterAlpha > 0f) {
        Poster(posterPath, Modifier.fillMaxSize().graphicsLayer { alpha = posterAlpha })
    }
}

/**
 * Lays the video's view out at [motionSurfaceRect] inside the screen-sized area: oversized and
 * offset as the crop needs, with the screen edges doing the clipping. Until the video size is known
 * the view simply fills the area (the poster covers it then anyway).
 */
private fun Modifier.motionSurfacePlacement(size: VideoSize?, crop: MotionCrop?): Modifier =
    layout { measurable, constraints ->
        val areaW = constraints.maxWidth
        val areaH = constraints.maxHeight
        val vw = size?.width ?: 0
        val vh = size?.height ?: 0
        val rect = if (vw > 0 && vh > 0 && areaW > 0 && areaH > 0) {
            motionSurfaceRect(areaW.toFloat(), areaH.toFloat(), vw.toFloat(), vh.toFloat(), crop)
        } else {
            MotionSurfaceRect(0f, 0f, areaW.toFloat(), areaH.toFloat())
        }
        val placeable = measurable.measure(
            Constraints.fixed(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1)),
        )
        layout(areaW, areaH) { placeable.place(rect.left.roundToInt(), rect.top.roundToInt()) }
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
 * arbitrary.) Animated Images then decides whether it plays (see [rememberMotionGate]).
 * If the animated decode fails outright, this layer renders nothing and the poster
 * beneath is simply what shows — the same degradation a corrupt video gets.
 */
@Composable
private fun AnimatedImageSurface(motionPath: String) {
    // Animated Images also governs the wallpaper: it counts as focused (it is the backdrop of
    // whatever is focused), so Animated and Reduced play it and Static holds its first frame.
    val gate = rememberMotionGate(focused = true)
    val context = LocalContext.current
    AsyncImage(
        model = remember(motionPath, gate, context) {
            ImageRequest.Builder(context)
                .data(motionPath)
                .repeatCount(MovieDrawable.REPEAT_INFINITE)
                .motionGate(gate)
                .build()
        },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().motionOnScreen(gate),
    )
}

private const val TAG = "MotionWallpaper"

/**
 * Folds "the launcher is in front" (ON_RESUME..ON_PAUSE) into the motion decision. The
 * [covered]/[throttled] inputs come from XMBShell's existing pipeline, but nothing there reacts to
 * the app leaving the front — the composition survives ON_STOP, and a launcher is backgrounded
 * constantly (every game launch). Missing this would leave a decoder running behind the emulator.
 * RESUMED rather than STARTED: a dialog-style app on top leaves the launcher visible but paused,
 * and nothing should animate behind it either.
 */
@Composable
fun rememberAppInFront(): Boolean {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var inFront by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> inFront = true
                Lifecycle.Event.ON_PAUSE -> inFront = false
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return inFront
}
