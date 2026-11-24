package com.playfieldportal.feature.xmb.ui

import androidx.compose.ui.geometry.Rect
import com.playfieldportal.feature.xmb.viewmodel.XMBItem
import com.playfieldportal.feature.xmb.viewmodel.XMBItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lifted-item outline's geometry: it hugs what is lifted and never leaves its own frame. */
class MoveOutlineGeometryTest {

    private fun Rect.containsRect(o: Rect) =
        o.left >= left - EPS && o.right <= right + EPS && o.top >= top - EPS && o.bottom <= bottom + EPS

    @Test
    fun squareFrameHugsEveryIconAspectWithEvenPadding() {
        // Wide memory card, tall phone, square console art, a 126x70 tile.
        val shapes = listOf(80f to 40f, 40f to 80f, 62f to 62f, 126f to 70f)
        for ((w, h) in shapes) {
            val icon = Rect(100f, 200f, 100f + w, 200f + h)
            val frame = moveIconFrame(icon, pad = 4f)
            assertEquals(4f, icon.left - frame.left, EPS)
            assertEquals(4f, frame.right - icon.right, EPS)
            assertEquals(4f, icon.top - frame.top, EPS)
            assertEquals(4f, frame.bottom - icon.bottom, EPS)
            assertEquals(icon.center, frame.center)
        }
    }

    @Test
    fun aGameTilesBoundsAreTheTileWithoutItsTextGap() {
        // The icon box of a game row: a 126 x 70 tile followed by the 16 gap before the title.
        assertEquals(Rect(0f, 0f, 126f, 70f), leadingIconBounds(boxWidth = 142f, boxHeight = 70f, textGap = 16f))
    }

    @Test
    fun aSlotIconsBoundsAreTheSquareCentredInTheSlot() {
        // A 62 memory card and a 48 glyph, each centred in the 74-wide slot.
        assertEquals(Rect(6f, 0f, 68f, 62f), leadingIconBounds(boxWidth = 74f, boxHeight = 62f, textGap = 16f))
        assertEquals(Rect(13f, 0f, 61f, 48f), leadingIconBounds(boxWidth = 74f, boxHeight = 48f, textGap = 16f))
    }

    @Test
    fun theLiftedRowsFrameStopsAtTheIconAndNeverReachesTheTitle() {
        // The title starts after the tile's text gap; the frame, 4 off the tile, ends before it.
        val tile = leadingIconBounds(boxWidth = 142f, boxHeight = 70f, textGap = 16f)
        val frame = moveIconFrame(tile, pad = 4f)
        assertEquals(Rect(-4f, -4f, 130f, 74f), frame)
        assertTrue(frame.right < 142f)
    }

    @Test
    fun aTileIsFramedWhereItIsDrawnAndEverythingElseOnItsSlot() {
        // A game, and an app given artwork, draw a tile whose size depends on the icon display mode
        // (ICON0, box art, a cartridge), so the tile itself carries the frame. A card or a glyph is a
        // fixed square in the icon slot, which the row can frame from outside.
        val game = XMBItem("g", "Patapon", gameId = 8L, isRealGame = true)
        val artApp = XMBItem("a", "Player", isAndroidApp = true, iconUri = "art.png")
        val plainApp = XMBItem("b", "Clock", isAndroidApp = true, packageName = "x.clock")
        val card = XMBItem("c", "Favourites", type = XMBItemType.CATEGORY_CARD)
        assertTrue(liftedFrameSitsOnTheTile(game))
        assertTrue(liftedFrameSitsOnTheTile(artApp))
        assertFalse(liftedFrameSitsOnTheTile(plainApp))
        assertFalse(liftedFrameSitsOnTheTile(card))
    }

    @Test
    fun theFrameSitsACoupleOfPixelsOffWhateverSizeTheIconIsDrawnAt() {
        // ICON0 tile, a tall box front, a wide cartridge: always the drawn size plus the same pad.
        for ((w, h) in listOf(126f to 70f, 59f to 84f, 110f to 84f)) {
            val frame = moveIconFrame(Rect(0f, 0f, w, h), pad = 3f)
            assertEquals(Rect(-3f, -3f, w + 3f, h + 3f), frame)
        }
    }

    @Test
    fun chevronsStayWithinTheFrameInBothAxes() {
        val frame = moveIconFrame(Rect(0f, 0f, 62f, 62f), pad = 4f)
        for (horizontal in listOf(false, true)) {
            for (atStart in listOf(true, false)) {
                val points = moveChevron(frame, horizontal, atStart, half = 8f, depth = 7f)
                assertEquals(3, points.size)
                points.forEach { assertTrue(frame.contains(it) || onEdge(frame, it)) }
            }
        }
    }

    @Test
    fun verticalChevronPointsOutOfItsEdge() {
        val frame = Rect(0f, 0f, 100f, 100f)
        val top = moveChevron(frame, horizontal = false, atStart = true, half = 8f, depth = 7f)
        assertEquals(0f, top[1].y, EPS)          // tip on the top edge
        assertEquals(7f, top[0].y, EPS)          // wings below it
        val bottom = moveChevron(frame, horizontal = false, atStart = false, half = 8f, depth = 7f)
        assertEquals(100f, bottom[1].y, EPS)
        assertEquals(93f, bottom[0].y, EPS)
    }

    @Test
    fun horizontalChevronsMirrorAboutTheFrameCentre() {
        // Mirrored in RTL: the left chevron is the right one reflected, so reversing the layout
        // direction changes nothing about the pair.
        val frame = moveIconFrame(Rect(10f, 20f, 82f, 92f), pad = 4f)
        val left = moveChevron(frame, horizontal = true, atStart = true, half = 8f, depth = 7f)
        val right = moveChevron(frame, horizontal = true, atStart = false, half = 8f, depth = 7f)
        for (i in 0..2) {
            assertEquals(frame.center.x - left[i].x, right[i].x - frame.center.x, EPS)
            assertEquals(left[i].y, right[i].y, EPS)
        }
        // The left one points left (tip furthest left), the right one right.
        assertTrue(left[1].x < left[0].x)
        assertTrue(right[1].x > right[0].x)
    }

    @Test
    fun crossbarFrameClearsNeighbourIconsAndTheLabelBelow() {
        // 124 slot, 72 icon centred in an 82 box; the neighbours' icons start one slot over.
        val icon = Rect(26f, 5f, 98f, 77f)
        val frame = moveIconFrame(icon, pad = 4f)
        val neighbour = Rect(124f + 26f, 5f, 124f + 98f, 77f)
        val label = Rect(0f, 86f, 124f, 106f)
        assertFalse(frame.overlaps(neighbour))
        assertFalse(frame.overlaps(label))
        assertTrue(frame.right < neighbour.left)
    }

    @Test
    fun notchSpansTheChevronAndCrossesTheBorderOnly() {
        val frame = Rect(0f, 0f, 100f, 100f)
        val top = moveNotch(frame, horizontal = false, atStart = true, half = 8f, notch = 4f, stroke = 2f)
        assertEquals(50f - 12f, top.left, EPS)
        assertEquals(50f + 12f, top.right, EPS)
        assertTrue(top.top < 0f && top.bottom > 0f)
        val side = moveNotch(frame, horizontal = true, atStart = false, half = 8f, notch = 4f, stroke = 2f)
        assertEquals(50f - 12f, side.top, EPS)
        assertTrue(side.left < 100f && side.right > 100f)
    }

    private fun onEdge(r: Rect, p: androidx.compose.ui.geometry.Offset) =
        p.x >= r.left - EPS && p.x <= r.right + EPS && p.y >= r.top - EPS && p.y <= r.bottom + EPS

    private companion object {
        const val EPS = 0.001f
    }
}
