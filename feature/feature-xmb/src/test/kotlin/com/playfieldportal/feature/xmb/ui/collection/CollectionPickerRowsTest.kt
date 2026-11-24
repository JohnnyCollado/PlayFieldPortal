package com.playfieldportal.feature.xmb.ui.collection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Add to Card as Options rows: one per card with its membership checked, then New Card last. */
class CollectionPickerRowsTest {

    private val cards = listOf(
        CollectionPickerOption(1, "Favourites", checked = true),
        CollectionPickerOption(2, "Couch co-op", checked = false),
    )

    @Test
    fun `one row per card, members checked, then the New Card row`() {
        val rows = collectionPickerRows(cards, newCardLabel = "New Custom Memory Card")
        assertEquals(listOf("Favourites", "Couch co-op", "New Custom Memory Card"), rows.map { it.label })
        assertEquals(listOf(true, false, false), rows.map { it.checked })
    }

    @Test
    fun `New Card opens its name entry, so it carries the chevron and the cards do not`() {
        val rows = collectionPickerRows(cards, newCardLabel = "New Custom Memory Card")
        assertTrue(rows.last().opensMenu)
        assertFalse(rows.dropLast(1).any { it.opensMenu })
    }

    @Test
    fun `with no cards yet only the New Card row is offered`() {
        assertEquals(listOf("New Custom Memory Card"), collectionPickerRows(emptyList(), "New Custom Memory Card").map { it.label })
    }
}
