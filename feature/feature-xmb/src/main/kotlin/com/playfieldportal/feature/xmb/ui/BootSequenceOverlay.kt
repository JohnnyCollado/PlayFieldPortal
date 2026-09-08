package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import com.playfieldportal.feature.xmb.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.ui.wave.WaveStyle
import com.playfieldportal.themekit.UiMediaLimits
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

// Boot sequence timing constants (ms)
private const val FADE_IN_MS  = 800
private const val HOLD_MS     = 1_400L
private const val FADE_OUT_MS = 600

/**
 * Hard cap on the WHOLE presentation, watchdog-enforced. The import gate already caps a boot clip
 * at [UiMediaLimits.BOOT_MAX_MS] (10 s) and the player clips to it again — this is the third and
 * last line: whatever the players are doing, boot ends and the launcher appears.
 */
private const val HARD_CAP_MS = 12_000L

/**
 * The PSP-style startup presentation, optionally replaced by the user's own video and/or audio.
 *
 * **Historical note.** This file used to carry a rule that boot must NEVER play the user's video —
 * boot being the most contended moment on the device. The project owner reversed that decision;
 * custom boot media is now a supported feature. The reversal is only safe because of the guard
 * rails that replaced the rule, and it is off again if any of them is removed:
 *
 *  1. The import gate caps a boot clip at 10 s and 25 MB, and rejects a file whose duration cannot
 *     be read at all (a file we cannot time is a file we cannot bound).
 *  2. The media item is clipped to the same cap in the pipeline, so the decoder itself stops.
 *  3. [HARD_CAP_MS] is a watchdog that does not consult the players: on timeout the boot completes
 *     regardless of what any of them is doing.
 *  4. A player error completes the presentation immediately; a video that will not decode falls
 *     back to the built-in logo animation rather than to a black screen.
 *  5. [onComplete] fires EXACTLY once across natural end, skip, error, and timeout.
 *
 * Boot still does not play the user's MOTION WALLPAPER — that restriction is unrelated to this
 * reversal and stays. [XmbBackground] is composed without wallpaper args on purpose.
 *
 * The four combinations (default/custom × video/audio) all work. [bootVideoPath] null means the
 * built-in logo animation. [bootAudioPath] is what XMBViewModel resolved via `resolveBootAudio`:
 * the user's boot sound, else the bundled opening chime ONLY when there is no custom video, else
 * null (a custom video keeps its own audio track). So a non-null [bootAudioPath] still means
 * "mute the clip and play this instead" — the ViewModel guarantees it is never the bundled chime
 * stacked under a custom video. There is no bundled boot .mp4 and none should be added.
 */
@Composable
fun BootSequenceOverlay(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    bootVideoPath: String? = null,
    bootAudioPath: String? = null,
) {
    val logoAlpha    = remember { Animatable(0f) }
    val logoScale    = remember { Animatable(0.92f) }
    val overlayAlpha = remember { Animatable(1f) }

    val currentComplete by rememberUpdatedState(onComplete)
    // Exactly-once across the four ways this can end.
    val completed = remember { AtomicBoolean(false) }

    // Set by whichever component owns the timeline: the logo animation, or the custom video.
    var presentationDone by remember { mutableStateOf(false) }
    // A custom video that fails to decode hands the timeline back to the logo animation.
    var useLogoAnimation by remember(bootVideoPath) { mutableStateOf(bootVideoPath == null) }

    LaunchedEffect(Unit) {
        val endedNaturally = withTimeoutOrNull(HARD_CAP_MS) {
            snapshotFlow { presentationDone }.first { it }
        } != null
        if (endedNaturally) {
            // Dissolve the overlay → the identical XMB background is revealed beneath.
            overlayAlpha.animateTo(0f, animationSpec = tween(FADE_OUT_MS))
        } else {
            Timber.w("Boot watchdog fired after ${HARD_CAP_MS}ms — completing boot regardless")
        }
        if (completed.compareAndSet(false, true)) currentComplete()
    }

    // The built-in presentation: logo eases in over the wave, holds, then the timeline ends.
    LaunchedEffect(useLogoAnimation) {
        if (!useLogoAnimation) return@LaunchedEffect
        logoScale.animateTo(1f, animationSpec = tween(FADE_IN_MS))
        logoAlpha.animateTo(1f, animationSpec = tween(FADE_IN_MS))
        delay(HOLD_MS)
        presentationDone = true
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(overlayAlpha.value),
        contentAlignment = Alignment.Center,
    ) {
        if (bootVideoPath != null && !useLogoAnimation) {
            // The user's clip owns the whole screen. Black underneath so letterboxing (there
            // should be none — the layer centre-crops) never shows the wave through a seam.
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                OneShotVideoLayer(
                    path = bootVideoPath,
                    clipEndMs = UiMediaLimits.BOOT_MAX_MS,
                    onEnded = { presentationDone = true },
                    onFailed = {
                        // Fall back to the built-in animation rather than a black screen.
                        useLogoAnimation = true
                    },
                    // A custom boot SOUND replaces the clip's own audio; without one the clip
                    // keeps its track. Two audio sources at once is never what the user meant.
                    muted = bootAudioPath != null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            // Same background the XMB uses — the classic blue "Original" gradient with the soft
            // wave folds, tinted by whatever theme is active (LocalPFPColors), so boot and menu
            // are visually identical. Deliberately no wallpaper args: see the KDoc.
            XmbBackground(waveStyle = WaveStyle.ANIMATED, modifier = Modifier.fillMaxSize())

            // PFP logo (transparent mark, sits on the wave).
            Image(
                painter = painterResource(R.drawable.pfp_boot_logo),
                contentDescription = "Play Field Portal",
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(220.dp)
                    .graphicsLayer {
                        alpha = logoAlpha.value
                        scaleX = logoScale.value
                        scaleY = logoScale.value
                    },
            )

            // Subtitle
            Text(
                text = "Play Field Portal",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 4.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
                    .alpha(logoAlpha.value),
            )
        }

        // Independent of the video: one failing must not stop the other. Composed inside the same
        // overlay so it is released the moment boot leaves composition.
        if (bootAudioPath != null) {
            OneShotAudioLayer(path = bootAudioPath, clipEndMs = UiMediaLimits.BOOT_MAX_MS)
        }
    }
}
