package com.playfieldportal.feature.appbar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What X — the App Drawer's controller search button — does. A search that still holds text is
 * brought back for more typing rather than wiped: closing it is for when the field is empty (or
 * BACK on PFP's keyboard). The touch magnifier keeps its plain toggle.
 */
class DrawerSearchButtonTest {

    @Test fun `a closed search opens`() {
        assertEquals(DrawerSearchButton.OPEN, drawerSearchButton(searchActive = false, query = ""))
        assertEquals(DrawerSearchButton.OPEN, drawerSearchButton(searchActive = false, query = "dolph"))
    }

    @Test fun `an open search with text reopens the keyboard and keeps the text`() {
        assertEquals(DrawerSearchButton.REOPEN, drawerSearchButton(searchActive = true, query = "dolph"))
    }

    @Test fun `an open empty search closes`() {
        assertEquals(DrawerSearchButton.CLOSE, drawerSearchButton(searchActive = true, query = ""))
        assertEquals(DrawerSearchButton.CLOSE, drawerSearchButton(searchActive = true, query = "   "))
    }
}
