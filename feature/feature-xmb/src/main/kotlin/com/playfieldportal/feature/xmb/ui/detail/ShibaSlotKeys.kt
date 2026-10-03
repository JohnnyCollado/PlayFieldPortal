package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.domain.achievement.ShibaTier

/** Theme-kit item slot for a Shiba Coins hub row id, or null when the row has no slot. */
internal fun shibaSlotKeyFor(rowId: String): String? = when (rowId) {
    "ach_connect" -> "item_shiba_connect"
    "ach_all" -> "item_shiba_track"
    "ach_untracked" -> "item_shiba_untracked"
    else -> null
}

/** Theme-kit slot for a tier's coin medallion. */
internal fun shibaCoinSlotKeyFor(tier: ShibaTier): String = when (tier) {
    ShibaTier.BRONZE -> "shiba_coin_bronze"
    ShibaTier.SILVER -> "shiba_coin_silver"
    ShibaTier.GOLD -> "shiba_coin_gold"
    ShibaTier.PLATINUM -> "shiba_coin_platinum"
}
