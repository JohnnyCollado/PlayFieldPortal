package com.playfieldportal.themekit

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PtfIconTintTest {

    private fun image(w: Int, h: Int, argbAt: (Int, Int) -> Int) =
        BmpImage(w, h, IntArray(w * h) { argbAt(it % w, it / w) })

    private fun assertNear(expected: Int, actual: Int, tolerance: Int = 2) {
        for (shift in intArrayOf(16, 8, 0)) {
            val e = expected shr shift and 0xFF
            val a = actual shr shift and 0xFF
            assertTrue(abs(e - a) <= tolerance, "channel ${shift / 8}: expected ~$e, was $a (${"%08X".format(actual)})")
        }
    }

    @Test
    fun `a single-colour icon set scores 100 and returns that colour`() {
        val orange = 0xFFF08020.toInt()
        val result = assertNotNull(PtfIconTint.derive(listOf(image(16, 16) { _, _ -> orange })))
        assertEquals(100, result.score)
        assertEquals(orange, result.argb, "the tint is a colour the art actually uses, not a rounded mean")
        assertTrue(result.medianDeltaE < 1f)
    }

    @Test
    fun `the tint is always one of the art's own colours`() {
        val shades = intArrayOf(0xFFE07020.toInt(), 0xFFE27424.toInt(), 0xFFDC6C1C.toInt())
        val result = assertNotNull(PtfIconTint.derive(listOf(image(30, 10) { x, _ -> shades[x % 3] })))
        assertTrue(result.argb in shades.toList(), "%08X".format(result.argb))
    }

    @Test
    fun `a half-red half-blue set scores about half`() {
        val red = 0xFFE02020.toInt()
        val blue = 0xFF2040E0.toInt()
        val result = assertNotNull(PtfIconTint.derive(listOf(image(20, 10) { x, _ -> if (x < 10) red else blue })))
        assertTrue(result.score in 45..55, "score ${result.score}")
    }

    @Test
    fun `transparent and translucent pixels are ignored`() {
        val green = 0xFF20C040.toInt()
        val icon = image(10, 10) { x, _ -> if (x < 5) green else 0x80FF00FF.toInt() }
        val result = assertNotNull(PtfIconTint.derive(listOf(icon)))
        assertEquals(100, result.score)
        assertNear(green, result.argb)
    }

    @Test
    fun `no opaque pixels means no tint`() {
        assertNull(PtfIconTint.derive(emptyList()))
        assertNull(PtfIconTint.derive(listOf(image(4, 4) { _, _ -> 0 })))
    }

    @Test
    fun `sampling a large set keeps the answer`() {
        val teal = 0xFF1AA0A0.toInt()
        val big = List(20) { image(64, 64) { _, _ -> teal } }
        val result = assertNotNull(PtfIconTint.derive(big, maxSamples = 500))
        assertEquals(100, result.score)
        assertNear(teal, result.argb)
    }

    @Test
    fun `CIEDE2000 matches Sharma's reference pairs`() {
        // Sharma, Wu, Dalal (2005), test data pairs 1, 2, 4, 7, 17 and 25.
        val pairs = listOf(
            doubleArrayOf(50.0, 2.6772, -79.7751, 50.0, 0.0, -82.7485, 2.0425),
            doubleArrayOf(50.0, 3.1571, -77.2803, 50.0, 0.0, -82.7485, 2.8615),
            doubleArrayOf(50.0, -1.3802, -84.2814, 50.0, 0.0, -82.7485, 1.0000),
            doubleArrayOf(50.0, 0.0, 0.0, 50.0, -1.0, 2.0, 2.3669),
            doubleArrayOf(50.0, 2.5, 0.0, 73.0, 25.0, -18.0, 27.1492),
            doubleArrayOf(60.2574, -34.0099, 36.2677, 60.4626, -34.1751, 39.4387, 1.2644),
        )
        for (p in pairs) {
            val de = PtfIconTint.deltaE2000(p[0], p[1], p[2], p[3], p[4], p[5])
            assertEquals(p[6], de, 1e-4, "pair ${p.toList()}")
        }
    }

    @Test
    fun `sRGB to Lab round-trips`() {
        for (argb in intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFE02020.toInt(), 0xFF2040E0.toInt(), 0xFF808080.toInt())) {
            val lab = PtfIconTint.toLab(argb)
            assertNear(argb, PtfIconTint.fromLab(lab[0], lab[1], lab[2]), tolerance = 1)
        }
    }

    // ── policy ────────────────────────────────────────────────────────────────

    private val vivid = 0xFFE07020.toInt()
    private val grey = 0xFFB0B0B0.toInt()
    private val wallpaperAccent = 0xFF3366FF.toInt()

    @Test
    fun `icon colour applies from the fair band up`() {
        assertEquals(vivid, PtfIconTint.iconColorFor(PtfIconTint.Result(vivid, 35, 10f)))
        assertNull(PtfIconTint.iconColorFor(PtfIconTint.Result(vivid, 34, 10f)))
        assertNull(PtfIconTint.iconColorFor(null))
    }

    @Test
    fun `a strong vivid tint becomes the accent`() {
        assertEquals(vivid, PtfIconTint.chooseAccent(PtfIconTint.Result(vivid, 60, 5f), wallpaperAccent))
    }

    @Test
    fun `otherwise the wallpaper accent stays`() {
        assertEquals(wallpaperAccent, PtfIconTint.chooseAccent(PtfIconTint.Result(vivid, 59, 5f), wallpaperAccent))
        assertEquals(wallpaperAccent, PtfIconTint.chooseAccent(PtfIconTint.Result(grey, 95, 2f), wallpaperAccent))
        assertEquals(wallpaperAccent, PtfIconTint.chooseAccent(null, wallpaperAccent))
        assertNull(PtfIconTint.chooseAccent(null, null))
    }

    @Test
    fun `a strong vivid tint still wins when the wallpaper has no accent`() {
        assertEquals(vivid, PtfIconTint.chooseAccent(PtfIconTint.Result(vivid, 80, 5f), null))
    }
}
