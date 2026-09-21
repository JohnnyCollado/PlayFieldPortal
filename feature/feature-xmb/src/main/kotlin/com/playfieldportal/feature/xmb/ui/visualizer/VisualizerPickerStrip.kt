package com.playfieldportal.feature.xmb.ui.visualizer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.ui.theme.menuCursorEdge
import com.playfieldportal.core.ui.theme.menuCursorFill

/**
 * The bottom filmstrip: `Off` first, then every field, each tile a **live** preview of the same
 * simulation the hero is drawing (see [VisualizerHost] for why that is affordable).
 *
 * The selected tile carries the menu cursor's border, matching every other list in the app, and
 * the focused tile frames itself with a [BringIntoViewRequester] rather than scroll arithmetic —
 * this repo's convention for controller focus.
 *
 * Input while the strip is open is captured by `XMBViewModel`: ◀/▶ move the cursor instead of
 * seeking, A confirms and B closes. Touch taps a tile directly, and dismisses with a swipe down
 * anywhere on the player — see [swipeDownToDismissStrip], which the player owns rather than this.
 */

private val TILE_WIDTH = 104.dp
private val TILE_HEIGHT = 62.dp

/**
 * Downward travel that dismisses the strip. Sized like the repo's other commit-on-release swipes
 * (`SWIPE_BACK_COMMIT_DP` in `XmbNavGestures`) so a finger resting on a tile cannot close it, and
 * deliberately not scaled by the touch-sensitivity preference — that slider tunes distance *per
 * step*, and a dismissal has no steps.
 */
private val SWIPE_DISMISS_COMMIT_DP = 56.dp

/**
 * Swipe down to dismiss the strip — the touch counterpart of B.
 *
 * Applied by the **player screen to its whole surface**, not by the strip to itself: the strip is a
 * 92dp band at the bottom edge, and a gesture whose target is the thing you are trying to get rid
 * of is a gesture you have to aim at. Anywhere on screen is the behaviour people expect from a
 * sheet, and the player has nothing else bound to a downward drag.
 *
 * Commits on release rather than at the threshold, so a drag can be walked back. Vertical only, so
 * the strip's `LazyRow` keeps its own axis and the scrub bar keeps its horizontal drag — both are
 * children, and their consumed events cancel this detector's slop before it ever claims the
 * gesture.
 */
fun Modifier.swipeDownToDismissStrip(onDismiss: () -> Unit): Modifier = pointerInput(onDismiss) {
    val commitPx = SWIPE_DISMISS_COMMIT_DP.toPx()
    var travel = 0f
    detectVerticalDragGestures(
        onDragStart = { travel = 0f },
        onDragEnd = { if (travel >= commitPx) onDismiss() },
        onDragCancel = { travel = 0f },
    ) { _, delta -> travel += delta }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VisualizerPickerStrip(
    host: VisualizerHost,
    selectedId: String,
    focusedIndex: Int,
    sprite: ImageBitmap,
    onTileTapped: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showTouchControls: Boolean = true,
) {
    val listState = rememberLazyListState()
    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(VisualizerIds.ALL, key = { _, id -> id }) { index, id ->
            VisualizerTile(
                host = host,
                visualizerId = id,
                selected = id == selectedId,
                focused = index == focusedIndex,
                sprite = sprite,
                onClick = if (showTouchControls) ({ onTileTapped(index) }) else null,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VisualizerTile(
    host: VisualizerHost,
    visualizerId: String,
    selected: Boolean,
    focused: Boolean,
    sprite: ImageBitmap,
    onClick: (() -> Unit)?,
) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) requester.bringIntoView() }

    val shape = RoundedCornerShape(8.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(TILE_WIDTH, TILE_HEIGHT)
                .bringIntoViewRequester(requester)
                .clip(shape)
                // Near-black behind the preview whatever the wallpaper, so a tile reads as a
                // *sample* of the field rather than as a hole in the chrome.
                .background(Color.Black.copy(alpha = 0.45f))
                .then(if (focused) Modifier.background(menuCursorFill()) else Modifier)
                .then(
                    // Selection is the box, focus is the brighter, thicker box — the same pairing
                    // the reference uses and every other PFP list already follows.
                    when {
                        focused -> Modifier.border(2.dp, menuCursorEdge(), shape)
                        selected -> Modifier.border(1.dp, menuCursorEdge().copy(alpha = 0.6f), shape)
                        else -> Modifier
                    }
                )
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (visualizerId == VisualizerIds.OFF) {
                // Not a renderer, so there is nothing to preview: the tile says so in words.
                Text("OFF", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            } else {
                VisualizerField(
                    host = host,
                    visualizerId = visualizerId,
                    budget = tileBudget(visualizerId),
                    sprite = sprite,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            VisualizerIds.labelFor(visualizerId),
            color = Color.White.copy(alpha = if (selected || focused) 0.95f else 0.6f),
            fontSize = 11.sp,
        )
    }
}

/** Height the strip occupies, so the player can reserve it without measuring. */
val VISUALIZER_STRIP_HEIGHT = TILE_HEIGHT + 30.dp
