package com.playfieldportal.core.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.playfieldportal.core.ui.image.rememberArtworkModel
import com.playfieldportal.core.ui.theme.LocalPFPColors

// ── Hero banner ───────────────────────────────────────────────────────────────
//
// The page's visual anchor: full-bleed hero artwork with a dark readability gradient carrying the
// game's identity (title, platform, last played / playtime, favorite state).
//
// The banner is deliberately NOT a controller-focus target — the cursor starts on Launch below it —
// and missing artwork must not change the page's geometry, so the placeholder keeps the same height
// as the art.

/** Default banner height. Short landscape screens may compact it (see the caller). */
val DetailHeroHeight: Dp = 220.dp

/** The banner's full-size width: the page body at its maximum, less its side margins. */
val DetailHeroWidth: Dp = DetailContentMaxWidth - DetailContentPadding * 2

/**
 * The banner's shape at full size, and the Artwork Studio's HERO crop target (CropProfileRegistry),
 * so a cropped hero fills the banner without being trimmed again. Narrower or shorter screens show
 * the banner slightly wider than this and center-crop the small difference.
 */
val DetailHeroAspect: Float = DetailHeroWidth.value / DetailHeroHeight.value

@Composable
fun PfpDetailHeroBanner(
    artworkUri: String?,
    title: String,
    platform: String,
    modifier: Modifier = Modifier,
    // The page's accent, not the platform's: the hero has to sit in the same color scheme as the
    // cursor, the focused labels and the progress fill around it (detailPaletteFor).
    accentColor: Color = detailPalette().focus,
    /** Pre-formatted identity facts ("Last played 3 days ago", "Play time: 12 h"). */
    facts: List<String> = emptyList(),
    favorite: Boolean = false,
    height: Dp = DetailHeroHeight,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            // Artwork-less fallback: an accent-tinted plate at the SAME height, so a game without a
            // hero keeps the page's geometry exactly.
            .background(accentColor.copy(alpha = 0.16f)),
        contentAlignment = Alignment.BottomStart,
    ) {
        if (artworkUri != null) {
            AsyncImage(
                model = rememberArtworkModel(artworkUri),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Readability gradient: transparent at the top, deep at the bottom, so identity stays
            // legible over bright artwork without dimming the art itself.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.05f),
                            0.55f to Color.Black.copy(alpha = 0.45f),
                            1f to Color.Black.copy(alpha = 0.86f),
                        )
                    ),
            )
        }
        Column(modifier = Modifier.padding(horizontal = 26.dp, vertical = 18.dp)) {
            Text(
                text = title,
                color = DetailTextPrimary,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(shadow = DetailTextShadow),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = platform,
                color = DetailTextMuted,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(shadow = DetailTextShadow),
            )
            if (facts.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = facts.joinToString("  ·  "),
                    color = DetailTextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(shadow = DetailTextShadow),
                )
            }
        }
        // Favorite state is named, not just tinted — colour alone never carries it.
        if (favorite) {
            FavoriteBadge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(14.dp),
            )
        }
    }
}

@Composable
private fun FavoriteBadge(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.55f))
            .border(1.dp, lerp(Color.White, Color.Transparent, 0.55f), shape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // A drawn star, not a font glyph — the same reasoning as PfpCheckMark: a glyph's shape and
        // baseline come from whichever font the device falls back to.
        PfpStarMark(color = detailStarColor(), size = 12.dp)
        Text(
            text = "FAVORITE",
            color = DetailTextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun detailStarColor(): Color = lerp(LocalPFPColors.current.accentColor, Color(0xFFFFD766), 0.85f)
