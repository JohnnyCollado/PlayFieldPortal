package com.playfieldportal.feature.xmb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.ui.theme.menuCursorEdge

// A themed, PSP-style right-edge context menu for the detail screens (Game / Photo / Video) whose
// option popups are driven by their own ViewModels rather than the XMB context-menu state. Visually
// identical to ContextMenuOverlay so every context menu in the app reads the same: a wave-color
// panel at 75% alpha anchored to the right edge, a titled header with a thin underline, and a
// horizontal accent glow on the selected row (no boxed rows). Follows the active color scheme.

private val DetailMenuWidth = 300.dp

private val DetailMenuTextShadow = Shadow(
    color = Color.Black.copy(alpha = 0.75f),
    offset = Offset(0f, 2f),
    blurRadius = 4f,
)

/** One row in a [DetailContextMenu]. */
data class DetailMenuRow(
    val label: String,
    val isDestructive: Boolean = false,
    /** What the row is set to today, shown at its trailing edge — `On`, an emulator's name. */
    val value: String? = null,
    /** The row opens another panel rather than doing something itself. */
    val opensMenu: Boolean = false,
    /** A group name drawn above this row, on the first row of each group. */
    val header: String? = null,
)

@Composable
fun DetailContextMenu(
    title: String,
    rows: List<DetailMenuRow>,
    selectedIndex: Int,
    onRowClick: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    // The panel's own opacity. The default lets the wave show through, PSP-style; a host with a
    // busy page behind the panel (Game Detail's hero art and text) passes a more solid one.
    panelAlpha: Float = 0.75f,
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
            // Light scrim so the wave/photo stays visible behind, PSP-style; tap to dismiss.
            .background(Color(0x40000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(DetailMenuWidth)
                .background(colors.waveColor.copy(alpha = panelAlpha))
                // Consume clicks inside the panel so the scrim's dismiss doesn't fire.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(start = 28.dp, end = 40.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                fontSize = 19.sp,
                fontWeight = FontWeight.Light,
                color = Color.White.copy(alpha = 0.92f),
                style = TextStyle(shadow = DetailMenuTextShadow),
                maxLines = 2,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp)
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.30f)),
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.padding(top = 10.dp),
            ) {
                itemsIndexed(rows) { index, row ->
                    // Inside the row's own item, so the list index a caller navigates by stays the
                    // row index and the scroll above never has to skip over a header.
                    row.header?.let { DetailMenuGroupHeader(it, first = index == 0) }
                    DetailMenuRowView(
                        row = row,
                        isSelected = index == selectedIndex,
                        onClick = { onRowClick(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailMenuRowView(
    row: DetailMenuRow,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val glow = menuCursorEdge()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) {
                    Brush.horizontalGradient(0f to Color.Transparent, 1f to glow.copy(alpha = 0.40f))
                } else {
                    Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Transparent)
                }
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
            style = TextStyle(shadow = DetailMenuTextShadow),
            modifier = Modifier.weight(1f),
        )
        // A value and a chevron never share a row: a row either says what it is set to or opens
        // the panel where that is decided.
        val trailing = if (row.opensMenu) "›" else row.value
        if (trailing != null) {
            Text(
                text = trailing,
                fontSize = if (row.opensMenu) 18.sp else 12.sp,
                color = Color.White.copy(alpha = if (isSelected) 0.85f else 0.45f),
                style = TextStyle(shadow = DetailMenuTextShadow),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp, end = 8.dp).widthIn(max = 120.dp),
            )
        }
    }
}

@Composable
private fun DetailMenuGroupHeader(label: String, first: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = if (first) 0.dp else 8.dp)) {
        if (!first) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp)
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.14f)),
            )
        }
        Text(
            text = label,
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.45f),
            style = TextStyle(shadow = DetailMenuTextShadow),
            modifier = Modifier.padding(top = if (first) 0.dp else 8.dp),
        )
    }
}
