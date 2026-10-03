package com.playfieldportal.feature.xmb.ui.media

import com.playfieldportal.themekit.CustomizableIcons
import com.playfieldportal.themekit.IconSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDetailSlotKeysTest {

    @Test
    fun `every transport action maps to a registered media slot`() {
        for (action in TransportAction.entries) {
            val slot = CustomizableIcons.byKey(transportSlotKey(action))
            assertTrue("$action -> ${transportSlotKey(action)} must be registered", slot != null)
            assertEquals(IconSlot.Group.MEDIA, slot!!.group)
        }
    }

    @Test
    fun `transport actions cover the whole media group one to one`() {
        val keys = TransportAction.entries.map { transportSlotKey(it) }
        assertEquals(keys.size, keys.toSet().size)
        val group = CustomizableIcons.ALL.filter { it.group == IconSlot.Group.MEDIA }.map { it.key }.toSet()
        assertEquals(group, keys.toSet())
    }

    @Test
    fun `transport mapping is frozen`() {
        assertEquals("media_play", transportSlotKey(TransportAction.PLAY))
        assertEquals("media_pause", transportSlotKey(TransportAction.PAUSE))
        assertEquals("media_prev", transportSlotKey(TransportAction.PREVIOUS))
        assertEquals("media_next", transportSlotKey(TransportAction.NEXT))
        assertEquals("media_back10", transportSlotKey(TransportAction.BACK_10))
        assertEquals("media_fwd10", transportSlotKey(TransportAction.FORWARD_10))
    }

    @Test
    fun `play pause button shows the action it will perform`() {
        assertEquals(TransportAction.PAUSE, playPauseAction(isPlaying = true))
        assertEquals(TransportAction.PLAY, playPauseAction(isPlaying = false))
    }

    @Test
    fun `every detail action maps to a registered game detail slot`() {
        for (action in GameDetailAction.entries) {
            val slot = CustomizableIcons.byKey(detailSlotKey(action))
            assertTrue("$action -> ${detailSlotKey(action)} must be registered", slot != null)
            assertEquals(IconSlot.Group.GAME_DETAIL, slot!!.group)
        }
    }

    @Test
    fun `detail actions cover the whole group one to one`() {
        val keys = GameDetailAction.entries.map { detailSlotKey(it) }
        assertEquals(keys.size, keys.toSet().size)
        val group = CustomizableIcons.ALL.filter { it.group == IconSlot.Group.GAME_DETAIL }.map { it.key }.toSet()
        assertEquals(group, keys.toSet())
    }

    @Test
    fun `detail mapping is frozen`() {
        assertEquals("detail_play", detailSlotKey(GameDetailAction.PLAY))
        assertEquals("detail_favorite", detailSlotKey(GameDetailAction.FAVORITE))
        assertEquals("detail_artwork", detailSlotKey(GameDetailAction.ARTWORK))
        assertEquals("detail_manual", detailSlotKey(GameDetailAction.MANUAL))
        assertEquals("detail_more", detailSlotKey(GameDetailAction.MORE))
    }

    @Test
    fun `favorite override is dimmed only when not a favorite`() {
        assertEquals(1f, favoriteOverrideAlpha(isFavorite = true), 0f)
        assertTrue(favoriteOverrideAlpha(isFavorite = false) < 1f)
        assertTrue(favoriteOverrideAlpha(isFavorite = false) > 0f)
    }
}
