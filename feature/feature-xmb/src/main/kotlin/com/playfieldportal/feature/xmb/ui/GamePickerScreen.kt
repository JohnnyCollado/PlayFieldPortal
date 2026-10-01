package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
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
import com.playfieldportal.core.ui.components.ControllerPromptBar
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.core.ui.components.PfpCheckBadge
import com.playfieldportal.core.ui.icons.GameIconStyle
import com.playfieldportal.core.ui.theme.StorefrontColors
import com.playfieldportal.core.ui.theme.deriveStorefrontColors
import com.playfieldportal.feature.xmb.viewmodel.XMBItem
import kotlinx.coroutines.flow.distinctUntilChanged

// ── Game picker ("Add Games to Category"): a library of shelves ──────────────
//
// A vertical shelf list beside a fixed-column art grid, in the App Picker's language: the same
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
    viewModel: GamePickerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val sf = deriveStorefrontColors()
    // The view starts on the user's global icon display mode, then X steps it for this visit.
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
                                    onTileTapped = viewModel::tapTile,
                                    onTouchBrowse = viewModel::touchBrowse,
                                    colors = sf,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                        }
                    }
                }
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(sf.chromeDivider))
            PickerFooter(state.focusZone, sf, Modifier.fillMaxWidth().padding(vertical = 12.dp))
        }
    }
}

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
            text = "+${state.pendingAddCount()} adding · −${state.pendingRemovals().size} removing",
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

// ── Shelf list: a vertical tab list the D-pad steps through ───────────────────

private val LIST_WIDTH = 220.dp

@Composable
private fun ShelfList(
    state: GamePickerState,
    onShelfTapped: (Int) -> Unit,
    colors: StorefrontColors,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.shelfIndex) {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == state.shelfIndex }) listState.animateScrollToItem(state.shelfIndex)
    }
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
                    color = if (isCurrent) colors.textPrimary else colors.textSecondary,
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
            Box(Modifier.fillMaxWidth().height(2.dp).background(colors.textSecondary.copy(alpha = 0.18f))) {
                Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(colors.tileSelectedInner))
            }
        }
    }
}

// ── Shelf pane: heading + art grid ────────────────────────────────────────────

// Tallest art in any mode; every tile reserves it so mixed shapes share one ledge per row.
private val ART_HEIGHT = 88.dp
// Chrome room around the art: the focus frame sits this far outside the art on every side.
private val FRAME_PAD = 4.dp
private val ROW_SPACING = 14.dp
private val COLUMN_SPACING = 8.dp
private const val ICON0_ASPECT = 144f / 80f
// Cartridge and disc shots have no per-platform preset; a square frame suits both.
private const val PHYSICAL_MEDIA_ASPECT = 1f

@Composable
private fun ShelfPane(
    state: GamePickerState,
    shelf: PickerShelf,
    onTileTapped: (Int) -> Unit,
    onTouchBrowse: (Int) -> Unit,
    colors: StorefrontColors,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
        ShelfGrid(state, shelf, onTileTapped, onTouchBrowse, colors, Modifier.weight(1f))
    }
}

@Composable
private fun ShelfGrid(
    state: GamePickerState,
    shelf: PickerShelf,
    onTileTapped: (Int) -> Unit,
    onTouchBrowse: (Int) -> Unit,
    colors: StorefrontColors,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val focused = state.focusedIndex()
    val showCursor = !state.usingTouch && state.focusZone == PickerZone.GRID

    LaunchedEffect(focused, showCursor, shelf.size) {
        if (showCursor && shelf.size > 0) gridState.animateScrollToItem(focused.coerceIn(0, shelf.size - 1))
    }

    // Touch reconciliation, same shape as AppPickerGrid: drag-start parks the hidden cursor;
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
                    info.visibleItemsInfo
                        .minByOrNull { kotlin.math.abs(it.offset.y + it.size.height / 2 - center) }
                        ?.let { onTouchBrowse(it.index) }
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
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(GAME_PICKER_GRID_COLUMNS),
            contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(COLUMN_SPACING),
            verticalArrangement = Arrangement.spacedBy(ROW_SPACING),
            modifier = modifier.fillMaxSize(),
        ) {
            when (shelf) {
                is GameShelf -> itemsIndexed(shelf.games, key = { _, game -> game.id }) { index, game ->
                    val checked = game.id in state.selectedGameIds
                    PickerTile(
                        label = game.displayTitle,
                        isFocused = showCursor && index == focused,
                        isChecked = checked,
                        isRemoving = !checked && game.id in state.preselectedGameIds,
                        onClick = { onTileTapped(index) },
                        colors = colors,
                    ) { GameArt(game, state.viewMode, colors) }
                }
                is CardShelf -> itemsIndexed(shelf.cards, key = { _, card -> card.id }) { index, card ->
                    PickerTile(
                        label = card.name,
                        isFocused = showCursor && index == focused,
                        isChecked = card.id in state.selectedCollectionIds,
                        isRemoving = false,
                        onClick = { onTileTapped(index) },
                        colors = colors,
                    ) { CardArt(card, colors) }
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
    art: @Composable () -> Unit,
) {
    val focus by animateFloatAsState(if (isFocused) 1f else 0f, tween(FOCUS_TWEEN), label = "gamePickerFocus")
    val check by animateFloatAsState(if (isChecked) 1f else 0f, tween(CHECK_TWEEN), label = "gamePickerCheck")
    val ledge = colors.chromeDivider

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),   // the whole tile is the touch target
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
            color = if (isFocused) colors.textPrimary else colors.textSecondary,
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
 * The game in the picker's [view] mode. The art box takes that mode's shape — 144:80 for ICON0,
 * the platform's box for Box Art / 3D Box, square for Physical Media — so the focus frame hugs
 * the art. Placeholders take the theme's inner accent, so unscraped games stay in the theme.
 */
@Composable
private fun GameArt(game: Game, view: IconDisplayMode, colors: StorefrontColors) {
    val item = remember(game, colors.tileSelectedInner) {
        game.toPickerItem(accentArgb = colors.tileSelectedInner.toArgb().toLong() and 0xFFFFFFFFL)
    }
    val aspect = when (view) {
        IconDisplayMode.ICON0 -> ICON0_ASPECT
        IconDisplayMode.BOX_ART, IconDisplayMode.BOX_3D -> boxArtAspectFor(game.platformId)
        IconDisplayMode.PHYSICAL_MEDIA -> PHYSICAL_MEDIA_ASPECT
    }
    // Height-led so tall boxes reach the ledge height; wide shapes are capped by the column.
    val artModifier = if (view == IconDisplayMode.ICON0) {
        Modifier.fillMaxWidth().aspectRatio(aspect)
    } else {
        Modifier.height(ART_HEIGHT).aspectRatio(aspect, matchHeightConstraintsFirst = true)
    }
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

// ── Footer: the prompts for the level the cursor is on ───────────────────────

@Composable
private fun PickerFooter(zone: PickerZone, colors: StorefrontColors, modifier: Modifier = Modifier) {
    val items = when (zone) {
        PickerZone.RAIL -> listOf(
            ControllerPromptItem.fixed(listOf(ControllerIcon.DPAD_UP, ControllerIcon.DPAD_DOWN), "Shelf"),
            ControllerPromptItem(GamepadAction.SELECT, "Open"),
            ControllerPromptItem(GamepadAction.OPEN_CONTEXT_MENU, "Whole Shelf"),
            ControllerPromptItem(GamepadAction.CHANGE_SORT, "View"),
            ControllerPromptItem(GamepadAction.HOME, "Done"),
            ControllerPromptItem(GamepadAction.BACK, "Cancel"),
        )
        PickerZone.GRID -> listOf(
            ControllerPromptItem.fixed(ControllerIcon.DPAD_ALL, "Navigate"),
            ControllerPromptItem(GamepadAction.SELECT, "Toggle"),
            ControllerPromptItem(GamepadAction.OPEN_CONTEXT_MENU, "Whole Shelf"),
            ControllerPromptItem(GamepadAction.CHANGE_SORT, "View"),
            ControllerPromptItem(GamepadAction.HOME, "Done"),
            ControllerPromptItem(GamepadAction.BACK, "Shelves"),
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
