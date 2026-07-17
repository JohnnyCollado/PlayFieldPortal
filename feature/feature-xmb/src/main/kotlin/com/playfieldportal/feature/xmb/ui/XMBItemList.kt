package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.automirrored.filled.Logout
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
import com.playfieldportal.core.ui.achievement.BoneGlyph
import com.playfieldportal.core.ui.icons.GameIconStyle
import com.playfieldportal.core.ui.icons.LocalXmbIconOverrides
import com.playfieldportal.core.ui.icons.PortalIcon
import com.playfieldportal.core.ui.icons.ThemedGlyph
import com.playfieldportal.core.ui.icons.categoryIconFor
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.themekit.XmbLayoutSpec
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
// of its own size — icons can grow without breaking the caticon alignment. Sized from the shared
// theme-kit layout spec (single source of truth for the tuned XMB geometry).
internal val LEADING_ICON_SLOT = XmbLayoutSpec.DEFAULT.itemIconSlotDp.dp
// Default size of the glyph/art inside that slot (selected rows additionally scale up via the row).
private val LEADING_ICON_SIZE = XmbLayoutSpec.DEFAULT.itemIconDp.dp
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
// already shifted under the caticon). Clears the icon-only memory-card column and the ◀ that trails
// the active card, then seats the games just past it — kept tight so the games hug the active
// console icon and the PIC0 logo overlay (center-right) still has room to breathe.
private val DRILL_GAME_COLUMN_LEFT = 138.dp

// Focused-row text rides the PIC0 logo timeline — must match XMBShell's logo overlay timing.
private const val PIC0_TEXT_DELAY_MS = 650L
private const val PIC0_TEXT_FADE_MS = 500

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
    // Tap on a LEFT-column memory card. The caller decides what it means (tapping the active card
    // backs out of the drill); taps on other cards are delivered too so it can ignore them.
    onSiblingTap: (Int) -> Unit = {},
    iconStyle: GameIconStyle = GameIconStyle.PSP_RECTANGLE,
    // The Y of the category bar's top edge and bottom edge — passed the SAME values as the main XMB
    // so the drill is laid out identically: active row under the caticon, previous half-clipped above.
    barTopY: Dp = 40.dp,
    belowTopY: Dp = 152.dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        // LEFT: the memory-card cross — the main XMB list itself, icon-only, with a ◀ after the
        // active (drilled-into) card. Static while navigating games. Labels are hidden here so the
        // drilled console reads as a bare icon and the ◀ sits tight against it — the games are the
        // focus while drilled in, and the name already showed at the parent level.
        XMBItemList(
            items = siblings,
            selectedIndex = siblingIndex,
            onItemSelected = onSiblingTap,
            onItemLongPress = {},
            iconStyle = iconStyle,
            barTopY = barTopY,
            belowTopY = belowTopY,
            showLabels = false,
            drillCursorOnSelected = true,
            modifier = Modifier.fillMaxHeight().width(DRILL_GAME_COLUMN_LEFT - 10.dp),
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

// One sibling icon — plain glyph (no tile/shadow), dimmed when not the active sibling. Video
// sections use vector glyphs (folder / library / movie); everything else uses console art.
@Composable
private fun SiblingIcon(item: XMBItem, selected: Boolean) {
    val chip = if (selected) 56.dp else 40.dp
    val videoGlyph = when (item.type) {
        XMBItemType.VIDEO_FOLDER    -> Icons.Filled.Folder
        XMBItemType.VIDEO_LIBRARY   -> Icons.Filled.VideoLibrary
        XMBItemType.VIDEO_APPS        -> Icons.Filled.Movie
        XMBItemType.VIDEO_RECENT      -> Icons.Filled.History
        XMBItemType.VIDEO_FAVORITES   -> Icons.Filled.Star
        XMBItemType.VIDEO_COLLECTIONS -> Icons.Filled.Bookmarks
        XMBItemType.PHOTO_FOLDER    -> Icons.Filled.Folder
        XMBItemType.PHOTO_ALBUMS    -> Icons.Filled.PhotoLibrary
        XMBItemType.PHOTO_APPS      -> Icons.Filled.Collections
        // The video "Playlists" section row (PLAYLIST type with no playlistId) uses a playlist glyph.
        XMBItemType.PLAYLIST        -> Icons.Filled.QueueMusic
        else                        -> null
    }
    Box(
        modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (videoGlyph != null) {
            ThemedGlyph(
                slotKey = itemSlotKeyFor(item.type) ?: "",
                defaultVector = videoGlyph,
                contentDescription = item.title,
                tint = LocalPFPColors.current.iconColor,
                // Layer alpha (not tint alpha) so custom untinted icons dim identically.
                modifier = Modifier.size(chip).alpha(if (selected) 1f else 0.5f),
            )
        } else {
            PortalIcon(
                painter = painterResource(rememberConsoleIconId(consoleIconKeyFor(item))),
                contentDescription = item.title,
                modifier = Modifier.size(chip).alpha(if (selected) 1f else 0.5f),
            )
        }
    }
}

// Themeable icon slot (theme-kit IconSlots key) for item types whose leading glyph is a
// Material vector. Null = the type's glyph is not a themeable slot (console art, covers).
// Kept in lockstep with the Theme Studio's StudioIconSet.ITEM_VECTORS defaults.
/**
 * Theme slot for rows that show the default memory-card art — the Music/Videos/Photos
 * library cards and Games-side collections. Null for per-console cards (console identity
 * stays uniform across themes) and anything with real user artwork.
 */
internal fun memoryCardSlotKeyFor(item: XMBItem): String? = when {
    item.type == XMBItemType.COLLECTION -> "item_memcard_games"
    item.type != XMBItemType.MEMORY_CARD -> null
    item.id == "all_music" -> "item_memcard_music"
    item.id == "all_videos" -> "item_memcard_video"
    item.id == "all_photos" -> "item_memcard_photos"
    else -> null
}

internal fun itemSlotKeyFor(type: XMBItemType): String? = when (type) {
    XMBItemType.ADD_ACTION -> "item_add"
    XMBItemType.VIDEO_FOLDER -> "item_video_folder"
    XMBItemType.VIDEO_LIBRARY -> "item_video_library"
    XMBItemType.VIDEO_RECENT -> "item_video_recent"
    XMBItemType.VIDEO_FAVORITES -> "item_video_favorites"
    XMBItemType.VIDEO_COLLECTIONS -> "item_video_collections"
    XMBItemType.VIDEO_APPS -> "item_video_apps"
    XMBItemType.VIDEO_FILE -> "item_video_file"
    XMBItemType.PHOTO_FOLDER -> "item_photo_folder"
    XMBItemType.PHOTO_FILE -> "item_photo_file"
    XMBItemType.PHOTO_ALBUMS -> "item_photo_albums"
    XMBItemType.PHOTO_APPS -> "item_photo_apps"
    XMBItemType.CAMERA -> "item_camera"
    XMBItemType.MUSIC_TRACK -> "item_music_track"
    XMBItemType.PLAYLIST -> "item_playlist"
    XMBItemType.MUSIC_APPS -> "item_music_apps"
    XMBItemType.SOCIAL_ADD -> "item_social_add"
    XMBItemType.SOCIAL_ACCOUNT, XMBItemType.SOCIAL_FRIEND -> "item_social_account"
    XMBItemType.SOCIAL_FRIENDS -> "item_social_friends"
    XMBItemType.SOCIAL_VOICE, XMBItemType.SOCIAL_VOICE_CREATE -> "item_social_voice"
    XMBItemType.SOCIAL_VOICE_INVITE, XMBItemType.SOCIAL_VOICE_INVITES,
    XMBItemType.SOCIAL_VOICE_INVITE_ROW, XMBItemType.SOCIAL_VOICE_FRIEND_PICK -> "item_social_voice_invite"
    XMBItemType.SOCIAL_VOICE_MUTE -> "item_social_voice_mute"
    XMBItemType.SOCIAL_VOICE_SETTINGS, XMBItemType.SOCIAL_VOICE_TOGGLE,
    XMBItemType.SOCIAL_VOICE_CYCLE -> "item_social_voice_settings"
    XMBItemType.SOCIAL_VOICE_LEAVE -> "item_social_voice_leave"
    XMBItemType.SOCIAL_ACTIVITY_SETTINGS, XMBItemType.SOCIAL_TOGGLE -> "item_social_activity"
    XMBItemType.SOCIAL_DISCORD_SETTINGS -> "item_social_discord_settings"
    XMBItemType.SOCIAL_SIGNOUT -> "item_social_signout"
    else -> null
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
        // Uptime of the previous selection change — lets the glide duration follow the input
        // cadence: rapid held-repeat steps get a tween that finishes before the next step lands,
        // while isolated presses keep the full-length PSP glide.
        val lastStepUptime = remember { longArrayOf(0L) }

        LaunchedEffect(selectedIndex, count, anchorPx) {
            if (count == 0) return@LaunchedEffect
            val now = android.os.SystemClock.uptimeMillis()
            val sinceLastStep = now - lastStepUptime[0]
            lastStepUptime[0] = now
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
            // Duration tracks the step cadence (clamped) so held-repeat scrolling stays 1:1 with
            // input instead of every step interrupting a half-finished 240 ms glide.
            val delta = item.offset - anchorPx
            if (delta != 0f) {
                val duration = sinceLastStep.coerceIn(70L, 240L).toInt()
                listState.animateScrollBy(
                    delta,
                    animationSpec = tween(durationMillis = duration, easing = FastOutSlowInEasing),
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
    // When false, rows render icon-only (no title/subtitle label). The drill flyout's memory-card
    // column uses this so the drilled console reads as a bare icon + ◀, tight against the games.
    showLabels: Boolean = true,
    // When true, the selected row gets a ◀ drill cursor pinned directly to its right.
    drillCursorOnSelected: Boolean = false,
    // How far the dissolving previous item rises above the bar, in row heights (theme layout spec).
    previousRiseRows: Float = XmbLayoutSpec.DEFAULT.previousItemRiseRows,
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
        // Render only rows that FULLY fit below the anchor — the active row plus however many whole
        // rows remain in the space beneath it. No trailing partial row is composed, so nothing gets
        // clipped to a half-height sliver at the bottom edge (on any screen size).
        val rowsBelow = ((maxHeight.value - belowTopY.value) / ROW_HEIGHT.value).toInt()
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
                        // The real PSP XMB labels EVERY first-level item (selected bright, the
                        // rest dimmed) — labels show unless the caller asks for an icon-only column
                        // (the drill flyout's memory-card cross).
                        showText = showLabels,
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
                    // Rise distance is theme-tunable: PSP-style wallpapers want the previous item
                    // fully clear of the caticon hexagon before it dissolves.
                    .offset(y = barTopY - ROW_HEIGHT * previousRiseRows)
                    .clipToBounds(),
                // Bottom-align a full-height row inside a half-height window: its top half is clipped.
                // requiredHeight keeps the row its full ROW_HEIGHT (the window would otherwise coerce
                // it down to half) so it overflows upward and the clip cuts the top half off.
                contentAlignment = Alignment.BottomStart,
            ) {
                XmbVerticalListRow(
                    item = items[selectedIndex - 1],
                    isSelected = false,
                    // Show the previous item's label too, so its name rises up through the
                    // crossbar with the icon (unless the column is icon-only).
                    showText = showLabels,
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
                // indication = null suppresses the Android ripple/highlight on tap & long-press —
                // the XMB communicates focus through its own cursor (scale + white label), and the
                // grey ripple rectangle broke the PSP look.
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                    onLongClick = onLongPress,
                )
                .padding(horizontal = ROW_HORIZONTAL_PADDING),
        ) {
            if (showIcon && !item.textOnly) {
                XmbItemLeadingIcon(
                    item = item,
                    iconStyle = iconStyle,
                    isSelected = isSelected,
                )
            }

            // Game entities are icon-first: NO text on any game row except the ACTIVE row of
            // a logo-less game, where title + emulator fade in on the PIC0 timeline. Games
            // with a logo never show text — the logo overlay IS the identity. Non-game rows
            // keep their labels as always. A textOnly row (e.g. the Untracked list) always labels.
            val showGameText = item.textOnly || !item.isRealGame || (isSelected && item.logoUri == null)
            if (showText && showGameText) {
                val fadeWithPic0 = isSelected && item.isRealGame && !item.textOnly
                var textVisible by remember(item.id, fadeWithPic0) { mutableStateOf(!fadeWithPic0) }
                if (fadeWithPic0) {
                    LaunchedEffect(item.id, fadeWithPic0) {
                        kotlinx.coroutines.delay(PIC0_TEXT_DELAY_MS)
                        textVisible = true
                    }
                }
                val textAlpha by animateFloatAsState(
                    targetValue = if (textVisible) 1f else 0f,
                    animationSpec = tween(PIC0_TEXT_FADE_MS),
                    label = "rowTextFade",
                )
                // start padding pushes the label clear of the wallpaper's vertical cross bar, so the
                // text doesn't butt against the black band (a small gap, PSP-style).
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(start = XmbLayoutSpec.DEFAULT.itemTextStartGapDp.dp)
                        .alpha(textAlpha),
                ) {
                    val titleColor = if (isSelected) PrimaryText else InactiveText
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.title,
                            color = titleColor,
                            fontSize = if (isSelected) XmbLayoutSpec.DEFAULT.itemTextSelectedSp.sp
                            else XmbLayoutSpec.DEFAULT.itemTextSp.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            style = if (isSelected) TextStyle(shadow = SelectedTextShadow) else TextStyle.Default,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // Prestige Bones: "• N [bone glyph]" after the rank, player-card row only.
                        if (item.boneCount > 0) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "•  ${item.boneCount}",
                                color = titleColor,
                                fontSize = if (isSelected) 13.sp else 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                            Spacer(Modifier.width(4.dp))
                            BoneGlyph(tint = titleColor, size = 14.dp)
                        }
                    }
                    if (!item.subtitle.isNullOrBlank()) {
                        // Discord friend rows prefix the subtitle with a colored presence dot; every
                        // other row keeps the plain subtitle.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 1.dp),
                        ) {
                            item.socialStatusArgb?.let { argb ->
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(argb).copy(alpha = if (isSelected) 1f else 0.85f)),
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = item.subtitle,
                                color = SecondaryText,
                                fontSize = if (isSelected) 12.sp else 11.sp,
                                fontWeight = FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
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
    // Material glyph rows follow the theme's unified icon color, matching the tinted
    // silhouette art (PortalIcon) — row alpha handles the unselected dimming.
    val iconTint = LocalPFPColors.current.iconColor
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
                        ThemedGlyph(
                            slotKey = itemSlotKeyFor(item.type) ?: "",
                            defaultVector = Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = iconTint,
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
                ThemedGlyph(
                    slotKey = itemSlotKeyFor(item.type) ?: "",
                    defaultVector = Icons.Filled.QueueMusic,
                    contentDescription = null,
                    tint = iconTint,
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
                ThemedGlyph(
                    slotKey = itemSlotKeyFor(item.type) ?: "",
                    defaultVector = Icons.Filled.LibraryMusic,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
        // Video files show a landscape thumbnail (a frame grab), falling back to a movie glyph.
        item.type == XMBItemType.VIDEO_FILE -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.width(LEADING_ICON_SLOT),
            ) {
                if (item.coverUri != null) {
                    AsyncImage(
                        model = item.coverUri,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.size(width = 60.dp, height = 40.dp).clip(RoundedCornerShape(6.dp)),
                    )
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(width = 60.dp, height = 40.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1B1B27)),
                    ) {
                        ThemedGlyph(
                            slotKey = itemSlotKeyFor(item.type) ?: "",
                            defaultVector = Icons.Filled.Movie,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }
        }
        // A video library / "All Videos" folder: custom artwork when set, else a folder glyph.
        item.type == XMBItemType.VIDEO_FOLDER -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(LEADING_ICON_SLOT)) {
                if (item.coverUri != null) {
                    AsyncImage(
                        model = item.coverUri,
                        contentDescription = null,
                        modifier = Modifier.size(LEADING_ICON_SIZE).clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    ThemedGlyph(itemSlotKeyFor(item.type) ?: "", Icons.Filled.Folder, null, iconTint, Modifier.size(48.dp))
                }
            }
        }
        // The static "Video Libraries" and "Android Video Apps" rows use glyphs.
        item.type == XMBItemType.VIDEO_LIBRARY -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(LEADING_ICON_SLOT)) {
                ThemedGlyph(itemSlotKeyFor(item.type) ?: "", Icons.Filled.VideoLibrary, null, iconTint, Modifier.size(48.dp))
            }
        }
        item.type == XMBItemType.VIDEO_RECENT -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(LEADING_ICON_SLOT)) {
                ThemedGlyph(itemSlotKeyFor(item.type) ?: "", Icons.Filled.History, null, iconTint, Modifier.size(48.dp))
            }
        }
        item.type == XMBItemType.VIDEO_FAVORITES -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(LEADING_ICON_SLOT)) {
                ThemedGlyph(itemSlotKeyFor(item.type) ?: "", Icons.Filled.Star, null, iconTint, Modifier.size(48.dp))
            }
        }
        // "Collections" root row — umbrella for Recently Watched / Favorites / Playlists.
        item.type == XMBItemType.VIDEO_COLLECTIONS -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(LEADING_ICON_SLOT)) {
                ThemedGlyph(itemSlotKeyFor(item.type) ?: "", Icons.Filled.Bookmarks, null, iconTint, Modifier.size(46.dp))
            }
        }
        item.type == XMBItemType.VIDEO_APPS -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(LEADING_ICON_SLOT)) {
                ThemedGlyph(itemSlotKeyFor(item.type) ?: "", Icons.Filled.Movie, null, iconTint, Modifier.size(48.dp))
            }
        }
        // Photos show their cached thumbnail, falling back to a photo glyph for files whose
        // thumbnail couldn't be generated (corrupt/exotic formats).
        item.type == XMBItemType.PHOTO_FILE -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.width(LEADING_ICON_SLOT),
            ) {
                if (item.coverUri != null) {
                    AsyncImage(
                        model = item.coverUri,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.size(width = 60.dp, height = 40.dp).clip(RoundedCornerShape(6.dp)),
                    )
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(width = 60.dp, height = 40.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1B1B27)),
                    ) {
                        ThemedGlyph(
                            slotKey = itemSlotKeyFor(item.type) ?: "",
                            defaultVector = Icons.Filled.Photo,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }
        }
        // An Album folder card in the Albums list — folder glyph, matching the Video libraries.
        item.type == XMBItemType.PHOTO_FOLDER -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(LEADING_ICON_SLOT)) {
                ThemedGlyph(itemSlotKeyFor(item.type) ?: "", Icons.Filled.Folder, null, iconTint, Modifier.size(48.dp))
            }
        }
        // The "Albums" section row at the Photo root.
        item.type == XMBItemType.PHOTO_ALBUMS -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(LEADING_ICON_SLOT)) {
                ThemedGlyph(itemSlotKeyFor(item.type) ?: "", Icons.Filled.PhotoLibrary, null, iconTint, Modifier.size(48.dp))
            }
        }
        // The "Photo Apps" section row at the Photo root (distinct glyph from Albums and Camera).
        item.type == XMBItemType.PHOTO_APPS -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(LEADING_ICON_SLOT)) {
                ThemedGlyph(itemSlotKeyFor(item.type) ?: "", Icons.Filled.Collections, null, iconTint, Modifier.size(48.dp))
            }
        }
        // The Camera row (only present when a camera app exists).
        item.type == XMBItemType.CAMERA -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(LEADING_ICON_SLOT)) {
                ThemedGlyph(itemSlotKeyFor(item.type) ?: "", Icons.Filled.PhotoCamera, null, iconTint, Modifier.size(48.dp))
            }
        }
        // "Add …" / "Create …" rows across Photo / Music / Video sections.
        item.type == XMBItemType.ADD_ACTION -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(LEADING_ICON_SLOT)) {
                ThemedGlyph(itemSlotKeyFor(item.type) ?: "", Icons.Filled.Add, null, iconTint, Modifier.size(44.dp))
            }
        }
        // Discord Social rows.
        item.type == XMBItemType.SOCIAL_ADD -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(LEADING_ICON_SLOT)) {
                ThemedGlyph(itemSlotKeyFor(item.type) ?: "", Icons.Filled.QrCode2, null, iconTint, Modifier.size(44.dp))
            }
        }
        item.type == XMBItemType.SOCIAL_ACCOUNT || item.type == XMBItemType.SOCIAL_FRIEND -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(LEADING_ICON_SLOT)) {
                if (item.coverUri != null) {
                    AsyncImage(
                        model = item.coverUri,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).clip(CircleShape),
                    )
                } else {
                    ThemedGlyph(itemSlotKeyFor(item.type) ?: "", Icons.Filled.AccountCircle, null, iconTint, Modifier.size(48.dp))
                }
            }
        }
        item.type == XMBItemType.SOCIAL_VOICE ||
            item.type == XMBItemType.SOCIAL_VOICE_CREATE -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(LEADING_ICON_SLOT)) {
                ThemedGlyph(itemSlotKeyFor(item.type) ?: "", Icons.Filled.Headset, null, iconTint, Modifier.size(46.dp))
            }
        }
        item.type == XMBItemType.SOCIAL_VOICE_INVITE ||
            item.type == XMBItemType.SOCIAL_VOICE_INVITES ||
            item.type == XMBItemType.SOCIAL_VOICE_INVITE_ROW ||
            item.type == XMBItemType.SOCIAL_VOICE_FRIEND_PICK -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(LEADING_ICON_SLOT)) {
                if (item.coverUri != null) {
                    AsyncImage(
                        model = item.coverUri,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).clip(CircleShape),
                    )
                } else {
                    ThemedGlyph(itemSlotKeyFor(item.type) ?: "", Icons.Filled.PersonAdd, null, iconTint, Modifier.size(44.dp))
                }
            }
        }
        item.type == XMBItemType.SOCIAL_VOICE_MUTE -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(LEADING_ICON_SLOT)) {
                ThemedGlyph(itemSlotKeyFor(item.type) ?: "", Icons.Filled.Mic, null, iconTint, Modifier.size(46.dp))
            }
        }
        item.type == XMBItemType.SOCIAL_VOICE_SETTINGS ||
            item.type == XMBItemType.SOCIAL_VOICE_TOGGLE ||
            item.type == XMBItemType.SOCIAL_VOICE_CYCLE -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(LEADING_ICON_SLOT)) {
                ThemedGlyph(itemSlotKeyFor(item.type) ?: "", Icons.Filled.Tune, null, iconTint, Modifier.size(44.dp))
            }
        }
        item.type == XMBItemType.SOCIAL_VOICE_LEAVE -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(LEADING_ICON_SLOT)) {
                ThemedGlyph(itemSlotKeyFor(item.type) ?: "", Icons.Filled.CallEnd, null, iconTint, Modifier.size(44.dp))
            }
        }
        item.type == XMBItemType.SOCIAL_ACTIVITY_SETTINGS ||
            item.type == XMBItemType.SOCIAL_TOGGLE -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(LEADING_ICON_SLOT)) {
                ThemedGlyph(itemSlotKeyFor(item.type) ?: "", Icons.Filled.SportsEsports, null, iconTint, Modifier.size(46.dp))
            }
        }
        item.type == XMBItemType.SOCIAL_DISCORD_SETTINGS -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(LEADING_ICON_SLOT)) {
                ThemedGlyph(itemSlotKeyFor(item.type) ?: "", Icons.Filled.Settings, null, iconTint, Modifier.size(44.dp))
            }
        }
        item.type == XMBItemType.SOCIAL_FRIENDS -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(LEADING_ICON_SLOT)) {
                ThemedGlyph(itemSlotKeyFor(item.type) ?: "", Icons.Filled.People, null, iconTint, Modifier.size(46.dp))
            }
        }
        item.type == XMBItemType.SOCIAL_SIGNOUT -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(LEADING_ICON_SLOT)) {
                ThemedGlyph(itemSlotKeyFor(item.type) ?: "", Icons.AutoMirrored.Filled.Logout, null, iconTint, Modifier.size(44.dp))
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
                // A collection with a user-picked icon renders that catalog glyph; otherwise it uses
                // the physical-media memory-card art (_default.png) and reads as a memory card.
                val collectionIconKey = item.iconKey?.takeIf { item.type == XMBItemType.COLLECTION }
                // Explicit art (the "Music" card) loads directly; collections without a picked icon
                // fall back to the memory-card art instead of the blank sysicon_default.
                val memoryCardArt = item.coverUri
                    ?: MEMORY_CARD_DEFAULT_ART.takeIf { item.type == XMBItemType.COLLECTION && collectionIconKey == null }
                // Themes can replace the DEFAULT memory-card art per category — never a
                // user-picked collection glyph or real cover artwork.
                val memcardOverride = if (
                    collectionIconKey == null &&
                    (memoryCardArt == null || memoryCardArt.startsWith("file:///android_asset/systems/physical-media/"))
                ) {
                    memoryCardSlotKeyFor(item)?.let { LocalXmbIconOverrides.current[it] }
                } else {
                    null
                }
                if (memcardOverride != null) {
                    // Custom theme icons render as authored (untinted), like every slot.
                    Image(
                        bitmap = memcardOverride,
                        contentDescription = null,
                        modifier = Modifier.size(LEADING_ICON_SIZE),
                    )
                } else if (collectionIconKey != null) {
                    PortalIcon(
                        painter = painterResource(categoryIconFor(collectionIconKey).resId),
                        contentDescription = null,
                        modifier = Modifier.size(LEADING_ICON_SIZE),
                    )
                } else if (memoryCardArt != null) {
                    // The bundled physical-media memory-card art is a white silhouette — it
                    // follows the unified icon color like every other glyph. Real user/content
                    // artwork (custom collection covers) stays untinted.
                    val isBundledSilhouette =
                        memoryCardArt.startsWith("file:///android_asset/systems/physical-media/")
                    AsyncImage(
                        model = memoryCardArt,
                        contentDescription = null,
                        colorFilter = if (isBundledSilhouette) {
                            androidx.compose.ui.graphics.ColorFilter.tint(
                                LocalPFPColors.current.iconColor,
                                androidx.compose.ui.graphics.BlendMode.SrcIn,
                            )
                        } else null,
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
                    PortalIcon(
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
        // Every Settings item — including "Android Settings" — shares the wrench badge
        // (sysicon_settings) so Settings reads like the
        // rest of the XMB — an icon + label per row — instead of a blank-led list. Sized and slotted
        // exactly like the memory-card console icons.
        item.id.startsWith("settings_") -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.width(LEADING_ICON_SLOT),
            ) {
                val settingsOverride = LocalXmbIconOverrides.current["item_settings"]
                if (settingsOverride != null) {
                    Image(
                        bitmap = settingsOverride,
                        contentDescription = null,
                        modifier = Modifier.size(LEADING_ICON_SIZE),
                    )
                } else {
                    PortalIcon(
                        painter = painterResource(rememberConsoleIconId("settings")),
                        contentDescription = null,
                        modifier = Modifier.size(LEADING_ICON_SIZE),
                    )
                }
            }
        }
        // Shiba Coins player-card summary: a ring with the level centered in it (e.g. "Lv 27"),
        // sized like the other item icons. Ring and text follow the theme's icon color; no fill.
        item.levelBadge != null -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(LEADING_ICON_SLOT)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(LEADING_ICON_SIZE * 0.8f)
                        .clip(CircleShape)
                        .border(2.dp, iconTint, CircleShape),
                ) {
                    Text(
                        text = item.levelBadge,
                        color = iconTint,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }
        // "All Tracked Games" reads as a memory card (its list is the tracked games), using the
        // bundled physical-media card art tinted with the theme icon color.
        item.id == "ach_all" -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(LEADING_ICON_SLOT)) {
                AsyncImage(
                    model = MEMORY_CARD_DEFAULT_ART,
                    contentDescription = null,
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                        iconTint,
                        androidx.compose.ui.graphics.BlendMode.SrcIn,
                    ),
                    modifier = Modifier.size(LEADING_ICON_SIZE),
                )
            }
        }
        // Other Shiba Coins hub lens rows get a per-row Material glyph at the item-icon size (no
        // background), keyed by exact id so the untracked/coin rows keep their own treatment.
        achievementsGlyphFor(item.id) != null -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(LEADING_ICON_SLOT)) {
                ThemedGlyph("", achievementsGlyphFor(item.id)!!, null, iconTint, Modifier.size(LEADING_ICON_SIZE))
            }
        }
        else -> Spacer(modifier = Modifier.width(12.dp))
    }
}

// The leading glyph for a Shiba Coins hub lens row, or null if the id isn't one of them.
private fun achievementsGlyphFor(id: String): androidx.compose.ui.graphics.vector.ImageVector? = when (id) {
    "ach_untracked" -> Icons.Filled.HelpOutline
    "ach_connect" -> Icons.Filled.Link
    else -> null
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
