package com.playfieldportal.feature.xmb.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
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
import com.playfieldportal.core.domain.model.TouchGesture
import com.playfieldportal.core.ui.components.ControllerPromptBar
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.core.ui.components.PfpCheckMark
import com.playfieldportal.core.ui.components.TouchPromptBar
import com.playfieldportal.core.ui.components.TouchPromptItem
import com.playfieldportal.core.ui.components.XmbHeaderPill
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.ui.theme.LocalPfpTextColors
import com.playfieldportal.core.ui.theme.menuCursorEdge
import com.playfieldportal.core.ui.theme.menuCursorFill
import com.playfieldportal.feature.xmb.viewmodel.MusicTrackPickerState

// Composable getters rather than constants, so the resolved palette (and any user font colour)
// reaches every row here without an edit at the call sites — same treatment as the browser.
private val PickerText: Color
    @Composable get() = LocalPfpTextColors.current.primary
private val PickerSubtext: Color
    @Composable get() = LocalPfpTextColors.current.secondary

private val PickerCheck = Color(0xFF7CE5A2)
private val CoverPlaceholder = Color(0xFF1B1B27)

/**
 * Multi-select picker over all scanned tracks, used by a playlist's "Add Tracks" row. Selection and
 * commit are driven entirely by the ViewModel so controller and touch behave identically (mirrors
 * InstalledAppPicker).
 *
 * The two input families get different affordances for the same two actions. A pad commits with
 * the index-0 Confirm row (or HOME) and cancels with B; a finger gets Add and Cancel pills in the
 * header, because reaching that Confirm row on touch means scrolling back over every scanned song.
 * There is deliberately no tap-outside-to-dismiss: on a list this size the only "outside" is the
 * margin, and a stray tap there would throw away a selection built one song at a time.
 */
@Composable
fun MusicTrackPicker(
    state: MusicTrackPickerState,
    onActivateAt: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    showTouchControls: Boolean = true,
    /** Any finger on the picker: hides the controller cursor and arms the revival press. */
    onTouchInput: () -> Unit = {},
    /** Row index → Y, plus the viewport centre, for the revival press's nearest-visible anchor. */
    onGeometry: (Map<Int, Float>, Float) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Rows frame themselves with a BringIntoViewRequester; this only has to put the target within
    // reach of that when the cursor lands outside the composed window. Confirm row + one row per
    // track, clamped so a stale cursor can't walk off the end of the list.
    LaunchedEffect(state.selectedIndex, state.cursorVisible) {
        // Never chase the cursor while it is hidden: the list belongs to the finger then, and
        // scrolling it back to a stale index is the jerk this is here to prevent.
        if (!state.cursorVisible) return@LaunchedEffect
        val target = state.selectedIndex.coerceIn(0, state.tracks.size)
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == target }) {
            listState.scrollToItem(target)
        }
    }

    // The cursor appearing is a source transition, and the screen has to hold still for it: taking
    // the scroll mutex with an empty mutation cancels any fling the finger left running — so the
    // row the cursor just landed on cannot slide away from under it — and moves nothing itself.
    LaunchedEffect(state.cursorVisible) {
        if (!state.cursorVisible) return@LaunchedEffect
        listState.scroll { }
    }

    // Feed the revival press's nearest-visible rule from the list itself rather than from per-row
    // position callbacks: only the visible window matters, and the list already computes it.
    LaunchedEffect(listState, state.tracks) {
        snapshotFlow { listState.layoutInfo }.collect { info ->
            if (info.visibleItemsInfo.isEmpty()) return@collect
            val offsets = info.visibleItemsInfo.associate { it.index to it.offset.toFloat() }
            onGeometry(offsets, (info.viewportStartOffset + info.viewportEndOffset) / 2f)
        }
    }

    val pfpColors = LocalPFPColors.current
    val addLabel = if (state.selected.isEmpty()) "Done" else "Add ${state.selected.size}"
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to pfpColors.backgroundTop.copy(alpha = 0.94f),
                    1f to pfpColors.backgroundBottom.copy(alpha = 0.94f),
                )
            )
            // Any pointer down anywhere hands control to touch, the same root-level detector the
            // browser uses. requireUnconsumed = false so a row's own tap still reports.
            .pointerInput(Unit) {
                awaitEachGesture { awaitFirstDown(requireUnconsumed = false); onTouchInput() }
            },
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Add to ${state.playlistName}",
                    color = PickerText,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.weight(1f),
                )
                if (showTouchControls) {
                    XmbHeaderPill(label = addLabel, leadingGlyph = "✓", onClick = onConfirm)
                    Spacer(Modifier.width(10.dp))
                    XmbHeaderPill(label = "Cancel", leadingGlyph = "◀", onClick = onDismiss)
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            ) {
                Text("${state.selected.size} selected", color = PickerSubtext, fontSize = 12.sp)
                // One input family on screen at a time (ARCHITECTURE.md ▸ Conventions).
                if (showTouchControls) {
                    TouchPromptBar(
                        items = listOf(TouchPromptItem(TouchGesture.TAP, "Toggle")),
                        labelColor = PickerSubtext,
                        labelStyle = TextStyle(fontSize = 12.sp),
                        glyphSize = 18.dp,
                        arrangement = Arrangement.spacedBy(18.dp),
                    )
                } else {
                    ControllerPromptBar(
                        items = listOf(
                            ControllerPromptItem(GamepadAction.SELECT, "Toggle"),
                            ControllerPromptItem(GamepadAction.HOME, "Add"),
                            ControllerPromptItem(GamepadAction.BACK, "Cancel"),
                        ),
                        labelColor = PickerSubtext,
                        labelStyle = TextStyle(fontSize = 12.sp),
                        glyphSize = 16.dp,
                        arrangement = Arrangement.spacedBy(18.dp),
                    )
                }
            }

            if (state.tracks.isEmpty()) {
                Text(
                    "No more tracks to add — every scanned song is already in this playlist.",
                    color = PickerSubtext,
                    fontSize = 14.sp,
                )
            }

            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                // Index 0 = Confirm row. It is how a pad commits; touch has the header pill.
                item {
                    PickerRow(
                        // Same rule as the browser: the highlight marks where a D-pad press would
                        // land, and a finger has no "where". Owned by the cursor flag rather than
                        // inferred from the input source, because a finger scrolling this list
                        // never reports itself — which left the highlight on a stale row through a
                        // whole touch session. In touch mode the header's Add pill presses it.
                        selected = state.selectedIndex == 0 && state.cursorVisible,
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
                        selected = state.selectedIndex == rowIndex && state.cursorVisible,
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
                        if (checked) {
                            PfpCheckMark(PickerCheck, size = 18.dp)
                        }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PickerRow(
    selected: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    // The focused row frames itself by geometry rather than scroll arithmetic — this app's
    // convention for controller focus.
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) { if (selected) requester.bringIntoView() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) menuCursorFill() else Color.Transparent)
            .then(
                if (selected) Modifier.border(1.dp, menuCursorEdge(), RoundedCornerShape(7.dp))
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
}
