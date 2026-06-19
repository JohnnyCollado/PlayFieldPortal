package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.feature.xmb.R

@Composable
fun XMBCategoryBar(
    categories: List<Category>,
    selectedIndex: Int,
    onCategorySelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onCategoryLongPress: (Int) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val colors = LocalPFPColors.current

    // Auto-scroll to keep selected category visible
    LaunchedEffect(selectedIndex) {
        listState.animateScrollToItem(
            index = selectedIndex.coerceIn(0, (categories.size - 1).coerceAtLeast(0)),
            scrollOffset = -120,
        )
    }

    LazyRow(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(categories) { index, category ->
            XMBCategoryItem(
                category    = category,
                isSelected  = index == selectedIndex,
                onClick     = { onCategorySelected(index) },
                onLongPress = { onCategoryLongPress(index) },
                colors      = colors,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun XMBCategoryItem(
    category: Category,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    colors: com.playfieldportal.core.ui.theme.PFPColors,
) {
    val iconSize by animateDpAsState(
        targetValue = if (isSelected) 36.dp else 24.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "iconSize",
    )
    val textAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "textAlpha",
    )
    val iconAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.5f,
        label = "iconAlpha",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(vertical = 8.dp),
    ) {
        // Category icon — resolved from iconKey
        // In full implementation this maps iconKey → drawable resource
        Icon(
            painter = painterResource(id = resolveIconRes(category.iconKey)),
            contentDescription = category.name,
            tint = if (isSelected) colors.accentColor else colors.textSecondary,
            modifier = Modifier
                .size(iconSize)
                .alpha(iconAlpha),
        )

        // Label only visible for selected item — PSP XMB behavior
        Text(
            text = category.name,
            color = colors.textPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .alpha(textAlpha)
                .padding(top = 4.dp),
        )
    }
}

// Maps iconKey string → drawable resource ID
// Full implementation uses a generated resource map
private fun resolveIconRes(iconKey: String): Int {
    // Placeholder — replaced with real resource lookups once drawables are added
    return when (iconKey) {
        "ic_settings" -> R.drawable.ic_xmb_settings
        "ic_photos"   -> R.drawable.ic_xmb_photos
        "ic_music"    -> R.drawable.ic_xmb_music
        "ic_videos"   -> R.drawable.ic_xmb_videos
        "ic_games"    -> R.drawable.ic_xmb_games
        "ic_network"  -> R.drawable.ic_xmb_network
        else          -> R.drawable.ic_xmb_games
    }
}
