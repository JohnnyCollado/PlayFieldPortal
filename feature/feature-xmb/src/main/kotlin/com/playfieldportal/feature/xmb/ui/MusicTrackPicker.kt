package com.playfieldportal.feature.xmb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.feature.xmb.viewmodel.MusicTrackPickerState

private val PickerSelected = Color(0xFF574DDB)
private val PickerBorder = Color(0xFF8F7CFF)
private val PickerText = Color.White
private val PickerSubtext = Color(0xFFC9C7E8)
private val PickerCheck = Color(0xFF7CE5A2)
private val CoverPlaceholder = Color(0xFF1B1B27)

// Multi-select picker over all scanned tracks, used by a playlist's "Add Tracks" row. Selection and
// commit are driven entirely by the ViewModel so controller and touch behave identically (mirrors
// InstalledAppPicker).
@Composable
fun MusicTrackPicker(
    state: MusicTrackPickerState,
    onActivateAt: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.selectedIndex) {
        listState.animateScrollToItem(state.selectedIndex.coerceAtLeast(0))
    }

    val pfpColors = LocalPFPColors.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to pfpColors.backgroundTop.copy(alpha = 0.94f),
                    1f to pfpColors.backgroundBottom.copy(alpha = 0.94f),
                )
            )
            .clickable(onClick = onDismiss),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
            Text("Add to ${state.playlistName}", color = PickerText, fontSize = 22.sp, fontWeight = FontWeight.Light)
            Text(
                "${state.selected.size} selected  ·  A to toggle  ·  Start to add  ·  B to cancel",
                color = PickerSubtext,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )

            if (state.tracks.isEmpty()) {
                Text(
                    "No more tracks to add — every scanned song is already in this playlist.",
                    color = PickerSubtext,
                    fontSize = 14.sp,
                )
            }

            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                // Index 0 = Confirm row.
                item {
                    PickerRow(
                        selected = state.selectedIndex == 0,
                        modifier = Modifier.clickable { onConfirm() },
                    ) {
                        Text(
                            text = if (state.selected.isEmpty()) "Done" else "Add ${state.selected.size} track(s)",
                            color = PickerText,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                itemsIndexed(state.tracks, key = { _, t -> t.id }) { index, track ->
                    val rowIndex = index + 1
                    val checked = track.id in state.selected
                    PickerRow(
                        selected = state.selectedIndex == rowIndex,
                        modifier = Modifier.clickable { onActivateAt(rowIndex) },
                    ) {
                        TrackCover(track.artUri)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.displayTitle,
                                color = PickerText,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!track.artist.isNullOrBlank()) {
                                Text(
                                    text = track.artist!!,
                                    color = PickerSubtext,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Text(
                            text = if (checked) "✓" else "",
                            color = PickerCheck,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackCover(coverUri: String?) {
    if (coverUri != null) {
        AsyncImage(
            model = coverUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)),
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)).background(CoverPlaceholder),
        ) {
            Icon(Icons.Filled.MusicNote, contentDescription = null, tint = PickerSubtext, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun PickerRow(
    selected: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) PickerSelected.copy(alpha = 0.32f) else Color.Transparent)
            .then(
                if (selected) Modifier.border(1.dp, PickerBorder.copy(alpha = 0.8f), RoundedCornerShape(7.dp))
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
}
