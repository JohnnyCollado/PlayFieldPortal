package com.playfieldportal.core.data.repository

import com.playfieldportal.core.domain.model.IconDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-console icon display overrides are stored as ONE DataStore string (so the backup key
 * list can carry them by name). These cover the encode/decode round trip and the garbage cases
 * a hand-edited or partially-migrated value can produce.
 */
class IconDisplayPlatformModesTest {

    @Test
    fun `round trips a map of platform overrides`() {
        val modes = mapOf("psx" to IconDisplayMode.BOX_ART, "snes" to IconDisplayMode.BOX_3D)
        assertEquals(modes, IconDisplayPreferences.decodePlatformModes(
            IconDisplayPreferences.encodePlatformModes(modes),
        ))
    }

    @Test
    fun `an empty map encodes to an empty string and back`() {
        assertEquals("", IconDisplayPreferences.encodePlatformModes(emptyMap()))
        assertEquals(emptyMap<String, IconDisplayMode>(), IconDisplayPreferences.decodePlatformModes(""))
        assertEquals(emptyMap<String, IconDisplayMode>(), IconDisplayPreferences.decodePlatformModes(null))
    }

    @Test
    fun `unknown mode names and malformed entries are dropped, good ones survive`() {
        val decoded = IconDisplayPreferences.decodePlatformModes(
            "psx=BOX_ART\nsnes=NOT_A_MODE\nbroken\n=BOX_3D\nn64=PHYSICAL_MEDIA",
        )
        assertEquals(
            mapOf("psx" to IconDisplayMode.BOX_ART, "n64" to IconDisplayMode.PHYSICAL_MEDIA),
            decoded,
        )
    }

    @Test
    fun `setting a platform override leaves the other consoles alone`() {
        val existing = mapOf("psx" to IconDisplayMode.BOX_ART, "snes" to IconDisplayMode.BOX_3D)
        val updated = IconDisplayPreferences.withPlatformMode(existing, "n64", IconDisplayMode.ICON0)
        assertEquals(IconDisplayMode.BOX_ART, updated["psx"])
        assertEquals(IconDisplayMode.BOX_3D, updated["snes"])
        assertEquals(IconDisplayMode.ICON0, updated["n64"])
    }

    @Test
    fun `a null mode clears just that console back to the global setting`() {
        val existing = mapOf("psx" to IconDisplayMode.BOX_ART, "snes" to IconDisplayMode.BOX_3D)
        val updated = IconDisplayPreferences.withPlatformMode(existing, "psx", null)
        assertFalse("psx" in updated)
        assertTrue("snes" in updated)
    }
}
