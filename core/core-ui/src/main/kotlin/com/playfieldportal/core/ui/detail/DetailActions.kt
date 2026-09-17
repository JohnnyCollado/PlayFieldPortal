package com.playfieldportal.core.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.playfieldportal.core.ui.image.rememberArtworkModel
import com.playfieldportal.core.ui.theme.menuCursorEdge

// ── Primary action region ─────────────────────────────────────────────────────
//
// Launch is the page's primary action and its strongest focus treatment; the quick actions beneath
// it are secondary. Both keep the shared focus language (thin bright edge + slight fill lift, no
// scale, no layout shift) so the whole app reads as one cursor.

/** The 196×110 icon frame beside the launch action. */
val DetailIconTileWidth = 196.dp
val DetailIconTileHeight = 110.dp

@Composable
fun PfpDetailLaunchButton(
    label: String,
    icon: ImageVector?,
    focused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = DetailLaunchFill,
    textColor: Color = DetailLaunchText,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 58.dp)
            .clip(shape)
            // The fill lifts slightly under focus. The border is drawn inside the node's bounds, so
            // focus changes nothing about the layout.
            .background(if (focused) lerp(fill, Color.White, 0.10f) else fill)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color.White else Color.Black.copy(alpha = 0.25f),
                shape = shape,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 15.dp, horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = label,
            color = textColor,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A labelled quick action under Launch.
 *
 * [available] is presentation only: an unavailable action stays visible (so the page's action set
 * does not move around) but is drained, carries the blocked mark instead of its own icon, and is
 * left out of the controller graph by the caller. It remains touchable, because touching it is how
 * a user learns *why* it is unavailable.
 */
@Composable
fun PfpDetailQuickAction(
    label: String,
    icon: ImageVector?,
    focused: Boolean,
    available: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val shape = RoundedCornerShape(9.dp)
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 46.dp)
            .clip(shape)
            .detailFocusRing(
                focused = focused,
                edge = menuCursorEdge(),
                fill = menuCursorEdge().copy(alpha = 0.20f),
                shape = shape,
            )
            .background(DetailRowFill, shape)
            // TalkBack cannot see the row's drained tint or its "Unavailable" line, so the state is
            // carried in the node's own description when the caller supplies one.
            .semantics(mergeDescendants = true) {
                contentDescription?.let { this.contentDescription = it }
            }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (!available) {
            PfpBlockedMark(color = DetailTextMuted.copy(alpha = 0.7f), size = 16.dp)
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = DetailTextPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(modifier = Modifier.alpha(if (available) 1f else 0.62f)) {
            Text(
                text = label,
                color = DetailTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!available) {
                Text(
                    text = "Unavailable",
                    color = DetailTextMuted,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The game's icon/cover tile. [content] overrides the artwork (e.g. a package icon preview). */
@Composable
fun PfpDetailIconTile(
    uri: String?,
    title: String,
    modifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .width(DetailIconTileWidth)
            .height(DetailIconTileHeight)
            .clip(shape)
            .background(DetailRowFill)
            .border(1.dp, DetailRowEdge, shape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            content != null -> content()
            uri != null -> AsyncImage(
                model = rememberArtworkModel(uri),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            else -> Text(
                text = title.take(1).uppercase(),
                color = DetailTextMuted,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
