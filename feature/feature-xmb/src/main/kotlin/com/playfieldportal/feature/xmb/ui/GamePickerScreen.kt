package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.playfieldportal.core.domain.model.ControllerIcon
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameCollection
import com.playfieldportal.core.domain.model.GameContentType
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.IconDisplayMode
import com.playfieldportal.core.navigation.packFlowRows
import com.playfieldportal.core.ui.components.ControllerPromptBar
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.core.ui.components.PfpCheckBadge
import com.playfieldportal.core.ui.components.PspContextMenuOverlay
import com.playfieldportal.core.ui.icons.GameIconStyle
import com.playfieldportal.core.ui.keyboard.KeyboardPlacement
import com.playfieldportal.core.ui.keyboard.VirtualKeyboardTextInput
import com.playfieldportal.core.ui.keyboard.isVirtualKeyboardOverlayOpen
import com.playfieldportal.core.ui.keyboard.rememberVirtualKeyboardEdit
import com.playfieldportal.core.ui.keyboard.virtualKeyboardField
import com.playfieldportal.core.ui.theme.StorefrontColors
import com.playfieldportal.core.ui.theme.deriveStorefrontColors
import com.playfieldportal.core.ui.theme.dimmed
import com.playfieldportal.core.ui.theme.unselectedLabel
import com.playfieldportal.feature.xmb.viewmodel.XMBItem
import kotlinx.coroutines.flow.distinctUntilChanged

// ── Game picker ("Add Games to Category"): a library of shelves ──────────────
//
// A vertical shelf list beside free-flowing rows of art at XMB size, in the App Picker's language: the same
// storefront theming (deriveStorefrontColors — never LocalPFPColors.accentColor, which presets
// resolve to white), the same alpha-only focus chrome and check badge, and a prompt footer.
// Every tile is drawn in ONE icon display mode (the picker's view, X to change), whatever each
// game or console uses in the XMB, so a shelf reads as one consistent library standing on a thin
// ledge per row. Rules and navigation live in GamePickerLogic; this file only draws state.

@Composable
fun GamePickerScreen(
    onConfirm: (selectedGameIds: Set<Long>, selectedCollectionIds: Set<Long>) -> Unit,
    onCancel: () -> Unit,
    pendingGamepadAction: GamepadAction? = null,
    onGamepadActionConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
    // The category being filled: its games open checked, and only custom memory cards of the
    // same kind that are not already in it are offered.
    categoryId: String = "",
    categoryTitle: String = "",
    preselectedGameIds: Set<Long> = emptySet(),
    movableCollectionIds: Set<Long> = emptySet(),
    // Display ▸ Text Shadow: the Launcher's drop shadow behind every label, as the XMB draws it.
    textShadow: Boolean = true,
    viewModel: GamePickerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val sf = deriveStorefrontColors()
    // The view starts on the user's global icon display mode, then Y ▸ View changes it for this visit.
    val globalView = LocalIconDisplayMode.current

    LaunchedEffect(categoryId) {
        viewModel.prepare(preselectedGameIds, movableCollectionIds, categoryTitle, globalView)
    }

    // Confirm/cancel both clear the picker — the ViewModel is retained across open/close, so
    // selections must not carry over to the next time the picker is opened.
    val confirmAndClear: () -> Unit = {
        val (gameIds, collectionIds) = viewModel.getSelectedItems()
        onConfirm(gameIds, collectionIds)
        viewModel.clearSelection()
    }
    val cancelAndClear: () -> Unit = {
        viewModel.clearSelection()
        onCancel()
    }

    LaunchedEffect(pendingGamepadAction) {
        when (pendingGamepadAction) {
            null -> return@LaunchedEffect
            // B climbs one level (grid → shelf list) and closes from the list.
            GamepadAction.BACK -> if (!viewModel.back()) cancelAndClear()
            GamepadAction.HOME -> confirmAndClear()
            else -> viewModel.onAction(pendingGamepadAction)
        }
        onGamepadActionConsumed()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // No whole-background dismiss tap: B and the header's ‹ are the exits.
            .background(Brush.verticalGradient(listOf(sf.backgroundDeep, sf.backgroundMid))),
    ) {
        // Every label reads like the Launcher's: the XMB's directional drop shadow, when the user
        // keeps Text Shadow on and the storefront text is light (a dark-on-light theme gets none).
        ProvideTextStyle(LocalTextStyle.current.merge(TextStyle(shadow = pickerTextShadow(sf, textShadow)))) {
        Column(Modifier.fillMaxSize()) {
            PickerHeader(state, categoryTitle, onBack = cancelAndClear, colors = sf)
            Box(Modifier.fillMaxWidth().height(1.dp).background(sf.chromeDivider))

            Row(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = sf.tileSelectedEdge)
                    }
                    state.shelves.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No games in your library yet", color = sf.textSecondary, fontSize = 14.sp)
                    }
                    else -> {
                        ShelfList(state, onShelfTapped = viewModel::tapShelf, colors = sf)
                        state.currentShelf()?.let { shelf ->
                            // A fresh grid per shelf: each one scrolls on its own.
                            key(shelf.key) {
                                ShelfPane(
                                    state = state,
                                    shelf = shelf,
                                    onSearchChange = viewModel::onSearchChange,
                                    onSearchToggle = viewModel::onSearchToggle,
                                    onTileTapped = viewModel::tapTile,
                                    onTouchBrowse = viewModel::touchBrowse,
                                    onMeasured = viewModel::onShelfMeasured,
                                    colors = sf,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                        }
                    }
                }
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(sf.chromeDivider))
            PickerFooter(
                state,
                sf,
                // PFP's keyboard brings its own prompts; the picker's step aside while it is up.
                Modifier.fillMaxWidth().padding(vertical = 12.dp).alpha(if (isVirtualKeyboardOverlayOpen()) 0f else 1f),
            )
        }

        // Y's menu: the shared PSP panel, driven by PspMenuNav in the ViewModel.
        state.menu?.let { menu ->
            PspContextMenuOverlay(
                title = if (menu.level == PickerMenuLevel.ROOT) "Options" else "View",
                rows = state.menuRows(),
                selectedIndex = menu.selectedIndex,
                onRowActivated = viewModel::tapMenuRow,
                onDismiss = viewModel::dismissMenu,
            )
        }
        }
    }
}

// The XMB's drop shadow, only over light text with Text Shadow on.
private fun pickerTextShadow(colors: StorefrontColors, enabled: Boolean): Shadow? =
    if (enabled && colors.textPrimary.luminance() > 0.5f) XmbTextShadow else null

// ── Header: ‹ title on the left, what Done will do on the right ───────────────

@Composable
private fun PickerHeader(
    state: GamePickerState,
    categoryTitle: String,
    onBack: () -> Unit,
    colors: StorefrontColors,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f).clickable(onClick = onBack),
        ) {
            Text("‹", color = colors.textSecondary, fontSize = 18.sp, modifier = Modifier.padding(end = 8.dp))
            Text(
                text = buildAnnotatedString {
                    append("Add Games")
                    if (categoryTitle.isNotBlank()) {
                        withStyle(SpanStyle(color = colors.textSecondary, fontWeight = FontWeight.Normal)) {
                            append(" · $categoryTitle")
                        }
                    }
                },
                color = colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = pendingChangeLabel(state.pendingAddCount(), state.pendingRemovals().size),
            color = colors.textSecondary,
            fontSize = 13.sp,
        )
    }
}

// ── Focus chrome (AppPickerTile geometry: alpha only, so nothing ever shifts) ──

private const val FOCUS_TWEEN = 120
private const val CHECK_TWEEN = 100

/** Glow, bright outer edge and inner hairline, faded in by [focus] (0..1). */
@Composable
private fun BoxScope.FocusChrome(focus: Float, colors: StorefrontColors) {
    Box(Modifier.matchParentSize().background(colors.selectionGlow.copy(alpha = colors.selectionGlow.alpha * focus)))
    Box(Modifier.matchParentSize().border(1.dp, colors.tileSelectedEdge.copy(alpha = focus)))
    Box(Modifier.matchParentSize().padding(2.dp).border(1.dp, colors.tileSelectedInner.copy(alpha = focus)))
}

/**
 * Scrolls just far enough for item [index] to be wholly on screen (see [scrollIntoViewDelta]); an
 * item not composed at all is scrolled to directly.
 */
private suspend fun LazyListState.keepOnScreen(index: Int) {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        animateScrollToItem(index)
        return
    }
    val delta = scrollIntoViewDelta(item.offset, item.offset + item.size, info.viewportStartOffset, info.viewportEndOffset)
    if (delta != 0) animateScrollBy(delta.toFloat())
}

// ── Shelf list: a vertical tab list the D-pad steps through ───────────────────

private val LIST_WIDTH = 220.dp

@Composable
private fun ShelfList(
    state: GamePickerState,
    onShelfTapped: (Int) -> Unit,
    colors: StorefrontColors,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.shelfIndex) { listState.keepOnScreen(state.shelfIndex) }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.width(LIST_WIDTH).fillMaxHeight().background(colors.railBackground),
    ) {
        itemsIndexed(state.shelves, key = { _, shelf -> shelf.key }) { index, shelf ->
            ShelfListEntry(
                shelf = shelf,
                checked = state.checkedCount(shelf),
                isCurrent = index == state.shelfIndex,
                hasCursor = index == state.shelfIndex && state.focusZone == PickerZone.RAIL && !state.usingTouch,
                // The custom memory cards are a different kind of thing; a gap sets them apart.
                separated = shelf is CardShelf && index > 0,
                onClick = { onShelfTapped(index) },
                colors = colors,
            )
        }
    }
}

@Composable
private fun ShelfListEntry(
    shelf: PickerShelf,
    checked: Int,
    isCurrent: Boolean,
    hasCursor: Boolean,
    separated: Boolean,
    onClick: () -> Unit,
    colors: StorefrontColors,
) {
    val focus by animateFloatAsState(if (hasCursor) 1f else 0f, tween(FOCUS_TWEEN), label = "shelfCursor")
    val edge = colors.categorySelectedEdge
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (separated) 10.dp else 0.dp)
            .clickable(onClick = onClick)
            // The shelf on display keeps its fill and right edge while the cursor is in the grid,
            // so the list always says which shelf the grid belongs to.
            .background(if (isCurrent) colors.categorySelected else Color.Transparent)
            .drawBehind {
                if (isCurrent) {
                    val w = 2.dp.toPx()
                    drawRect(edge, topLeft = Offset(size.width - w, 0f), size = size.copy(width = w))
                }
            },
    ) {
        FocusChrome(focus, colors)
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 9.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = shelf.title,
                    color = if (isCurrent) colors.textPrimary else colors.unselectedLabel(),
                    fontSize = 13.sp,
                    fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text("$checked / ${shelf.size}", color = colors.textSecondary, fontSize = 11.sp)
            }
            Spacer(Modifier.height(6.dp))
            val fraction = if (shelf.size > 0) checked.toFloat() / shelf.size else 0f
            Box(Modifier.fillMaxWidth().height(2.dp).background(colors.iconSecondary.copy(alpha = 0.18f))) {
                Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(colors.tileSelectedInner))
            }
        }
    }
}

// ── Shelf pane: heading + art grid ────────────────────────────────────────────

// Tallest art in any mode (the XMB's natural-art height); every tile reserves it so mixed shapes
// share one ledge per row. Sizes come from GamePickerLogic, which packs the rows with them.
private val ART_HEIGHT = PICKER_ART_HEIGHT_DP.dp
private val FRAME_PAD = PICKER_FRAME_PAD_DP.dp
private val ROW_SPACING = 14.dp
private val COLUMN_SPACING = PICKER_TILE_SPACING_DP.dp

@Composable
private fun ShelfPane(
    state: GamePickerState,
    shelf: PickerShelf,
    onSearchChange: (String) -> Unit,
    onSearchToggle: (Boolean) -> Unit,
    onTileTapped: (Int) -> Unit,
    onTouchBrowse: (Int) -> Unit,
    onMeasured: (Float) -> Unit,
    colors: StorefrontColors,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShelfSearchField(state, onSearchChange, onSearchToggle, colors)
            Text(shelf.title, color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(12.dp))
            Text(
                text = when (shelf) {
                    is GameShelf -> "${shelf.size} games · ${state.checkedCount(shelf)} checked"
                    is CardShelf -> "${shelf.size} cards · picking one moves it here"
                },
                color = colors.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            if (shelf is GameShelf) {
                Text(
                    text = "View: ${state.viewMode.label}",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .border(1.dp, colors.tileSelectedEdge.copy(alpha = 0.55f), RoundedCornerShape(2.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        ShelfGrid(state, shelf, onTileTapped, onTouchBrowse, onMeasured, colors, Modifier.weight(1f))
    }
}

@Composable
private fun ShelfGrid(
    state: GamePickerState,
    shelf: PickerShelf,
    onTileTapped: (Int) -> Unit,
    onTouchBrowse: (Int) -> Unit,
    onMeasured: (Float) -> Unit,
    colors: StorefrontColors,
    modifier: Modifier = Modifier,
) = BoxWithConstraints(modifier.fillMaxSize()) {
    // The logic packs the same rows from the width reported here, so D-pad moves match the screen.
    val paneWidth = maxWidth.value
    LaunchedEffect(paneWidth) { onMeasured(paneWidth) }
    val widths = remember(shelf, state.viewMode) { shelf.tileWidthsDp(state.viewMode) }
    val rows = remember(widths, paneWidth) { packFlowRows(widths, paneWidth, PICKER_TILE_SPACING_DP) }

    val listState = rememberLazyListState()
    val focused = state.focusedIndex()
    val showCursor = !state.usingTouch && state.focusZone == PickerZone.GRID

    LaunchedEffect(focused, showCursor, rows) {
        val row = rows.rowOf(focused)
        if (showCursor && row >= 0) listState.keepOnScreen(row)
    }

    // Touch reconciliation, same shape as AppPickerGrid: drag-start parks the hidden cursor;
    // scroll-settle parks it in the row nearest the viewport centre (on the cursor if it's there).
    val currentRows by rememberUpdatedState(rows)
    val currentFocused by rememberUpdatedState(focused)
    fun parkIn(row: Int) {
        val range = currentRows.getOrNull(row) ?: return
        onTouchBrowse(if (currentFocused in range) currentFocused else range.first)
    }
    var fingerScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                fingerScrolled = true
                parkIn(listState.firstVisibleItemIndex)
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (!scrolling && fingerScrolled) {
                    fingerScrolled = false
                    val info = listState.layoutInfo
                    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                    info.visibleItemsInfo
                        .minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - center) }
                        ?.let { parkIn(it.index) }
                }
            }
    }

    // One mode for every tile: the picker's view replaces the per-game, per-console and global
    // modes for this screen only. ICON1 video snaps never play here.
    CompositionLocalProvider(
        LocalIconDisplayMode provides state.viewMode,
        LocalIconDisplayModeByPlatform provides emptyMap(),
        LocalFocusedGameVideo provides null,
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(ROW_SPACING),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(rows.size) { r ->
                // Rows are left-aligned and each tile exactly its own width, so shapes flow freely.
                Row(horizontalArrangement = Arrangement.spacedBy(COLUMN_SPACING)) {
                    for (index in rows[r]) {
                        val tileModifier = Modifier.width(widths[index].dp)
                        when (shelf) {
                            is GameShelf -> {
                                val game = shelf.games[index]
                                val checked = game.id in state.selectedGameIds
                                key(game.id) {
                                    PickerTile(
                                        label = game.displayTitle,
                                        isFocused = showCursor && index == focused,
                                        isChecked = checked,
                                        isRemoving = !checked && game.id in state.preselectedGameIds,
                                        onClick = { onTileTapped(index) },
                                        colors = colors,
                                        modifier = tileModifier,
                                    ) { GameArt(game, state.viewMode, colors) }
                                }
                            }
                            is CardShelf -> {
                                val card = shelf.cards[index]
                                key(card.id) {
                                    PickerTile(
                                        label = card.name,
                                        isFocused = showCursor && index == focused,
                                        isChecked = card.id in state.selectedCollectionIds,
                                        isRemoving = false,
                                        onClick = { onTileTapped(index) },
                                        colors = colors,
                                        modifier = tileModifier,
                                    ) { CardArt(card, colors) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Tile: the art stands on the ledge; focus, tint and badge hug the art ──────

@Composable
private fun PickerTile(
    label: String,
    isFocused: Boolean,
    isChecked: Boolean,
    isRemoving: Boolean,
    onClick: () -> Unit,
    colors: StorefrontColors,
    modifier: Modifier = Modifier,
    art: @Composable () -> Unit,
) {
    val focus by animateFloatAsState(if (isFocused) 1f else 0f, tween(FOCUS_TWEEN), label = "gamePickerFocus")
    val check by animateFloatAsState(if (isChecked) 1f else 0f, tween(CHECK_TWEEN), label = "gamePickerCheck")
    val ledge = colors.chromeDivider

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable(onClick = onClick),   // the whole tile is the touch target
    ) {
        // The slot reserves the tallest art so every row's ledge lines up; the art is bottom-
        // aligned onto it and the chrome wraps only the art, never the empty space above it.
        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier
                .fillMaxWidth()
                .height(ART_HEIGHT + FRAME_PAD * 2)
                // The ledge: a thin accent line at the art's foot with a short shadow below it.
                // It runs into the column gap on both sides, so a full row reads as one shelf.
                .drawBehind {
                    val gap = COLUMN_SPACING.toPx() / 2f
                    val y = size.height - FRAME_PAD.toPx()
                    drawRect(
                        brush = Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.22f), Color.Transparent),
                            startY = y,
                            endY = y + 8.dp.toPx(),
                        ),
                        topLeft = Offset(-gap, y),
                        size = size.copy(width = size.width + gap * 2, height = 8.dp.toPx()),
                    )
                    drawLine(ledge.copy(alpha = 0.35f), Offset(-gap, y), Offset(size.width + gap, y), 1.dp.toPx())
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                FocusChrome(focus, colors)
                // Selection layer: a low tint that reads across the grid, independent of the cursor.
                Box(Modifier.matchParentSize().background(colors.tileSelectedInner.copy(alpha = 0.10f * check)))
                Box(Modifier.padding(FRAME_PAD).alpha(if (isRemoving) 0.45f else 1f)) { art() }
                PfpCheckBadge(
                    fill = colors.tileSelectedEdge,
                    markColor = colors.backgroundDeep.copy(alpha = 1f),
                    size = 16.dp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(1.dp).alpha(check),
                )
                if (isRemoving) RemovalMark(colors.destructive, Modifier.align(Alignment.TopEnd).padding(1.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = buildAnnotatedString {
                if (isRemoving) withStyle(SpanStyle(color = colors.destructive)) { append("Removing · ") }
                append(label)
            },
            color = if (isFocused) colors.textPrimary else colors.unselectedLabel(),
            fontSize = 11.sp,
            lineHeight = 13.sp,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** A dashed ring where the check badge was: this game leaves the category on Done. */
@Composable
private fun RemovalMark(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(16.dp)) {
        val stroke = 1.dp.toPx()
        drawCircle(
            color = color,
            radius = size.minDimension / 2f - stroke,
            style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
        )
    }
}

// ── Art ───────────────────────────────────────────────────────────────────────

/**
 * The game in the picker's [view] mode, at its XMB size — ICON0 at the game row's 126 × 70, the
 * platform's box / square physical media at the natural-art height — so the focus frame hugs the
 * art. Placeholders take the theme's inner accent, so unscraped games stay in the theme.
 */
@Composable
private fun GameArt(game: Game, view: IconDisplayMode, colors: StorefrontColors) {
    val item = remember(game, colors.tileSelectedInner) {
        game.toPickerItem(accentArgb = colors.tileSelectedInner.toArgb().toLong() and 0xFFFFFFFFL)
    }
    val artHeight = if (view == IconDisplayMode.ICON0) PICKER_ICON0_HEIGHT_DP.dp else ART_HEIGHT
    val artModifier = Modifier.size(width = pickerArtWidthDp(game.platformId, view).dp, height = artHeight)
    GameIcon(
        item = item,
        iconStyle = GameIconStyle.PSP_RECTANGLE,
        naturalArtHeight = ART_HEIGHT,
        modifier = artModifier,
    )
}

/** A custom memory card: a landscape card with its game count. */
@Composable
private fun CardArt(card: GameCollection, colors: StorefrontColors) {
    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .height(ART_HEIGHT)
            .clip(RoundedCornerShape(3.dp))
            .background(Brush.linearGradient(listOf(colors.tileSelected, colors.tileNormal)))
            .border(1.dp, colors.tileSelectedInner.copy(alpha = 0.4f), RoundedCornerShape(3.dp))
            .padding(8.dp),
    ) {
        Text(
            text = card.name,
            color = colors.textPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (card.gameCount == 1) "1 game" else "${card.gameCount} games",
            color = colors.textSecondary,
            fontSize = 9.sp,
        )
    }
}

/**
 * The fields [GameIcon] reads, from a library [Game]. The per-game display override is left
 * out on purpose: the picker draws every tile in its own single view.
 */
internal fun Game.toPickerItem(accentArgb: Long): XMBItem = XMBItem(
    id = id.toString(),
    title = displayTitle,
    artworkUri = artworkUri,
    iconUri = iconUri,
    boxArtUri = boxArtUri,
    physicalMediaUri = physicalMediaUri,
    box3dUri = box3dUri,
    gameId = id,
    platformId = platformId,
    accentColor = accentArgb,
    isAndroidApp = packageName != null,
    isRealGame = contentType == GameContentType.GAME,
    packageName = packageName,
)

// ── X: search the shelf on screen ─────────────────────────────────────────────

/**
 * The shelf's search field, on the shared text-field structure: PFP's virtual keyboard when the
 * controller opened it (the system keyboard for touch), Done keeps the query, BACK closes the search.
 * The same two-frame focus idiom as the App Picker's search.
 */
@Composable
private fun ShelfSearchField(
    state: GamePickerState,
    onSearchChange: (String) -> Unit,
    onSearchToggle: (Boolean) -> Unit,
    colors: StorefrontColors,
) {
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val searchEdit = rememberVirtualKeyboardEdit(
        text = state.query,
        onTextChange = onSearchChange,
        placement = KeyboardPlacement.BOTTOM_CENTER,
        onClose = { onSearchToggle(false) },
    )
    // Keyed on searchReopens too: X on an open search with text brings a keyboard back.
    LaunchedEffect(state.searchActive, state.searchReopens) {
        if (state.searchActive) {
            withFrameNanos {}
            withFrameNanos {}
            val virtual = searchEdit.isOpen || searchEdit.start()
            if (virtual) withFrameNanos {}
            runCatching { searchFocus.requestFocus() }
            if (!virtual) keyboard?.show()
        } else {
            searchEdit.stop()
            keyboard?.hide()
        }
    }
    AnimatedVisibility(visible = state.searchActive, enter = fadeIn(), exit = fadeOut()) {
        VirtualKeyboardTextInput(searchEdit) {
            BasicTextField(
                value = searchEdit.fieldValue,
                onValueChange = searchEdit::onFieldValueChange,
                singleLine = true,
                textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
                cursorBrush = SolidColor(colors.searchBorder),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                decorationBox = { inner ->
                    Box {
                        if (state.query.isEmpty()) Text(
                            "Search this shelf\u2026",
                            color = colors.textSecondary.dimmed(0.6f),
                            fontSize = 14.sp,
                        )
                        inner()
                    }
                },
                modifier = Modifier
                    .padding(end = 12.dp)
                    .width(220.dp)
                    .virtualKeyboardField(searchEdit)
                    .focusRequester(searchFocus)
                    .background(colors.searchField, RoundedCornerShape(2.dp))
                    .border(1.dp, colors.searchBorder, RoundedCornerShape(2.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

// ── Footer: the prompts for the level the cursor is on ───────────────────────

@Composable
private fun PickerFooter(state: GamePickerState, colors: StorefrontColors, modifier: Modifier = Modifier) {
    // B names what it will do: close a search first, then climb, then cancel.
    val back = when {
        state.searchActive -> "Close Search"
        state.focusZone == PickerZone.GRID -> "Shelves"
        else -> "Cancel"
    }
    val items = when (state.focusZone) {
        PickerZone.RAIL -> listOf(
            ControllerPromptItem.fixed(listOf(ControllerIcon.DPAD_UP, ControllerIcon.DPAD_DOWN), "Shelf"),
            ControllerPromptItem(GamepadAction.SELECT, "Open"),
            ControllerPromptItem(GamepadAction.OPEN_CONTEXT_MENU, "Options"),
            ControllerPromptItem(GamepadAction.CHANGE_SORT, "Search"),
            ControllerPromptItem(GamepadAction.HOME, "Done"),
            ControllerPromptItem(GamepadAction.BACK, back),
        )
        PickerZone.GRID -> listOf(
            ControllerPromptItem.fixed(ControllerIcon.DPAD_ALL, "Navigate"),
            ControllerPromptItem(GamepadAction.SELECT, "Toggle"),
            ControllerPromptItem(GamepadAction.OPEN_CONTEXT_MENU, "Options"),
            ControllerPromptItem(GamepadAction.CHANGE_SORT, "Search"),
            ControllerPromptItem(GamepadAction.HOME, "Done"),
            ControllerPromptItem(GamepadAction.BACK, back),
        )
    }
    ControllerPromptBar(
        items = items,
        labelColor = colors.textSecondary,
        labelStyle = LocalTextStyle.current.merge(TextStyle(fontSize = 12.sp)),
        glyphSize = 16.dp,
        arrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
        modifier = modifier,
    )
}
