package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import com.playfieldportal.core.ui.wave.WaveStyle
import com.playfieldportal.themekit.UiMediaLimits
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

// The overlay's own fade-out after the presentation ends (ms).
private const val TITLE_FADE_MS = 500

/**
 * The short PSP-style presentation between confirming a game and the emulator taking the screen.
 *
 * Same shape as [BootSequenceOverlay] — one-shot players released on dispose — but with a tighter
 * budget: the emulator is waiting. Two properties matter more here than anywhere else in the app:
 *
 *  • **It always ends.** [HARD_CAP_MS] completes the presentation regardless of player state, and
 *    [GameBootGate] has its own, slightly longer, timeout behind this one. A stuck presentation
 *    costs seconds, never the launch.
 *  • **It only draws — the audio is not owned here.** The gate (or, for a settings preview, the
 *    XMB ViewModel) starts the GameBoot sound through
 *    [com.playfieldportal.core.ui.media.UiMediaAudioPlayer] before this overlay composes, so the
 *    sound is already running when the first frame lands and this composable can never release it
 *    mid-clip. The only audio it ever plays itself is a custom VIDEO's own track; that one ends
 *    with the clip, never mid-note.
 *
 * Audio does NOT go through MenuSoundPlayer, so muting menu sounds cannot silence it. That is the
 * design's "mute must not silence GameBoot" rule satisfied structurally rather than by a check.
 *
 * GameBoot is ONE thing with one switch. With no custom video, [GameBootSequence] draws the
 * built-in PSP-style light sweep (motion-budget aware via [waveStyle]) against the bundled launch
 * sound; assign a video and it replaces the whole presentation, sound included. The gate has
 * already decided which of the two is playing by the time this composes, so there is no fallback
 * branch here.
 */
@Composable
fun GameBootOverlay(
    gameTitle: String,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    videoPath: String? = null,
    audioPath: String? = null,
    waveStyle: WaveStyle = WaveStyle.ANIMATED,
) {
    val currentComplete by rememberUpdatedState(onComplete)
    val completed = remember { AtomicBoolean(false) }
    val overlayAlpha = remember { Animatable(1f) }

    var presentationDone by remember { mutableStateOf(false) }
    var useTitleCard by remember(videoPath) { mutableStateOf(videoPath == null) }

    // The watchdog is sized to whichever presentation is actually playing: the built-in sequence
    // is a fixed 5 s and keeps its tight bound, while a user clip may run to
    // UiMediaLimits.GAMEBOOT_CLIP_MAX_MS. One cap for both would either cut long clips off or let
    // a stalled sequence sit for twice as long as it can possibly need.
    val hardCapMs = if (videoPath != null) VIDEO_HARD_CAP_MS else SEQUENCE_HARD_CAP_MS

    LaunchedEffect(Unit) {
        val endedNaturally = withTimeoutOrNull(hardCapMs) {
            snapshotFlow { presentationDone }.first { it }
        } != null
        if (endedNaturally) {
            overlayAlpha.animateTo(0f, animationSpec = tween(TITLE_FADE_MS))
        } else {
            Timber.w("GameBoot watchdog fired after ${hardCapMs}ms — continuing to the emulator")
        }
        if (completed.compareAndSet(false, true)) currentComplete()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .alpha(overlayAlpha.value),
    ) {
        if (videoPath != null && !useTitleCard) {
            OneShotVideoLayer(
                path = videoPath,
                clipEndMs = UiMediaLimits.GAMEBOOT_CLIP_MAX_MS,
                onEnded = { presentationDone = true },
                // A clip that will not decode must not delay the launch: fall through to the
                // default flash, which ends on its own timer.
                onFailed = { useTitleCard = true },
                muted = audioPath != null,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // No custom video: the built-in PSP-style light sweep, beat-matched to the bundled
            // launch sound. It reports back when its timeline has run its course — always the
            // full length, whatever the motion budget does to the drawing.
            GameBootSequence(
                gameTitle = gameTitle,
                waveStyle = waveStyle,
                onFinished = { presentationDone = true },
                modifier = Modifier.fillMaxSize(),
            )
        }

    }
}

/**
 * Hard cap on the built-in sequence — its fixed 5 s plus the fade, with room for a slow first
 * frame. Deliberately shorter than [com.playfieldportal.feature.launcher.GameBootGate.TIMEOUT_MS]
 * so the overlay normally resolves itself and the gate's watchdog stays the last resort.
 */
private const val SEQUENCE_HARD_CAP_MS = 6_500L

/**
 * Hard cap on a user's clip — [UiMediaLimits.GAMEBOOT_CLIP_MAX_MS] (the import ceiling, which the
 * player also clips to) plus the fade and first-frame slack. The clip path is the only one that
 * can genuinely stall, which is why it gets the long cap and the sequence does not.
 */
private const val VIDEO_HARD_CAP_MS = 11_500L
