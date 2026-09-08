package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.themekit.UiMediaLimits
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

// Default (no custom media) presentation timing (ms).
private const val TITLE_FADE_MS = 500
private const val TITLE_HOLD_MS = 900L

/**
 * The short PS3-style presentation between confirming a game and the emulator taking the screen.
 *
 * Same shape as [BootSequenceOverlay] — one-shot players, audio ENABLED, released on dispose — but
 * with a tighter budget: the emulator is waiting. Two properties matter more here than anywhere
 * else in the app:
 *
 *  • **It always ends.** [HARD_CAP_MS] completes the presentation regardless of player state, and
 *    [GameBootGate] has its own, slightly longer, timeout behind this one. A stuck presentation
 *    costs seconds, never the launch.
 *  • **Playback stops before the emulator gets the screen.** The players are released in
 *    `onDispose`, which runs on the frame this overlay leaves composition — before `startActivity`
 *    resumes — so no audio ever bleeds into the game.
 *
 * Audio does NOT go through MenuSoundPlayer, so muting menu sounds cannot silence it. That is the
 * design's "mute must not silence GameBoot" rule satisfied structurally rather than by a check.
 */
@Composable
fun GameBootOverlay(
    gameTitle: String,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    videoPath: String? = null,
    audioPath: String? = null,
) {
    val currentComplete by rememberUpdatedState(onComplete)
    val completed = remember { AtomicBoolean(false) }
    val overlayAlpha = remember { Animatable(1f) }
    val titleAlpha = remember { Animatable(0f) }

    var presentationDone by remember { mutableStateOf(false) }
    var useTitleCard by remember(videoPath) { mutableStateOf(videoPath == null) }

    LaunchedEffect(Unit) {
        val endedNaturally = withTimeoutOrNull(HARD_CAP_MS) {
            snapshotFlow { presentationDone }.first { it }
        } != null
        if (endedNaturally) {
            overlayAlpha.animateTo(0f, animationSpec = tween(TITLE_FADE_MS))
        } else {
            Timber.w("GameBoot watchdog fired after ${HARD_CAP_MS}ms — continuing to the emulator")
        }
        if (completed.compareAndSet(false, true)) currentComplete()
    }

    // The built-in presentation when the user has assigned no video: the game's name over black.
    // There is no bundled GameBoot clip and none should be added.
    LaunchedEffect(useTitleCard) {
        if (!useTitleCard) return@LaunchedEffect
        titleAlpha.animateTo(1f, animationSpec = tween(TITLE_FADE_MS))
        delay(TITLE_HOLD_MS)
        presentationDone = true
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .alpha(overlayAlpha.value),
        contentAlignment = Alignment.Center,
    ) {
        if (videoPath != null && !useTitleCard) {
            OneShotVideoLayer(
                path = videoPath,
                clipEndMs = UiMediaLimits.GAMEBOOT_MAX_MS,
                onEnded = { presentationDone = true },
                // A clip that will not decode must not delay the launch: fall through to the
                // title card, which ends on its own timer.
                onFailed = { useTitleCard = true },
                muted = audioPath != null,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = gameTitle,
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 24.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 2.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 48.dp)
                    .alpha(titleAlpha.value),
            )
        }

        if (audioPath != null) {
            OneShotAudioLayer(path = audioPath, clipEndMs = UiMediaLimits.GAMEBOOT_MAX_MS)
        }
    }
}

/**
 * Hard cap on the presentation — the 5 s media cap plus the fade. Deliberately shorter than
 * [com.playfieldportal.feature.launcher.GameBootGate.TIMEOUT_MS] so the overlay normally resolves
 * itself and the gate's watchdog stays the last resort.
 */
private const val HARD_CAP_MS = 6_000L
