package com.playfieldportal.feature.appbar.appdrawer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.playfieldportal.core.ui.theme.StorefrontColors
import com.playfieldportal.feature.appbar.GRID_COLUMNS
import com.playfieldportal.feature.appbar.InstalledApp
import kotlinx.coroutines.flow.distinctUntilChanged

// ── Application grid ──────────────────────────────────────────────────────────
//
// The primary visual element: a 6-column artwork grid with generous spacing and minimal chrome
// (see AppDrawerGridItem). The scroll-to-selection and touch-browse effects are subtle and
// correct — they are kept verbatim from the previous implementation:
//  - controller cursor movement auto-scrolls the target tile into view;
//  - a finger drag parks the (hidden) cursor on the tile nearest the viewport centre when the
//    scroll settles, so switching back to the D-pad starts where the finger left off.

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppDrawerGrid(
    apps: List<InstalledApp>,
    selectedIndex: Int,
    usingTouch: Boolean,
    onAppTapped: (Int) -> Unit,
    onAppLaunched: (String) -> Unit,
    onAppMenu: (InstalledApp) -> Unit,
    onTouchBrowse: (Int) -> Unit,
    colors: StorefrontColors,
) {
    val gridState = rememberLazyGridState()

    LaunchedEffect(selectedIndex, usingTouch) {
        // Clamp: a stale cursor past the end (list shrank mid-scroll) must not crash the grid.
        if (!usingTouch && apps.isNotEmpty()) gridState.animateScrollToItem(selectedIndex.coerceIn(0, apps.lastIndex))
    }

    var fingerScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(gridState) {
        gridState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                fingerScrolled = true
                onTouchBrowse(gridState.firstVisibleItemIndex)
            }
        }
    }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (!scrolling && fingerScrolled) {
                    fingerScrolled = false
                    val info = gridState.layoutInfo
                    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                    val nearest = info.visibleItemsInfo.minByOrNull { item ->
                        val itemCenter = item.offset.y + item.size.height / 2
                        kotlin.math.abs(itemCenter - center)
                    }?.index
                    if (nearest != null) onTouchBrowse(nearest)
                }
            }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(GRID_COLUMNS),
        // Wide horizontal margin so the artwork row breathes; vertical padding reserves a little
        // room between the tab row and the first row of icons.
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Keyed by packageName: without a key a refresh would drop/recreate every tile, costing
        // recomposition stability on resume/refresh.
        itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
            AppDrawerGridItem(
                app = app,
                isSelected = !usingTouch && index == selectedIndex,
                onClick = { onAppTapped(index); onAppLaunched(app.packageName) },
                onMenu = { onAppTapped(index); onAppMenu(app) },
                colors = colors,
            )
        }
    }
}
