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

    @Test fun `the largest panel height matches the 48 dp metrics`() {
        val m = VirtualKeyboardMetrics.forWidth(960.dp)
        val height = m.keyHeight * 5 + VirtualKeyboardMetrics.KeyGap * 4 + VirtualKeyboardMetrics.PanelPadding * 2
        assertEquals(VirtualKeyboardMetrics.MaxPanelHeight, height)
    }
}
