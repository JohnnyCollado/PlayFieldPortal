package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .alpha(rowAlpha),
    ) {
        XmbItemLeadingIcon(
            item = item,
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
    iconStyle: GameIconStyle,
) {
    when {
        item.type == XMBItemType.ALL_GAMES ||
            item.type == XMBItemType.FAVORITES ||
            item.type == XMBItemType.MEMORY_CARD ||
            item.type == XMBItemType.COLLECTION -> {
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.width(58.dp),
            ) {
                // Memory-card rows show their matching console icon. All Games gets the generic
                // cartridge art (sysicon_allgames) and Favorites the star (sysicon_favorites), both
                // to stand apart from the Game controller; any other unknown row (Collections,
                // unmatched console) falls back to sysicon_default.
                val iconKey = when (item.type) {
                    XMBItemType.MEMORY_CARD -> item.platformId
                    XMBItemType.ALL_GAMES   -> "allgames"
                    XMBItemType.FAVORITES   -> "favorites"
                    else                    -> null
                }
                Image(
                    painter = painterResource(rememberConsoleIconId(iconKey)),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                )
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
        item.isAndroidApp && item.iconUri != null -> {
            // Apps the user has given artwork render the same 144:80 landscape tile as games, so
            // non-gaming categories (Video / Music / custom) look uniform. These rows stay
            // content_type ANDROID_APP, so artwork never makes them appear in All Games.
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
// e.g. platformId "psp" -> R.drawable.sysicon_psp. Falls back to the theme's generic
// sysicon_default when the platform is unknown or has no dedicated icon.
@Composable
private fun rememberConsoleIconId(platformId: String?): Int {
    val context = LocalContext.current
    return remember(platformId) {
        val safe = platformId?.lowercase()?.filter { it.isLetterOrDigit() || it == '_' }
        val specific = if (!safe.isNullOrBlank()) {
            context.resources.getIdentifier("sysicon_$safe", "drawable", context.packageName)
        } else 0
        if (specific != 0) specific
        else context.resources.getIdentifier("sysicon_default", "drawable", context.packageName)
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
