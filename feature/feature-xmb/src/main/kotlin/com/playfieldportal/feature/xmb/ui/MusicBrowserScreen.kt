package com.playfieldportal.feature.xmb.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.runtime.remember
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.ui.theme.menuCursorEdge
import com.playfieldportal.feature.xmb.viewmodel.MusicBrowserState
import com.playfieldportal.feature.xmb.viewmodel.XMBItem
import com.playfieldportal.feature.xmb.viewmodel.XMBItemType

private val PrimaryText = Color.White
private val SecondaryText = Color(0xFFC9C7E8)
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
    // contextual App Drawer button. Controller users rely on the on-screen A/X/Y/B hints below.
    showTouchControls: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.selectedIndex, state.scrollToTopToken) {
        if (state.rows.isNotEmpty()) {
            val target = (state.selectedIndex - 1).coerceIn(0, state.rows.lastIndex)
            listState.animateScrollToItem(target)
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
            ),
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
                    // pill is hidden there); the label shows the active sort.
                    state.sortLabel?.let { label ->
                        HeaderPill(onClick = onSortTapped) {
                            Text("Sort: $label", color = PrimaryText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.width(10.dp))
                    }
                    HeaderPill(onClick = onOptionsTapped) {
                        Text("Options", color = PrimaryText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Always-visible search bar.
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
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(state.rows, key = { _, row -> row.id }) { index, row ->
                    BrowserRow(
                        row = row,
                        selected = index == state.selectedIndex,
                        onClick = { onActivateAt(index) },
                        onLongClick = { onLongPressAt(index) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "A: open   ·   X: sort   ·   Y: options   ·   B: back",
                color = SecondaryText.copy(alpha = 0.7f),
                fontSize = 11.sp,
            )
        }
    }
}

// The header's shared pill treatment (Back / Sort / Options): translucent chip, touch-first.
@Composable
private fun HeaderPill(
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x1FFFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        content = content,
    )
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
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
                Icon(Icons.Filled.QueueMusic, contentDescription = null, tint = SecondaryText, modifier = Modifier.size(30.dp))
            }
        }
        else -> {
            // Track row: album cover, or a framed music-note fallback.
            if (row.coverUri != null) {
                AsyncImage(
                    model = row.coverUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
                )
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(CoverPlaceholder),
                ) {
                    Icon(Icons.Filled.MusicNote, contentDescription = null, tint = SecondaryText, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}
