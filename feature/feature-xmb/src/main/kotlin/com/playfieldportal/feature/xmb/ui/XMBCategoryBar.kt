package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.domain.model.Category

private val SelectedIcon = Color.White
private val InactiveIcon = Color(0xFFC7C6DF)
private val LabelInactive = Color(0xFFE4E2F5)

// Width of a single category slot. Exposed so the subitem column (XMBShell) can
// align its left edge to the selected category's centered slot — the XMB crossbar.
internal val CategorySlotWidth = 124.dp
private val ItemSlotWidth = CategorySlotWidth

@Composable
fun XMBCategoryBar(
    categories: List<Category>,
    selectedIndex: Int,
    onCategorySelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onCategoryLongPress: (Int) -> Unit = {},
) {
    val listState = rememberLazyListState()

    LaunchedEffect(selectedIndex, categories.size) {
        if (categories.isNotEmpty()) {
            listState.animateScrollToItem(
                index = selectedIndex.coerceIn(0, categories.lastIndex),
            )
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val sidePadding = ((maxWidth - ItemSlotWidth) / 2f).coerceAtLeast(24.dp)
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = sidePadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(categories, key = { _, category -> category.id }) { index, category ->
                XMBCategoryItem(
                    category = category,
                    isSelected = index == selectedIndex,
                    onClick = { onCategorySelected(index) },
                    onLongPress = { onCategoryLongPress(index) },
                    modifier = Modifier.width(ItemSlotWidth),
                )
            }
        }
    }
}

// Bundled category-bar artwork (from the xmb-menu-es-de set) for the categories that have
// a clean match. Categories without one (Settings/Photo/Network/App Store) fall back to the
// built-in vector icons. Returns 0 when there is no art for this category.
@Composable
private fun rememberCategoryArtId(category: Category): Int {
    val context = LocalContext.current
    return remember(category.id) {
        val name = when (category.id) {
            "games"  -> "catbar_games"
            "music"  -> "catbar_music"
            "videos" -> "catbar_video"
            else     -> return@remember 0
        }
        context.resources.getIdentifier(name, "drawable", context.packageName)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun XMBCategoryItem(
    category: Category,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val iconSize by animateDpAsState(
        targetValue = if (isSelected) 60.dp else 48.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "xmbCategoryIconSize",
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "xmbCategoryGlow",
    )
    val itemAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.58f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "xmbCategoryAlpha",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .height(112.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(top = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(82.dp)
                .drawBehind {
                    if (glowAlpha > 0f) {
                        // Soft, neutral halo behind the selected icon — no colored cursor.
                        val haloRadius = size.minDimension * 0.62f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.26f * glowAlpha),
                                    Color.White.copy(alpha = 0.08f * glowAlpha),
                                    Color.Transparent,
                                ),
                                center = center,
                                radius = haloRadius,
                            ),
                            radius = haloRadius,
                            center = center,
                        )
                    }
                }
                .alpha(itemAlpha),
        ) {
            val artIcon = rememberCategoryArtId(category)
            if (artIcon != 0) {
                Image(
                    painter = painterResource(artIcon),
                    contentDescription = category.name,
                    modifier = Modifier.size(iconSize),
                )
            } else {
                XmbCategoryIcon(
                    type = xmbCategoryIconType(category),
                    tint = if (isSelected) SelectedIcon else InactiveIcon,
                    modifier = Modifier.size(iconSize),
                )
            }
        }

        Text(
            text = category.name,
            color = if (isSelected) SelectedIcon else LabelInactive,
            fontSize = if (isSelected) 15.sp else 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .alpha(if (isSelected) 1f else 0.82f),
        )
    }
}
