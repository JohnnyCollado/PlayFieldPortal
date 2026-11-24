package com.playfieldportal.core.ui.keyboard

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Key sizing from the width a host offers (touch targets, 48 dp ceiling). */
class VirtualKeyboardMetricsTest {

    private fun panelWidth(m: VirtualKeyboardMetrics) =
        m.keyUnit * 10 + VirtualKeyboardMetrics.KeyGap * 9 + VirtualKeyboardMetrics.PanelPadding * 2

    @Test fun `a 360 dp phone keeps all ten columns inside the width`() {
        val m = VirtualKeyboardMetrics.forWidth(360.dp)
        assertTrue(panelWidth(m) <= 360.dp)
        assertTrue(m.keyUnit < 48.dp)
    }

    @Test fun `600 dp reaches the 48 dp ceiling`() {
        assertEquals(48.dp, VirtualKeyboardMetrics.forWidth(600.dp).keyUnit)
    }

    @Test fun `960 dp stays at the 48 dp ceiling`() {
        assertEquals(48.dp, VirtualKeyboardMetrics.forWidth(960.dp).keyUnit)
    }

    @Test fun `key height never drops below 44 dp`() {
        listOf(200.dp, 360.dp, 600.dp, 960.dp).forEach {
            assertTrue(VirtualKeyboardMetrics.forWidth(it).keyHeight >= 44.dp)
        }
    }

    @Test fun `unit grows monotonically with width`() {
        val units = listOf(300.dp, 360.dp, 420.dp, 500.dp, 600.dp).map { VirtualKeyboardMetrics.forWidth(it).keyUnit }
        assertEquals(units.sortedBy { it.value }, units)
    }

    @Test fun `a panel height gives back its key height`() {
        val key = VirtualKeyboardMetrics.keyHeightFor(VirtualKeyboardMetrics.panelHeight(41.dp))
        assertEquals(41f, key.value, 0.01f)
    }

    @Test fun `on the Odin the bottom keyboard takes what Gboard takes`() {
        val size = bottomKeyboardSize(468.dp, SystemKeyboardShare.LANDSCAPE_DEFAULT)
        assertEquals(468f * SystemKeyboardShare.LANDSCAPE_DEFAULT, size.reserve.value, 0.5f)
        assertTrue("keys about Gboard's: ${size.keyHeight}", size.keyHeight in 38.dp..44.dp)
    }

    @Test fun `on a short screen keys stop at the floor and the keyboard stands a little taller`() {
        val size = bottomKeyboardSize(360.dp, SystemKeyboardShare.LANDSCAPE_DEFAULT)
        assertEquals(VirtualKeyboardMetrics.MinMatchedKeyHeight, size.keyHeight)
        assertTrue(size.reserve > 360.dp * SystemKeyboardShare.LANDSCAPE_DEFAULT)
    }

    @Test fun `on a tablet the keys grow so the keyboard reaches the system keyboard's height`() {
        val size = bottomKeyboardSize(800.dp, SystemKeyboardShare.LANDSCAPE_DEFAULT)
        assertEquals(800f * SystemKeyboardShare.LANDSCAPE_DEFAULT, size.reserve.value, 0.5f)
        assertTrue(size.keyHeight > 48.dp)
    }

    @Test fun `a tall key is a wide one where the width allows`() {
        val m = VirtualKeyboardMetrics.forKeyHeight(80.dp, 1280.dp)
        assertTrue(m.keyUnit > 80.dp)
    }
}
