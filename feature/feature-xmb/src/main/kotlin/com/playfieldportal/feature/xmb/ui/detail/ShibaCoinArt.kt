package com.playfieldportal.feature.xmb.ui.detail

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
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
fun ShibaCoinIcon(tier: ShibaTier, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(shibaCoinRes(tier)),
        contentDescription = null,
        modifier = modifier,
    )
}
