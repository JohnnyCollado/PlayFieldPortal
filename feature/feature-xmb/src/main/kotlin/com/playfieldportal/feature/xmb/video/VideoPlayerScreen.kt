package com.playfieldportal.feature.xmb.video

import android.net.Uri
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import com.playfieldportal.core.domain.model.Video
import com.playfieldportal.core.ui.components.ControllerPrompt
import com.playfieldportal.core.ui.components.ControllerPromptBar
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.core.ui.components.XmbHeaderPill
import com.playfieldportal.core.ui.components.XmbKebabTouchButton
import com.playfieldportal.core.ui.components.XmbMediaPillScrim
import com.playfieldportal.core.ui.theme.deriveStorefrontColors
import com.playfieldportal.core.ui.theme.menuCursor
import com.playfieldportal.core.ui.theme.menuCursorEdge
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
 * ever traps focus. The player is released and the resume position saved on exit.
 *
 * Touch mode mirrors that ladder rather than replacing it: a Back pill and the Options kebab in
 * the top corners (the touch counterparts of B and Y, as in the photo viewer), a transport row for
 * what the D-pad and face buttons do, and tappable Options rows — every controller action
 * reachable without a pad. [showTouchControls] follows the last input source, so the controls
 * appear for a finger and get out of the way for a stick.
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
    showTouchControls: Boolean = true,
    onTouchInput: () -> Unit = {},
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
    // A tap on the video toggles, where a controller press only pokes: with no pad to press, the
    // auto-hide timeout would otherwise be the only way back to an unobstructed frame.
    fun toggleControls() { if (controlsVisible) controlsVisible = false else poke() }
    fun seekBy(deltaMs: Long) {
        player.seekTo((player.currentPosition + deltaMs).coerceAtLeast(0L))
        poke()
    }
    fun playPause() { if (player.isPlaying) player.pause() else player.play(); poke() }
    // What each Options row does when activated. Extracted so the pad's SELECT and a tap on
    // the row run the same code - the touch path cannot drift from the controller path.
    fun applyOption(row: Int) {
        when (row) {
            0 -> { speedIndex = (speedIndex + 1) % SPEEDS.size; player.playbackParameters = PlaybackParameters(SPEEDS[speedIndex]) }
            1 -> cycleTrack(player, C.TRACK_TYPE_TEXT, allowOff = true)
            2 -> cycleTrack(player, C.TRACK_TYPE_AUDIO, allowOff = false)
            3 -> screenModeIndex = (screenModeIndex + 1) % SCREEN_MODES.size
        }
    }
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
                GamepadAction.SELECT, GamepadAction.NAVIGATE_RIGHT -> applyOption(optionsRow)
                GamepadAction.BACK, GamepadAction.OPEN_CONTEXT_MENU -> optionsOpen = false
                else -> Unit
            }
            onGamepadActionConsumed(); return@LaunchedEffect
        }
        when (action) {
            GamepadAction.SELECT -> {
                if (errorMessage != null) onExit() else playPause()
            }
            GamepadAction.BACK -> onExit()
            GamepadAction.NAVIGATE_LEFT -> seekBy(-SEEK_STEP_MS)
            GamepadAction.NAVIGATE_RIGHT -> seekBy(SEEK_STEP_MS)
            GamepadAction.NAVIGATE_UP, GamepadAction.NAVIGATE_DOWN -> poke()
            GamepadAction.PREV_CATEGORY -> switchTo(index - 1)
            GamepadAction.NEXT_CATEGORY -> switchTo(index + 1)
            GamepadAction.OPEN_CONTEXT_MENU -> { optionsOpen = true; optionsRow = 0 }
            else -> Unit
        }
        onGamepadActionConsumed()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Any touch marks the input source as touch (revealing the corner controls) without
            // consuming the event, as the detail screens do.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); onTouchInput() } }
            .background(Color.Black)
            .clickable { toggleControls() },
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
                    ControllerPrompt(
                        actions = listOf(GamepadAction.SELECT, GamepadAction.BACK),
                        label = "Go back",
                        labelColor = Color(0xFFB0B0B0),
                        labelStyle = TextStyle(fontSize = 13.sp),
                        glyphSize = 18.dp,
                    )
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
                showTouchControls = showTouchControls,
                onBack = onExit,
                onOptions = { optionsOpen = true; optionsRow = 0 },
                onPlayPause = { playPause() },
                onSeekBack = { seekBy(-SEEK_STEP_MS) },
                onSeekForward = { seekBy(SEEK_STEP_MS) },
                onPrevious = { switchTo(index - 1) },
                onNext = { switchTo(index + 1) },
                onSeekTo = { ms -> player.seekTo(ms.coerceAtLeast(0L)); poke() },
            )
        }

        if (optionsOpen && errorMessage == null) {
            OptionsOverlay(
                selectedRow = optionsRow,
                speed = SPEEDS[speedIndex],
                subtitleLabel = currentTrackLabel(player, C.TRACK_TYPE_TEXT),
                audioLabel = currentTrackLabel(player, C.TRACK_TYPE_AUDIO),
                screenMode = SCREEN_MODES[screenModeIndex].second,
                showTouchControls = showTouchControls,
                onRowClick = { row -> optionsRow = row; applyOption(row) },
                onClose = { optionsOpen = false },
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
    showTouchControls: Boolean,
    onBack: () -> Unit,
    onOptions: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekTo: (Long) -> Unit,
) {
    // Three bands over the video, as every mobile player arranges them: chrome at the top,
    // transport in the middle where the thumbs are, progress and settings at the bottom. The
    // bands are the same for both input families; only what fills them changes, so the screen
    // does not rearrange itself when the user picks up a pad.
    Box(Modifier.fillMaxSize()) {

        // Every control here is plain white on whatever frame is paused underneath, and only the
        // bottom band has a gradient. Dimming the whole picture while the controls are up is what
        // the reference players do, and it is the one treatment that covers the centred title and
        // the bare transport symbols as well - the alternative is putting a box back behind each.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)))

        // ── Top: back, title, options ─────────────────────────────────
        Box(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(24.dp)) {
            if (showTouchControls) {
                XmbHeaderPill(
                    label = "Back",
                    leadingGlyph = "◀",
                    onClick = onBack,
                    background = XmbMediaPillScrim,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                XmbKebabTouchButton(
                    onClick = onOptions,
                    background = XmbMediaPillScrim,
                    size = 40.dp,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
            // Centred on the screen, not in the gap between the controls: laying it out between
            // them in a Row would shift the title sideways as the Back pill's width changed, and
            // off-centre again the moment a pad hid both. The side reserve keeps it from running
            // underneath them instead.
            Text(
                title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = if (showTouchControls) 96.dp else 0.dp),
            )
        }

        // ── Middle: transport, over the picture ───────────────────────────
        // Touch only. A pad drives all of this from the face buttons and the D-pad, and the
        // prompt row at the bottom already says so; drawing it twice would just cover the video.
        if (showTouchControls) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Dimmed rather than dropped at the ends of the library, so the row keeps its
                // shape and play never shifts out from under the thumb mid-video.
                TransportButton(
                    Icons.Filled.SkipPrevious, "Previous video", hasPrev, onPrevious,
                    size = 44.dp, iconSize = 26.dp,
                )
                TransportButton(
                    Icons.Filled.Replay10, "Back 10 seconds", true, onSeekBack,
                    size = 52.dp, iconSize = 30.dp,
                )
                TransportButton(
                    icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    enabled = true,
                    onClick = onPlayPause,
                    size = 64.dp,
                    iconSize = 48.dp,
                )
                TransportButton(
                    Icons.Filled.Forward10, "Forward 10 seconds", true, onSeekForward,
                    size = 52.dp, iconSize = 30.dp,
                )
                TransportButton(
                    Icons.Filled.SkipNext, "Next video", hasNext, onNext,
                    size = 44.dp, iconSize = 26.dp,
                )
            }
        }

        // ── Bottom: progress, and the pad prompts ─────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                // Enough to float the 4dp bar and the time off a bright frame, and no more: a
                // gradient so there is no edge where the darkening starts, and it lands at 60%
                // rather than the photo viewer's 80% because this band sits over motion the user
                // is actually watching. Applied before the padding so it covers the full band,
                // not just the content inset.
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000))))
                .padding(24.dp),
        ) {
            // The drag overrides the player's own position while it lasts, so the bar and the
            // clock follow the finger instead of snapping back on every 500ms poll.
            var scrubFraction by remember { mutableStateOf<Float?>(null) }
            val playedFraction =
                if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            val shownFraction = scrubFraction ?: playedFraction
            val shownMs = scrubFraction?.let { (it * durationMs).toLong() } ?: positionMs

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Not menuCursorEdge(): that reads accentColor, which every preset AND the XMB
                // colour picker leave white (the picker writes the hue into waveColor). This is
                // the same derivation the App Drawer uses, so the bar tracks the picked colour.
                val played = deriveStorefrontColors().chromeDivider
                val seekable = showTouchControls && durationMs > 0
                Scrubber(
                    fraction = shownFraction,
                    color = played,
                    scrubbing = scrubFraction != null,
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            // Touch only, and only once the duration is known: a pad seeks in
                            // 10s steps from the D-pad, and a bar it cannot move should not
                            // show a cursor that invites dragging.
                            if (!seekable) Modifier else Modifier
                                .pointerInput(durationMs) {
                                    detectTapGestures { offset ->
                                        onSeekTo(((offset.x / size.width).coerceIn(0f, 1f) * durationMs).toLong())
                                    }
                                }
                                .pointerInput(durationMs) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { offset ->
                                            scrubFraction = (offset.x / size.width).coerceIn(0f, 1f)
                                        },
                                        // Commit on release, not continuously: seeking an
                                        // ExoPlayer on every pixel of travel stutters the
                                        // decoder for the whole length of the drag.
                                        onDragEnd = {
                                            scrubFraction?.let { onSeekTo((it * durationMs).toLong()) }
                                            scrubFraction = null
                                        },
                                        onDragCancel = { scrubFraction = null },
                                    ) { change, _ ->
                                        scrubFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                                        change.consume()
                                    }
                                }
                        ),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "${fmt(shownMs)} / ${fmt(durationMs)}",
                    color = Color.White,
                    fontSize = 13.sp,
                )
            }

            if (!showTouchControls) {
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ControllerPromptBar(
                        items = listOfNotNull(
                            ControllerPromptItem(
                                GamepadAction.SELECT,
                                if (isPlaying) "Pause" else "Play",
                            ),
                            ControllerPromptItem(
                                listOf(GamepadAction.NAVIGATE_LEFT, GamepadAction.NAVIGATE_RIGHT),
                                "Seek",
                            ),
                            if (hasPrev || hasNext) {
                                ControllerPromptItem(
                                    listOf(GamepadAction.PREV_CATEGORY, GamepadAction.NEXT_CATEGORY),
                                    "Prev / Next",
                                )
                            } else {
                                null
                            },
                            ControllerPromptItem(GamepadAction.OPEN_CONTEXT_MENU, "Options"),
                        ),
                        labelColor = Color(0xFFCCCCCC),
                        labelStyle = TextStyle(fontSize = 12.sp),
                        glyphSize = 16.dp,
                        arrangement = Arrangement.spacedBy(16.dp),
                    )
                    // Status, not a prompt - no button changes it from here.
                    Text("${speed}\u00D7 \u00B7 $screenMode", color = Color(0xFFCCCCCC), fontSize = 12.sp)
                }
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
    showTouchControls: Boolean,
    onRowClick: (Int) -> Unit,
    onClose: () -> Unit,
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
                        // Clickable in both modes: it is the same target either way, and a
                        // kebab opening a menu nothing could act on would be a dead end.
                        .clickable { onRowClick(i) }
                        .menuCursor(i == selectedRow)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(label, color = Color.White, fontSize = 15.sp)
                    Text(value, color = menuCursorEdge(), fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            if (showTouchControls) {
                // The pad closes this with B or Y; touch needs somewhere to press, and
                // tapping outside is not discoverable.
                XmbHeaderPill(
                    label = "Close",
                    onClick = onClose,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            } else {
                ControllerPromptBar(
                    items = listOf(
                        ControllerPromptItem(
                            listOf(GamepadAction.SELECT, GamepadAction.NAVIGATE_RIGHT),
                            "Change",
                        ),
                        ControllerPromptItem(
                            listOf(GamepadAction.OPEN_CONTEXT_MENU, GamepadAction.BACK),
                            "Close",
                        ),
                    ),
                    labelColor = Color(0xFF888888),
                    labelStyle = TextStyle(fontSize = 11.sp),
                    glyphSize = 15.dp,
                    arrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * Progress bar plus its cursor, drawn in one pass so the thumb cannot drift off the fill.
 *
 * The canvas is [SCRUBBER_TOUCH_HEIGHT] tall for a 4dp bar: the visual weight is the reference's, but a
 * 4dp drag target is unhittable. The cursor's centre is clamped inside the track by its own
 * radius, so it never hangs half-off either end.
 */
@Composable
private fun Scrubber(
    fraction: Float,
    color: Color,
    scrubbing: Boolean,
    modifier: Modifier = Modifier,
) {
    val radius by animateDpAsState(
        targetValue = if (scrubbing) 9.dp else 6.dp,
        label = "scrubberThumb",
    )
    Canvas(modifier.height(SCRUBBER_TOUCH_HEIGHT)) {
        val centreY = size.height / 2f
        val barHeight = 4.dp.toPx()
        val corner = CornerRadius(barHeight / 2f)
        val top = Offset(0f, centreY - barHeight / 2f)

        drawRoundRect(color.copy(alpha = 0.28f), top, Size(size.width, barHeight), corner)
        drawRoundRect(color, top, Size(size.width * fraction, barHeight), corner)

        val thumb = radius.toPx()
        val x = (size.width * fraction).coerceIn(thumb, (size.width - thumb).coerceAtLeast(thumb))
        // A dark ring under the cursor for the same reason the symbols carry one: this band has
        // a scrim, but the cursor sits at the bright end of it.
        drawCircle(Color.Black.copy(alpha = 0.35f), thumb + 1.5.dp.toPx(), Offset(x, centreY))
        drawCircle(color, thumb, Offset(x, centreY))
    }
}

/** Bar visual is 4dp; the target around it has to be a finger wide. */
private val SCRUBBER_TOUCH_HEIGHT = 28.dp

/**
 * One transport target: the symbol alone, no ring and no fill.
 *
 * Material vectors rather than Text glyphs - U+23EE/23EA/23E9/23ED/23F8 all carry
 * Emoji_Presentation, so a typed symbol falls back to the colour emoji font on most Android
 * builds and the row comes out as blue-and-white emoji buttons. feature-xmb already carries
 * material-icons-extended, which is where Replay10 / Forward10 come from.
 *
 * [size] is the touch target, which the symbol alone would undersize, and stays 44dp at minimum.
 */
@Composable
private fun TransportButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    size: Dp = 44.dp,
    iconSize: Dp = 26.dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .alpha(if (enabled) 1f else 0.35f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(iconSize),
        )
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
