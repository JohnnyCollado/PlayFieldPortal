package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.IconDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolveIconDisplayTest {

    private val fullArt = XMBItem(
        id = "1", title = "Game",
        iconUri = "icon", boxArtUri = "box", physicalMediaUri = "cart", box3dUri = "box3d",
        heroUri = "hero", artworkUri = "bg",
    )

    @Test
    fun `icon0 mode shows only the custom icon`() {
        val r = resolveIconDisplay(fullArt, IconDisplayMode.ICON0)
        assertEquals("icon", r.uri)
        assertFalse(r.naturalAspect)
    }

    @Test
    fun `icon0 with no custom icon keeps a null uri for the 144x80 letter tile`() {
        val r = resolveIconDisplay(fullArt.copy(iconUri = null), IconDisplayMode.ICON0)
        assertNull(r.uri)
        assertFalse(r.naturalAspect)
        assertEquals(IconDisplayMode.ICON0, r.mode)
    }

    @Test
    fun `box art mode renders naturally, missing art keeps a null uri for the box placeholder`() {
        val r = resolveIconDisplay(fullArt, IconDisplayMode.BOX_ART)
        assertEquals("box", r.uri)
        assertTrue(r.naturalAspect)

        val fallback = resolveIconDisplay(fullArt.copy(boxArtUri = null), IconDisplayMode.BOX_ART)
        assertNull(fallback.uri)
        assertEquals(IconDisplayMode.BOX_ART, fallback.mode)
    }

    @Test
    fun `physical media mode keeps a null uri so the platform cartridge placeholder shows`() {
        val r = resolveIconDisplay(fullArt.copy(physicalMediaUri = null), IconDisplayMode.PHYSICAL_MEDIA)
        assertNull(r.uri)
        assertEquals(IconDisplayMode.PHYSICAL_MEDIA, r.mode)
    }

    @Test
    fun `box3d shows only its own asset, missing art keeps a null uri for the box placeholder`() {
        val r = resolveIconDisplay(fullArt, IconDisplayMode.BOX_3D)
        assertEquals("box3d", r.uri)
        assertTrue(r.naturalAspect)

        val empty = resolveIconDisplay(fullArt.copy(box3dUri = null), IconDisplayMode.BOX_3D)
        assertNull(empty.uri)
        assertEquals(IconDisplayMode.BOX_3D, empty.mode)
    }

    @Test
    fun `per-game override beats the global mode`() {
        val item = fullArt.copy(iconDisplayModeOverride = IconDisplayMode.BOX_3D.name)
        val r = resolveIconDisplay(item, IconDisplayMode.ICON0)
        assertEquals("box3d", r.uri)
        assertEquals(IconDisplayMode.BOX_3D, r.mode)
    }

    @Test
    fun `unknown override name follows the global mode`() {
        val item = fullArt.copy(iconDisplayModeOverride = "NOT_A_MODE")
        assertEquals(IconDisplayMode.BOX_ART, resolveIconDisplay(item, IconDisplayMode.BOX_ART).mode)
    }
}
