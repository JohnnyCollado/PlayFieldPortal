package com.playfieldportal.feature.appbar.appdrawer

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.ui.theme.StorefrontColors
import com.playfieldportal.feature.appbar.AppFilter

// ── Horizontal category tabs ──────────────────────────────────────────────────
//
// The filter row communicates state — the L1/R1 buttons still drive it without focus ever
// entering the row, and touch users can tap a tab directly. Selected: full-brightness label plus
// a 2dp accent underline that slides out from under the text. Unselected: secondary text at 0.65
// alpha, no background. No pills, no chips, no cards. Per-filter counts ride beside each label.

private val TAB_SPACING = 28.dp

@Composable
internal fun AppDrawerCategoryTabs(
    activeFilter: AppFilter,
    filterCounts: Map<AppFilter, Int>,
    onFilterSelected: (AppFilter) -> Unit,
    colors: StorefrontColors,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(TAB_SPACING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppFilter.entries.forEach { filter ->
            AppDrawerCategoryTab(
                label = filter.label,
                count = filterCounts[filter] ?: 0,
                selected = filter == activeFilter,
                onClick = { onFilterSelected(filter) },
                colors = colors,
            )
        }
    }
}

@Composable
private fun AppDrawerCategoryTab(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    colors: StorefrontColors,
) {
    // The underline is drawn under the measured text row; its width animates 0 -> text width so
    // the accent line slides out from under the label on selection (tween(140), no bounce).
    var contentWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val underlineWidth by animateDpAsState(
        targetValue = if (selected) with(density) { contentWidthPx.toDp() } else 0.dp,
        animationSpec = tween(durationMillis = 140),
        label = "categoryTabUnderline",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier.onSizeChanged { contentWidthPx = it.width },
            contentAlignment = Alignment.BottomStart,
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(bottom = 5.dp),
            ) {
                Text(
                    text = label.uppercase(),
                    color = if (selected) colors.textPrimary
                    else colors.textSecondary.copy(alpha = 0.65f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (count > 0) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = count.toString(),
                        color = if (selected) colors.textPrimary.copy(alpha = 0.85f)
                        else colors.textSecondary.copy(alpha = 0.55f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .width(underlineWidth)
                    .height(2.dp)
                    .background(colors.categorySelectedEdge),
            )
        }
    }
}
