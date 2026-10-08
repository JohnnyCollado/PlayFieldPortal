package com.playfieldportal.themekit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slots whose built-in art is one picture are grouped once, in [SharedIconArt], so every job that
 * touches one of them (PTF import, default art) reaches all of them. The Studio's
 * StudioIconSetSharedArtTest is the byte-level guard that the groups match the bundled art.
 */
class SharedIconArtTest {

    @Test
    fun `the memory card group is every slot drawing the default memory-card art`() {
        assertEquals(
            setOf(
                "item_memcard_games", "item_memcard_music", "item_memcard_video", "item_memcard_photos",
                "sysicon_allgames", "item_shiba_track",
            ),
            SharedIconArt.MEMORY_CARD.toSet(),
        )
    }

    @Test
    fun `the games group is the Game column glyph and the generic console art`() {
        assertEquals(setOf("catbar_games", "sysicon_default"), SharedIconArt.GAMES.toSet())
    }

    @Test
    fun `every grouped key is a registered slot and sits in one group only`() {
        val keys = SharedIconArt.ALL.flatten()
        keys.forEach { assertTrue(CustomizableIcons.isValidKey(it), "$it must be a registered slot") }
        assertEquals(keys.size, keys.toSet().size, "a key is in two groups")
        assertTrue(SharedIconArt.ALL.all { it.size > 1 }, "a group of one shares nothing")
    }

    @Test
    fun `groupOf gives the whole group, or just the key when it shares nothing`() {
        assertEquals(SharedIconArt.MEMORY_CARD, SharedIconArt.groupOf("sysicon_allgames"))
        assertEquals(SharedIconArt.GAMES, SharedIconArt.groupOf("catbar_games"))
        assertEquals(listOf("item_umd"), SharedIconArt.groupOf("item_umd"))
    }
}
