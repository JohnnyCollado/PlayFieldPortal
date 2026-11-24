package com.playfieldportal.core.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.playfieldportal.core.ui.icons.MatteDark
import com.playfieldportal.core.ui.icons.MatteLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The matte colour behind the status strip and hint-row "shadow twin" is the luminance opposite. */
class ShadowTwinTest {

    @Test
    fun `light content gets the dark matte`() {
        val twin = shadowTwinColor(Color.White)
        assertEquals(MatteDark.copy(alpha = twin.alpha), twin)
        assertTrue(twin.luminance() < 0.1f)
    }

    @Test
    fun `dark content gets the light matte`() {
        val twin = shadowTwinColor(Color.Black)
        assertEquals(MatteLight.copy(alpha = twin.alpha), twin)
        assertTrue(twin.luminance() > 0.8f)
    }

    @Test
    fun `a faint element gets a proportionally faint matte`() {
        val solid = shadowTwinColor(Color(0xFFEEEEEE))
        val faint = shadowTwinColor(Color(0x40EEEEEE))
        assertTrue(faint.alpha < solid.alpha)
        assertEquals(MatteDark.red, faint.red, 0.001f)
    }

    @Test
    fun `a fully transparent element gets no matte`() {
        assertEquals(0f, shadowTwinColor(Color.Transparent).alpha, 0f)
    }
}
