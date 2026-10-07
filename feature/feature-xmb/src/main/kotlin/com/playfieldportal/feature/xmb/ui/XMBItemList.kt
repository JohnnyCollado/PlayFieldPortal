package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.playfieldportal.core.domain.model.XmbListMotion
import com.playfieldportal.core.ui.achievement.BoneGlyph
import com.playfieldportal.core.ui.components.ControllerPromptGlyphs
import com.playfieldportal.core.ui.icons.GameIconStyle
import com.playfieldportal.core.ui.icons.LocalXmbIcons
import com.playfieldportal.core.ui.icons.OverrideGlyphSurface
import com.playfieldportal.core.ui.icons.PortalIcon
import com.playfieldportal.core.ui.icons.ThemedGlyph
import com.playfieldportal.core.ui.icons.categoryIconFor
import com.playfieldportal.core.ui.icons.systemIconRes
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.ui.theme.themedSubText
import com.playfieldportal.core.ui.theme.themedText
import com.playfieldportal.feature.xmb.ui.detail.shibaSlotKeyFor
import com.playfieldportal.feature.xmb.viewmodel.XMBItem
import com.playfieldportal.feature.xmb.viewmodel.XMBItemType
import com.playfieldportal.themekit.XmbLayoutSpec
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// Game icons use the authentic PSP ICON0 ratio 144:80 (= 1.8), scaled for the list.
private val GAME_ICON_WIDTH = 126.dp
private val GAME_ICON_HEIGHT = 70.dp

// Every row is exactly this tall so the list viewport can be sized to a whole number of rows —
// this is what lets us "hard stop" at a row boundary and never render a partially-clipped row.
// Internal because XMBShell needs it too: the drill flyout's PIC0 logo centres on the active
// row, and it can only do that if it measures the row the same way the column lays it out.
internal val ROW_HEIGHT = 88.dp

// The inserted UMD, focused and read: ICON0's native 144 × 80, read as dp — a step up from a game
// row's 126 × 70 icon. The PSP's own scale (three caticons wide, 216 dp here) read far too big on a
// handheld (device feedback, 2026-10-01). Its row grows to hold it, with the same breathing room
// above and below a normal row's icon has.
private val UMD_ICON_WIDTH = 144.dp
private val UMD_ICON_HEIGHT = 80.dp
private val UMD_ROW_HEIGHT = UMD_ICON_HEIGHT + 16.dp

// The selected row's grow, pivoted on the icon line (see XmbVerticalListRow).
private const val SELECTED_ROW_SCALE = 1.06f

// The first-level column clips only top, bottom and right. Its left edge is where the column
// starts, not the screen's, and the focused UMD's ICON0 runs past it to be cut by the screen edge,
// as on the PSP. Nothing else in the column reaches left of its own start.
private val ClipAllButLeft = androidx.compose.foundation.shape.GenericShape { size, _ ->
    addRect(androidx.compose.ui.geometry.Rect(-size.width * 8, 0f, size.width, size.height))
}

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
// blue-white so they read against the saturated blue gradient. The user's font colours replace
// them at their own alphas — titles (selected and dimmed) the Main colour, subtitles the Sub — so
// the selected/unselected/subtitle steps survive.
private val PrimaryText = Color.White
private val SecondaryText = com.playfieldportal.core.ui.theme.ThemeTokens.XmbSecondaryLabel
private val InactiveText = com.playfieldportal.core.ui.theme.ThemeTokens.XmbInactiveLabel
// Soft dark halo behind the bright selected label — keeps white legible on the light wave.
private val SelectedTextShadow = Shadow(
    color = Color(0x73001627),
    offset = Offset.Zero,
    blurRadius = 12f,
)

// A row's inner content padding. Shared so anything that needs to land next to a row's artwork
// (e.g. the flyout cursor) can derive its position from the same number the row lays out with.
private val ROW_HORIZONTAL_PADDING = 18.dp

// "Text Shadow" (Display ▸ Appearance): the repo's standard directional drop shadow — the same
// values PspContextMenu / ControllerHintBar use — applied to XMB row
// subtitles. The settings scaffold's SettingsTextShadow is feature-internal, so the same idiom is
// restated here for the shell (the XMB draws over the raw wallpaper, no scrim at all).
val XmbTextShadow = com.playfieldportal.core.ui.theme.ThemeTokens.TextShadow

// Physical-media memory-card art for rows that should read as a memory card but have no console icon
// of their own (collections). Mirrors the ViewModel's MEMORY_CARD_ASSET_URI.
internal const val MEMORY_CARD_DEFAULT_ART = "file:///android_asset/systems/physical-media/_default.png"

// The UMD slot's unfocused icon: the PSP's physical media, for a game of any platform.
internal const val UMD_SLOT_ART = "file:///android_asset/systems/physical-media/psp.png"

/** The UMD slot's themeable art (theme-kit IconSlots key). */
internal const val UMD_SLOT_KEY = "item_umd"

/**
 * Whether a row shows its title. Game entities are icon-first: NO text on any game row except the
 * ACTIVE row of a logo-less game, where title + emulator show immediately — there is no logo
 * overlay to wait for. Games with a logo never show text — the logo overlay IS the identity.
 * Non-game rows keep their labels as always; a textOnly row (e.g. Untracked) always labels.
 *
 * The UMD slot is named while it is the UMD glyph. Once the read turns it into the inserted game's
 * ICON0 it follows the game rule: bare when the game has a logo, titled beside ICON0 when it has
 * none — the same as a hot list row.
 */
internal fun xmbRowShowsTitle(item: XMBItem, isSelected: Boolean, umdShowsGame: Boolean, moving: Boolean): Boolean = when {
    item.type == XMBItemType.UMD_SLOT -> !umdShowsGame || item.logoUri == null
    // A row being moved always says so, logo or not.
    moving -> true
    else -> item.textOnly || !item.isRealGame || (isSelected && item.logoUri == null)
}

// ── Drill flyout layout ──────────────────────────────────────────────────────
// Left inset of the game-card column, measured from the flyout's left edge (which the caller has
// already shifted under the caticon). Clears the icon-only memory-card column and the ◀ that trails
// the active card, then seats the games just past it — kept tight so the games hug the active
// console icon and the PIC0 logo overlay (center-right) still has room to breathe.
private val DRILL_GAME_COLUMN_LEFT = 138.dp

// ── Two-pane drill flyout (PSP/XMB style) ────────────────────────────────────
//
// Two columns sharing the same [belowTopY] line (the row directly under the caticon):
//
//   • LEFT — the PLATFORM MEMORY CARDS (the items: All Games / Favorites / consoles / collections),
//     rendered by the main [XMBItemList] itself so the cross is identical: the drilled-into card at
//     belowTopY with a ◀ trailing it, the previous card half-clipped above the bar. Icon-only.
//     This column is fixed while you run through the games.
//   • RIGHT — the GAME CARDS (rom icons), icon-only, in a centre-pinned column: the active game is
//     pinned on the belowTopY / ◀ line and the shared step spring (XmbStepSpring) glides the
//     next/previous card onto the pin, the same motion as the category bar and the main list.
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
    // Snap-rule inputs for the game column (see XMBItemList): a sort or search bump, and which game
    // list is on screen, so those snap to the restored cursor instead of gliding there.
    scrollToTopToken: Int = 0,
    columnKey: Any? = null,
    // The Y of the category bar's top edge and bottom edge — passed the SAME values as the main XMB
    // so the drill is laid out identically: active row under the caticon, previous half-clipped above.
    barTopY: Dp = 40.dp,
    belowTopY: Dp = 152.dp,
    // Whether focused-row GIF icons may animate (see XMBItemList).
    iconAnimatingAllowed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        // LEFT: the memory-card cross — the main XMB list itself, icon-only, with a ◀ after the
        // active (drilled-into) card. Static while navigating games. Labels are hidden here so the
        // drilled console reads as a bare icon and the ◀ sits tight against it — the games are the
        // focus while drilled in, and the name already showed at the parent level.
        // A Move or a mark belongs to the game column; the parent card must not wear either.
        androidx.compose.runtime.CompositionLocalProvider(LocalXmbRowDecor provides XmbRowDecor()) {
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
            iconAnimatingAllowed = iconAnimatingAllowed,
            modifier = Modifier.fillMaxHeight().width(DRILL_GAME_COLUMN_LEFT - 10.dp),
        )
        }

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
            scrollToTopToken = scrollToTopToken,
            columnKey = columnKey,
            iconAnimatingAllowed = iconAnimatingAllowed,
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
    scrollToTopToken: Int = 0,
    columnKey: Any? = null,
    iconAnimatingAllowed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize().clipToBounds()) {
        // The same animated position and snap rules as XMBItemList: the selected index in rows, on the
        // shared step spring, created at its target so first composition and resume never glide.
        val inputs = StepInputs(
            rawSelectedIndex = selectedIndex,
            itemCount = items.size,
            scrollToTopToken = scrollToTopToken,
            columnKey = columnKey,
            moving = LocalXmbRowDecor.current.movingLabel != null,
        )
        val position = remember { ItemListPosition(inputs.target) }
        // Decided here, in the frame the inputs change, so a snap is already drawn by that frame.
        position.accept(inputs)
        LaunchedEffect(position.epoch) { position.run() }

        if (items.isEmpty()) return@BoxWithConstraints
        // Window: only the rows that can land on screen above/below the animated position (+2 buffer
        // each way so the next/previous card is always already composed before it scrolls into view).
        // It follows p, not the target, so rows still in transit stay composed. Recomposes only when
        // the set of rows changes, not on every animated frame.
        val rowsAbove = (belowTopY.value / ROW_HEIGHT.value).toInt() + 2
        val rowsBelow = ((maxHeight.value - belowTopY.value) / ROW_HEIGHT.value).toInt() + 2
        val lastIndex = items.lastIndex
        val window by remember(items.size, rowsAbove, rowsBelow) {
            derivedStateOf {
                val p = position.read()
                (floor(p).toInt() - rowsAbove).coerceAtLeast(0)..(ceil(p).toInt() + rowsBelow).coerceAtMost(lastIndex)
            }
        }
        // Place each row by its OWN offset from the anchor line, keyed by item: the row at p lands
        // exactly on belowTopY, earlier rows one ROW_HEIGHT up each, later rows one down each. At rest
        // p is the target, so this is the old contiguous layout. Independent placement (not a shared
        // Column) guarantees rows past the active are laid out.
        for (i in window) {
            val item = items[i]
            key(item.id) {
                XmbVerticalListRow(
                    item = item,
                    isSelected = i == selectedIndex,
                    showText = true,   // every game card keeps its [Title] / {Platform (Emulator)} label
                    iconStyle = iconStyle,
                    onClick = { onItemSelected(i) },
                    onLongPress = { onItemLongPress(i) },
                    showIcon = true,
                    // Only the active card animates (its rows funnel through the same per-row gate).
                    iconAnimatingAllowed = iconAnimatingAllowed,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .height(ROW_HEIGHT)
                        .offset { IntOffset(0, (belowTopY + ROW_HEIGHT * (i - position.read())).roundToPx()) }
                        .testTag("xmbRow:${item.id}"),
                )
            }
        }
    }
}

// One sibling icon — plain glyph (no tile/shadow), dimmed when not the active sibling. Video
// sections use vector glyphs (folder / library / movie); everything else uses console art.
// solidUnfocusedIcons = the Display ▸ Appearance toggle: full-opacity unselected glyphs.
@Composable
private fun SiblingIcon(item: XMBItem, selected: Boolean, solidUnfocusedIcons: Boolean = false) {
    val chip = if (selected) 56.dp else 40.dp
    val videoGlyph = when (item.type) {
        // Missing takes the vector path rather than console art: there is no sysicon for it, and
        // the console fallback is the blank sysicon_default. Same "?" glyph the Untracked row in
        // the Shiba hub uses — both mean "we know about this entry but can't account for it".
        XMBItemType.MISSING         -> Icons.AutoMirrored.Filled.HelpOutline
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
        XMBItemType.PLAYLIST        -> Icons.AutoMirrored.Filled.QueueMusic
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
                modifier = Modifier.size(chip).alpha(if (selected || solidUnfocusedIcons) 1f else 0.5f),
            )
        } else {
            com.playfieldportal.core.ui.icons.ConsoleIcon(
                platformId = consoleIconKeyFor(item),
                contentDescription = item.title,
                modifier = Modifier.size(chip).alpha(if (selected || solidUnfocusedIcons) 1f else 0.5f),
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
    XMBItemType.MISSING -> "item_missing"
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
    XMBItemType.CATEGORY_CARD -> "allgames"
    XMBItemType.FAVORITES   -> "favorites"
    XMBItemType.MEMORY_CARD -> item.platformId
    else                    -> null   // collections / unknown fall back to sysicon_default
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
    // cycle). Without it the reorder would glide the old position to the new one.
    scrollToTopToken: Int = 0,
    // Identifies which list this is (XMBUiState.viewCursorKey). A change means a different list took
    // this column's place — a Settings section, a Music view — so the position snaps to the restored
    // cursor instead of gliding there. Null for a column that never swaps its list.
    columnKey: Any? = null,
    // Bumps when the cursor lands on a section's default row: the column snaps there, never glides.
    landingToken: Int = 0,
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
    // "Solid Unfocused Icons" (Display ▸ Appearance): when true, unselected rows skip the
    // unfocused dim — selection still reads by the row's scale and the bright label. Default
    // false = today's dimming.
    solidUnfocusedIcons: Boolean = false,
    // "Text Shadow" (Display ▸ Appearance): directional drop shadow behind row labels and
    // subtitles, so helper text stays readable over bright wallpaper regions. Default true —
    // without it the flat gray subtitle is the one label that washes out.
    textShadow: Boolean = true,
    // Whether this list's animated art may play at all (battery saver / blocking overlays gate
    // it). Provided per row with the row's selection; see LocalMotionAllowed.
    iconAnimatingAllowed: Boolean = false,
    // "Item List Motion" (Display): Rewind's hand-off across the bar, or one Glide. Glide unless
    // the caller opts in, so every other column keeps the plain spring.
    listMotion: XmbListMotion = XmbListMotion.GLIDE,
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
    // bar) and the next becomes selected (below it). Every row is composed ONCE, keyed by its item, and
    // placed by itemRowTopPx from one animated position — the selected index in rows — so a row that
    // steps up leaves the below slot, passes behind the bar and settles in the previous slot as one
    // continuous motion. Like the category bar, the column is positioned, never scrolled: at rest the
    // position equals the target exactly, so there is nothing to drift, and it starts at its target, so
    // first composition and resume never glide.
    BoxWithConstraints(modifier = modifier.fillMaxWidth().fillMaxHeight().clip(ClipAllButLeft)) {
        // The focused UMD's row grows once its disc is read, as the PSP's does, pushing the rows
        // under it down rather than covering them. Same read clock as the row's own swap.
        val umdRead = rememberUmdRead(umdReadKey(items.getOrNull(selectedIndex), columnIndex = 0))
        val umdRowHeight by androidx.compose.animation.core.animateDpAsState(
            targetValue = if (umdRead) UMD_ROW_HEIGHT else ROW_HEIGHT,
            animationSpec = tween(200),
            label = "umdRowHeight",
        )
        // Keep whole rows only below the anchor — the active row plus however many whole rows remain
        // in the space beneath it — so nothing rests as a half-height sliver at the bottom edge (on any
        // screen size). A row still in transit past the last whole slot is cut at that slot's line.
        val rowsBelow = ((maxHeight.value - belowTopY.value) / ROW_HEIGHT.value).toInt()
            .coerceAtLeast(1)

        // The anchors in whole px, rounded the way the old Column and half-row window rounded them, so
        // at rest every row lands on the same pixel as before (see itemRowTopPx).
        val density = LocalDensity.current
        val rowPx = with(density) { ROW_HEIGHT.roundToPx() }
        val belowTopPx = with(density) { belowTopY.roundToPx() }
        val winPx = with(density) { (ROW_HEIGHT / 2).roundToPx() }
        // Rise distance is theme-tunable: PSP-style wallpapers want the previous item fully clear of
        // the caticon hexagon before it dissolves.
        val winTopPx = with(density) { (barTopY - ROW_HEIGHT * previousRiseRows).roundToPx() }
        // The UMD's growth belongs to the UMD row, so it keeps pushing the rows after it down while it
        // shrinks back after the cursor leaves it, instead of snapping with the selection.
        val umdIndex = remember(items) { items.indexOfFirst { it.type == XMBItemType.UMD_SLOT } }
        val umdExtraPx = with(density) { umdRowHeight.roundToPx() - rowPx }

        val inputs = StepInputs(
            rawSelectedIndex = selectedIndex,
            itemCount = items.size,
            scrollToTopToken = scrollToTopToken,
            columnKey = columnKey,
            moving = LocalXmbRowDecor.current.movingLabel != null,
            landingToken = landingToken,
        )
        val position = remember { ItemListPosition(inputs.target, constantSpeed = true) }
        // Decided here, in the frame the inputs change, so a snap is already drawn by that frame.
        position.accept(inputs, listMotion)
        LaunchedEffect(position.epoch) { position.run() }
        LaunchedEffect(position.epoch) { position.runClock() }
        // Flips only when a hand-off releases the new focus, not on every frame of the clock.
        val focusShown by remember { derivedStateOf { position.focusShown() } }

        // Recomposes only when the set of rows changes, not on every animated frame.
        val window by remember(selectedIndex, items.size, rowsBelow) {
            derivedStateOf { itemRowWindow(selectedIndex, items.size, rowsBelow, position.read()) }
        }
        val target = inputs.target.toFloat()

        for (i in window) {
            val item = items[i]
            key(item.id) {
                // The row's top in px for a position. Read in the layout and draw phases.
                fun top(p: Float) = itemRowTopPx(i, p, belowTopPx, winTopPx, winPx, rowPx, umdIndex, umdExtraPx)
                XmbVerticalListRow(
                    item = item,
                    isSelected = i == selectedIndex && focusShown,
                    // The real PSP XMB labels EVERY first-level item (selected bright, the
                    // rest dimmed) — labels show unless the caller asks for an icon-only column
                    // (the drill flyout's memory-card cross).
                    showText = showLabels,
                    iconStyle = iconStyle,
                    onClick = { onItemSelected(i) },
                    onLongPress = { onItemLongPress(i) },
                    showIcon = showIcons,
                    trailingCursor = drillCursorOnSelected && i == selectedIndex,
                    solidUnfocusedIcons = solidUnfocusedIcons,
                    textShadow = textShadow,
                    iconAnimatingAllowed = iconAnimatingAllowed,
                    // The tag sits before the row's own graphicsLayer, so tests read unscaled bounds.
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(0, top(position.positionFor(i)).roundToInt()) }
                        .height(if (i == umdIndex) umdRowHeight else ROW_HEIGHT)
                        // A row above its slot shows only its part of the half-row window above the
                        // bar. requiredHeight overflow is CENTRED, so that window has always shown the
                        // row's middle half, not its bottom half: it reads as "coming in" from behind
                        // the crossbar. Rows at or below the selected slot stay unclipped so a lifted
                        // row's outline and a marked badge, which reach above the row, are not shaved.
                        .drawWithContent {
                            val p = position.positionFor(i)
                            val rowTop = top(p)
                            val big = size.width * 8f
                            val windowRect = Rect(0f, winTopPx - rowTop, size.width, winTopPx + winPx - rowTop)
                            // Rewind: a row crossing the bar also draws in the band between the window
                            // and the focus slot, behind the category icon, fading as it settles.
                            val band = position.bandAlphaFor(i)
                            if (band > 0f) {
                                val bandRect = Rect(-big, winTopPx + winPx - rowTop, big, belowTopPx - rowTop)
                                clipRect(bandRect.left, bandRect.top, bandRect.right, bandRect.bottom) {
                                    drawContext.canvas.saveLayer(bandRect, Paint().apply { alpha = band })
                                    this@drawWithContent.drawContent()
                                    drawContext.canvas.restore()
                                }
                            }
                            when (itemRowClip(i - p, rowsBelow, atRest = position.read() == target)) {
                                ItemRowClip.None -> drawContent()
                                ItemRowClip.Window -> clipRect(
                                    windowRect.left, windowRect.top, windowRect.right, windowRect.bottom,
                                ) { this@drawWithContent.drawContent() }
                                ItemRowClip.BelowOrWindow -> clipPath(
                                    Path().apply {
                                        addRect(Rect(-big, belowTopPx - rowTop, big, big))
                                        addRect(windowRect)
                                    },
                                ) { this@drawWithContent.drawContent() }
                                ItemRowClip.BottomLimit -> {
                                    val limit = belowTopPx + rowsBelow * rowPx - rowTop
                                    clipRect(-big, -big, big, limit) { this@drawWithContent.drawContent() }
                                }
                            }
                        }
                        .testTag("xmbRow:${item.id}"),
                )
            }
        }
    }
}

/**
 * The item column's animated position: the selected index in rows, on the shared step spring. It is
 * decided in composition ([accept]) and carried out in an effect ([run]); a snap is [held] meanwhile,
 * so the frame that first shows the new selection already draws it instead of drawing it a frame late.
 *
 * Under [XmbListMotion.REWIND] a single, unheld step also hands one row across the category bar on
 * its own fast clock ([Crossing], see XmbStepMotion): [positionFor] is where each row draws, and the
 * column itself waits out [XmbHandOff.COLUMN_LAG_MS] on a step down.
 *
 * [constantSpeed] (the main XMB column) glides at [XmbGlide]'s one speed instead of the spring;
 * the drill flyout's game column keeps the spring.
 */
private class ItemListPosition(initial: Int, private val constantSpeed: Boolean = false) {
    private val animatable = Animatable(initial.toFloat(), Float.VectorConverter, STEP_SETTLE_ROWS)

    // Where a snap has landed but the animatable has not been told yet; NaN when there is none.
    private var held by mutableFloatStateOf(Float.NaN)
    private var previous: StepInputs? = null
    private var motion: StepMotion = StepMotion.Snap(initial)
    // Rewind: the rows crossing the bar now, the clock they move on, and the step-timing memory.
    private val crossings = mutableStateListOf<Crossing>()
    private var nowMs by mutableLongStateOf(0L)
    private var lastStepMs: Long? = null
    private var columnDelayMs = 0L
    private var focusFromMs by mutableLongStateOf(0L)

    /** Bumps on every change of inputs, so the effect restarts even for an identical motion. */
    var epoch = 0
        private set

    /** The position to draw this frame, in rows. */
    fun read(): Float = held.takeUnless { it.isNaN() } ?: animatable.value

    /** The position row [index] draws at: the column's, or its own while it crosses the bar. */
    fun positionFor(index: Int): Float {
        val p = read()
        val c = crossings.lastOrNull { it.row == index } ?: return p
        return index - crossingD(c, nowMs, index - p)
    }

    /** How much of row [index] draws in the catbar band: any row in transit across it, none at rest. */
    fun bandAlphaFor(index: Int): Float = transitBandAlpha(index - positionFor(index))

    /** False while a hand-off holds the new focus back: no row draws as focused until it sets off. */
    fun focusShown(): Boolean = nowMs >= focusFromMs

    fun accept(inputs: StepInputs, style: XmbListMotion = XmbListMotion.GLIDE) {
        if (inputs == previous) return
        val prev = previous
        motion = xmbStepMotion(prev, inputs).also {
            when (it) {
                is StepMotion.Snap -> held = it.target.toFloat()
                is StepMotion.SnapThenGlide -> held = it.from.toFloat()
                is StepMotion.Glide -> Unit
            }
        }
        columnDelayMs = 0L
        val now = System.nanoTime() / 1_000_000
        nowMs = now
        focusFromMs = now
        val m = motion
        if (m !is StepMotion.Glide || prev == null || style != XmbListMotion.REWIND) {
            // A snap or a long jump starts over; Glide never hands off, and a crossing left over
            // from Rewind (the setting changed mid-crossing) could never complete under it.
            if (m !is StepMotion.Glide || style != XmbListMotion.REWIND) crossings.clear()
            lastStepMs = null
        } else if (prev.target != m.target) {
            val row = if (m.target > prev.target) prev.target else m.target
            val step = handOffFor(prev.target, m.target, now, lastStepMs, fromD = row - positionFor(row))
            if (step != null) {
                // A reversal hands the same row back: the old crossing goes, and the new one starts
                // from where that row is drawn now.
                crossings.removeAll { it.row == row || it.down != step.down }
                crossings += step
            } else {
                // No hand-off this time, but a row still crossing the other way must turn back with
                // the column, or it never completes and stays stranded across the bar.
                val turned = handBackReversed(crossings.toList(), down = m.target > prev.target, m.target, now) { row ->
                    row - positionFor(row)
                }
                crossings.clear()
                crossings += turned
            }
            columnDelayMs = columnStartMs(step, now) - now
            focusFromMs = focusStartMs(step, now)
            lastStepMs = now
        }
        previous = inputs
        epoch++
    }

    suspend fun run() {
        held.takeUnless { it.isNaN() }?.let {
            animatable.snapTo(it)
            held = Float.NaN
        }
        val m = motion
        if (m !is StepMotion.Snap) {
            if (columnDelayMs > 0) delay(columnDelayMs)
            val target = m.target.toFloat()
            if (constantSpeed) {
                // Retargeted mid-glide (a held run), it carries on from where it is at the same speed.
                animatable.animateTo(target, tween(glideDurationMs(animatable.value, target), easing = LinearEasing))
            } else {
                animatable.animateTo(target, XmbStepSpring.spec(STEP_SETTLE_ROWS))
            }
        }
    }

    /** Drives the hand-off clock every frame while a row crosses or the new focus is held back. */
    suspend fun runClock() {
        while (crossings.isNotEmpty() || nowMs < focusFromMs) {
            withFrameNanos { nowMs = it / 1_000_000 }
            val p = read()
            crossings.removeAll { crossingDone(it, nowMs, it.row - p) }
        }
    }
}

// Settle distance in rows: the category bar's 0.1 dp, so the tail does not visibly snap.
private val STEP_SETTLE_ROWS = 0.1f / ROW_HEIGHT.value

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
    // "Solid Unfocused Icons": when true, this unselected row skips the unfocused dim.
    solidUnfocusedIcons: Boolean = false,
    // "Text Shadow" (Display ▸ Appearance): drop shadow behind row helper text (subtitle).
    textShadow: Boolean = true,
    // Whether THIS row may animate its GIF icon — true only for the focused row, so exactly
    // one decoder runs at a time (decision 3). Provided per-row around the icon.
    iconAnimatingAllowed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Strong size delta between the locked selection and the rows scrolling past it — the PSP
    // "the cursor stays, the list breathes" feel.
    val scale by animateFloatAsState(
        targetValue = if (isSelected) SELECTED_ROW_SCALE else 0.9f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "xmbListRowScale",
    )
    val rowAlpha by animateFloatAsState(
        targetValue = when {
            // "Solid Unfocused Icons": skip the unfocused dim; selection still reads by scale + label.
            isSelected || solidUnfocusedIcons -> 1f
            item.type == XMBItemType.EMPTY -> 0.5f
            else -> 0.68f
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "xmbListRowAlpha",
    )
    // Shadow for row helper text: the subtitle is the only label with no separation treatment
    // (the title brightens + shadows when selected), so it's the text that washes out over the
    // bright half of a wallpaper. Same directional shadow idiom as PspContextMenu/ControllerHintBar.
    val subtitleStyle = if (textShadow) TextStyle(shadow = XmbTextShadow) else TextStyle.Default

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
            // Arrangement state for this row: lifted (being moved) or marked (multi-select).
            val decor = LocalXmbRowDecor.current
            val moving = isSelected && decor.movingLabel != null
            val marked = decor.markMode && item.gameId != null && item.gameId in decor.markedGameIds
            // A focused UMD slot stays the UMD, named, until the disc has been read.
            val umdShowsGame = isSelected && rememberUmdRead(umdReadKey(item.takeIf { isSelected }, columnIndex = 0))
            if (showIcon && !item.textOnly) {
                // Per-row animation gate (Animated Images): this row's art counts as focused only
                // while the row is selected — what Reduced plays — and nothing in it may animate
                // while the list's own gate is shut (battery saver, a blocking overlay).
                androidx.compose.runtime.CompositionLocalProvider(
                    com.playfieldportal.core.ui.motion.LocalMotionFocused provides isSelected,
                    com.playfieldportal.core.ui.motion.LocalIconFocused provides isSelected,
                    com.playfieldportal.core.ui.motion.LocalMotionAllowed provides
                        (com.playfieldportal.core.ui.motion.LocalMotionAllowed.current && iconAnimatingAllowed),
                ) {
                Box(
                    modifier = Modifier.arrangeDecoration(
                        moving = moving,
                        marked = marked,
                        // App icons are clipped to a rounded square (APP_ICON_CORNER), so their
                        // outline follows that shape; everything else is a tile or a glyph.
                        appIcon = item.isAndroidApp && item.gameId == null && item.iconUri == null,
                        accent = LocalPFPColors.current.accentColor,
                    ),
                ) {
                XmbItemLeadingIcon(
                    item = item,
                    iconStyle = iconStyle,
                    isSelected = isSelected,
                    umdShowsGame = umdShowsGame,
                )
                }
                }
            }

            val showGameText = xmbRowShowsTitle(item, isSelected, umdShowsGame, moving)
            // "Moving · 2 of 5" replaces the subtitle on the lifted row.
            val subtitleText = if (moving) decor.movingLabel else item.subtitle
            if (showText && showGameText) {
                // start padding pushes the label clear of the wallpaper's vertical cross bar, so the
                // text doesn't butt against the black band (a small gap, PSP-style).
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(start = XmbLayoutSpec.DEFAULT.itemTextStartGapDp.dp),
                ) {
                    // The bone glyph keeps the built-in tone: icons are not text.
                    val glyphColor = if (isSelected) PrimaryText else InactiveText
                    val titleColor = themedText(glyphColor)
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
                            BoneGlyph(tint = glyphColor, size = 14.dp)
                        }
                    }
                    if (!subtitleText.isNullOrBlank() || item.subtitleHintIcon != null) {
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
                            subtitleText?.takeIf { it.isNotBlank() }?.let { subtitle ->
                                Text(
                                    text = subtitle,
                                    color = themedSubText(SecondaryText),
                                    fontSize = if (isSelected) 12.sp else 11.sp,
                                    fontWeight = FontWeight.Normal,
                                    style = subtitleStyle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            // A fixed-position prompt trailing the subtitle (the PTT capture's
                            // cancel button). Drawn in the user's controller family via the
                            // ambient style, like every other prompt.
                            item.subtitleHintIcon?.let { icon ->
                                Spacer(Modifier.width(6.dp))
                                ControllerPromptGlyphs(
                                    icons = listOf(icon),
                                    label = item.subtitleHintLabel.orEmpty(),
                                    labelColor = themedSubText(SecondaryText),
                                    labelStyle = TextStyle(
                                        fontSize = if (isSelected) 12.sp else 11.sp,
                                    ),
                                    glyphSize = 14.dp,
                                )
                            }
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

/**
 * What the list tells its rows about arranging: [movingLabel] is the lifted row's position line
 * ("Moving · 2 of 5") while a Move is in progress, and [markedGameIds] are the games marked in
 * multi-select. Provided by the shell so the rows need no extra parameters.
 */
data class XmbRowDecor(
    val movingLabel: String? = null,
    val markMode: Boolean = false,
    val markedGameIds: Set<Long> = emptySet(),
)

val LocalXmbRowDecor = androidx.compose.runtime.compositionLocalOf { XmbRowDecor() }

/**
 * Draws a row's arrangement state over its icon. A row being moved gets a white outline hugging
 * the icon with a chevron set into its top and bottom edges; a marked game gets a check badge on the icon's top
 * left. Drawn, not laid out, so neither changes the row's size or shifts its neighbours.
 */
private fun Modifier.arrangeDecoration(
    moving: Boolean,
    marked: Boolean,
    appIcon: Boolean,
    accent: Color,
): Modifier = if (!moving && !marked) this else drawWithContent {
    drawContent()
    // The icon's own bounds inside this box: a wide box is a game tile followed by its text gap,
    // anything else is a glyph or app icon centred in the icon slot. The gap is taken off before
    // comparing: an app row's slot (74×48dp) is wide too, but only because the slot is.
    val isTile = size.width - ARTWORK_TEXT_GAP.toPx() > size.height * 1.5f
    val iconWidth = if (isTile) size.width - ARTWORK_TEXT_GAP.toPx() else size.height
    val left = if (isTile) 0f else (size.width - iconWidth) / 2f
    if (moving) {
        drawMoveOutline(
            icon = androidx.compose.ui.geometry.Rect(left, 0f, left + iconWidth, size.height),
            // An app's outline is concentric with its icon's clip: the same corner, grown by the
            // gap between them.
            corner = if (appIcon) APP_ICON_CORNER + 4.dp else 8.dp,
        )
    }
    if (marked) {
        val radius = 10.dp.toPx()
        val center = Offset(left + 2.dp.toPx(), 2.dp.toPx())
        drawCircle(accent, radius = radius, center = center)
        drawCircle(Color.White, radius = radius, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
        val check = androidx.compose.ui.graphics.Path().apply {
            moveTo(center.x - radius * 0.45f, center.y)
            lineTo(center.x - radius * 0.1f, center.y + radius * 0.38f)
            lineTo(center.x + radius * 0.5f, center.y - radius * 0.35f)
        }
        drawPath(
            check,
            Color.White,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 2.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            ),
        )
    }
}

@Composable
private fun XmbItemLeadingIcon(
    item: XMBItem,
    iconStyle: GameIconStyle,
    isSelected: Boolean,
    // A focused UMD slot whose read has finished — see rememberUmdRead.
    umdShowsGame: Boolean = false,
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
                    com.playfieldportal.core.ui.motion.ArtworkImage(
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
                    defaultVector = Icons.AutoMirrored.Filled.QueueMusic,
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
                    com.playfieldportal.core.ui.motion.ArtworkImage(
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
                    com.playfieldportal.core.ui.motion.ArtworkImage(
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
                    com.playfieldportal.core.ui.motion.ArtworkImage(
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
                    com.playfieldportal.core.ui.motion.ArtworkImage(
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
                    com.playfieldportal.core.ui.motion.ArtworkImage(
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
        // Missing sits beside All Games / Favorites but is not console art — it gets the same "?"
        // glyph as the Shiba hub's Untracked row, at the memory-card icon size so it lines up with
        // the cards above it. Themeable via the item_missing slot like any other vector row.
        item.type == XMBItemType.MISSING -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(LEADING_ICON_SLOT)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(LEADING_ICON_SIZE).selectedIconBloom(isSelected),
                ) {
                    ThemedGlyph(
                        itemSlotKeyFor(item.type) ?: "",
                        Icons.AutoMirrored.Filled.HelpOutline,
                        null,
                        iconTint,
                        Modifier.size(LEADING_ICON_SIZE),
                    )
                }
            }
        }
        // The UMD slot, unfocused or still being read: the PSP's own physical media — the UMD —
        // whatever platform the inserted game is from, unless the user or the theme replaced it
        // (item_umd; two-tier like the memory cards). Once read, it falls through to the game
        // branch below and becomes the game's icon.
        item.type == XMBItemType.UMD_SLOT && !umdShowsGame -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.width(LEADING_ICON_SLOT),
            ) {
                val umdOverride = LocalXmbIcons.current[UMD_SLOT_KEY]
                if (umdOverride != null) {
                    com.playfieldportal.core.ui.icons.CustomIconSurface(
                        icon = umdOverride,
                        contentDescription = null,
                        modifier = Modifier.size(LEADING_ICON_SIZE),
                    )
                } else {
                    BundledSilhouetteIcon(
                        assetUri = UMD_SLOT_ART,
                        modifier = Modifier.size(LEADING_ICON_SIZE),
                    )
                }
            }
        }
        // The UMD slot, focused and read: the game's ICON0 at the PSP's size, anchored the way the
        // PSP draws it — its right edge on the selected caticon's right edge, the extra width
        // running off to the left and out past the screen edge. The slot shares the caticon's
        // centre line, so that is half the width difference.
        item.type == XMBItemType.UMD_SLOT -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.width(LEADING_ICON_SLOT).height(UMD_ICON_HEIGHT),
            ) {
                GameIcon(
                    item = item,
                    iconStyle = iconStyle,
                    modifier = Modifier
                        .requiredSize(width = UMD_ICON_WIDTH, height = UMD_ICON_HEIGHT)
                        // Divided back out of the row's selected scale, which grows the icon about
                        // that same line, so the right edge lands on the caticon's once scaled.
                        .offset(x = (XmbLayoutSpec.DEFAULT.categoryIconSelectedDp.dp / SELECTED_ROW_SCALE - UMD_ICON_WIDTH) / 2),
                )
            }
        }
        item.type == XMBItemType.ALL_GAMES ||
            item.type == XMBItemType.FAVORITES ||
            item.type == XMBItemType.MEMORY_CARD ||
            item.type == XMBItemType.CATEGORY_CARD ||
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
                // user-picked collection glyph or real cover artwork. Two-tier: user pick,
                // then the applied theme's icon.
                val memcardOverride = if (
                    collectionIconKey == null &&
                    (memoryCardArt == null || memoryCardArt.startsWith("file:///android_asset/systems/physical-media/"))
                ) {
                    memoryCardSlotKeyFor(item)?.let { key ->
                        LocalXmbIcons.current[key]
                    }
                } else {
                    null
                }
                if (memcardOverride != null) {
                    // Custom icons render as authored (untinted), like every slot — but the
                    // configured icon-legibility matte still draws behind the still frame.
                    com.playfieldportal.core.ui.icons.CustomIconSurface(
                        icon = memcardOverride,
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
                    // follows the unified icon color like every other glyph (PortalIcon applies
                    // the SrcIn tint AND the icon-legibility matte). Real user/content artwork
                    // (custom collection covers) stays untinted and matte-free.
                    val isBundledSilhouette =
                        memoryCardArt.startsWith("file:///android_asset/systems/physical-media/")
                    if (isBundledSilhouette) {
                        BundledSilhouetteIcon(
                            assetUri = memoryCardArt,
                            modifier = Modifier.size(LEADING_ICON_SIZE),
                        )
                    } else {
                        com.playfieldportal.core.ui.motion.ArtworkImage(
                            model = memoryCardArt,
                            contentDescription = null,
                            modifier = Modifier.size(LEADING_ICON_SIZE),
                        )
                    }
                } else {
                    // Memory-card rows show their matching console icon. All Games gets the generic
                    // cartridge art (sysicon_allgames) and Favorites the star (sysicon_favorites),
                    // both to stand apart from the Game controller.
                    val iconKey = when (item.type) {
                        XMBItemType.MEMORY_CARD -> item.platformId
                        XMBItemType.ALL_GAMES   -> "allgames"
                        // A category's own Memory Card is its All Games: the same glyph.
                        XMBItemType.CATEGORY_CARD -> "allgames"
                        XMBItemType.FAVORITES   -> "favorites"
                        else                    -> null
                    }
                    // Override-aware console icon: user pick > theme sysicon > built-in art.
                    if (iconKey != null) {
                        com.playfieldportal.core.ui.icons.ConsoleIcon(
                            platformId = iconKey,
                            contentDescription = null,
                            modifier = Modifier.size(LEADING_ICON_SIZE),
                        )
                    } else {
                        PortalIcon(
                            painter = painterResource(systemIconRes(iconKey)),
                            contentDescription = null,
                            modifier = Modifier.size(LEADING_ICON_SIZE),
                        )
                    }
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
                val settingsOverride = LocalXmbIcons.current["item_settings"]
                if (settingsOverride != null) {
                    com.playfieldportal.core.ui.icons.CustomIconSurface(
                        icon = settingsOverride,
                        contentDescription = null,
                        modifier = Modifier.size(LEADING_ICON_SIZE),
                    )
                } else {
                    PortalIcon(
                        painter = painterResource(systemIconRes("settings")),
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
        // bundled physical-media card art tinted with the theme icon color — and the matte,
        // like every other silhouette glyph.
        item.id == "ach_all" -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(LEADING_ICON_SLOT)) {
                // Two-tier override (user pick, then theme) before the bundled card art.
                val trackKey = shibaSlotKeyFor(item.id)
                val trackOverride = trackKey?.let {
                    LocalXmbIcons.current[it]
                }
                if (trackOverride != null) {
                    com.playfieldportal.core.ui.icons.CustomIconSurface(
                        icon = trackOverride,
                        contentDescription = null,
                        modifier = Modifier.size(LEADING_ICON_SIZE),
                    )
                } else {
                    BundledSilhouetteIcon(
                        assetUri = MEMORY_CARD_DEFAULT_ART,
                        modifier = Modifier.size(LEADING_ICON_SIZE),
                    )
                }
            }
        }
        // Other Shiba Coins hub lens rows get a per-row Material glyph at the item-icon size (no
        // background), keyed by exact id so the untracked/coin rows keep their own treatment.
        achievementsGlyphFor(item.id) != null -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(LEADING_ICON_SLOT)) {
                ThemedGlyph(shibaSlotKeyFor(item.id) ?: "", achievementsGlyphFor(item.id)!!, null, iconTint, Modifier.size(LEADING_ICON_SIZE))
            }
        }
        else -> Spacer(modifier = Modifier.width(12.dp))
    }
}

// The bundled physical-media silhouettes (collections without a picked icon, the ach_all
// card, memory cards falling back to the default card art), decoded to a bitmap once and
// rendered through PortalIcon so they get the theme tint AND the icon-legibility matte like
// every other silhouette glyph. AsyncImage cannot host the matte — its intrinsic size is
// unknown until the image loads, which would desync the matte geometry. A decode failure
// degrades to the plain untinted image rather than dropping the row's icon.
@Composable
internal fun BundledSilhouetteIcon(assetUri: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = remember(assetUri) {
        runCatching {
            val assetPath = assetUri.removePrefix("file:///android_asset/")
            context.assets.open(assetPath).use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream).asImageBitmap()
            }
        }.getOrNull()
    }
    if (bitmap != null) {
        PortalIcon(
            painter = BitmapPainter(bitmap),
            contentDescription = null,
            modifier = modifier,
        )
    } else {
        com.playfieldportal.core.ui.motion.ArtworkImage(model = assetUri, contentDescription = null, modifier = modifier)
    }
}

// The leading glyph for a Shiba Coins hub lens row, or null if the id isn't one of them.
private fun achievementsGlyphFor(id: String): androidx.compose.ui.graphics.vector.ImageVector? = when (id) {
    "ach_untracked" -> Icons.AutoMirrored.Filled.HelpOutline
    "ach_connect" -> Icons.Filled.Link
    else -> null
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
        modifier = modifier.clip(RoundedCornerShape(APP_ICON_CORNER)),
    )
}

// The rounded square every installed app's icon is clipped to; the Move outline follows it.
private val APP_ICON_CORNER = 6.dp
