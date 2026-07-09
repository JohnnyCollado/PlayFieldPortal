package com.playfieldportal.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.ui.theme.menuCursorEdge

// ── PSP-style context menu panel ──────────────────────────────────────────────
//
// The canonical XMB sub-menu look: a translucent column anchored to the right
// edge over a light scrim, a plain title underlined by a thin rule, and the
// selected item marked by a soft horizontal glow band that bleeds to the screen
// edge (no boxed panel). Shared by the XMB's Y/Triangle menu and any settings
// screen that opens a per-item options menu — one source, no style drift.
//
// Controller navigation is the caller's job (selectedIndex in, activation out);
// this composable handles touch/click interaction.

/** One row of a [PspContextMenuOverlay]. */
data class PspMenuRow(
    val label: String,
    val isDestructive: Boolean = false,
    // Marks a current membership/selection (e.g. collections the item already belongs to).
    val checked: Boolean = false,
)

private val PanelWidth = 300.dp

// Black drop shadow on the menu text so it stays legible over the wave/backdrop.
private val TextDropShadow = Shadow(
    color = Color.Black.copy(alpha = 0.75f),
    offset = Offset(0f, 2f),
    blurRadius = 4f,
)

@Composable
fun PspContextMenuOverlay(
    title: String,
    rows: List<PspMenuRow>,
    selectedIndex: Int,
    onRowActivated: (index: Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPFPColors.current
    val listState = rememberLazyListState()

    LaunchedEffect(selectedIndex) {
        if (rows.isNotEmpty()) {
            listState.animateScrollToItem(selectedIndex.coerceIn(0, rows.size - 1))
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Light scrim — the wave stays visible behind, PSP-style.
            .background(Color(0x40000000))
            .clickable(onClick = onDismiss),
    ) {
        // Right-edge column. A solid backdrop at 75% alpha in the scheme's theme
        // color (the wave color — blue for Classic Blue, etc.) gives contrast
        // while still letting the wave show through.
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(PanelWidth)
                .background(colors.waveColor.copy(alpha = 0.75f))
                .clickable(onClick = {}) // consume clicks so the scrim isn't triggered inside
                .padding(start = 28.dp, end = 40.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            // ── Title ─────────────────────────────────────────────────────
            Text(
                text = title,
                fontSize = 19.sp,
                fontWeight = FontWeight.Light,
                color = Color.White.copy(alpha = 0.92f),
                style = TextStyle(shadow = TextDropShadow),
                maxLines = 2,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            // Thin underline rule beneath the title.
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp)
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.30f)),
            )

            // ── Items ──────────────────────────────────────────────────────
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(top = 10.dp),
            ) {
                itemsIndexed(rows) { index, row ->
                    PspContextMenuRow(
                        row        = row,
                        isSelected = index == selectedIndex,
                        onClick    = { onRowActivated(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PspContextMenuRow(
    row: PspMenuRow,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    // Accent-tinted glow so the cursor follows the chosen color scheme; blended toward white in
    // menuCursorEdge so a dark theme accent still reads clearly on the scrim.
    val glow = menuCursorEdge()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Horizontal glow band for the active item — brighter toward the
            // screen edge, fading out to the left. No border or rounded box.
            .background(
                if (isSelected) {
                    Brush.horizontalGradient(
                        0f to Color.Transparent,
                        1f to glow.copy(alpha = 0.40f),
                    )
                } else {
                    Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Transparent)
                }
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.label,
                fontSize = if (isSelected) 16.sp else 15.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    row.isDestructive && isSelected -> Color(0xFFFF7070)
                    row.isDestructive               -> Color(0xAAFF7070)
                    isSelected                      -> Color.White
                    else                            -> Color.White.copy(alpha = 0.62f)
                },
                style = TextStyle(shadow = TextDropShadow),
                modifier = Modifier.weight(1f, fill = false),
            )
            if (row.checked) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "✓",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    style = TextStyle(shadow = TextDropShadow),
                )
            }
        }
    }
}
