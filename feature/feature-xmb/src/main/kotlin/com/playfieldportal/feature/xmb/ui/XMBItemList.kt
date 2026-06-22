package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.playfieldportal.core.ui.icons.GameIconStyle
import com.playfieldportal.feature.xmb.viewmodel.XMBItem
import com.playfieldportal.feature.xmb.viewmodel.XMBItemType

// Game icons use the authentic PSP ICON0 ratio 144:80 (= 1.8), scaled for the list.
private val GAME_ICON_WIDTH = 126.dp
private val GAME_ICON_HEIGHT = 70.dp

private val RowShape = RoundedCornerShape(7.dp)
private val RowSelectedFill = Color(0xFF574DDB)
private val RowSelectedBorder = Color(0xFF8F7CFF)
private val RowGlow = Color(0xFF9B79FF)
private val PrimaryText = Color.White
private val SecondaryText = Color(0xFFC9C7E8)
private val InactiveText = Color(0xFFE3E1F0)

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

    LaunchedEffect(selectedIndex, items.size) {
        if (items.isNotEmpty()) {
            listState.animateScrollToItem(
                index = selectedIndex.coerceIn(0, items.lastIndex),
                scrollOffset = -44,
            )
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
    ) {
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            XmbVerticalListRow(
                item = item,
                isSelected = index == selectedIndex,
                iconStyle = iconStyle,
                onClick = { onItemSelected(index) },
                onLongPress = { onItemLongPress(index) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun XmbVerticalListRow(
    item: XMBItem,
    isSelected: Boolean,
    iconStyle: GameIconStyle,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.035f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "xmbListRowScale",
    )
    val rowAlpha by animateFloatAsState(
        targetValue = when {
            isSelected -> 1f
            item.type == XMBItemType.EMPTY -> 0.62f
            else -> 0.76f
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "xmbListRowAlpha",
    )
    val selectionAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "xmbListRowSelection",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .drawBehind {
                if (selectionAlpha > 0f) {
                    drawRoundRect(
                        color = RowGlow.copy(alpha = 0.30f * selectionAlpha),
                        topLeft = Offset(-10f, -8f),
                        size = Size(size.width + 20f, size.height + 16f),
                        cornerRadius = CornerRadius(18f, 18f),
                    )
                }
            }
            .clip(RowShape)
            .background(RowSelectedFill.copy(alpha = 0.26f * selectionAlpha), RowShape)
            .then(
                if (isSelected) {
                    Modifier.border(1.dp, RowSelectedBorder.copy(alpha = 0.78f), RowShape)
                } else {
                    Modifier
                }
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .alpha(rowAlpha),
    ) {
        XmbItemLeadingIcon(
            item = item,
            isSelected = isSelected,
            iconStyle = iconStyle,
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = if (isSelected) PrimaryText else InactiveText,
                fontSize = if (isSelected) 19.sp else 16.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
            if (!item.subtitle.isNullOrBlank()) {
                Text(
                    text = item.subtitle,
                    color = SecondaryText,
                    fontSize = if (isSelected) 12.sp else 11.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun XmbItemLeadingIcon(
    item: XMBItem,
    isSelected: Boolean,
    iconStyle: GameIconStyle,
) {
    val iconTint = if (isSelected) Color.White else Color(0xFFD4D2E8)
    when {
        item.type == XMBItemType.ALL_GAMES || item.type == XMBItemType.MEMORY_CARD -> {
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.width(58.dp),
            ) {
                val consoleIcon = if (item.type == XMBItemType.MEMORY_CARD) {
                    rememberConsoleIconId(item.platformId)
                } else 0
                if (consoleIcon != 0) {
                    Image(
                        painter = painterResource(consoleIcon),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                    )
                } else {
                    XmbMemoryCardIcon(
                        tint = iconTint,
                        cutoutColor = Color(0xFF263190).copy(alpha = if (isSelected) 0.95f else 0.70f),
                        modifier = Modifier.size(width = 42.dp, height = 30.dp),
                    )
                }
            }
        }
        item.gameId != null -> {
            // Full 144:80 landscape tile (ratio 1.8) — the authentic PSP ICON0 rectangle.
            GameIcon(
                item = item,
                iconStyle = iconStyle,
                modifier = Modifier.size(width = GAME_ICON_WIDTH, height = GAME_ICON_HEIGHT),
            )
        }
        item.isAndroidApp && item.packageName != null -> {
            Box(modifier = Modifier.width(58.dp)) {
                AppListIcon(
                    packageName = item.packageName,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
        else -> Spacer(modifier = Modifier.width(12.dp))
    }
}

// Resolves a per-console icon (bundled from the xmb-menu-es-de set) by platform id,
// e.g. platformId "psp" -> R.drawable.sysicon_psp. Returns 0 when no icon is available,
// in which case the caller falls back to the generic Memory Card glyph.
@Composable
private fun rememberConsoleIconId(platformId: String?): Int {
    val context = LocalContext.current
    return remember(platformId) {
        if (platformId.isNullOrBlank()) return@remember 0
        val safe = platformId.lowercase().filter { it.isLetterOrDigit() || it == '_' }
        context.resources.getIdentifier("sysicon_$safe", "drawable", context.packageName)
    }
}

@Composable
private fun AppListIcon(
    packageName: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val drawable = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
    } ?: return
    Image(
        painter = rememberDrawablePainter(drawable),
        contentDescription = null,
        modifier = modifier.clip(RoundedCornerShape(6.dp)),
    )
}
