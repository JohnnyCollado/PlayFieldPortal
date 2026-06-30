package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor
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

private val PrimaryText = Color.White
private val SecondaryText = Color(0xFFC9C7E8)
private val InactiveText = Color(0xFFE3E1F0)

// Width reserved for the parent label column in the two-pane drill flyout.
private val FLYOUT_PARENT_WIDTH = 200.dp

// Height of one icon slot in the sibling column.
private val SIBLING_SLOT = 72.dp

// ── Two-pane drill flyout (PSP/XMB style) ────────────────────────────────────
//
// Used when drilled into a Games sub-item: the category's sibling icons sit in a centre-locked
// column on the left, the active one aligned with the fixed ▶ arrow; the children scroll in a
// centre-locked column on the right. Both columns use the same centring so they stay in sync.
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
    modifier: Modifier = Modifier,
) {
    val accent = LocalPFPColors.current.accentColor
    Row(modifier = modifier.fillMaxSize()) {
        // Left: the category's sibling icons, the current one centred on the arrow.
        CenterLockedColumn(
            count = siblings.size,
            selectedIndex = siblingIndex,
            rowHeight = SIBLING_SLOT,
            modifier = Modifier.width(FLYOUT_PARENT_WIDTH).fillMaxHeight(),
        ) { index ->
            SiblingIcon(item = siblings[index], selected = index == siblingIndex)
        }

        // Right: the children, centre-locked under the fixed arrow.
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            CenterLockedColumn(
                count = items.size,
                selectedIndex = selectedIndex,
                rowHeight = ROW_HEIGHT,
                modifier = Modifier.fillMaxSize().padding(start = 30.dp),
            ) { index ->
                XmbVerticalListRow(
                    item = items[index],
                    isSelected = index == selectedIndex,
                    iconStyle = iconStyle,
                    onClick = { onItemSelected(index) },
                    onLongPress = { onItemLongPress(index) },
                    modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT),
                )
            }
            // The fixed arrow — never moves; the active child is scrolled to align with it.
            Text(
                text = "▶",
                color = accent,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
    }
}

// One sibling icon on a dark rounded tile (so it stays visible over any wallpaper).
@Composable
private fun SiblingIcon(item: XMBItem, selected: Boolean) {
    val chip = if (selected) 60.dp else 42.dp
    Box(
        modifier = Modifier.fillMaxWidth().padding(end = 14.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            modifier = Modifier
                .size(chip)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = if (selected) 0.5f else 0.3f))
                .alpha(if (selected) 1f else 0.6f),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(rememberConsoleIconId(consoleIconKeyFor(item))),
                contentDescription = item.title,
                modifier = Modifier.size(chip * 0.72f),
            )
        }
    }
}

// Maps a memory-card-style item to its sysicon key (mirrors XmbItemLeadingIcon's mapping).
private fun consoleIconKeyFor(item: XMBItem): String? = when (item.type) {
    XMBItemType.ALL_GAMES   -> "allgames"
    XMBItemType.FAVORITES   -> "favorites"
    XMBItemType.MEMORY_CARD -> item.platformId
    else                    -> null   // collections / unknown fall back to sysicon_default
}

// A vertical list whose [selectedIndex] row is always pinned to the vertical centre; rows scroll
// under it. Each row is exactly [rowHeight] tall. Used for both flyout columns so they stay aligned.
@Composable
private fun CenterLockedColumn(
    count: Int,
    selectedIndex: Int,
    rowHeight: Dp,
    modifier: Modifier = Modifier,
    row: @Composable (index: Int) -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        // Symmetric padding so the first AND last items can scroll all the way to the centre.
        val centerPad = ((maxHeight - rowHeight) / 2).coerceAtLeast(0.dp)
        val listState = rememberLazyListState()

        LaunchedEffect(selectedIndex, count) {
            if (count == 0) return@LaunchedEffect
            val idx = selectedIndex.coerceIn(0, count - 1)
            // Ensure the target is measured, then scroll by the exact delta to centre it. Measuring
            // (rather than a fixed offset) centres edge items correctly and never over-scrolls.
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == idx }) {
                listState.scrollToItem(idx)
            }
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == idx } ?: return@LaunchedEffect
            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            val delta = (item.offset + item.size / 2f) - viewportCenter
            listState.animateScrollBy(delta)
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = centerPad),
            userScrollEnabled = false,   // selection-driven; taps still work, drag can't fight the lock
            modifier = Modifier.fillMaxSize(),
        ) {
            items(count) { index ->
                Box(modifier = Modifier.fillMaxWidth().height(rowHeight)) { row(index) }
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
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Snap to the top on every sort, even when the cursor was already at index 0 (so the selection
    // scroll effect below wouldn't re-fire). Skips the initial composition (token 0).
    LaunchedEffect(scrollToTopToken) {
        if (scrollToTopToken > 0 && items.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    // Size the list to a whole number of rows (BoxWithConstraints gives the height available from
    // the parent), then scroll on row boundaries — so only fully-visible rows ever render. No fade,
    // no partial row at the top or bottom.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val visibleRows = floor(maxHeight / ROW_HEIGHT).toInt().coerceAtLeast(1)
        val listHeight = ROW_HEIGHT * visibleRows

        LaunchedEffect(selectedIndex, items.size, visibleRows) {
            if (items.isNotEmpty()) {
                // Keep one full row of context above the selection where possible; scrolling to an
                // item with offset 0 lands the viewport exactly on a row boundary.
                val target = (selectedIndex - 1).coerceIn(0, items.lastIndex)
                listState.animateScrollToItem(target)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(listHeight),
        ) {
            itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                XmbVerticalListRow(
                    item = item,
                    isSelected = index == selectedIndex,
                    iconStyle = iconStyle,
                    onClick = { onItemSelected(index) },
                    onLongPress = { onItemLongPress(index) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ROW_HEIGHT),
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
    iconStyle: GameIconStyle,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.035f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "xmbListRowScale",
    )
    val rowAlpha by animateFloatAsState(
        targetValue = when {
            isSelected -> 1f
            item.type == XMBItemType.EMPTY -> 0.62f
            else -> 0.76f
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "xmbListRowAlpha",
    )
    // Outer row fills the slot for layout/centering; only the inner cluster is the tap target.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
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
                .padding(horizontal = 18.dp),
        ) {
            XmbItemLeadingIcon(
                item = item,
                iconStyle = iconStyle,
            )

            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = item.title,
                    color = if (isSelected) PrimaryText else InactiveText,
                    fontSize = if (isSelected) 19.sp else 16.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
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
    }
}

@Composable
private fun XmbItemLeadingIcon(
    item: XMBItem,
    iconStyle: GameIconStyle,
) {
    when {
        // Music tracks (and the "Now Playing" row) show a square album cover, falling back to a
        // framed music-note glyph when the track had no embedded art. The 58dp box keeps every
        // track's title left-aligned with the small-icon rows above/below.
        item.type == XMBItemType.MUSIC_TRACK -> {
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.width(58.dp),
            ) {
                if (item.coverUri != null) {
                    AsyncImage(
                        model = item.coverUri,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
                    )
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1B1B27)),
                    ) {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = SecondaryText,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }
        }
        // Playlist rows (and the static "Playlist" item) use a queue-music glyph.
        item.type == XMBItemType.PLAYLIST -> {
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.width(58.dp),
            ) {
                Icon(
                    Icons.Filled.QueueMusic,
                    contentDescription = null,
                    tint = InactiveText,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
        // The static "Music Apps" item uses a music-library glyph.
        item.type == XMBItemType.MUSIC_APPS -> {
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.width(58.dp),
            ) {
                Icon(
                    Icons.Filled.LibraryMusic,
                    contentDescription = null,
                    tint = InactiveText,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
        item.type == XMBItemType.ALL_GAMES ||
            item.type == XMBItemType.FAVORITES ||
            item.type == XMBItemType.MEMORY_CARD ||
            item.type == XMBItemType.COLLECTION -> {
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.width(58.dp),
            ) {
                if (item.type == XMBItemType.MEMORY_CARD && item.coverUri != null) {
                    // A memory-card row with explicit art (e.g. the "Music" item) loads it directly
                    // — used for the physical-media memory-card PNG served from assets.
                    AsyncImage(
                        model = item.coverUri,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                    )
                } else {
                    // Memory-card rows show their matching console icon. All Games gets the generic
                    // cartridge art (sysicon_allgames) and Favorites the star (sysicon_favorites),
                    // both to stand apart from the Game controller; any other unknown row
                    // (Collections, unmatched console) falls back to sysicon_default.
                    val iconKey = when (item.type) {
                        XMBItemType.MEMORY_CARD -> item.platformId
                        XMBItemType.ALL_GAMES   -> "allgames"
                        XMBItemType.FAVORITES   -> "favorites"
                        else                    -> null
                    }
                    Image(
                        painter = painterResource(rememberConsoleIconId(iconKey)),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                    )
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
            Box(modifier = Modifier.width(58.dp)) {
                AppListIcon(
                    packageName = item.packageName,
                    modifier = Modifier.size(34.dp),
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
