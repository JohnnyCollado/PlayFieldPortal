package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
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
import com.playfieldportal.themekit.XmbLayoutSpec

// Classic PSP blue theme: the active category's label is crisp white with a dark glow. Labels on
// other categories are hidden entirely (alpha 0) until the user navigates to them — the bar stays
// uncluttered and only the focused category announces itself.
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
internal val XmbLeftAnchor = CategorySlotWidth + XmbLayoutSpec.DEFAULT.leftAnchorExtraDp.dp

/**
 * The categories the bar actually lays out. Drilled in, the XMB hides every category to the RIGHT
 * of the active one so focus collapses onto the active column (PSP second-level behaviour) — and
 * they are DROPPED here rather than rendered empty inside the row.
 *
 * An emptied `items` entry still occupies a slot, so dropping is what keeps the bar from ending in
 * a run of blank gaps to the right of the active column.
 *
 * This once carried a second, load-bearing job. The bar was a LazyRow seated by `scrollToItem`, and
 * trailing phantoms left it holding just about exactly the scroll extent that call asks for and no
 * headroom, so any re-measure clamped the scroll short and the selected caticon came to rest a slot
 * off, in the game column (the flyout-then-resume corruption). Dropping them made the extent honest
 * — but "honest" still put the target exactly on the clamp boundary, and the corruption survived.
 * [XMBCategoryBar] no longer scrolls, so that failure is gone at its source and this function is
 * back to meaning only what it says.
 *
 * `take` keeps the surviving indices aligned with [categories], so [selectedIndex], the selected
 * flag and the click callbacks all still mean the same thing. A selection that isn't a real index
 * yet (nothing loaded, -1) hides nothing — an empty bar is worse than an unfiltered one.
 */
internal fun visibleCategories(
    categories: List<Category>,
    selectedIndex: Int,
    drilledIn: Boolean,
): List<Category> =
    if (drilledIn && selectedIndex in categories.indices) categories.take(selectedIndex + 1)
    else categories

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
    // "Solid Unfocused Icons" (Display ▸ Appearance): when true, unselected category icons skip
    // the unfocused dim — selection still reads by icon size and the label fade. Default false
    // = today's dimming. (IconLegibility rides LocalIconLegibility, no parameter needed.)
    solidUnfocusedIcons: Boolean = false,
    // Whether the selected category's GIF icon may animate (battery saver / overlays gate it).
    iconAnimatingAllowed: Boolean = false,
) {
    // The slots the row actually lays out (see [visibleCategories] for why drilled-in categories are
    // dropped rather than emptied).
    val rowCategories = remember(categories, drilledIn, selectedIndex) {
        visibleCategories(categories, selectedIndex, drilledIn)
    }

    // Left-anchored: the selected slot rests at XmbLeftAnchor, shifted by the live layout-adjust
    // offset so the caticon bar tracks the item column when the cross is nudged left/right.
    val anchor = (XmbLeftAnchor + LocalXmbHorizontalShift.current).coerceAtLeast(0.dp)

    // The bar is POSITIONED, never scrolled. A LazyRow held this before, seated by scrollToItem
    // against a hand-computed end padding sized so the list's own end-of-list correction landed the
    // selected slot on [anchor]. Three mechanisms agreeing on one invariant, re-asserted only by an
    // effect keyed on the selection and the measured geometry — so any re-measure that did not
    // change those keys (an overlay opening and flipping iconAnimatingAllowed, a resume that keeps
    // the composition) could clamp the scroll a slot short with nothing left to notice. The bar then
    // sat with the active caticon in the game column until the user backed out. Laid out directly,
    // the selected slot is at [anchor] arithmetically on every measure: there is no stale offset to
    // go wrong, so the drift is gone by construction rather than by correction.
    //
    // The offset is deliberately TWO parts:
    //
    //  - [anchor] is geometry. It moves when the cross is nudged or the user drills in/out, and it
    //    must SNAP — the item column's own startPad moves un-animated on the same frame, and a
    //    gliding bar would visibly lag the column it is meant to be one cross with.
    //  - [slide] is selection. It moves when the user steps categories, and it glides.
    //
    // Keeping them apart is what retires the old snap-vs-animate branch: sharing one channel, a
    // re-seat had to infer from "did the index change" whether it was a real press or geometry
    // catching up. Two channels, no inference. animateDpAsState also starts AT its target rather
    // than animating to it, so the bar cannot visibly slide in from the start on a resume — the
    // failure the old effect existed to prevent is now unavailable rather than prevented.
    val slide by animateDpAsState(
        targetValue = -CategorySlotWidth * selectedIndex.coerceIn(0, rowCategories.lastIndex.coerceAtLeast(0)),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "xmbCategorySlide",
    )

    // The clip is load-bearing: the LazyRow clipped for free, so without it the categories offset
    // off the left edge paint over the drilled-in sibling column beneath the bar.
    Box(modifier = modifier.fillMaxWidth().clipToBounds()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // Measured UNBOUNDED, and load-bearing. The full bar is a fixed filmstrip wider than
            // the screen (every slot is [ItemSlotWidth], and there are more categories than fit), so
            // under the incoming max width a Row hands each child only what is left after the ones
            // before it and squeezes the tail to nothing — the late categories lost their icon size
            // and their labels clipped to a letter. The LazyRow never hit this because it measured
            // only what was visible and carried position as scroll instead of width. Overflow is
            // the intent here, and the parent Box's clip is what contains it.
            modifier = Modifier
                .offset(x = anchor + slide)
                .wrapContentWidth(align = Alignment.Start, unbounded = true),
        ) {
            rowCategories.forEachIndexed { index, category ->
                key(category.id) {
                    XMBCategoryItem(
                        category = category,
                        isSelected = index == selectedIndex,
                        onClick = { onCategorySelected(index) },
                        onLongPress = { onCategoryLongPress(index) },
                        solidUnfocusedIcons = solidUnfocusedIcons,
                        iconAnimatingAllowed = iconAnimatingAllowed,
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
    solidUnfocusedIcons: Boolean,
    iconAnimatingAllowed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val iconSize by animateDpAsState(
        targetValue = if (isSelected) XmbLayoutSpec.DEFAULT.categoryIconSelectedDp.dp
        else XmbLayoutSpec.DEFAULT.categoryIconDp.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "xmbCategoryIconSize",
    )
    val itemAlpha by animateFloatAsState(
        // "Solid Unfocused Icons": skip the unfocused dim; selection still reads by size + label.
        targetValue = if (isSelected || solidUnfocusedIcons) 1f else 0.58f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "xmbCategoryAlpha",
    )
    // Only the active category shows its label; others stay hidden (alpha 0) until navigated to.
    // The label keeps its slot so icons never shift when labels fade in/out.
    val labelAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "xmbCategoryLabelAlpha",
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
            // The selected category's GIF (if the slot holds one) animates exactly while it is
            // the focused column — the same gate the item rows obey.
            androidx.compose.runtime.CompositionLocalProvider(
                com.playfieldportal.core.ui.icons.LocalIconAnimating provides
                    (isSelected && iconAnimatingAllowed),
            ) {
            // All category icons resolve through the shared core-ui catalog (catbar_* column
            // glyphs and sysicon_* console art) — selection is conveyed by size and alpha (no halo).
            CategoryIconGlyph(
                iconKey = category.iconKey,
                contentDescription = category.name,
                modifier = Modifier.size(iconSize),
            )
            }
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
                .alpha(labelAlpha),
        )
    }
}
