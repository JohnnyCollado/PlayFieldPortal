package com.playfieldportal.feature.xmb.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.ui.theme.StorefrontColors

// ── The pickers' header buttons, on the helper-button standard (ARCHITECTURE.md ▸ Conventions) ──
//
// A ◀ breadcrumb at the left that backs out as B does, and in touch mode only, Search, ✓ Done and
// the ⋮ kebab at the right, in that order.

/** Test tags for the pickers' header buttons. */
internal object PickerHeaderTags {
    const val BACK = "pickerHeader:back"
    const val SEARCH = "pickerHeader:search"
    const val DONE = "pickerHeader:done"
    const val OPTIONS = "pickerHeader:options"
}

/** The standard pill height: [StorefrontTouchPill]'s 14sp line plus its 8dp vertical padding. */
private val PILL_HEIGHT = 36.dp

/**
 * The pickers' touch-mode header button — [com.playfieldportal.core.ui.components.XmbHeaderPill]'s
 * shape in storefront colours. XmbHeaderPill draws white on a white wash, which vanishes on a
 * dark-on-light storefront; this reads the same search-field fill and text colour as the header around it.
 * [leading] replaces the glyph with a drawn icon.
 */
@Composable
internal fun StorefrontTouchPill(
    label: String,
    leadingGlyph: String,
    onClick: () -> Unit,
    colors: StorefrontColors,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.searchField)
            .border(1.dp, colors.searchBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (leading != null) leading()
        else Text(leadingGlyph, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
        Text(label, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** Search: a drawn magnifier, so no font has to carry the glyph. */
@Composable
internal fun StorefrontSearchPill(onClick: () -> Unit, colors: StorefrontColors) {
    StorefrontTouchPill(
        label = "Search",
        leadingGlyph = "",
        onClick = onClick,
        colors = colors,
        modifier = Modifier.testTag(PickerHeaderTags.SEARCH),
        leading = { MagnifierGlyph(colors.textPrimary, Modifier.size(16.dp)) },
    )
}

/** Options: the app-wide drawn ⋮ kebab, square at the pills' height. */
@Composable
internal fun StorefrontKebabPill(onClick: () -> Unit, colors: StorefrontColors) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .testTag(PickerHeaderTags.OPTIONS)
            .size(PILL_HEIGHT)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.searchField)
            .border(1.dp, colors.searchBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Canvas(Modifier.size(18.dp)) {
            val radius = 2.dp.toPx()
            val x = size.width / 2f
            listOf(0.2f, 0.5f, 0.8f).forEach { drawCircle(colors.textPrimary, radius, Offset(x, size.height * it)) }
        }
    }
}

/** Back: the ◀ breadcrumb, a 48dp target, the same on every screen. */
@Composable
internal fun PickerBreadcrumb(onClick: () -> Unit, color: Color) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .testTag(PickerHeaderTags.BACK)
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(onClick = onClick),
    ) {
        Text("◀", color = color, fontSize = 16.sp)
    }
}

/** A hand-drawn magnifier — no icon vector. */
@Composable
internal fun MagnifierGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val strokeW = 1.8f.dp.toPx()
        val cx = size.width * 0.42f
        val cy = size.height * 0.42f
        val r = size.width * 0.30f
        drawCircle(color = color, radius = r, center = Offset(cx, cy), style = Stroke(strokeW))
        drawLine(
            color = color,
            start = Offset(cx + r * 0.70f, cy + r * 0.70f),
            end = Offset(cx + r * 0.70f + size.width * 0.22f, cy + r * 0.70f + size.height * 0.22f),
            strokeWidth = strokeW,
            cap = StrokeCap.Round,
        )
    }
}
