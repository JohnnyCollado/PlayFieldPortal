package com.playfieldportal.feature.xmb.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.TouchGesture
import com.playfieldportal.core.ui.components.ControllerPromptBar
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.core.ui.components.TouchPromptBar
import com.playfieldportal.core.ui.components.TouchPromptItem
import com.playfieldportal.core.ui.components.XmbHeaderPill
import com.playfieldportal.core.ui.components.XmbKebabTouchButton
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.ui.theme.LocalPfpTextColors
import com.playfieldportal.core.ui.theme.menuCursorEdge
import com.playfieldportal.feature.xmb.viewmodel.MusicBrowserNowPlaying
import com.playfieldportal.feature.xmb.viewmodel.MusicBrowserState
import com.playfieldportal.feature.xmb.viewmodel.XMBItem
import com.playfieldportal.feature.xmb.viewmodel.XMBItemType

// Composable getters, not constants: they read the resolved palette out of LocalPfpTextColors,
// so a user font colour (or a backdrop-driven clamp) repaints every row here with no edit at any
// of the call sites. Same treatment as the settings screens.
private val PrimaryText: Color
    @Composable get() = LocalPfpTextColors.current.primary
private val SecondaryText: Color
    @Composable get() = LocalPfpTextColors.current.secondary

private val CoverPlaceholder = Color(0xFF1B1B27)

/**
 * Fullscreen, searchable "Settings-style" browser for the Music and Playlist root items. Stateless:
 * renders [state] and forwards intents. Controller input is handled in the ViewModel (the search
 * field is touch-driven); the list also accepts touch.
 */
@Composable
fun MusicBrowserScreen(
    state: MusicBrowserState,
    onQueryChange: (String) -> Unit,
    onActivateAt: (Int) -> Unit,
    onLongPressAt: (Int) -> Unit,
    onBack: () -> Unit,
    onSortTapped: () -> Unit = {},
    onOptionsTapped: () -> Unit = {},
    // Show the touch header pills only when the last input was touch (AUTO), matching the XMB's
    // contextual App Drawer button. Controller users rely on the prompt bar below, which names
    // the actions and lets the shared resolver draw whichever buttons their pad binds them to.
    onSearchFocusChanged: (Boolean) -> Unit = {},
    /** Touch: the now-playing strip — brings the player back up on the song it names. */
    onNowPlayingTapped: () -> Unit = {},
    /** Any finger on the list: hides the controller cursor and arms the revival press. */
    onTouchInput: () -> Unit = {},
    /** Row id → Y, plus the viewport centre, for the engine's nearest-visible recovery. */
    onGeometry: (Map<String, Float>, Float) -> Unit = { _, _ -> },
    showTouchControls: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // The focused row frames itself with a BringIntoViewRequester (this app's convention for
    // controller focus — see StudioAssetManager). That can only work once the row is composed, so a
    // jump that lands outside the viewport — a sort cycling back to the top, a query refiltering the
    // list — brings it into range first and lets the row do the framing from there.
    LaunchedEffect(state.selectedIndex, state.scrollToTopToken, state.cursorVisible) {
        if (state.rows.isEmpty()) return@LaunchedEffect
        // Never chase the cursor while it is hidden: the list belongs to the finger then, and
        // scrolling it back to a stale focus is exactly the jerk this migration removes.
        if (!state.cursorVisible) return@LaunchedEffect
        val target = state.selectedIndex.coerceIn(0, state.rows.lastIndex)
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == target }) {
            listState.scrollToItem(target)
        }
    }

    // A cursor appearing is a source transition, and the screen has to hold still for it. Taking
    // the scroll mutex with an empty mutation cancels whatever fling the finger left running — so
    // the row the cursor just landed on cannot slide out from under it a moment later — and moves
    // nothing itself, which is what keeps the revival press from being a jump.
    LaunchedEffect(state.cursorVisible) {
        if (!state.cursorVisible) return@LaunchedEffect
        listState.scroll { }
    }

    // Feed the engine row geometry from the LazyColumn itself rather than from per-row
    // onGloballyPositioned callbacks: the list already computes this, and only the visible window
    // matters to a "nearest visible node" query.
    LaunchedEffect(listState, state.rows) {
        snapshotFlow { listState.layoutInfo }.collect { info ->
            if (info.visibleItemsInfo.isEmpty()) return@collect
            val geometry = info.visibleItemsInfo.mapNotNull { item ->
                state.rows.getOrNull(item.index)?.id?.let { it to item.offset.toFloat() }
            }.toMap()
            onGeometry(geometry, (info.viewportStartOffset + info.viewportEndOffset) / 2f)
        }
    }

    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // Two-frame focus idiom (AppPickerScreen / AppDrawerScreen): the field must be composed before
    // the FocusRequester can take it.
    LaunchedEffect(state.searchActive) {
        if (state.searchActive) {
            withFrameNanos {}
            withFrameNanos {}
            runCatching { searchFocus.requestFocus() }
            keyboard?.show()
        } else {
            keyboard?.hide()
            focusManager.clearFocus()
        }
    }

    val pfpColors = LocalPFPColors.current
    Box(
        modifier = modifier
            .fillMaxSize()
            // Semi-transparent scrim so the XMB wave/wallpaper background stays visible behind the
            // menu (the XMB foreground itself is hidden by XMBShell while this is open).
            .background(
                Brush.verticalGradient(
                    0f to pfpColors.backgroundTop.copy(alpha = 0.72f),
                    1f to pfpColors.backgroundBottom.copy(alpha = 0.90f),
                )
            )
            // Any pointer down anywhere hands control to touch, the same root-level detector
            // SettingsScaffold uses. requireUnconsumed = false so a row's own click still reports.
            .pointerInput(Unit) {
                awaitEachGesture { awaitFirstDown(requireUnconsumed = false); onTouchInput() }
            },
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
            // Header: breadcrumb (matching the detail menus — ◀ + title + trail, tap = back, no
            // press highlight, always visible), with touch pills for the X (sort) and Y (options)
            // actions on the right.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = onBack,
                    ),
                ) {
                    Text("◀", color = SecondaryText, fontSize = 18.sp, modifier = Modifier.padding(end = 16.dp))
                    Column {
                        Text(state.title, color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.Light)
                        Text(
                            when (state.view) {
                                is com.playfieldportal.feature.xmb.viewmodel.MusicBrowserView.Playlist -> "Music  ›  Playlists"
                                com.playfieldportal.feature.xmb.viewmodel.MusicBrowserView.Playlists -> "Music"
                                com.playfieldportal.feature.xmb.viewmodel.MusicBrowserView.AllMusic -> "All Tracks"
                            },
                            color = SecondaryText, fontSize = 12.sp,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (showTouchControls) {
                    // Sort applies to track views only (the ViewModel ignores it for playlists, so the
                    // pill is hidden there); the label carries the active sort, which is why this one
                    // stays a labelled pill rather than collapsing to a glyph.
                    state.sortPillLabel?.let { label ->
                        XmbHeaderPill(
                            label = label,
                            leadingGlyph = "⇵",
                            onClick = onSortTapped,
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    // Options is the vertical kebab app-wide — see ARCHITECTURE.md ▸ Conventions.
                    XmbKebabTouchButton(onClick = onOptionsTapped, size = 40.dp)
                }
            }

            Spacer(Modifier.height(14.dp))

            // Always visible, because the query is the list's filter and hiding it would hide
            // why the list looks the way it does. X focuses it; on touch, so does a tap.
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = SecondaryText) },
                placeholder = { Text("Search", color = SecondaryText.copy(alpha = 0.7f)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = PrimaryText,
                    unfocusedTextColor = PrimaryText,
                    focusedBorderColor = menuCursorEdge(),
                    unfocusedBorderColor = Color(0x33FFFFFF),
                    cursorColor = menuCursorEdge(),
                    focusedContainerColor = Color(0x22FFFFFF),
                    unfocusedContainerColor = Color(0x14FFFFFF),
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(searchFocus)
                    // A tap on the field takes focus without going through the ViewModel, so the
                    // flag follows the field rather than the other way round — otherwise B would
                    // think the keyboard was down while the user was still typing.
                    .onFocusChanged { onSearchFocusChanged(it.isFocused) },
            )

            Spacer(Modifier.height(12.dp))

            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(state.rows, key = { _, row -> row.id }) { index, row ->
                    BrowserRow(
                        row = row,
                        // Cursor visibility is the navigation engine's, not a guess from the last
                        // input source: touch hides it, and the next controller press re-anchors it
                        // to the nearest visible row before showing it again.
                        selected = index == state.selectedIndex && state.cursorVisible,
                        onClick = { onActivateAt(index) },
                        onLongClick = { onLongPressAt(index) },
                    )
                }
            }

            // The way back to the player, always on screen while something is loaded. Sized to one
            // row and drawn in both input modes: it is information first (what is playing, and that
            // the browser has not abandoned it), and the tap is the same reveal the Options menu's
            // Resume row performs. It is deliberately not a controller target — the D-pad list is
            // the navigation engine's and a focusable node here would sit outside it — so a pad
            // reaches this through Options ▸ Resume, which is also where it is discoverable blind.
            state.nowPlaying?.let { now ->
                Spacer(Modifier.height(10.dp))
                NowPlayingStrip(now = now, onTap = onNowPlayingTapped)
            }

            Spacer(Modifier.height(8.dp))
            // One input family on screen at a time (ARCHITECTURE.md ▸ Conventions). A finger gets
            // the gestures the rows actually bind; a pad gets the buttons. Sort and Back are absent
            // from the touch bar because the header already carries both as targets.
            if (showTouchControls) {
                TouchPromptBar(
                    items = listOf(
                        TouchPromptItem(TouchGesture.TAP, "Open"),
                        TouchPromptItem(TouchGesture.LONG_PRESS, "Options"),
                    ),
                    labelColor = SecondaryText.copy(alpha = 0.7f),
                    labelStyle = TextStyle(fontSize = 11.sp),
                    glyphSize = 18.dp,
                    arrangement = Arrangement.spacedBy(18.dp),
                )
            } else {
                ControllerPromptBar(
                    items = listOf(
                        ControllerPromptItem(GamepadAction.SELECT, "Open"),
                        // X is Search on every view, where Sort was a no-op on the playlists list.
                        // Sort now lives in the Options menu, which is also where a pad user was
                        // already going for anything list-shaped.
                        ControllerPromptItem(
                            GamepadAction.CHANGE_SORT,
                            if (state.searchActive) "Close Search" else "Search",
                        ),
                        ControllerPromptItem(GamepadAction.OPEN_CONTEXT_MENU, "Options"),
                        ControllerPromptItem(GamepadAction.BACK, "Back"),
                    ),
                    labelColor = SecondaryText.copy(alpha = 0.7f),
                    labelStyle = TextStyle(fontSize = 11.sp),
                    glyphSize = 16.dp,
                    arrangement = Arrangement.spacedBy(18.dp),
                )
            }
        }
    }
}

/**
 * The loaded song, named, with a tap that brings the player back up on it.
 *
 * Shows the transport state rather than the position: the strip is a way back to the player, not a
 * second player, so it carries nothing that would have to tick.
 */
@Composable
private fun NowPlayingStrip(
    now: MusicBrowserNowPlaying,
    onTap: () -> Unit,
) {
    // The search field's resting container, exactly: same fill, same hairline, same corner. The
    // strip is a control the way that field is, and neither of them is a row of the list above
    // (those are transparent until the cursor lands on one).
    val shape = RoundedCornerShape(10.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0x14FFFFFF))
            .border(1.dp, Color(0x33FFFFFF), shape)
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        TrackCoverThumb(coverUri = now.artUri, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Resume: ${now.title}",
                color = PrimaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!now.artist.isNullOrBlank()) {
                Text(
                    text = now.artist,
                    color = SecondaryText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            imageVector = if (now.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = menuCursorEdge(),
            modifier = Modifier.size(20.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowserRow(
    row: XMBItem,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val clickable = row.type != XMBItemType.EMPTY
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) { if (selected) requester.bringIntoView() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) com.playfieldportal.core.ui.theme.menuCursorFill() else Color.Transparent)
            .then(
                if (selected) Modifier.border(1.dp, com.playfieldportal.core.ui.theme.menuCursorEdge(), RoundedCornerShape(8.dp))
                else Modifier
            )
            .then(if (clickable) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        BrowserLeading(row)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                color = if (row.type == XMBItemType.EMPTY) SecondaryText else PrimaryText,
                fontSize = 16.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!row.subtitle.isNullOrBlank()) {
                Text(
                    text = row.subtitle,
                    color = SecondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BrowserLeading(row: XMBItem) {
    when {
        // Action rows (Create Playlist / Add Tracks) — a plus glyph.
        row.type == XMBItemType.STANDARD || row.type == XMBItemType.EMPTY -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp)) {
                if (row.type == XMBItemType.STANDARD) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = SecondaryText, modifier = Modifier.size(28.dp))
                }
            }
        }
        row.type == XMBItemType.PLAYLIST -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp)) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = SecondaryText, modifier = Modifier.size(30.dp))
            }
        }
        // Track row: album cover, or a framed music-note fallback — shared with the now-playing
        // strip so the same song cannot be drawn two ways on one screen.
        else -> TrackCoverThumb(coverUri = row.coverUri, size = 44.dp)
    }
}

/** Album art in a rounded tile, or a framed music note when the file has no art. */
@Composable
private fun TrackCoverThumb(coverUri: String?, size: Dp) {
    if (coverUri != null) {
        AsyncImage(
            model = coverUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(RoundedCornerShape(6.dp)),
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(CoverPlaceholder),
        ) {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = SecondaryText,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}
