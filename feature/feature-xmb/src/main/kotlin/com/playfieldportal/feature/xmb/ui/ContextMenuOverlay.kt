package com.playfieldportal.feature.xmb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.feature.xmb.viewmodel.XMBContextMenu
import com.playfieldportal.feature.xmb.viewmodel.XMBContextMenuItem

// ── Context menu overlay — appears on Y/Triangle press ───────────────────────
//
// Controller nav is handled by XMBViewModel.dispatchGamepadAction when
// activeContextMenu != null. This composable handles touch/click interaction.

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
            .background(Color(0x66000000))
            .clickable(onClick = onDismiss),
    ) {
        // Panel — right-aligned, PSP XMB / Xbox Dashboard style
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 72.dp)
                .widthIn(min = 256.dp, max = 340.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xF2080812))
                .border(1.dp, Color(0x2AFFFFFF), RoundedCornerShape(6.dp))
                .clickable(onClick = {}), // consume clicks so scrim isn't triggered inside panel
        ) {
            // ── Header ────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.accentColor.copy(alpha = 0.16f))
                    .padding(horizontal = 20.dp, vertical = 13.dp),
            ) {
                Text(
                    text = menu.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 2,
                )
            }

            Box(Modifier.fillMaxWidth().size(1.dp).background(Color(0x1AFFFFFF)))

            // ── Items ──────────────────────────────────────────────────────
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(vertical = 6.dp),
            ) {
                itemsIndexed(menu.items) { index, item ->
                    ContextMenuRow(
                        item        = item,
                        isSelected  = index == menu.selectedIndex,
                        accentColor = colors.accentColor,
                        onClick     = { onItemActivated(index) },
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
    accentColor: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) accentColor.copy(alpha = 0.20f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp, horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Left accent bar (visible when selected)
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 16.dp)
                    .background(
                        if (isSelected) accentColor else Color.Transparent,
                        RoundedCornerShape(1.5.dp),
                    )
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = item.label,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    item.isDestructive && isSelected -> Color(0xFFFF7070)
                    item.isDestructive               -> Color(0xAAFF7070)
                    isSelected                       -> Color.White
                    else                             -> Color.White.copy(alpha = 0.60f)
                },
            )
        }
    }
}
