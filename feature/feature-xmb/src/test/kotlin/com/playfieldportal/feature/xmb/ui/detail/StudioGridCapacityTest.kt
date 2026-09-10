package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.feature.artwork.store.ArtworkKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** C16 task L.1 — the grid capacity rules and their worked examples, pinned by slot size. */
class StudioGridCapacityTest {

    private fun capacity(width: Float, height: Float, tileClass: StudioTileClass) =
        StudioGridCapacity.of(width, height, tileClass).let { it.columns to it.rows }

    // ── Worked examples (plan table, landscape and portrait) ──────────────────

    @Test
    fun `AYN Thor, the reference canvas`() {
        assertEquals(5 to 3, capacity(635f, 259f, StudioTileClass.LANDSCAPE))
        assertEquals(7 to 2, capacity(635f, 259f, StudioTileClass.PORTRAIT))
    }

    @Test
    fun `16 by 9 small handheld`() {
        assertEquals(4 to 2, capacity(570f, 223f, StudioTileClass.LANDSCAPE))
        assertEquals(6 to 1, capacity(570f, 223f, StudioTileClass.PORTRAIT))
    }

    @Test
    fun `20 by 9 phone in landscape`() {
        assertEquals(6 to 2, capacity(717f, 203f, StudioTileClass.LANDSCAPE))
        assertEquals(8 to 1, capacity(717f, 203f, StudioTileClass.PORTRAIT))
    }

    @Test
    fun `TV`() {
        assertEquals(6 to 3, capacity(762f, 331f, StudioTileClass.LANDSCAPE))
        assertEquals(8 to 2, capacity(762f, 331f, StudioTileClass.PORTRAIT))
    }

    @Test
    fun `4 by 3 tablet`() {
        assertEquals(6 to 6, capacity(776f, 559f, StudioTileClass.LANDSCAPE))
        assertEquals(8 to 4, capacity(776f, 559f, StudioTileClass.PORTRAIT))
    }

    @Test
    fun `16 by 10 tablet`() {
        assertEquals(8 to 6, capacity(1032f, 591f, StudioTileClass.LANDSCAPE))
        assertEquals(8 to 3, capacity(1032f, 591f, StudioTileClass.PORTRAIT))
    }

    @Test
    fun `square and wide classes use their own aspect and minimum width`() {
        assertEquals(6 to 2, capacity(635f, 259f, StudioTileClass.SQUARE))
        assertEquals(4 to 3, capacity(635f, 259f, StudioTileClass.WIDE))
    }

    // ── Rounding and clamps ───────────────────────────────────────────────────

    @Test
    fun `an exact fit is not floored one short`() {
        // (592 + 8) ÷ (112 + 8) is exactly 5 columns.
        assertEquals(5, StudioGridCapacity.of(592f, 259f, StudioTileClass.LANDSCAPE).columns)
        // 5 landscape tiles in 635 dp are 80.4 tall: 3 rows need exactly 3 × 88.4 − 8 = 257.2 dp.
        assertEquals(3, StudioGridCapacity.of(635f, 257.2f, StudioTileClass.LANDSCAPE).rows)
    }

    @Test
    fun `a near miss still rounds down`() {
        // 1.998 rows: the epsilon must not promote it to 2.
        assertEquals(5 to 1, capacity(570f, 223f, StudioTileClass.SQUARE))
    }

    @Test
    fun `a tiny or unmeasured slot clamps to at least 3 by 1`() {
        assertEquals(3 to 1, capacity(100f, 40f, StudioTileClass.LANDSCAPE))
        assertEquals(3 to 1, capacity(0f, 0f, StudioTileClass.PORTRAIT))
    }

    @Test
    fun `a huge slot clamps to at most 8 by 6`() {
        assertEquals(8 to 6, capacity(4000f, 3000f, StudioTileClass.LANDSCAPE))
    }

    @Test
    fun `the unmeasured default is the old 4 by 5 page`() {
        assertEquals(20, StudioGridCapacity.UNMEASURED.pageSize)
    }

    // ── Tab → tile class ──────────────────────────────────────────────────────

    @Test
    fun `every tab carries the tile class from the plan table`() {
        val expected = mapOf(
            ArtworkKind.ICON to StudioTileClass.LANDSCAPE,
            ArtworkKind.ICON1 to StudioTileClass.LANDSCAPE,
            ArtworkKind.BOX_ART to StudioTileClass.PORTRAIT,
            ArtworkKind.BOX_3D to StudioTileClass.PORTRAIT,
            ArtworkKind.PHYSICAL_MEDIA to StudioTileClass.SQUARE,
            ArtworkKind.HERO to StudioTileClass.LANDSCAPE,
            ArtworkKind.BACKGROUND to StudioTileClass.LANDSCAPE,
            ArtworkKind.LOGO to StudioTileClass.WIDE,
            ArtworkKind.SCREENSHOT to StudioTileClass.LANDSCAPE,
            ArtworkKind.MANUAL to StudioTileClass.PORTRAIT,
            ArtworkKind.VIDEO to StudioTileClass.LANDSCAPE,
        )
        assertEquals(expected, STUDIO_TABS.associate { it.kind to it.tileClass })
    }
}
