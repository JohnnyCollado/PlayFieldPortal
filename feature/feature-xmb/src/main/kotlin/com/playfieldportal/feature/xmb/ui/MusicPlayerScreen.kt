package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.MusicTrack
import com.playfieldportal.core.ui.components.ControllerPromptBar
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.core.ui.components.XmbHeaderPill
import com.playfieldportal.core.ui.components.XmbKebabTouchButton
import com.playfieldportal.core.ui.preview.CombinedPreviews
import com.playfieldportal.core.ui.preview.PfpScreenPreview
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.ui.theme.LocalPfpTextColors
import com.playfieldportal.core.ui.theme.deriveStorefrontColors
import com.playfieldportal.feature.xmb.music.MusicPlaybackState
import com.playfieldportal.feature.xmb.ui.media.MediaScrubBar
import com.playfieldportal.feature.xmb.ui.media.TransportButton
import com.playfieldportal.feature.xmb.ui.media.formatMediaTime
import com.playfieldportal.feature.xmb.ui.visualizer.VISUALIZER_STRIP_HEIGHT
import com.playfieldportal.feature.xmb.ui.visualizer.VisualizerField
import com.playfieldportal.feature.xmb.ui.visualizer.VisualizerIds
import com.playfieldportal.feature.xmb.ui.visualizer.VisualizerPickerStrip
import com.playfieldportal.feature.xmb.ui.visualizer.heroBudget
import com.playfieldportal.feature.xmb.ui.visualizer.rememberVisualizerHost
import com.playfieldportal.feature.xmb.ui.visualizer.rememberVisualizerSprite
import com.playfieldportal.feature.xmb.ui.visualizer.swipeDownToDismissStrip

// Composable getters rather than constants, so the resolved palette reaches every label here
// without an edit at the call sites — the same treatment as the browser and the picker.
private val PrimaryText: Color
    @Composable get() = LocalPfpTextColors.current.primary
private val SecondaryText: Color
    @Composable get() = LocalPfpTextColors.current.secondary

private val ArtPlaceholder = Color(0xFF15151F)

/** The PSP Visual Player's art size: a thumbnail, not the hero. */
private val ART_THUMB = 62.dp

/** The reference runs its progress bar across the right half only, not the full width. */
private const val TIME_BAR_WIDTH_FRACTION = 0.5f

/**
 * Full-screen "Now Playing" view. Stateless: it renders [state] and forwards control intents.
 *
 * Laid out on the PSP Visual Player rather than on the video player's three bands, because the two
 * screens have opposite jobs. The video player's centre *is* the content; here the centre is the
 * one thing that must stay empty:
 *
 *  - a tinted banner naming the queue and the position in it,
 *  - album art as a 62dp thumbnail with the title beside it, over a hairline rule that runs the
 *    full width,
 *  - the **field** in the middle, with nothing on top of it,
 *  - and one bottom band carrying everything else — see [BottomBand].
 *
 * The inversion is the point: the old layout made the album art the hero and left the field
 * nowhere to go.
 *
 * Every control lives in the banner or in the bottom band. Nothing floats loose over the field,
 * because two things positioned independently over the same empty middle is how the controls ended
 * up scattered and overlapping the first time.
 *
 * Chrome visibility is owned by the caller ([chromeVisible]) rather than by a timer in here,
 * because the picker strip has to pin it open and the strip's state lives in the ViewModel.
 */
@Composable
fun MusicPlayerScreen(
    state: MusicPlaybackState,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekTo: (Int) -> Unit,
    onOptions: () -> Unit,
    onBack: () -> Unit,
    /** One of [VisualizerIds]. [VisualizerIds.OFF] leaves the centre to the wave and wallpaper. */
    visualizerId: String = VisualizerIds.OFF,
    chromeVisible: Boolean = true,
    /** Cursor position while the picker strip is open; null when it is closed. */
    pickerFocusedIndex: Int? = null,
    onVisualizerTileTapped: (Int) -> Unit = {},
    onStripAffordanceTapped: () -> Unit = {},
    /** Touch dismissal of the strip — a swipe down anywhere on the player, the counterpart of B. */
    onStripDismissed: () -> Unit = {},
    showTouchControls: Boolean = true,
    onTouchInput: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val track = state.track
    val pfpColors = LocalPFPColors.current
    val accent = deriveStorefrontColors().chromeDivider
    val stripOpen = pickerFocusedIndex != null
    val fieldOn = visualizerId != VisualizerIds.OFF

    // `Off` is the absence of a renderer, so the backdrop drops to roughly the scrim the Settings
    // screens use and the live XMB wave / the user's wallpaper read straight through it. A field
    // needs a darker ground to stay legible, so it gets the heavier one.
    val scrimTop = if (fieldOn) 0.94f else 0.84f
    val scrimBottom = if (fieldOn) 0.96f else 0.88f

    val sprite = rememberVisualizerSprite(accent)
    val host = rememberVisualizerHost(
        isPlaying = state.isPlaying,
        trackId = track?.id,
        tint = accent,
        // The clock runs for the hero, and also whenever the strip is open, so its tiles preview
        // live even while `Off` is the selection.
        active = fieldOn || stripOpen,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to pfpColors.backgroundTop.copy(alpha = scrimTop),
                    1f to pfpColors.backgroundBottom.copy(alpha = scrimBottom),
                )
            )
            .pointerInput(Unit) {
                awaitEachGesture { awaitFirstDown(requireUnconsumed = false); onTouchInput() }
            }
            // Whole surface, and only while the strip is open: a downward drag means nothing else
            // in this player, and aiming at a 92dp band to close it is a worse gesture than one
            // that works wherever the thumb happens to be.
            .then(
                if (stripOpen && showTouchControls) {
                    Modifier.swipeDownToDismissStrip(onStripDismissed)
                } else Modifier
            ),
    ) {
        // ── 3. The field. Drawn first and edge to edge; everything else floats over it. ──
        VisualizerField(
            host = host,
            visualizerId = visualizerId,
            budget = heroBudget(visualizerId, stripOpen),
            sprite = sprite,
            modifier = Modifier.fillMaxSize(),
        )

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                    QueueBanner(
                        state = state,
                        tint = accent,
                        showTouchControls = showTouchControls,
                        onBack = onBack,
                        onOptions = onOptions,
                    )
                    TrackMetadata(track = track, rule = accent.copy(alpha = 0.45f))
                }

                BottomBand(
                    state = state,
                    accent = accent,
                    stripOpen = stripOpen,
                    showTouchControls = showTouchControls,
                    onPlayPause = onPlayPause,
                    onPrev = onPrev,
                    onNext = onNext,
                    onSeekBack = onSeekBack,
                    onSeekForward = onSeekForward,
                    onSeekTo = onSeekTo,
                    onStripAffordanceTapped = onStripAffordanceTapped,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

        // The strip sits outside the chrome fade: while it is open the chrome is pinned anyway, so
        // wrapping it would only risk the tiles animating a frame behind their own labels.
        pickerFocusedIndex?.let { focusedIndex ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 24.dp, vertical = 10.dp),
            ) {
                VisualizerPickerStrip(
                    host = host,
                    selectedId = visualizerId,
                    focusedIndex = focusedIndex,
                    sprite = sprite,
                    onTileTapped = onVisualizerTileTapped,
                    showTouchControls = showTouchControls,
                )
            }
        }
    }
}

// ── 1. Banner ─────────────────────────────────────────────────────────────────

/**
 * Full-width tinted band: `♫` and the queue's name on the left, the position in it on the right.
 *
 * The tint follows the theme accent rather than the album art. Sampling the art is closer to the
 * PSP — which tints from the field itself — but it needs a palette pass on every track change for
 * a band a few pixels tall, and the accent version already reads as part of the app.
 */
@Composable
private fun QueueBanner(
    state: MusicPlaybackState,
    tint: Color,
    showTouchControls: Boolean,
    onBack: () -> Unit,
    onOptions: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    0f to tint.copy(alpha = 0.28f),
                    1f to tint.copy(alpha = 0.10f),
                )
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        if (showTouchControls) {
            XmbHeaderPill(
                label = "Back",
                leadingGlyph = "◀",
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.CenterStart)
                // Clears the Back pill in touch mode; a pad has B and needs no room reserved.
                .padding(start = if (showTouchControls) 100.dp else 0.dp),
        ) {
            Text("♫", color = PrimaryText, fontSize = 15.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                state.queueName ?: "Now Playing",
                color = PrimaryText, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.queueSize > 0) {
            Text(
                "(${state.index + 1}/${state.queueSize})",
                color = SecondaryText, fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = if (showTouchControls) 52.dp else 0.dp),
            )
        }
        if (showTouchControls) {
            XmbKebabTouchButton(
                onClick = onOptions,
                size = 36.dp,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

// ── 2. Metadata ───────────────────────────────────────────────────────────────

/**
 * Thumbnail, title, and the hairline that runs the full width beneath it — with artist, album and
 * the codec chip sitting *on* the rule line rather than under it, which is what keeps the block
 * reading as one header instead of three stacked labels.
 */
@Composable
private fun TrackMetadata(track: MusicTrack?, rule: Color) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(ART_THUMB)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ArtPlaceholder),
                contentAlignment = Alignment.Center,
            ) {
                val art = track?.artUri
                if (art != null) {
                    AsyncImage(
                        model = art,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = SecondaryText,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Text(
                text = track?.displayTitle ?: "Nothing playing",
                color = PrimaryText, fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(rule))
        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            val sub = listOfNotNull(track?.artist, track?.album).joinToString("  ·  ")
            Text(
                sub, color = SecondaryText, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            codecLabel(track?.mimeType)?.let { codec ->
                Text(
                    codec,
                    color = SecondaryText.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * Short codec name for the chip, from the scanned MIME type. Null when the scan recorded nothing
 * usable — an empty chip is worse than no chip.
 */
internal fun codecLabel(mimeType: String?): String? {
    val subtype = mimeType?.substringAfter('/', "")?.lowercase()?.removePrefix("x-") ?: return null
    if (subtype.isBlank()) return null
    return when (subtype) {
        "mpeg", "mp3" -> "MP3"
        "mp4", "m4a", "aac", "aacp" -> "AAC"
        "flac" -> "FLAC"
        "ogg", "vorbis" -> "OGG"
        "opus" -> "OPUS"
        "wav", "wave" -> "WAV"
        else -> subtype.uppercase().take(6)
    }
}

// ── 4. Transport cluster ──────────────────────────────────────────────────────

@Composable
private fun TransportCluster(
    state: MusicPlaybackState,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Dimmed rather than dropped at the ends of the queue, so the row keeps its shape and play
        // never shifts out from under the thumb mid-song.
        TransportButton(
            Icons.Filled.SkipPrevious, "Previous track", state.queueSize > 1, onPrev,
            size = 44.dp, iconSize = 24.dp,
        )
        TransportButton(
            Icons.Filled.Replay10, "Back 10 seconds", state.isPrepared, onSeekBack,
            size = 48.dp, iconSize = 28.dp,
        )
        TransportButton(
            icon = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (state.isPlaying) "Pause" else "Play",
            enabled = state.isPrepared,
            onClick = onPlayPause,
            size = 60.dp,
            iconSize = 42.dp,
        )
        TransportButton(
            Icons.Filled.Forward10, "Forward 10 seconds", state.isPrepared, onSeekForward,
            size = 48.dp, iconSize = 28.dp,
        )
        TransportButton(
            Icons.Filled.SkipNext, "Next track", state.queueSize > 1, onNext,
            size = 44.dp, iconSize = 24.dp,
        )
    }
}

// ── 5/6. Bottom band ──────────────────────────────────────────────────────────

/**
 * Everything that lives along the bottom edge, in **one column** so the pieces cannot collide.
 *
 * An earlier version placed the clock and the prompt bar as separately-aligned children of a Box —
 * one at `BottomEnd` at 45% width, one at `BottomCenter` at 55% — which of course overlapped,
 * because two alignments in the same Box know nothing about each other. Stacked rows can't.
 *
 * Top to bottom: the touch transport, then the play-state glyph and the clock/scrub pair sharing a
 * row as equal halves, then the prompt bar or the strip affordance on a line of its own.
 *
 * The play-state glyph is controller-only. In touch mode the transport's own play/pause button is
 * right above it, and a second, larger, non-tappable copy of the same symbol reads as a broken
 * button rather than as status.
 */
@Composable
private fun BottomBand(
    state: MusicPlaybackState,
    accent: Color,
    stripOpen: Boolean,
    showTouchControls: Boolean,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekTo: (Int) -> Unit,
    onStripAffordanceTapped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 18.dp)
            // Lift clear of the strip when it is up, rather than letting the strip cover the scrub
            // bar the user may be dragging.
            .padding(bottom = if (stripOpen) VISUALIZER_STRIP_HEIGHT + 16.dp else 0.dp),
    ) {
        // Touch only: a pad drives all five of these from the face buttons, the D-pad and the
        // shoulders, and the prompt bar below names them.
        if (showTouchControls) {
            TransportCluster(
                state = state,
                onPlayPause = onPlayPause,
                onPrev = onPrev,
                onNext = onNext,
                onSeekBack = onSeekBack,
                onSeekForward = onSeekForward,
            )
            Spacer(Modifier.height(18.dp))
        }

        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f - TIME_BAR_WIDTH_FRACTION)) {
                if (!showTouchControls) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "Playing" else "Paused",
                        tint = PrimaryText,
                        modifier = Modifier.align(Alignment.BottomStart).size(52.dp),
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.weight(TIME_BAR_WIDTH_FRACTION),
            ) {
                // Elapsed carries the weight: it is the number being read, and at the reference's
                // size it was a caption in a corner. Total stays small and quiet beside it.
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        formatMediaTime(state.positionMs.toLong()),
                        color = accent, fontSize = 30.sp, fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "/ ${formatMediaTime(state.durationMs.toLong())}",
                        color = PrimaryText.copy(alpha = 0.8f), fontSize = 16.sp,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                // Not menuCursorEdge(): that reads accentColor, which every preset AND the XMB
                // colour picker leave white (the picker writes the hue into waveColor). Dragging
                // is touch-only — a pad seeks in 10s steps from the D-pad.
                MediaScrubBar(
                    positionMs = state.positionMs.toLong(),
                    durationMs = state.durationMs.toLong(),
                    color = accent,
                    seekable = showTouchControls,
                    onSeekTo = { onSeekTo(it.toInt()) },
                    showClock = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Its own line. This is the row that used to sit on top of the scrub bar.
        if (showTouchControls) {
            XmbHeaderPill(
                label = "Visualizer",
                leadingGlyph = "▦",
                onClick = onStripAffordanceTapped,
            )
        } else {
            ControllerPromptBar(
                items = listOfNotNull(
                    ControllerPromptItem(
                        GamepadAction.SELECT,
                        if (state.isPlaying) "Pause" else "Play",
                    ),
                    ControllerPromptItem(
                        listOf(GamepadAction.NAVIGATE_LEFT, GamepadAction.NAVIGATE_RIGHT),
                        "Seek",
                    ),
                    // Same shoulder pair the video player uses for its library, and named only
                    // when there is a queue to move through.
                    if (state.queueSize > 1) {
                        ControllerPromptItem(
                            listOf(GamepadAction.PREV_CATEGORY, GamepadAction.NEXT_CATEGORY),
                            "Prev / Next",
                        )
                    } else null,
                    ControllerPromptItem(GamepadAction.OPEN_CONTEXT_MENU, "Options"),
                    ControllerPromptItem(GamepadAction.BACK, "Close"),
                ),
                labelColor = SecondaryText.copy(alpha = 0.7f),
                labelStyle = TextStyle(fontSize = 11.sp),
                glyphSize = 16.dp,
                arrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────
//
// One per field state, because the three differ in exactly the way a preview is good at showing:
// how much backdrop the chrome has to stay legible against.

private val PreviewTrack = MusicTrack(
    id = "t1",
    folderId = "f1",
    uri = "content://tracks/t1",
    displayName = "01 - Underwater Bubbles.flac",
    title = "Underwater Bubbles",
    artist = "Nobuo Uematsu",
    album = "Final Fantasy VII OST",
    durationMs = 254_000L,
    mimeType = "audio/flac",
)

private val PreviewState = MusicPlaybackState(
    track = PreviewTrack,
    isPlaying = true,
    positionMs = 33_000,
    durationMs = 254_000,
    index = 3,
    queueSize = 28,
    isPrepared = true,
    queueName = "All Music",
)

@Composable
private fun PreviewPlayer(visualizerId: String, pickerFocusedIndex: Int? = null) {
    PfpScreenPreview {
        MusicPlayerScreen(
            state = PreviewState,
            onPlayPause = {}, onPrev = {}, onNext = {},
            onSeekBack = {}, onSeekForward = {}, onSeekTo = {},
            onOptions = {}, onBack = {},
            visualizerId = visualizerId,
            pickerFocusedIndex = pickerFocusedIndex,
            showTouchControls = true,
        )
    }
}

@CombinedPreviews
@Composable
private fun MusicPlayerOffPreview() = PreviewPlayer(VisualizerIds.OFF)

@CombinedPreviews
@Composable
private fun MusicPlayerPortalPreview() = PreviewPlayer(VisualizerIds.PORTAL)

@CombinedPreviews
@Composable
private fun MusicPlayerRipplePreview() = PreviewPlayer(VisualizerIds.RIPPLE)

@CombinedPreviews
@Composable
private fun MusicPlayerPickerOpenPreview() =
    PreviewPlayer(VisualizerIds.PORTAL, pickerFocusedIndex = 1)
