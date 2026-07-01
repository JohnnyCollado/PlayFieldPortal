package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.playfieldportal.core.ui.icons.GameIconStyle
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.feature.xmb.viewmodel.XMBItem
import com.playfieldportal.feature.xmb.viewmodel.XMBItemType

// Game icons use the authentic PSP ICON0 ratio 144:80 (= 1.8), scaled for the list.
private val GAME_ICON_WIDTH = 126.dp
private val GAME_ICON_HEIGHT = 70.dp

// Every row is exactly this tall so the list viewport can be sized to a whole number of rows —
// this is what lets us "hard stop" at a row boundary and never render a partially-clipped row.
private val ROW_HEIGHT = 88.dp

// Gap between a wide artwork tile and its title. Small-icon rows get this spacing for free from
// their 58dp icon box; the 126dp artwork tiles have none, so the text butts against the art.
private val ARTWORK_TEXT_GAP = 16.dp

// The tap target is shorter than the full row so there are inert gaps between rows, and it wraps
// the content width so the empty space to the right of the text isn't clickable — both keep stray
// touches from selecting/launching items.
private val TAP_TARGET_HEIGHT = 72.dp

// Fixed-width slot every leading icon is centred in, so an icon's horizontal centre is independent
// of its own size — icons can grow without breaking the caticon alignment.
internal val LEADING_ICON_SLOT = 64.dp
// Default size of the glyph/art inside that slot (selected rows additionally scale up via the row).
private val LEADING_ICON_SIZE = 52.dp
// Horizontal centre of a row's leading icon from the row's left edge: 18.dp row padding + half the
// slot. The grow/shrink scale pivots here, and the column is shifted so this lands on the caticon's
// vertical line. Shared with XMBShell's column offset so the two never drift apart.
internal val LEADING_ICON_CENTER = 18.dp + LEADING_ICON_SLOT / 2

// Classic PSP blue theme: the selected row is crisp white; unselected rows recede into a dimmer
// blue-white so they read against the saturated blue gradient.
private val PrimaryText = Color.White
private val SecondaryText = Color(0xAAC8DAF2)
private val InactiveText = Color(0xCCD8E6FF)
// Soft dark halo behind the bright selected label — keeps white legible on the light wave.
private val SelectedTextShadow = Shadow(
    color = Color(0x73001627),
    offset = Offset.Zero,
    blurRadius = 12f,
)

// A row's inner content padding. Shared so anything that needs to land next to a row's artwork
// (e.g. the flyout cursor) can derive its position from the same number the row lays out with.
private val ROW_HORIZONTAL_PADDING = 18.dp

// Physical-media memory-card art for rows that should read as a memory card but have no console icon
// of their own (collections). Mirrors the ViewModel's MEMORY_CARD_ASSET_URI.
private const val MEMORY_CARD_DEFAULT_ART = "file:///android_asset/systems/physical-media/_default.png"

// ── Drill flyout layout ──────────────────────────────────────────────────────
// Left inset of the game-card column, measured from the flyout's left edge (which the caller has
// already shifted under the caticon). Clears the memory-card icon column on the left so the game
// cards sit to its right, with room for the ◀ that trails the active memory card.
private val DRILL_GAME_COLUMN_LEFT = 150.dp

// ── Two-pane drill flyout (PSP/XMB style) ────────────────────────────────────
//
// Two columns sharing the same [belowTopY] line (the row directly under the caticon):
//
//   • LEFT — the PLATFORM MEMORY CARDS (the items: All Games / Favorites / consoles / collections),
//     rendered by the main [XMBItemList] itself so the cross is identical: the drilled-into card at
//     belowTopY with a ◀ trailing it, the previous card half-clipped above the bar. Icon-only.
//     This column is fixed while you run through the games.
//   • RIGHT — the GAME CARDS (rom icons), icon-only, in a centre-pinned column: the active game is
//     pinned on the belowTopY / ◀ line and the tween glides the next/previous card onto the pin.
//
//     [ card 3 ]   ½-clipped above the bar
//  ═══ caticon bar (right hidden) ═══
//     [ card 2 ] ◀      [ ACTIVE GAME ]   ← belowTopY: active memory card + ◀ + active game card
//     [ card 1 ]        [ game ]
//                       [ game ]
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun XmbDrillFlyout(
    siblings: List<XMBItem>,
    siblingIndex: Int,
    items: List<XMBItem>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    onItemLongPress: (Int) -> Unit,
    iconStyle: GameIconStyle = GameIconStyle.PSP_RECTANGLE,
    // The Y of the category bar's top edge and bottom edge — passed the SAME values as the main XMB
    // so the drill is laid out identically: active row under the caticon, previous half-clipped above.
    barTopY: Dp = 40.dp,
    belowTopY: Dp = 152.dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        // LEFT: the memory-card cross — the main XMB list itself, icon-only, with a ◀ after the
        // active (drilled-into) card. Static while navigating games.
        XMBItemList(
            items = siblings,
            selectedIndex = siblingIndex,
            onItemSelected = {},
            onItemLongPress = {},
            iconStyle = iconStyle,
            barTopY = barTopY,
            belowTopY = belowTopY,
            forceHideText = true,
            drillCursorOnSelected = true,
            modifier = Modifier.fillMaxSize(),
        )

        // RIGHT: the game cards — a single continuous column laid out (not scrolled) so the active
        // game sits exactly on the belowTopY / ◀ line, with the previous card contiguous directly
        // above it and the next below — uniform ROW_HEIGHT spacing throughout, no crossbar gap and
        // no scroll state to lag the highlight. Offset to the right of the memory-card column.
        XmbGameColumn(
            items = items,
            selectedIndex = selectedIndex,
            iconStyle = iconStyle,
            belowTopY = belowTopY,
            onItemSelected = onItemSelected,
            onItemLongPress = onItemLongPress,
            modifier = Modifier.fillMaxSize().padding(start = DRILL_GAME_COLUMN_LEFT),
        )
    }
}

// The flyout's game column: every game in one continuous column, laid out so [selectedIndex] lands on
// [belowTopY]. Because it's pure layout (no LazyColumn scroll), the active card is always exactly on
// the line — the highlight can't drift — and the rows keep uniform ROW_HEIGHT spacing with no gap
// above the active. Only the rows that can reach the viewport are rendered.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun XmbGameColumn(
    items: List<XMBItem>,
    selectedIndex: Int,
    iconStyle: GameIconStyle,
    belowTopY: Dp,
    onItemSelected: (Int) -> Unit,
    onItemLongPress: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize().clipToBounds()) {
        if (items.isEmpty()) return@BoxWithConstraints
        val sel = selectedIndex.coerceIn(0, items.lastIndex)
        // Window: only the rows that can land on screen above/below the active one (+2 buffer each way
        // so the next/previous card is always already composed before it scrolls into view).
        val rowsAbove = (belowTopY.value / ROW_HEIGHT.value).toInt() + 2
        val rowsBelow = ((maxHeight.value - belowTopY.value) / ROW_HEIGHT.value).toInt() + 2
        val first = (sel - rowsAbove).coerceAtLeast(0)
        val last = (sel + rowsBelow).coerceAtMost(items.lastIndex)
        // Place each row by its OWN absolute offset from the anchor line: the active row (i == sel)
        // lands exactly on belowTopY, earlier rows one ROW_HEIGHT up each, later rows one down each.
        // Independent placement (not a shared Column) guarantees rows past the active are laid out.
        for (i in first..last) {
            XmbVerticalListRow(
                item = items[i],
                isSelected = i == selectedIndex,
                showText = true,   // every game card keeps its [Title] / {Platform (Emulator)} label
                iconStyle = iconStyle,
                onClick = { onItemSelected(i) },
                onLongPress = { onItemLongPress(i) },
                showIcon = true,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height(ROW_HEIGHT)
                    .offset(y = belowTopY + ROW_HEIGHT * (i - sel)),
            )
        }
    }
}

// One sibling console icon — plain glyph (no tile/shadow), dimmed when not the active sibling.
@Composable
private fun SiblingIcon(item: XMBItem, selected: Boolean) {
    val chip = if (selected) 56.dp else 40.dp
    Box(
        modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Image(
            painter = painterResource(rememberConsoleIconId(consoleIconKeyFor(item))),
            contentDescription = item.title,
            modifier = Modifier.size(chip).alpha(if (selected) 1f else 0.5f),
        )
    }
}

// Maps a memory-card-style item to its sysicon key (mirrors XmbItemLeadingIcon's mapping).
private fun consoleIconKeyFor(item: XMBItem): String? = when (item.type) {
    XMBItemType.ALL_GAMES   -> "allgames"
    XMBItemType.FAVORITES   -> "favorites"
    XMBItemType.MEMORY_CARD -> item.platformId
    else                    -> null   // collections / unknown fall back to sysicon_default
}

// A vertical list whose [selectedIndex] row is pinned to a fixed line; rows scroll under it. When
// [anchorTopY] is unspecified the row centres vertically; otherwise the row's TOP is pinned at
// [anchorTopY] (used by the drill flyout to seat the active row just below the caticon, exactly like
// the main XMB, with earlier rows scrolling up past it). Each row is exactly [rowHeight] tall.
@Composable
private fun CenterLockedColumn(
    count: Int,
    selectedIndex: Int,
    rowHeight: Dp,
    modifier: Modifier = Modifier,
    anchorTopY: Dp = Dp.Unspecified,
    row: @Composable (index: Int) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.clipToBounds()) {
        val density = LocalDensity.current
        val centered = anchorTopY.isUnspecified
        // Where the active row's TOP sits, and the padding that lets the first/last rows reach it.
        val topPad = (if (centered) (maxHeight - rowHeight) / 2 else anchorTopY).coerceAtLeast(0.dp)
        val bottomPad = (if (centered) (maxHeight - rowHeight) / 2 else maxHeight - anchorTopY - rowHeight)
            .coerceAtLeast(0.dp)
        val anchorPx = with(density) { topPad.toPx() }
        val listState = rememberLazyListState()

        LaunchedEffect(selectedIndex, count, anchorPx) {
            if (count == 0) return@LaunchedEffect
            val idx = selectedIndex.coerceIn(0, count - 1)
            // If the target is off-screen (e.g. a big jump or first composition), get it measured
            // and roughly in view instantly so the visible glide covers only the final short delta —
            // this avoids a long, laggy sweep across many rows.
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == idx }) {
                listState.scrollToItem(idx, scrollOffset = -anchorPx.toInt())
            }
            val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == idx }
                ?: return@LaunchedEffect
            // Glide by the exact remaining delta so the row's top settles precisely on the line.
            // A single ease-in-out tween reads as one smooth motion with no spring overshoot/bounce.
            val delta = item.offset - anchorPx
            if (delta != 0f) {
                listState.animateScrollBy(
                    delta,
                    animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                )
            }
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = topPad, bottom = bottomPad),
            userScrollEnabled = false,   // selection-driven; taps still work, drag can't fight the lock
            modifier = Modifier.fillMaxSize(),
        ) {
            items(count) { index ->
                // Centre the ROW_HEIGHT content within the taller (row + gap) cell so the card's
                // midline lands exactly on the shared centre line — same line as the sibling & arrow.
                Box(
                    modifier = Modifier.fillMaxWidth().height(rowHeight),
                    contentAlignment = Alignment.Center,
                ) { row(index) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun XMBItemList(
    items: List<XMBItem>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    onItemLongPress: (Int) -> Unit,
    iconStyle: GameIconStyle = GameIconStyle.PSP_RECTANGLE,
    // Increments when the list must snap to the top regardless of cursor position (e.g. a sort
    // cycle). Necessary because keyed reorders otherwise keep the viewport anchored to the old item.
    scrollToTopToken: Int = 0,
    // Y of the category bar's TOP edge, measured from the top of this list.
    barTopY: Dp = 40.dp,
    // Y of the category bar's BOTTOM edge — where the selected item is seated, directly under the
    // caticon. The previous item sits one row ABOVE barTopY; the bar is the gap between them.
    belowTopY: Dp = 152.dp,
    // When false, rows render text-only (no game-icon artwork).
    showIcons: Boolean = true,
    // When true, EVERY row is icon-only (labels suppressed) — used by the flyout's memory-card column.
    forceHideText: Boolean = false,
    // When true, EVERY row shows its label — used by the flyout's game column so all cards are named.
    forceShowText: Boolean = false,
    // When true, the selected row gets a ◀ drill cursor pinned directly to its right.
    drillCursorOnSelected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // The XMB cross, exactly as the hardware does it:
    //
    //     [ previous item ]   ← one row above the bar (the only thing shown above it)
    //     [ CATEGORY  BAR ]   ← fixed pivot
    //     [ SELECTED item ]   ← directly below the bar
    //     [ next item     ]
    //     [ next+1 …       ]
    //
    // Pressing down slides the whole column up one: the old selected becomes the previous (above the
    // bar) and the next becomes selected (below it). The bar is taller than a row, so the column is
    // rendered in two pieces — one item above, selected + following below — rather than one list.
    BoxWithConstraints(modifier = modifier.fillMaxWidth().fillMaxHeight().clipToBounds()) {
        val rowsBelow = (((maxHeight.value - belowTopY.value) / ROW_HEIGHT.value).toInt() + 1)
            .coerceAtLeast(1)
        val sel = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))

        // BELOW the bar: the selected item first, then the items after it.
        if (items.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().offset(y = belowTopY)) {
                val last = minOf(items.size, sel + rowsBelow)
                for (i in sel until last) {
                    XmbVerticalListRow(
                        item = items[i],
                        isSelected = i == selectedIndex,
                        // Force-show names every row; force-hide none; else the classic XMB shows the
                        // selected row and the next one (and all rows when icons are hidden).
                        showText = forceShowText ||
                            (!forceHideText && (!showIcons || i == selectedIndex || i == selectedIndex + 1)),
                        iconStyle = iconStyle,
                        onClick = { onItemSelected(i) },
                        onLongPress = { onItemLongPress(i) },
                        showIcon = showIcons,
                        trailingCursor = drillCursorOnSelected && i == selectedIndex,
                        modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT),
                    )
                }
            }
        }

        // ABOVE the bar: only the immediately-previous item, and only its BOTTOM HALF — the top half
        // is clipped off above the bar, so it reads as "coming in" from behind the crossbar. The clip
        // window is half a row tall, seated just above the bar; the full-height row inside is shifted
        // up by half a row so its lower half lands in the window.
        if (selectedIndex in 1..items.lastIndex) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ROW_HEIGHT / 2)
                    .offset(y = barTopY - ROW_HEIGHT / 2)
                    .clipToBounds(),
                // Bottom-align a full-height row inside a half-height window: its top half is clipped.
                // requiredHeight keeps the row its full ROW_HEIGHT (the window would otherwise coerce
                // it down to half) so it overflows upward and the clip cuts the top half off.
                contentAlignment = Alignment.BottomStart,
            ) {
                XmbVerticalListRow(
                    item = items[selectedIndex - 1],
                    isSelected = false,
                    // Force-show names the clipped previous card too; else icon/force-hide keep it bare.
                    showText = forceShowText || (!forceHideText && !showIcons),
                    iconStyle = iconStyle,
                    onClick = { onItemSelected(selectedIndex - 1) },
                    onLongPress = { onItemLongPress(selectedIndex - 1) },
                    showIcon = showIcons,
                    modifier = Modifier
                        .fillMaxWidth()
                        .requiredHeight(ROW_HEIGHT),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun XmbVerticalListRow(
    item: XMBItem,
    isSelected: Boolean,
    // Like the real XMB first-level column, rows are icon-only unless flagged: the caller shows text
    // only for the active row and the one directly below it (the "up next" preview).
    showText: Boolean,
    iconStyle: GameIconStyle,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    // When false, the leading game/console icon is omitted — the row is text-only.
    showIcon: Boolean = true,
    // When true, a ◀ drill cursor is drawn directly to the right of this row's content.
    trailingCursor: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Strong size delta between the locked selection and the rows scrolling past it — the PSP
    // "the cursor stays, the list breathes" feel.
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.06f else 0.9f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "xmbListRowScale",
    )
    val rowAlpha by animateFloatAsState(
        targetValue = when {
            isSelected -> 1f
            item.type == XMBItemType.EMPTY -> 0.5f
            else -> 0.68f
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "xmbListRowAlpha",
    )
    // Pivot the grow/shrink scale at the leading icon's centre (not the row centre) so the icon
    // never drifts horizontally as it scales — every row's icon stays on the caticon's vertical line.
    val density = LocalDensity.current
    val iconCenterPx = remember(density) { with(density) { LEADING_ICON_CENTER.toPx() } }
    var rowWidthPx by remember { mutableStateOf(0f) }
    // Outer row fills the slot for layout/centering; only the inner cluster is the tap target.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .onSizeChanged { rowWidthPx = it.width.toFloat() }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                if (rowWidthPx > 0f) {
                    transformOrigin = TransformOrigin((iconCenterPx / rowWidthPx).coerceIn(0f, 1f), 0.5f)
                }
            }
            .alpha(rowAlpha),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                // fill = false: shrink to the content width when the title is short (so the empty
                // trailing area stays inert), but cap at the available width so long titles still
                // truncate instead of overflowing.
                .weight(1f, fill = false)
                .height(TAP_TARGET_HEIGHT)
                .combinedClickable(onClick = onClick, onLongClick = onLongPress)
                .padding(horizontal = ROW_HORIZONTAL_PADDING),
        ) {
            if (showIcon) {
                XmbItemLeadingIcon(
                    item = item,
                    iconStyle = iconStyle,
                    isSelected = isSelected,
                )
            }

            if (showText) {
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = item.title,
                        color = if (isSelected) PrimaryText else InactiveText,
                        fontSize = if (isSelected) 19.sp else 16.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        style = if (isSelected) TextStyle(shadow = SelectedTextShadow) else TextStyle.Default,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!item.subtitle.isNullOrBlank()) {
                        Text(
                            text = item.subtitle,
                            color = SecondaryText,
                            fontSize = if (isSelected) 12.sp else 11.sp,
                            fontWeight = FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                }
            }
            // Drill cursor — a ◀ pinned directly to the RIGHT of the (active) card, vertically
            // centred by the Row's CenterVertically. Only the selected row sets this.
            if (trailingCursor) {
                Text(
                    text = "◀",
                    color = LocalPFPColors.current.accentColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

// Icon shadows/blooms dropped per design — selection is conveyed by the row's scale alone.
private fun Modifier.selectedIconBloom(isSelected: Boolean): Modifier = this

@Composable
private fun XmbItemLeadingIcon(
    item: XMBItem,
    iconStyle: GameIconStyle,
    isSelected: Boolean,
) {
    when {
        // Music tracks (and the "Now Playing" row) show a square album cover, falling back to a
        // framed music-note glyph when the track had no embedded art. The 58dp box keeps every
        // track's title left-aligned with the small-icon rows above/below.
        item.type == XMBItemType.MUSIC_TRACK -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.width(LEADING_ICON_SLOT),
            ) {
                if (item.coverUri != null) {
                    AsyncImage(
                        model = item.coverUri,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)),
                    )
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1B1B27)),
                    ) {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = SecondaryText,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            }
        }
        // Playlist rows (and the static "Playlist" item) use a queue-music glyph.
        item.type == XMBItemType.PLAYLIST -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.width(LEADING_ICON_SLOT),
            ) {
                Icon(
                    Icons.Filled.QueueMusic,
                    contentDescription = null,
                    tint = InactiveText,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
        // The static "Music Apps" item uses a music-library glyph.
        item.type == XMBItemType.MUSIC_APPS -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.width(LEADING_ICON_SLOT),
            ) {
                Icon(
                    Icons.Filled.LibraryMusic,
                    contentDescription = null,
                    tint = InactiveText,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
        item.type == XMBItemType.ALL_GAMES ||
            item.type == XMBItemType.FAVORITES ||
            item.type == XMBItemType.MEMORY_CARD ||
            item.type == XMBItemType.COLLECTION -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.width(LEADING_ICON_SLOT),
            ) {
              Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(LEADING_ICON_SIZE).selectedIconBloom(isSelected),
              ) {
                // Explicit art (the "Music" card) loads directly; collections have no console icon of
                // their own, so they use the physical-media memory-card art (_default.png) and read as
                // a memory card instead of falling back to the blank sysicon_default.
                val memoryCardArt = item.coverUri
                    ?: MEMORY_CARD_DEFAULT_ART.takeIf { item.type == XMBItemType.COLLECTION }
                if (memoryCardArt != null) {
                    AsyncImage(
                        model = memoryCardArt,
                        contentDescription = null,
                        modifier = Modifier.size(LEADING_ICON_SIZE),
                    )
                } else {
                    // Memory-card rows show their matching console icon. All Games gets the generic
                    // cartridge art (sysicon_allgames) and Favorites the star (sysicon_favorites),
                    // both to stand apart from the Game controller.
                    val iconKey = when (item.type) {
                        XMBItemType.MEMORY_CARD -> item.platformId
                        XMBItemType.ALL_GAMES   -> "allgames"
                        XMBItemType.FAVORITES   -> "favorites"
                        else                    -> null
                    }
                    Image(
                        painter = painterResource(rememberConsoleIconId(iconKey)),
                        contentDescription = null,
                        modifier = Modifier.size(LEADING_ICON_SIZE),
                    )
                }
              }
            }
        }
        item.gameId != null -> {
            // Full 144:80 landscape tile (ratio 1.8) — the authentic PSP ICON0 rectangle.
            GameIcon(
                item = item,
                iconStyle = iconStyle,
                modifier = Modifier.size(width = GAME_ICON_WIDTH, height = GAME_ICON_HEIGHT),
            )
            Spacer(modifier = Modifier.width(ARTWORK_TEXT_GAP))
        }
        item.isAndroidApp && item.iconUri != null -> {
            // Apps the user has given artwork render the same 144:80 landscape tile as games, so
            // non-gaming categories (Video / Music / custom) look uniform. These rows stay
            // content_type ANDROID_APP, so artwork never makes them appear in All Games.
            GameIcon(
                item = item,
                iconStyle = iconStyle,
                modifier = Modifier.size(width = GAME_ICON_WIDTH, height = GAME_ICON_HEIGHT),
            )
            Spacer(modifier = Modifier.width(ARTWORK_TEXT_GAP))
        }
        item.isAndroidApp && item.packageName != null -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.width(LEADING_ICON_SLOT),
            ) {
                AppListIcon(
                    packageName = item.packageName,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
        // Every Settings item shares the wrench badge (sysicon_settings) so Settings reads like the
        // rest of the XMB — an icon + label per row — instead of a blank-led list. Sized and slotted
        // exactly like the memory-card console icons.
        item.id.startsWith("settings_") -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.width(LEADING_ICON_SLOT),
            ) {
                Image(
                    painter = painterResource(rememberConsoleIconId("settings")),
                    contentDescription = null,
                    modifier = Modifier.size(LEADING_ICON_SIZE),
                )
            }
        }
        else -> Spacer(modifier = Modifier.width(12.dp))
    }
}

// Resolves a per-console icon (bundled from the xmb-menu-es-de set) by platform id,
// e.g. platformId "psp" -> R.drawable.sysicon_psp. Falls back to the theme's generic
// sysicon_default when the platform is unknown or has no dedicated icon.
@Composable
private fun rememberConsoleIconId(platformId: String?): Int {
    val context = LocalContext.current
    return remember(platformId) {
        val safe = platformId?.lowercase()?.filter { it.isLetterOrDigit() || it == '_' }
        val specific = if (!safe.isNullOrBlank()) {
            context.resources.getIdentifier("sysicon_$safe", "drawable", context.packageName)
        } else 0
        if (specific != 0) specific
        else context.resources.getIdentifier("sysicon_default", "drawable", context.packageName)
    }
}

@Composable
private fun AppListIcon(
    packageName: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val drawable = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
    } ?: return
    Image(
        painter = rememberDrawablePainter(drawable),
        contentDescription = null,
        modifier = modifier.clip(RoundedCornerShape(6.dp)),
    )
}
