package com.playfieldportal.feature.xmb.video

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.theme.menuCursor
import com.playfieldportal.core.ui.theme.menuCursorEdge
import com.playfieldportal.core.domain.model.Video
import kotlinx.coroutines.delay
import timber.log.Timber

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val SCREEN_MODES = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "Fill",
)
private const val SEEK_STEP_MS = 10_000L
private const val CONTROLS_TIMEOUT_MS = 3_500L

/**
 * Built-in fullscreen video player (Media3 ExoPlayer). Fully controller-driven — no touch required:
 *  A = play/pause · B = back (saves resume) · ◀/▶ = seek ∓10s · L1/R1 = prev/next · Y = options
 *  (speed / subtitle / audio / screen mode). Options is a controller-navigable overlay so nothing
 *  ever traps focus. The player is released and the resume position saved on exit.
 */
@UnstableApi
@Composable
fun VideoPlayerScreen(
    videos: List<Video>,
    startIndex: Int,
    startPositionMs: Long,
    onSaveResume: (videoId: String, positionMs: Long, durationMs: Long) -> Unit,
    onExit: () -> Unit,
    pendingGamepadAction: GamepadAction?,
    onGamepadActionConsumed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (videos.isEmpty()) { onExit(); return }
    val context = androidx.compose.ui.platform.LocalContext.current

    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, videos.lastIndex)) }
    val current = videos[index]

    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var speedIndex by remember { mutableIntStateOf(SPEEDS.indexOf(1f)) }
    var screenModeIndex by remember { mutableIntStateOf(0) }
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsPoke by remember { mutableIntStateOf(0) }
    var optionsOpen by remember { mutableStateOf(false) }
    var optionsRow by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // The very first video seeks to the requested resume position; later prev/next start at 0.
    val initialSeek = remember { startPositionMs }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) durationMs = duration.coerceAtLeast(0L)
                }
                override fun onPlayerError(error: PlaybackException) {
                    Timber.w(error, "Playback error for ${current.uri}")
                    errorMessage = when (error.errorCode) {
                        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Video not found."
                        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "Permission denied for this file."
                        PlaybackException.ERROR_CODE_DECODING_FAILED,
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "This video can't be decoded on this device."
                        else -> "Unable to play this video."
                    }
                }
            })
        }
    }

    // Load whichever video is current. Validates the URI up front so a bad file shows a friendly
    // error instead of crashing. The first load honours the resume position.
    LaunchedEffect(index) {
        errorMessage = null
        val uri = runCatching { Uri.parse(current.uri) }.getOrNull()
        if (uri == null) { errorMessage = "Video not found."; return@LaunchedEffect }
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        val seek = if (index == startIndex) initialSeek else 0L
        if (seek > 0L) player.seekTo(seek)
        player.playWhenReady = true
    }

    // Poll the position while playing so the scrubber/label stay live.
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    // Auto-hide the controls a few seconds after the last interaction (unless the options menu is up).
    LaunchedEffect(controlsPoke, optionsOpen) {
        if (optionsOpen) { controlsVisible = true; return@LaunchedEffect }
        controlsVisible = true
        delay(CONTROLS_TIMEOUT_MS)
        controlsVisible = false
    }

    // Persist resume position on dispose (back-out or process teardown) and release the player.
    val currentRef by rememberUpdatedState(current)
    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                onSaveResume(currentRef.id, player.currentPosition.coerceAtLeast(0L), player.duration.coerceAtLeast(0L))
                player.release()
            }
        }
    }

    fun poke() { controlsPoke++ }
    fun switchTo(newIndex: Int) {
        if (newIndex !in videos.indices) return
        // Save the outgoing video's position before moving on.
        onSaveResume(current.id, player.currentPosition.coerceAtLeast(0L), player.duration.coerceAtLeast(0L))
        index = newIndex
        poke()
    }

    // ── Controller handling ───────────────────────────────────────────────────
    LaunchedEffect(pendingGamepadAction) {
        val action = pendingGamepadAction ?: return@LaunchedEffect
        if (optionsOpen) {
            when (action) {
                GamepadAction.NAVIGATE_UP   -> optionsRow = (optionsRow - 1 + OPTION_COUNT) % OPTION_COUNT
                GamepadAction.NAVIGATE_DOWN -> optionsRow = (optionsRow + 1) % OPTION_COUNT
                GamepadAction.SELECT, GamepadAction.NAVIGATE_RIGHT -> when (optionsRow) {
                    0 -> { speedIndex = (speedIndex + 1) % SPEEDS.size; player.playbackParameters = PlaybackParameters(SPEEDS[speedIndex]) }
                    1 -> cycleTrack(player, C.TRACK_TYPE_TEXT, allowOff = true)
                    2 -> cycleTrack(player, C.TRACK_TYPE_AUDIO, allowOff = false)
                    3 -> screenModeIndex = (screenModeIndex + 1) % SCREEN_MODES.size
                }
                GamepadAction.BACK, GamepadAction.BUTTON_Y -> optionsOpen = false
                else -> Unit
            }
            onGamepadActionConsumed(); return@LaunchedEffect
        }
        when (action) {
            GamepadAction.SELECT -> {
                if (errorMessage != null) { onExit() }
                else { if (player.isPlaying) player.pause() else player.play(); poke() }
            }
            GamepadAction.BACK -> onExit()
            GamepadAction.NAVIGATE_LEFT -> { player.seekTo((player.currentPosition - SEEK_STEP_MS).coerceAtLeast(0L)); poke() }
            GamepadAction.NAVIGATE_RIGHT -> { player.seekTo(player.currentPosition + SEEK_STEP_MS); poke() }
            GamepadAction.NAVIGATE_UP, GamepadAction.NAVIGATE_DOWN -> poke()
            GamepadAction.PREV_CATEGORY -> switchTo(index - 1)
            GamepadAction.NEXT_CATEGORY -> switchTo(index + 1)
            GamepadAction.BUTTON_Y, GamepadAction.LONG_PRESS -> { optionsOpen = true; optionsRow = 0 }
            else -> Unit
        }
        onGamepadActionConsumed()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { poke() },
    ) {
        if (errorMessage == null) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        useController = false
                        this.player = player
                        resizeMode = SCREEN_MODES[screenModeIndex].first
                    }
                },
                update = { it.resizeMode = SCREEN_MODES[screenModeIndex].first },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(errorMessage!!, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("Press A or B to go back", color = Color(0xFFB0B0B0), fontSize = 13.sp)
                }
            }
        }

        if (controlsVisible && errorMessage == null) {
            ControlsOverlay(
                title = current.displayTitle,
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                speed = SPEEDS[speedIndex],
                screenMode = SCREEN_MODES[screenModeIndex].second,
                hasPrev = index > 0,
                hasNext = index < videos.lastIndex,
            )
        }

        if (optionsOpen && errorMessage == null) {
            OptionsOverlay(
                selectedRow = optionsRow,
                speed = SPEEDS[speedIndex],
                subtitleLabel = currentTrackLabel(player, C.TRACK_TYPE_TEXT),
                audioLabel = currentTrackLabel(player, C.TRACK_TYPE_AUDIO),
                screenMode = SCREEN_MODES[screenModeIndex].second,
            )
        }
    }
}

private const val OPTION_COUNT = 4

@Composable
private fun ControlsOverlay(
    title: String,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    speed: Float,
    screenMode: String,
    hasPrev: Boolean,
    hasNext: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)

        Column {
            // Scrubber
            val frac = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color(0x55FFFFFF), RoundedCornerShape(2.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(frac)
                        .height(4.dp)
                        .background(Color(0xFF4A90D9), RoundedCornerShape(2.dp)),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${fmt(positionMs)} / ${fmt(durationMs)}", color = Color.White, fontSize = 13.sp)
                Text(
                    buildString {
                        append(if (isPlaying) "❚❚  A Pause" else "▶  A Play")
                        append("   ·   ◀▶ Seek")
                        if (hasPrev || hasNext) append("   ·   L1/R1 Prev/Next")
                        append("   ·   Y Options")
                        append("   ·   ${speed}× · $screenMode")
                    },
                    color = Color(0xFFCCCCCC),
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun OptionsOverlay(
    selectedRow: Int,
    speed: Float,
    subtitleLabel: String,
    audioLabel: String,
    screenMode: String,
) {
    val rows = listOf(
        "Playback Speed" to "${speed}×",
        "Subtitles" to subtitleLabel,
        "Audio Track" to audioLabel,
        "Screen Mode" to screenMode,
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(
            modifier = Modifier
                .padding(40.dp)
                .width(320.dp)
                .background(Color(0xF0101018), RoundedCornerShape(14.dp))
                .padding(vertical = 16.dp),
        ) {
            Text(
                "Options",
                color = menuCursorEdge(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            rows.forEachIndexed { i, (label, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuCursor(i == selectedRow)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(label, color = Color.White, fontSize = 15.sp)
                    Text(value, color = menuCursorEdge(), fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "A/▶ change · Y/B close",
                color = Color(0xFF888888),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
    }
}

// ── Track selection helpers ──────────────────────────────────────────────────

@UnstableApi
private fun cycleTrack(player: Player, trackType: Int, allowOff: Boolean) {
    val groups = player.currentTracks.groups.filter { it.type == trackType && it.isSupported }
    if (groups.isEmpty()) return
    // Build the ordered choices: [off?] + one entry per (group, trackIndex).
    val choices = buildList {
        if (allowOff) add(null)
        groups.forEach { g -> for (t in 0 until g.length) if (g.isTrackSupported(t)) add(g to t) }
    }
    if (choices.isEmpty()) return
    // Find the currently-selected choice.
    val currentIdx = choices.indexOfFirst { choice ->
        choice != null && choice.first.isTrackSelected(choice.second)
    }.let { if (it < 0 && allowOff) 0 else it }
    val next = choices[(currentIdx + 1).mod(choices.size)]
    val params = player.trackSelectionParameters.buildUpon()
    if (next == null) {
        params.setTrackTypeDisabled(trackType, true)
    } else {
        params.setTrackTypeDisabled(trackType, false)
        params.setOverrideForType(TrackSelectionOverride(next.first.mediaTrackGroup, next.second))
    }
    player.trackSelectionParameters = params.build()
}

@UnstableApi
private fun currentTrackLabel(player: Player, trackType: Int): String {
    val groups = player.currentTracks.groups.filter { it.type == trackType && it.isSupported }
    if (groups.isEmpty()) return if (trackType == C.TRACK_TYPE_TEXT) "None" else "Default"
    val selected = groups.flatMap { g -> (0 until g.length).mapNotNull { t -> if (g.isTrackSelected(t)) g.getTrackFormat(t) else null } }
        .firstOrNull()
    return when {
        selected == null && trackType == C.TRACK_TYPE_TEXT -> "Off"
        selected == null -> "Default"
        else -> selected.language?.uppercase() ?: selected.label ?: "Track"
    }
}

private fun fmt(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
