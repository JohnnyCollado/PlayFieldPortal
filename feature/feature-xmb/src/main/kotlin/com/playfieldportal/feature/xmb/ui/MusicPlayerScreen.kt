package com.playfieldportal.feature.xmb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.playfieldportal.feature.xmb.music.MusicPlaybackState

private val Backdrop   = Color(0xF20A0A12)
private val Primary    = Color.White
private val Secondary  = Color(0xFFC9C7E8)
private val Accent      = Color(0xFF4A9EFF)

/**
 * Full-screen "Now Playing" view. Stateless: it renders [state] and forwards control intents.
 * Controller input is handled in the ViewModel (this screen also accepts touch). Y opens the
 * options menu (Play in Background) via the standard context-menu overlay.
 */
@Composable
fun MusicPlayerScreen(
    state: MusicPlaybackState,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeekTo: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = state.track
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Backdrop)
            // Tap the dim area outside the controls to go back.
            .clickable(onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Album art when the track had embedded artwork; otherwise a framed music glyph.
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF15151F)),
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
                    Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Secondary, modifier = Modifier.size(72.dp))
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = track?.displayTitle ?: "Nothing playing",
                color = Primary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
            val sub = listOfNotNull(track?.artist, track?.album).joinToString("  ·  ")
            if (sub.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(sub, color = Secondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
            if (state.queueSize > 1) {
                Spacer(Modifier.height(2.dp))
                Text("${state.index + 1} / ${state.queueSize}", color = Secondary.copy(alpha = 0.7f), fontSize = 11.sp)
            }

            Spacer(Modifier.height(20.dp))

            // Seek bar + times.
            val duration = state.durationMs.coerceAtLeast(0)
            Slider(
                value = state.positionMs.coerceIn(0, duration).toFloat(),
                onValueChange = { onSeekTo(it.toInt()) },
                valueRange = 0f..(duration.toFloat().coerceAtLeast(1f)),
                colors = SliderDefaults.colors(
                    thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Color(0xFF3A3A4A),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(state.positionMs), color = Secondary, fontSize = 11.sp)
                Text(formatTime(duration), color = Secondary, fontSize = 11.sp)
            }

            Spacer(Modifier.height(16.dp))

            // Transport controls (touch). Controller maps Left/Right=prev/next, A=play-pause.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                TransportButton(Icons.Filled.SkipPrevious, "Previous", 40.dp, onPrev)
                TransportButton(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (state.isPlaying) "Pause" else "Play",
                    60.dp, onPlayPause,
                )
                TransportButton(Icons.Filled.SkipNext, "Next", 40.dp, onNext)
            }

            Spacer(Modifier.height(20.dp))
            Text("Y: Play in background   ·   B: Close", color = Secondary.copy(alpha = 0.7f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun TransportButton(icon: ImageVector, desc: String, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size + 16.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, tint = Primary, modifier = Modifier.size(size))
    }
}

private fun formatTime(ms: Int): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
