package com.playfieldportal.feature.xmb.ui

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.ui.theme.LocalPFPColors

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
        // Category icon — rendered from xmb_icon_sprite_sheet.png
        XmbSpriteIcon(
            iconKey            = category.iconKey,
            contentDescription = category.name,
            modifier           = Modifier
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

