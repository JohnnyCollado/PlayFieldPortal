package com.playfieldportal.feature.xmb.ui.apppicker

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.playfieldportal.core.domain.model.ControllerIcon
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.ControllerPromptBar
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.core.ui.theme.StorefrontColors
import com.playfieldportal.core.ui.theme.deriveStorefrontColors
import com.playfieldportal.feature.xmb.viewmodel.AppPickerEntry
import com.playfieldportal.feature.xmb.viewmodel.AppPickerState
import com.playfieldportal.feature.xmb.viewmodel.PICKER_GRID_COLUMNS
import com.playfieldportal.feature.xmb.viewmodel.pendingRemovals
import com.playfieldportal.feature.xmb.viewmodel.visibleApps
import kotlinx.coroutines.flow.distinctUntilChanged

// ── Installed-app picker (grid) ───────────────────────────────────────────────
//
// The shared picker for the Android library ("Find Games" / "Add Android Apps") and the
// Video / Music / Photo "Add Apps" flows. Reads as a simplified App Drawer: same storefront
// theming (deriveStorefrontColors — never LocalPFPColors.accentColor, which presets resolve
// to white), a header with back + live selection count, an inline search, a controller-first
// tile grid, and a controller prompt footer that overlays (never shifts) the grid.
//
// Stateless: driven entirely by [AppPickerState] plus callbacks, so the XMB shell wires it
// exactly like every other overlay. Focus and selection are independent layers — the check
// badge survives the cursor moving away (doc §7's most-repeated requirement).

@Composable
fun AppPickerScreen(
    state: AppPickerState,
    onTileTapped: (Int) -> Unit,
    onTouchBrowse: (Int) -> Unit,
    onHeaderBack: () -> Unit,
    onSearchToggle: (Boolean) -> Unit,
    onSearchChange: (String) -> Unit,
    onSearchDone: () -> Unit,
    onApply: () -> Unit,
    onConfirmRemoval: () -> Unit,
    onCancelRemoval: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sf = deriveStorefrontColors()
    val visible = state.visibleApps()

    Box(
        modifier = modifier
            .fillMaxSize()
            // No whole-background dismiss tap: with a grid and a search field it is an easy
            // accidental cancel. Back and the header's ‹ are the exits.
            .background(Brush.verticalGradient(listOf(sf.backgroundDeep, sf.backgroundMid))),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppPickerHeader(
                state = state,
                onBack = onHeaderBack,
                onSearchToggle = onSearchToggle,
                onSearchChange = onSearchChange,
                onSearchDone = onSearchDone,
                colors = sf,
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(sf.chromeDivider))

            Box(modifier = Modifier.weight(1f)) {
                if (visible.isEmpty()) {
                    Text(
                        text = if (state.query.isBlank()) "No installed apps"
                        else "No installed apps match \"${state.query.trim()}\"",
                        color = sf.textSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    AppPickerGrid(
                        state = state,
                        visible = visible,
                        onTileTapped = onTileTapped,
                        onTouchBrowse = onTouchBrowse,
                        colors = sf,
                    )
                }
            }
        }

        // Footer overlays the grid (BottomCenter) so its fades never shift the geometry —
        // the same convention AppDrawerScreen uses for its hint bar.
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(200)),
            exit = ExitTransition.None,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            AppPickerFooter(
                confirmingRemovals = state.confirmingRemovals,
                colors = sf,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }

        // Removal confirmation — centered panel over the grid, raised by the ViewModel
        // (confirmingRemovals) so controller SELECT and the touch buttons hit one path.
        if (state.confirmingRemovals) {
            RemovalConfirmPanel(
                labels = state.apps
                    .filter { it.packageName in state.pendingRemovals() }
                    .map { it.label },
                focusedOption = state.confirmFocusedOption,
                onConfirm = onConfirmRemoval,
                onCancel = onCancelRemoval,
                colors = sf,
            )
        }
    }
}

// ── Header: ‹ + title on the left, selection count + search on the right ──────

private val HEADER_HEIGHT = 56.dp

@Composable
private fun AppPickerHeader(
    state: AppPickerState,
    onBack: () -> Unit,
    onSearchToggle: (Boolean) -> Unit,
    onSearchChange: (String) -> Unit,
    onSearchDone: () -> Unit,
    colors: StorefrontColors,
) {
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Two-frame focus idiom (AppDrawerScreen): the field must be composed before the
    // FocusRequester can grab it.
    LaunchedEffect(state.searchActive) {
        if (state.searchActive) {
            withFrameNanos {}
            withFrameNanos {}
            runCatching { searchFocus.requestFocus() }
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = "\u2039",
                color = colors.textSecondary,
                fontSize = 18.sp,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(end = 8.dp),
            )
            Text(
                text = state.title,
                color = colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { onBack() },
            )
        }

        Text(
            text = "${state.selected.size} Selected",
            color = colors.textSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(end = 16.dp),
        )

        AnimatedVisibility(visible = state.searchActive, enter = fadeIn(), exit = fadeOut()) {
            BasicTextField(
                value = state.query,
                onValueChange = onSearchChange,
                singleLine = true,
                textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
                cursorBrush = SolidColor(colors.searchBorder),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearchDone() }, onDone = { onSearchDone() }),
                decorationBox = { inner ->
                    Box {
                        if (state.query.isEmpty()) Text(
                            "Search\u2026",
                            color = colors.textSecondary.copy(alpha = 0.6f),
                            fontSize = 14.sp,
                        )
                        inner()
                    }
                },
                modifier = Modifier
                    .width(220.dp)
                    .focusRequester(searchFocus)
                    .background(colors.searchField, RoundedCornerShape(2.dp))
                    .border(1.dp, colors.searchBorder, RoundedCornerShape(2.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        Spacer(Modifier.width(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onSearchToggle(!state.searchActive) },
        ) {
            // Hand-drawn magnifier — no icon vector.
            Canvas(modifier = Modifier.size(18.dp)) {
                val strokeW = 1.8f.dp.toPx()
                val cx = size.width * 0.42f
                val cy = size.height * 0.42f
                val r = size.width * 0.30f
                drawCircle(
                    color = colors.textSecondary,
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(strokeW),
                )
                drawLine(
                    color = colors.textSecondary,
                    start = Offset(cx + r * 0.70f, cy + r * 0.70f),
                    end = Offset(
                        cx + r * 0.70f + size.width * 0.22f,
                        cy + r * 0.70f + size.height * 0.22f,
                    ),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round,
                )
            }
            Spacer(Modifier.width(5.dp))
            Text(
                if (state.searchActive) "Clear" else "Search",
                color = colors.textSecondary,
                fontSize = 13.sp,
            )
        }
    }
}

// ── Grid ──────────────────────────────────────────────────────────────────────

@Composable
private fun AppPickerGrid(
    state: AppPickerState,
    visible: List<AppPickerEntry>,
    onTileTapped: (Int) -> Unit,
    onTouchBrowse: (Int) -> Unit,
    colors: StorefrontColors,
) {
    val gridState = rememberLazyGridState()

    // Scroll-into-view, clamped. Keyed on visible.size too, so a list that shrank under a
    // stationary cursor re-clamps (doc §16) instead of leaving the LazyColumn off the end.
    LaunchedEffect(state.focusedIndex, state.usingTouch, visible.size) {
        if (!state.usingTouch && visible.isNotEmpty()) {
            gridState.animateScrollToItem(state.focusedIndex.coerceIn(0, visible.lastIndex))
        }
    }

    // Touch reconciliation, same shape as AppDrawerGrid: drag-start parks the hidden cursor;
    // scroll-settle parks it on the tile nearest the viewport centre.
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
        columns = GridCells.Fixed(PICKER_GRID_COLUMNS),
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(visible, key = { _, app -> app.packageName }) { index, app ->
            AppPickerTile(
                entry = app,
                isFocused = !state.usingTouch && index == state.focusedIndex,
                isChecked = app.packageName in state.selected,
                onClick = { onTileTapped(index) },
                colors = colors,
            )
        }
    }
}

// ── Tile: focus chrome (drawer-faithful) + independent selection check badge ──

private val ARTWORK_SIZE = 72.dp
private val TILE_BORDER = 1.dp
// Chrome room around the artwork: outer border + 2dp gap + inner hairline on each side.
private val FRAME_ROOM = 8.dp
private val FOCUS_TWEEN = 120
private val CHECK_TWEEN = 100

@Composable
private fun AppPickerTile(
    entry: AppPickerEntry,
    isFocused: Boolean,
    isChecked: Boolean,
    onClick: () -> Unit,
    colors: StorefrontColors,
) {
    val focus by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = tween(FOCUS_TWEEN),
        label = "appPickerFocus",
    )
    val check by animateFloatAsState(
        targetValue = if (isChecked) 1f else 0f,
        animationSpec = tween(CHECK_TWEEN),
        label = "appPickerCheck",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)   // whole tile is the touch target — never just the badge
            .padding(vertical = 4.dp),
    ) {
        Box(modifier = Modifier.size(ARTWORK_SIZE + FRAME_ROOM)) {
            // ── Focus layer (AppDrawerGridItem geometry, alpha-driven only — no scale,
            // no bounce, no elevation, so tile geometry never shifts) ──
            Box(
                Modifier
                    .matchParentSize()
                    .background(colors.selectionGlow.copy(alpha = colors.selectionGlow.alpha * focus)),
            )
            Box(
                Modifier
                    .matchParentSize()
                    .border(TILE_BORDER, colors.tileSelectedEdge.copy(alpha = focus)),
            )
            Box(
                Modifier
                    .matchParentSize()
                    .padding(2.dp)
                    .border(TILE_BORDER, colors.tileSelectedInner.copy(alpha = focus)),
            )
            // ── Selection layer: persistent low-alpha tint so a checked tile reads from
            // across the grid, independent of the cursor. ──
            Box(
                Modifier
                    .matchParentSize()
                    .background(colors.tileSelectedInner.copy(alpha = 0.10f * check)),
            )
            val icon = entry.icon
            if (icon != null) {
                Image(
                    painter = rememberDrawablePainter(icon),
                    contentDescription = entry.label,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(ARTWORK_SIZE),
                )
            } else {
                Spacer(Modifier.size(ARTWORK_SIZE))
            }
            // Check badge — upper-right, independent of focus; survives the cursor leaving.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(18.dp)
                    .alpha(check)
                    .background(colors.tileSelectedEdge, RoundedCornerShape(50)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "✓",
                    color = colors.backgroundDeep,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = entry.label,
            color = if (isFocused) colors.textPrimary else colors.textSecondary,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = 13.sp,
        )
    }
}

// ── Footer: controller prompt bar (glyphs resolve from LocalControllerPromptStyle) ──

@Composable
private fun AppPickerFooter(
    confirmingRemovals: Boolean,
    colors: StorefrontColors,
    modifier: Modifier = Modifier,
) {
    // While the removal-confirmation modal is up the prompt describes the modal's controls —
    // the grid behind the scrim is inert.
    val items = if (confirmingRemovals) {
        listOf(
            ControllerPromptItem.fixed(ControllerIcon.DPAD_ALL, "Choose"),
            ControllerPromptItem(GamepadAction.SELECT, "Confirm"),
            ControllerPromptItem(GamepadAction.BACK, "Cancel"),
        )
    } else {
        listOf(
            ControllerPromptItem.fixed(ControllerIcon.DPAD_ALL, "Navigate"),
            ControllerPromptItem(GamepadAction.SELECT, "Toggle"),
            ControllerPromptItem(GamepadAction.CHANGE_SORT, "Search"),
            ControllerPromptItem(GamepadAction.HOME, "Apply"),
            ControllerPromptItem(GamepadAction.BACK, "Cancel"),
        )
    }
    ControllerPromptBar(
        items = items,
        labelColor = colors.textSecondary,
        labelStyle = TextStyle(fontSize = 12.sp),
        glyphSize = 16.dp,
        arrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
        modifier = modifier,
    )
}

// ── Removal confirmation panel (hand-built scrim + panel, like UninstallConfirmDialog) ──
//
// A hard input boundary: while it is up, dpad LEFT/RIGHT step the highlight between Cancel
// and Remove, SELECT activates the highlighted option, and BACK cancels — routed through
// XMBViewModel (confirmFocusedOption), never the grid behind the scrim.

/** One modal button row. Focus chrome is alpha/border only — geometry never shifts. */
@Composable
private fun ConfirmOption(
    label: String,
    focused: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
    colors: StorefrontColors,
) {
    val focus by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(CHECK_TWEEN),
        label = "confirmOptionFocus",
    )
    val baseColor = if (destructive) colors.destructive else colors.textSecondary
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp)
            .background(colors.tileSelectedInner.copy(alpha = 0.25f * focus), RoundedCornerShape(2.dp))
            .border(1.dp, colors.tileSelectedEdge.copy(alpha = focus), RoundedCornerShape(2.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = if (focus > 0.5f) colors.textPrimary else baseColor,
            fontSize = 14.sp,
            fontWeight = if (destructive) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun RemovalConfirmPanel(
    labels: List<String>,
    focusedOption: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    colors: StorefrontColors,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.overlayDim),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(340.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.menuPanel)
                .border(1.dp, colors.chromeDivider.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
        ) {
            Text(
                text = "Remove ${labels.size} app(s) from this library?",
                color = colors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .padding(horizontal = 12.dp)
                    .background(colors.chromeDivider.copy(alpha = 0.25f)),
            )
            Text(
                text = labels.joinToString(", "),
                color = colors.textSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConfirmOption(
                    label = "Cancel",
                    focused = focusedOption == AppPickerState.CONFIRM_CANCEL,
                    onClick = onCancel,
                    colors = colors,
                )
                ConfirmOption(
                    label = "Remove",
                    focused = focusedOption == AppPickerState.CONFIRM_REMOVE,
                    onClick = onConfirm,
                    destructive = true,
                    colors = colors,
                )
            }
        }
    }
}
