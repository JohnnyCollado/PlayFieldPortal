package com.playfieldportal.feature.xmb.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.ImeAction
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.core.ui.detail.DetailContentPadding
import com.playfieldportal.core.ui.detail.DetailPalette

// ── Shared achievement-page parts ─────────────────────────────────────────────
//
// The surfaces the achievements library (Tracked / Untracked) and a game's own coins page both
// draw, kept in one file so the two pages can never drift apart: the pinned Search row, the focus
// treatment, the thin progress line, a coin tally, the row separator, and the shared dimensions.
// Extracted from ShibaLibraryScreen without changing a single value — the library must look
// exactly as it did.

/** Every list row is this tall on both pages, focused or not, so the list never shifts. */
internal val RowHeight = 64.dp

/** The pinned Search row (navigation position 0) on both pages. */
internal val SearchRowHeight = 48.dp

internal val FocusShape = RoundedCornerShape(4.dp)

/** Coin order wherever tiers are listed together: Platinum, Gold, Silver, Bronze (design §6). */
internal val CoinOrder = listOf(ShibaTier.PLATINUM, ShibaTier.GOLD, ShibaTier.SILVER, ShibaTier.BRONZE)

/** The design's darker header band: the page darkened in place, so it follows the theme. */
internal fun headerShade(palette: DetailPalette): Color =
    Color.Black.copy(alpha = if (palette.textPrimary.luminance() < 0.5f) 0.10f else 0.28f)

/** The focus treatment every focusable element on these pages shares; drawn inside its own bounds. */
internal fun Modifier.shibaFocus(focused: Boolean, palette: DetailPalette): Modifier =
    if (focused) {
        background(palette.focus.copy(alpha = 0.14f), FocusShape).border(1.5.dp, palette.focus, FocusShape)
    } else {
        this
    }

/**
 * The permanent Search row. Controller-first, the Artwork Studio's Change Match way: the field is
 * read-only while it is merely focused, and the keyboard opens only in text-entry mode — an open
 * keyboard receives key events before MainActivity, so raising it on focus would swallow the pad.
 *
 * [trailing] sits beside the field in the same row, outside its focus ring: the coins page puts its
 * All / Earned / Locked tabs there, and the library leaves it empty.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchRow(
    query: String,
    editing: Boolean,
    focused: Boolean,
    palette: DetailPalette,
    onQueryChange: (String) -> Unit,
    onClick: () -> Unit,
    onEditEnded: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search games…",
    trailing: (@Composable () -> Unit)? = null,
) {
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val isEditing by rememberUpdatedState(editing)
    LaunchedEffect(editing) {
        if (editing) {
            // Settle a frame around the readOnly → editable flip before raising the keyboard.
            withFrameNanos { }
            runCatching { fieldFocus.requestFocus() }
            withFrameNanos { }
            keyboard?.show()
        } else {
            keyboard?.hide()
            focusManager.clearFocus()
        }
    }
    // The keyboard dismissed by its own Back key ends text entry, so the pad drives the list again.
    val imeVisible = WindowInsets.isImeVisible
    var imeWasShown by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            imeWasShown = true
        } else if (imeWasShown && isEditing) {
            imeWasShown = false
            onEditEnded()
        }
    }

    Column(modifier.fillMaxWidth().padding(horizontal = DetailContentPadding)) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(SearchRowHeight)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .shibaFocus(focused, palette)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                    .padding(horizontal = 12.dp),
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = palette.textMuted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    readOnly = !editing,
                    singleLine = true,
                    textStyle = TextStyle(color = palette.textPrimary, fontSize = 15.sp),
                    cursorBrush = SolidColor(palette.focus),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onEditEnded() }, onDone = { onEditEnded() }),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(placeholder, color = palette.textMuted, fontSize = 15.sp)
                            }
                            inner()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(fieldFocus)
                        // A tap lands on the field itself: treat it as the touch path into text entry.
                        .onFocusChanged { if (it.isFocused && !isEditing) onClick() },
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(16.dp))
                trailing()
            }
        }
        Separator(palette)
    }
}

/** A tier's coin medallion beside a count. */
@Composable
internal fun CoinCount(tier: ShibaTier, count: Int, palette: DetailPalette, iconSize: Dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ShibaCoinIcon(tier, Modifier.size(iconSize))
        Spacer(Modifier.width(4.dp))
        Text("$count", color = palette.textPrimary, fontSize = 13.sp, maxLines = 1, softWrap = false)
    }
}

/** The thin accent progress line used for completion, rarity and level progress. */
@Composable
internal fun ProgressLine(
    fraction: Float,
    palette: DetailPalette,
    modifier: Modifier = Modifier,
    height: Dp = 3.dp,
) {
    Box(modifier.height(height).clip(RoundedCornerShape(2.dp)).background(palette.track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(palette.focus))
    }
}

/** The hairline between list rows. */
@Composable
internal fun Separator(palette: DetailPalette) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.rowEdge))
}

/** A short, human age: "Just now", "5 min ago", "3 hr ago", "Yesterday", "5 days ago". */
internal fun relativeTime(atMillis: Long, now: Long = System.currentTimeMillis()): String {
    val diff = (now - atMillis).coerceAtLeast(0)
    val minutes = diff / 60_000
    val hours = diff / 3_600_000
    val days = diff / 86_400_000
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours hr ago"
        days == 1L -> "Yesterday"
        else -> "$days days ago"
    }
}
