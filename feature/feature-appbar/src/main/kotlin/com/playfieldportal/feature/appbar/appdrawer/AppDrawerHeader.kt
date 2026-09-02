package com.playfieldportal.feature.appbar.appdrawer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.ui.theme.StorefrontColors

// ── Header / breadcrumb bar ───────────────────────────────────────────────────
//
// Compact PSP-era chrome: a back-breadcrumb (‹ Android › <category>) on the left, and the search
// affordance on the right — a small hand-drawn magnifier that expands the inline search field
// within the header when active. Draws over the root gradient's deep (header) region, so it has no
// background of its own.

private val HEADER_HEIGHT = 56.dp
private val FIELD_CORNER = 2.dp
private val FIELD_BORDER = 1.dp

@Composable
internal fun AppDrawerHeader(
    categoryLabel: String,
    searchQuery: String,
    searchActive: Boolean,
    searchFocus: FocusRequester,
    onSearchToggle: (Boolean) -> Unit,
    onSearchChange: (String) -> Unit,
    onSearchDone: () -> Unit,
    onBack: () -> Unit,
    colors: StorefrontColors,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Breadcrumb: ‹ Android › category
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = "\u2039",
                color = colors.textSecondary,
                fontSize = 18.sp,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(end = 8.dp),
            )
            Text(
                text = "Android",
                color = colors.textSecondary,
                fontSize = 14.sp,
                modifier = Modifier.clickable { onBack() }.padding(end = 6.dp),
            )
            Text(
                text = "\u203A",
                color = colors.textSecondary.copy(alpha = 0.6f),
                fontSize = 14.sp,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                text = categoryLabel,
                color = colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // Search field — temporarily expands within the header while active (PSP-era mode feel,
        // not a floating Material component).
        AnimatedVisibility(visible = searchActive, enter = fadeIn(), exit = fadeOut()) {
            BasicTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                singleLine = true,
                textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
                cursorBrush = SolidColor(colors.searchBorder),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearchDone() }, onDone = { onSearchDone() }),
                decorationBox = { inner ->
                    Box {
                        if (searchQuery.isEmpty()) Text(
                            "Search\u2026",
                            color = colors.textSecondary.copy(alpha = 0.6f),
                            fontSize = 14.sp,
                        )
                        inner()
                    }
                },
                modifier = Modifier
                    .width(220.dp)
                    .focusRequester(searchFocus)
                    .background(colors.searchField, RoundedCornerShape(FIELD_CORNER))
                    .border(FIELD_BORDER, colors.searchBorder, RoundedCornerShape(FIELD_CORNER))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        Spacer(Modifier.width(16.dp))

        // Search button (magnifying glass) — hand-drawn Canvas, not an icon font.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable {
                onSearchToggle(!searchActive)
            },
        ) {
            Canvas(modifier = Modifier.size(18.dp)) {
                val strokeW = 1.8f.dp.toPx()
                val cx = size.width * 0.42f
                val cy = size.height * 0.42f
                val r = size.width * 0.30f
                // Lens circle
                drawCircle(
                    color = colors.textSecondary,
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(strokeW),
                )
                // Handle line
                val handleStart = Offset(
                    cx + r * 0.70f,
                    cy + r * 0.70f,
                )
                val handleEnd = Offset(
                    cx + r * 0.70f + size.width * 0.22f,
                    cy + r * 0.70f + size.height * 0.22f,
                )
                drawLine(
                    color = colors.textSecondary,
                    start = handleStart,
                    end = handleEnd,
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round,
                )
            }
            Spacer(Modifier.width(5.dp))
            Text("Search", color = colors.textSecondary, fontSize = 13.sp)
        }
    }
}
