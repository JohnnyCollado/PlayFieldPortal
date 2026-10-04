package com.playfieldportal.studio

import com.playfieldportal.studio.preview.StudioIconSet
import com.playfieldportal.themekit.CustomizableIcons
import com.playfieldportal.themekit.IconSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Studio's side of the physical-media slots the launcher registry now carries (`physmedia_<id>`). */
class PhysicalMediaArtTest {

    private val media = CustomizableIcons.group(IconSlot.Group.PHYSICAL_MEDIA)

    @Test
    fun `physical media slots are editable and distinct from console art`() {
        assertTrue(EditableSlots.isEditable("physmedia_psp"))
        assertTrue(EditableSlots.isEditable("sysicon_psp"))
        assertTrue(media.all { EditableSlots.isEditable(it.key) })
    }

    @Test
    fun `every slot shows the launcher's bundled media art`() {
        for (slot in media) {
            val path = assertNotNull(StudioIconSet.physicalMediaResource(slot.key), slot.key)
            assertTrue(StudioIconSet::class.java.classLoader.getResource(path) != null, "${slot.key} -> $path missing")
        }
        assertEquals("xmb/physical-media/xbox360.png", StudioIconSet.physicalMediaResource("physmedia_x360"))
        assertEquals("xmb/physical-media/psx.png", StudioIconSet.physicalMediaResource("physmedia_psx"))
        assertNull(StudioIconSet.physicalMediaResource("sysicon_psx"))
    }

    @Test
    fun `media art is drawn as authored, not tinted`() {
        assertTrue(StudioIconSet.isFullColour("physmedia_psx"))
        assertFalse(StudioIconSet.isFullColour("sysicon_psx"))
    }
}
