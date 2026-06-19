package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.ui.icons.GameIconStyle
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.feature.xmb.viewmodel.XMBItem

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun XMBItemList(
    items: List<XMBItem>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    onItemLongPress: (Int) -> Unit,
    iconStyle: GameIconStyle = GameIconStyle.PSP_RECTANGLE,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val colors = LocalPFPColors.current

    LaunchedEffect(selectedIndex) {
        if (items.isNotEmpty()) {
            listState.animateScrollToItem(
                index = selectedIndex.coerceIn(0, items.size - 1),
                scrollOffset = -80,
            )
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
    ) {
        itemsIndexed(items) { index, item ->
            val isSelected = index == selectedIndex

            val scale by animateFloatAsState(
                targetValue = if (isSelected) 1.05f else 1f,
                animationSpec = spring(stiffness = Spring.StiffnessMedium),
                label = "itemScale",
            )
            val alpha by animateFloatAsState(
                targetValue = when {
                    isSelected -> 1f
                    index == selectedIndex - 1 || index == selectedIndex + 1 -> 0.7f
                    else -> 0.4f
                },
                label = "itemAlpha",
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .scale(scale)
                    .alpha(alpha)
                    .combinedClickable(
                        onClick = { onItemSelected(index) },
                        onLongClick = { onItemLongPress(index) },
                    )
                    .padding(vertical = 5.dp, horizontal = 8.dp),
            ) {
                Text(
                    text = if (isSelected) ">" else " ",
                    color = colors.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 10.dp),
                )

                // Only game items (gameId != null) get styled icons;
                // non-game items (platform shortcuts, settings) show no icon.
                if (item.gameId != null) {
                    GameIcon(
                        item      = item,
                        iconStyle = iconStyle,
                        modifier  = Modifier.padding(end = 12.dp),
                    )
                }

                Column {
                    Text(
                        text = item.title,
                        color = if (isSelected) colors.accentColor else colors.textPrimary,
                        fontSize = if (isSelected) 16.sp else 14.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    )
                    if (!item.subtitle.isNullOrBlank()) {
                        Text(
                            text = item.subtitle,
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            // Separator line under selected item — PSP XMB detail
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp)
                        .height(1.dp)
                        .alpha(0.3f),
                )
            }
        }
    }
}
