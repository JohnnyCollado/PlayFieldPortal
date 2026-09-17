package com.playfieldportal.feature.artwork.importer

import org.junit.Assert.assertEquals
import org.junit.Test

/** C21 task 1.5 — D1: a multi-asset slot's order after Scan & Relink. */
class RelinkSlotOrderingTest {

    private data class F(val stem: String, val ordinal: Int)

    private fun order(
        files: List<F>,
        prior: Map<String, Int> = emptyMap(),
    ): List<String> =
        RelinkSlotOrdering.order(
            files = files,
            stemOf = { it.stem },
            ordinalOf = { it.ordinal },
            priorSortOrder = { name -> prior[name] },
        ).map { it.stem }

    // The X / X_01 / X_02 delete example (plan §1): X deleted, records compacted to
    // X_01@0 / X_02@1; the files X_01 and X_02 are untouched on disk. Relink must not swap them.
    @Test
    fun `a delete's compacted records keep their files in the same order`() {
        val files = listOf(F("X_01", ordinal = 1), F("X_02", ordinal = 2))
        val prior = mapOf("x_01" to 0, "x_02" to 1)

        assertEquals(listOf("X_01", "X_02"), order(files, prior))
    }

    // A reorder swaps two records' sort_order without touching either file — relink must
    // reproduce the swap, not reset the pair back to filename-ordinal order.
    @Test
    fun `a reorder survives a relink`() {
        val files = listOf(F("X", ordinal = 0), F("X_01", ordinal = 1))
        // After the reorder, "X_01" is the primary (sort_order 0) and "X" follows (sort_order 1).
        val prior = mapOf("x" to 1, "x_01" to 0)

        assertEquals(listOf("X_01", "X"), order(files, prior))
    }

    // A fresh library has no records at all — order must fall back to the filename ordinal.
    @Test
    fun `a fresh library with no records orders by ordinal`() {
        val files = listOf(F("Zelda_02", ordinal = 2), F("Zelda", ordinal = 0), F("Zelda_01", ordinal = 1))

        assertEquals(listOf("Zelda", "Zelda_01", "Zelda_02"), order(files, emptyMap()))
    }

    @Test
    fun `a file with no prior record sorts after every prior-matched file`() {
        val files = listOf(F("New", ordinal = 0), F("Old", ordinal = 5))
        val prior = mapOf("old" to 0)

        assertEquals(listOf("Old", "New"), order(files, prior))
    }

    @Test
    fun `matching is case-insensitive`() {
        val files = listOf(F("Portal_01", ordinal = 1))
        val prior = mapOf("portal_01" to 0)

        assertEquals(listOf("Portal_01"), order(files, prior))
    }
}
