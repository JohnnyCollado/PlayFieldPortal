package com.playfieldportal.studio.preview

import androidx.compose.ui.graphics.ImageBitmap
import com.playfieldportal.studio.StudioState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PreviewModelConsoleTest {

    @Test
    fun `console overrides reach the preview alongside icon overrides`() {
        val icon = ImageBitmap(2, 2)
        val console = ImageBitmap(2, 2)
        val model = StudioState(
            iconBitmaps = mapOf("item_add" to icon),
            sysiconBitmaps = mapOf("sysicon_ps3" to console),
        ).toPreviewModel()
        assertSame(icon, model.iconOverrides["item_add"])
        assertSame(console, model.iconOverrides["sysicon_ps3"])
    }

    @Test
    fun `sample game rows use console keys`() {
        val consoles = SampleContent.rootRows(4).map { it.slotKey }
        assertEquals(listOf("sysicon_allgames", "sysicon_favorites", "sysicon_ps3", "sysicon_psp", "sysicon_windows"), consoles)
        assertTrue(SampleContent.rootRows(4).all { r -> r.children.all { it.slotKey == r.slotKey } })
    }
}
