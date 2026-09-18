package com.playfieldportal.feature.xmb.ui.detail

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.feature.xmb.R

/** The bundled Shiba coin medallion for a tier. */
@DrawableRes
fun shibaCoinRes(tier: ShibaTier): Int = when (tier) {
    ShibaTier.BRONZE -> R.drawable.shiba_coin_bronze
    ShibaTier.SILVER -> R.drawable.shiba_coin_silver
    ShibaTier.GOLD -> R.drawable.shiba_coin_gold
    ShibaTier.PLATINUM -> R.drawable.shiba_coin_platinum
}

/** Draws the tier's coin medallion image. */
@Composable
fun ShibaCoinIcon(tier: ShibaTier, modifier: Modifier = Modifier, colorFilter: ColorFilter? = null) {
    Image(
        painter = painterResource(shibaCoinRes(tier)),
        contentDescription = null,
        colorFilter = colorFilter,
        modifier = modifier,
    )
}

/** A locked coin's art is drained of color but still legible — not hidden, just not yours yet. */
private const val DIMMED_ALPHA = 0.6f

/**
 * A coin's art: the provider's own badge when it has one, and the tier medallion when it doesn't.
 *
 * [dimmed] greys the art out for a coin that isn't earned. A redacted hidden coin must NOT pass its
 * badge here at all — the artwork alone can give away what the coin is for — so callers hand it the
 * tier medallion instead (see CoinListRow).
 */
@Composable
internal fun CoinArt(
    iconUrl: String?,
    tier: ShibaTier,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    cornerRadius: Dp = 8.dp,
) {
    val greyscale = if (dimmed) remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) } else null
    val art = if (dimmed) modifier.alpha(DIMMED_ALPHA) else modifier
    if (iconUrl != null) {
        AsyncImage(
            model = iconUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = greyscale,
            modifier = art.clip(RoundedCornerShape(cornerRadius)),
        )
    } else {
        ShibaCoinIcon(tier, art, colorFilter = greyscale)
    }
}
