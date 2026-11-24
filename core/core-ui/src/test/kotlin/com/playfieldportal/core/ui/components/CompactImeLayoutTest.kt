package com.playfieldportal.core.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The system keyboard on a phone in landscape leaves ~150dp — less than a full modal card needs.
 * Below [CompactImeThreshold] the modals collapse to one strip on the keyboard; with room to spare,
 * or with no system keyboard at all (PFP's keyboard reserves its own room), the full card stays.
 */
class CompactImeLayoutTest {

    @Test
    fun `a phone keyboard leaving too little room goes compact`() {
        assertTrue(useCompactImeLayout(keyboardOpen = true, spaceAboveKeyboard = 150.dp))
    }

    @Test
    fun `enough room above the keyboard keeps the full card`() {
        assertFalse(useCompactImeLayout(keyboardOpen = true, spaceAboveKeyboard = 500.dp))
        assertFalse(useCompactImeLayout(keyboardOpen = true, spaceAboveKeyboard = CompactImeThreshold))
    }

    @Test
    fun `no system keyboard never goes compact, however short the screen`() {
        assertFalse(useCompactImeLayout(keyboardOpen = false, spaceAboveKeyboard = 150.dp))
    }
}
