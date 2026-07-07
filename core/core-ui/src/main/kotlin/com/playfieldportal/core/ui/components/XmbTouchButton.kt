package com.playfieldportal.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.ui.theme.LocalPFPColors

// The one touch-button treatment shared across XMB screens: a transparent square that blends into
// the wave, with a thin accent ring tying it to the active theme and a soft dark halo on the glyph
// so it stays legible over both the dark top and bright bottom of the background gradient.

/** Soft dark halo applied to glyph text so it reads on any part of the wave gradient. */
val XmbGlyphShadow = Shadow(Color(0xB3000000), Offset.Zero, 10f)

/**
 * Base themed touch button: transparent background, rounded accent ring, centered [content].
 * The square [size] keeps hit targets comfortable (52dp default, matching the app-drawer button).
 */
@Composable
fun XmbTouchButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val accent = LocalPFPColors.current.accentColor
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .border(1.5.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Back button — "◀" glyph in the shared themed frame. */
@Composable
fun XmbBackTouchButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
) {
    XmbTouchButton(onClick = onClick, modifier = modifier, size = size) {
        Text(
            text = "◀",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            style = TextStyle(shadow = XmbGlyphShadow),
        )
    }
}

/** Single-glyph button (e.g. "⋯" for Options) in the shared themed frame. */
@Composable
fun XmbGlyphTouchButton(
    glyph: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
) {
    XmbTouchButton(onClick = onClick, modifier = modifier, size = size) {
        Text(
            text = glyph,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            style = TextStyle(shadow = XmbGlyphShadow),
        )
    }
}

/**
 * Flat translucent header chip — the full-screen-menu header style shared by the Music browser and
 * the detail screens (Back / Options). A white 12% fill (no accent ring) with an optional leading
 * glyph and white label, so every full-screen menu's header reads the same. When [focused] (a
 * controller has this chip selected) a themed accent ring is drawn so d-pad users can see focus.
 */
@Composable
fun XmbHeaderPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingGlyph: String? = null,
    focused: Boolean = false,
    /**
     * The default faint white wash suits the themed gradient backdrops. Screens that float
     * pills over arbitrary imagery (photo viewer) pass a dark scrim instead — a 12% white
     * fill vanishes over bright content.
     */
    background: Color = Color(0x1FFFFFFF),
) {
    val accent = LocalPFPColors.current.accentColor
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .then(
                if (focused) Modifier.border(1.5.dp, accent.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (leadingGlyph != null) {
            Text(
                text = leadingGlyph,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                style = TextStyle(shadow = XmbGlyphShadow),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            style = TextStyle(shadow = XmbGlyphShadow),
        )
    }
}

/**
 * Wide pill variant for header rows (Back / Sort / Options labels). Same transparent-plus-ring
 * treatment as [XmbTouchButton] but sized to its label.
 */
@Composable
fun XmbTouchPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingGlyph: String? = null,
) {
    val accent = LocalPFPColors.current.accentColor
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.5.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (leadingGlyph != null) "$leadingGlyph  $label" else label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            style = TextStyle(shadow = XmbGlyphShadow),
        )
    }
}
