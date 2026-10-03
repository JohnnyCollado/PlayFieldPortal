package com.playfieldportal.studio.preview

import com.playfieldportal.themekit.PfpThemeManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The wave maths per mode, pinned to the values feature-xmb XmbBackground documents. */
class WaveMotionTest {

    private val animated = WaveMotion.paramsFor(PfpThemeManifest.WAVE_ANIMATED)
    private val reduced = WaveMotion.paramsFor(PfpThemeManifest.WAVE_REDUCED)
    private val static = WaveMotion.paramsFor(PfpThemeManifest.WAVE_STATIC)
    private val reducedStatic = WaveMotion.paramsFor(PfpThemeManifest.WAVE_REDUCED_STATIC)
    private val back = WaveMotion.FOLDS[0]
    private val front = WaveMotion.FOLDS[1]

    @Test
    fun `the four modes carry the launcher's speed amplitude and alpha`() {
        assertEquals(WaveParams(true, 1f, 1f, 1f), animated)
        assertEquals(WaveParams(true, 0.5f, 0.65f, 0.5f), reduced)
        assertEquals(WaveParams(false, 1f, 1f, 1f), static)
        assertEquals(WaveParams(false, 0.5f, 0.65f, 0.5f), reducedStatic)
    }

    @Test
    fun `an unknown style falls back to animated`() {
        assertEquals(animated, WaveMotion.paramsFor("rainbow"))
    }

    @Test
    fun `the fold constants are the launcher's`() {
        assertEquals(listOf(0.63f, 0.75f), WaveMotion.FOLDS.map { it.base })
        assertEquals(listOf(0.090f, 0.105f), WaveMotion.FOLDS.map { it.sheet })
        assertEquals(listOf(0.125f, 0.145f), WaveMotion.FOLDS.map { it.edge })
    }

    @Test
    fun `crest height matches the launcher formula`() {
        assertEquals(0.66634f, WaveMotion.crestY01(back, animated, 0f, 2f), 1e-4f)
        assertEquals(0.66647f, WaveMotion.crestY01(back, animated, 0.25f, 2f), 1e-4f)
        assertEquals(0.69281f, WaveMotion.crestY01(front, animated, 0.5f, 2f), 1e-4f)
        // Reduced amplitude pulls the crest toward its base line.
        assertEquals(0.71282f, WaveMotion.crestY01(front, reduced, 0.5f, 2f), 1e-4f)
        // And time moves it.
        assertEquals(0.74618f, WaveMotion.crestY01(front, animated, 0.5f, 6f), 1e-4f)
        assertEquals(0.65547f, WaveMotion.crestY01(back, reduced, 0.25f, 5f), 1e-4f)
    }

    @Test
    fun `reduced halves the fold alphas`() {
        assertEquals(0.090f, WaveMotion.sheetAlpha(back, animated), 1e-6f)
        assertEquals(0.045f, WaveMotion.sheetAlpha(back, reduced), 1e-6f)
        assertEquals(0.0725f, WaveMotion.edgeAlpha(front, reduced), 1e-6f)
        assertEquals(WaveMotion.sheetAlpha(back, reduced), WaveMotion.sheetAlpha(back, reducedStatic), 0f)
    }

    @Test
    fun `animated time is elapsed seconds times speed`() {
        assertEquals(0f, WaveMotion.timeSeconds(animated, 0L), 0f)
        assertEquals(3f, WaveMotion.timeSeconds(animated, 3000L), 1e-6f)
        assertEquals(1.5f, WaveMotion.timeSeconds(reduced, 3000L), 1e-6f)
    }

    @Test
    fun `frozen modes ignore time and pose at two seconds`() {
        for (params in listOf(static, reducedStatic)) {
            assertFalse(params.animated)
            for (elapsed in listOf(0L, 1234L, 600_000L)) {
                assertEquals(WaveMotion.STATIC_TIME, WaveMotion.timeSeconds(params, elapsed), 0f)
            }
        }
        assertEquals(2.0f, WaveMotion.STATIC_TIME, 0f)
    }

    @Test
    fun `a frozen wave never moves between frames`() {
        val first = WaveMotion.crestY01(back, static, 0.3f, WaveMotion.timeSeconds(static, 0L))
        val later = WaveMotion.crestY01(back, static, 0.3f, WaveMotion.timeSeconds(static, 90_000L))
        assertEquals(first, later, 0f)
    }

    @Test
    fun `a frame that is not live renders the frozen pose even for an animated style`() {
        assertEquals(WaveMotion.STATIC_TIME, WaveMotion.timeSeconds(animated, null), 0f)
        assertTrue(WaveMotion.timeSeconds(animated, 10L) != WaveMotion.STATIC_TIME)
    }
}
