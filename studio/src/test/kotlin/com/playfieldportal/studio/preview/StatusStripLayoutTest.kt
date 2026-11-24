package com.playfieldportal.studio.preview

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.playfieldportal.studio.StudioState
import java.awt.EventQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap

/**
 * The preview's status strip is the launcher's 1.3.5 strip (XmbStatusStrip.XmbPspStatusStrip): every
 * piece packed against the end edge, in one colour, each word and icon with its crisp shadow twin.
 */
class StatusStripLayoutTest {

    private val width = 1664 // 2 px per dp
    private val height = 936

    /** The strip band (the top 28 dp) as [x][y] → whether that pixel is near-white, i.e. strip content. */
    private fun litColumns(): BooleanArray {
        val lit = BooleanArray(width)
        EventQueue.invokeAndWait {
            ImageComposeScene(width, height, density = Density(width / PreviewGeometry.BASE_WIDTH)).use { scene ->
                scene.setContent {
                    CompositionLocalProvider(LocalPreviewClock provides { 1_791_043_200_000L }) {
                        XmbFrame(StudioState().toPreviewModel(), PreviewNavState.HOME)
                    }
                }
                val bitmap = Bitmap.makeFromImage(scene.render(0))
                for (x in 0 until width) for (y in 0 until 56) {
                    val c = bitmap.getColor(x, y)
                    val r = (c shr 16) and 255
                    val g = (c shr 8) and 255
                    val b = c and 255
                    if (r > 200 && g > 200 && b > 200) lit[x] = true
                }
            }
        }
        return lit
    }

    @Test
    fun `the whole strip sits in the end corner, nothing on the left`() {
        val lit = litColumns()
        val firstLit = lit.indexOfFirst { it }
        assertTrue(firstLit >= 0, "the strip draws something")
        assertTrue(firstLit > width / 2, "first strip pixel at x=$firstLit; the launcher packs date and time to the right too")
        val lastLit = lit.indexOfLast { it }
        assertTrue(lastLit > width - 2 * 40, "the strip reaches the end edge (x=$lastLit)")
    }

    @Test
    fun `one colour for every word and icon, the date included`() {
        val unthemed = StudioState().toPreviewModel()
        assertEquals(Color(0xFFEEEEEE), StatusStripTints.icon(unthemed))
        assertEquals(Color(0xFFEEEEEE), StatusStripTints.date(unthemed))
    }

    @Test
    fun `each piece's twin is the dark matte for light content and fades with it`() {
        assertEquals(Color(0xFF000A12).copy(alpha = 0.95f), StatusStripTints.twin(Color(0xFFEEEEEE)))
        assertEquals(Color(0xFFEBF5FF).copy(alpha = 0.95f), StatusStripTints.twin(Color(0xFF101010)))
        val faint = StatusStripTints.twin(Color(0x40EEEEEE))
        assertEquals(0.95f * (0x40 / 255f), faint.alpha, 0.01f)
    }
}
