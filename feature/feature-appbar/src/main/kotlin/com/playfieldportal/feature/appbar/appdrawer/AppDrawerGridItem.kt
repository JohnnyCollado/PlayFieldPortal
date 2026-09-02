package com.playfieldportal.feature.appbar.appdrawer

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.drawablepainter.DrawablePainter
import com.playfieldportal.core.ui.theme.StorefrontColors
import com.playfieldportal.feature.appbar.InstalledApp

// ── Application tile ──────────────────────────────────────────────────────────
//
// Artwork-first: a bare 72dp icon with the label beneath — no Material card, no corner radius,
// no shadow, no fill in the resting state. Selection is crisp and immediate: a 1dp bright outer
// border, a 1dp inner hairline inset 2dp, a very low-alpha accent plate behind the artwork, and
// the label stepping from secondary to primary. Nothing scales, bounces or elevates; the chrome
// cross-fades in/out over 120ms and occupies the same box either way, so tile geometry never
// shifts when the cursor moves.

private val ARTWORK_SIZE = 72.dp
private val TILE_BORDER = 1.dp
// Chrome room around the artwork: outer border + 2dp gap + inner hairline on each side.
private val FRAME_ROOM = 8.dp
private val SELECTION_TWEEN = 120

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppDrawerGridItem(
    app: InstalledApp,
    isSelected: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    colors: StorefrontColors,
) {
    // Cross-fade the selection chrome; the borders/plate are always composed (alpha 0 when off)
    // so appearing/disappearing never nudges the layout.
    val selection by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = tween(SELECTION_TWEEN),
        label = "appTileSelection",
    )
    val labelColor by animateColorAsState(
        targetValue = if (isSelected) colors.textPrimary else colors.textSecondary,
        animationSpec = tween(SELECTION_TWEEN),
        label = "appTileLabel",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = onMenu)
            .padding(vertical = 4.dp),
    ) {
        Box(modifier = Modifier.size(ARTWORK_SIZE + FRAME_ROOM)) {
            // Very low-alpha accent plate behind the artwork.
            Box(
                Modifier
                    .matchParentSize()
                    .background(colors.selectionGlow.copy(alpha = colors.selectionGlow.alpha * selection)),
            )
            // 1dp bright outer selection edge.
            Box(
                Modifier
                    .matchParentSize()
                    .border(TILE_BORDER, colors.tileSelectedEdge.copy(alpha = selection)),
            )
            // 1dp inner line inset 2dp from the outer edge.
            Box(
                Modifier
                    .matchParentSize()
                    .padding(2.dp)
                    .border(TILE_BORDER, colors.tileSelectedInner.copy(alpha = selection)),
            )
            Image(
                painter = DrawablePainter(app.icon),
                contentDescription = app.label,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(ARTWORK_SIZE),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = app.label,
            color = labelColor,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = 13.sp,
        )
    }
}
