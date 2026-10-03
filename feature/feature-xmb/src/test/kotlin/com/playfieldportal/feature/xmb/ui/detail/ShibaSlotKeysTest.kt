package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.themekit.CustomizableIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the Shiba Coins row ids and tiers to the theme-kit slot keys the launcher reads. */
class ShibaSlotKeysTest {

    @Test
    fun `hub row ids map to their item slots`() {
        assertEquals("item_shiba_connect", shibaSlotKeyFor("ach_connect"))
        assertEquals("item_shiba_track", shibaSlotKeyFor("ach_all"))
        assertEquals("item_shiba_untracked", shibaSlotKeyFor("ach_untracked"))
    }

    @Test
    fun `other ids have no shiba slot`() {
        assertNull(shibaSlotKeyFor("ach_game_123"))
        assertNull(shibaSlotKeyFor(""))
    }

    @Test
    fun `each tier maps to its coin slot`() {
        assertEquals("shiba_coin_bronze", shibaCoinSlotKeyFor(ShibaTier.BRONZE))
        assertEquals("shiba_coin_silver", shibaCoinSlotKeyFor(ShibaTier.SILVER))
        assertEquals("shiba_coin_gold", shibaCoinSlotKeyFor(ShibaTier.GOLD))
        assertEquals("shiba_coin_platinum", shibaCoinSlotKeyFor(ShibaTier.PLATINUM))
    }

    @Test
    fun `every mapped key is a registered customizable slot`() {
        val keys = listOf("ach_connect", "ach_all", "ach_untracked").map { shibaSlotKeyFor(it)!! } +
            ShibaTier.entries.map { shibaCoinSlotKeyFor(it) }
        keys.forEach { assertNotNull(it, CustomizableIcons.byKey(it)) }
    }
}
