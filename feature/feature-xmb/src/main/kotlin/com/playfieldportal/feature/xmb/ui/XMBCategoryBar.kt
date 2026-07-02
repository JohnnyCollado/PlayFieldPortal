package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.ui.icons.CategoryIconGlyph

// Classic PSP blue theme: selected label crisp white with a dark glow; unselected labels recede
// into a dimmer blue-white so they read against the saturated blue gradient.
private val SelectedIcon = Color.White
private val LabelInactive = Color(0xCCD8E6FF)
private val SelectedLabelShadow = Shadow(
    color = Color(0x73001627),
    offset = Offset.Zero,
    blurRadius = 12f,
)

// Width of a single category slot. Exposed so the subitem column (XMBShell) can
// align its left edge to the selected category's slot — the XMB crossbar.
internal val CategorySlotWidth = 124.dp
private val ItemSlotWidth = CategorySlotWidth

// The XMB is left-anchored: the selected category slot (and the subitem column below it) sit
// at this fixed left offset instead of centering, keeping the right side clear for the context
// menu. It's exactly one slot width so the *previous* category tiles fully into x=0..slot with
// no partial "poke", and the category before that lands fully off-screen. At the last category
// you therefore see exactly the previous + selected — the last two, and only them — with no
// clipping. XMBShell reads this so the crossbar and its subitems stay on the same vertical line.
internal val XmbLeftAnchor = CategorySlotWidth

@Composable
fun XMBCategoryBar(
    categories: List<Category>,
    selectedIndex: Int,
    onCategorySelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onCategoryLongPress: (Int) -> Unit = {},
    // When drilled into a sub-item, the XMB hides every category to the RIGHT of the active one so
    // the focus collapses onto the active column (PSP second-level behaviour).
    drilledIn: Boolean = false,
) {
    val listState = rememberLazyListState()

    // Seat the selected slot INSTANTLY on the bar's first composition — including every time the
    // XMB foreground re-appears after a fullscreen menu closes (music browser, app drawer, Settings)
    // — so the bar never visibly scrolls in from the start (the old "snap back" on close). Only real
    // category changes after that glide smoothly.
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(selectedIndex, categories.size) {
        if (categories.isEmpty()) return@LaunchedEffect
        val target = selectedIndex.coerceIn(0, categories.lastIndex)
        if (settled) {
            listState.animateScrollToItem(target)
        } else {
            listState.scrollToItem(target)
            settled = true
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // Left-anchored: the selected slot rests at XmbLeftAnchor. The trailing padding is sized
        // so even the last category can scroll to that same left anchor.
        val endPadding = (maxWidth - XmbLeftAnchor - ItemSlotWidth).coerceAtLeast(24.dp)
        LazyRow(
            state = listState,
            // Selection-driven only: the bar auto-scrolls to the selected slot (above), and touch
            // swipes are handled by the home-screen gesture (step category), so user scrolling is
            // disabled to keep the bar from drifting out of sync with the selection.
            userScrollEnabled = false,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = XmbLeftAnchor, end = endPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(categories, key = { _, category -> category.id }) { index, category ->
                // While drilled in, categories to the right of the active one are hidden.
                if (!(drilledIn && index > selectedIndex)) {
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
    val itemAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.58f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "xmbCategoryAlpha",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .height(112.dp)
            // No ripple — the XMB shows focus with its own caticon scale/alpha, and the Android
            // highlight rectangle broke the PSP look (see the matching change in XMBItemList).
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongPress,
            )
            .padding(top = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(82.dp)
                .alpha(itemAlpha),
        ) {
            // All category icons resolve through the shared core-ui catalog (catbar_* column
            // glyphs and sysicon_* console art) — selection is conveyed by size and alpha (no halo).
            CategoryIconGlyph(
                iconKey = category.iconKey,
                contentDescription = category.name,
                modifier = Modifier.size(iconSize),
            )
        }

        Text(
            text = category.name,
            color = if (isSelected) SelectedIcon else LabelInactive,
            fontSize = if (isSelected) 15.sp else 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            style = if (isSelected) TextStyle(shadow = SelectedLabelShadow) else TextStyle.Default,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .alpha(if (isSelected) 1f else 0.82f),
        )
    }
}
