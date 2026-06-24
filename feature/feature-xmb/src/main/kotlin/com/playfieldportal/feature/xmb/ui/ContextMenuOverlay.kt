package com.playfieldportal.feature.xmb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.playfieldportal.feature.xmb.viewmodel.XMBContextMenu
import com.playfieldportal.feature.xmb.viewmodel.XMBContextMenuItem

// ── Context menu overlay — appears on Y/Triangle press ───────────────────────
//
// Styled after the PSP XMB sub-menu: a translucent column anchored to the right
// edge, a plain title underlined by a thin rule, and the selected item marked by
// a soft horizontal glow band that bleeds to the screen edge (no boxed panel).
//
// Controller nav is handled by XMBViewModel.dispatchGamepadAction when
// activeContextMenu != null. This composable handles touch/click interaction.

private val PanelWidth = 300.dp

// Black drop shadow on the menu text so it stays legible over the wave/backdrop.
private val TextDropShadow = Shadow(
    color = Color.Black.copy(alpha = 0.75f),
    offset = Offset(0f, 2f),
    blurRadius = 4f,
)

@Composable
fun ContextMenuOverlay(
    menu: XMBContextMenu,
    onItemActivated: (index: Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPFPColors.current
    val listState = rememberLazyListState()

    LaunchedEffect(menu.selectedIndex) {
        if (menu.items.isNotEmpty()) {
            listState.animateScrollToItem(menu.selectedIndex.coerceIn(0, menu.items.size - 1))
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
                text = menu.title,
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
                itemsIndexed(menu.items) { index, item ->
                    ContextMenuRow(
                        item       = item,
                        isSelected = index == menu.selectedIndex,
                        onClick    = { onItemActivated(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextMenuRow(
    item: XMBContextMenuItem,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Soft horizontal glow band for the active item — brighter toward the
            // screen edge, fading out to the left. No border or rounded box.
            .background(
                if (isSelected) {
                    Brush.horizontalGradient(
                        0f to Color.Transparent,
                        1f to Color.White.copy(alpha = 0.22f),
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
                text = item.label,
                fontSize = if (isSelected) 16.sp else 15.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    item.isDestructive && isSelected -> Color(0xFFFF7070)
                    item.isDestructive               -> Color(0xAAFF7070)
                    isSelected                       -> Color.White
                    else                             -> Color.White.copy(alpha = 0.62f)
                },
                style = TextStyle(shadow = TextDropShadow),
                modifier = Modifier.weight(1f, fill = false),
            )
            // Checkmark for items that represent a current membership/selection (e.g. the
            // collections a game already belongs to in "Add to Collection").
            if (item.checked) {
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
