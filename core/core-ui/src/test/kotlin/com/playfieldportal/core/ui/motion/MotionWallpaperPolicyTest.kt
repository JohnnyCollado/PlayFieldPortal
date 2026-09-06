package com.playfieldportal.core.ui.motion

import com.playfieldportal.core.ui.wave.WaveStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the motion-wallpaper freeze contract — the plan's central claim: **when the wave would be
 * frozen, the motion wallpaper is not merely paused, no decoder exists.** Every freeze input must
 * independently force [MotionWallpaperPolicy.Decision.POSTER], and only the all-clear case plays.
 *
 * The composable that consumes [MotionWallpaperPolicy] releases its ExoPlayer whenever the
 * decision is POSTER (leaving the motion branch), so these decisions are literally the difference
 * between "a codec is running" and "a JPEG on screen".
 */
class MotionWallpaperPolicyTest {

    private val allClear = MotionWallpaperPolicy.Inputs(
        hasMotion = true,
        hasPoster = true,
        style = WaveStyle.ANIMATED,
        covered = false,
        throttled = false,
        appVisible = true,
    )

    // ── The all-clear case is the ONLY one that plays ──────────────────────────

    @Test
    fun `all-clear inputs play at full motion`() {
        assertEquals(MotionWallpaperPolicy.Decision.PLAY, MotionWallpaperPolicy.decide(allClear))
    }

    // ── Every freeze input independently forces POSTER ─────────────────────────

    @Test
    fun `covered background forces poster (overlay is opaque above it)`() {
        assertEquals(
            MotionWallpaperPolicy.Decision.POSTER,
            MotionWallpaperPolicy.decide(allClear.copy(covered = true)),
        )
    }

    @Test
    fun `battery-saver or thermal throttle forces poster (no decoder, not paused)`() {
        assertEquals(
            MotionWallpaperPolicy.Decision.POSTER,
            MotionWallpaperPolicy.decide(allClear.copy(throttled = true)),
        )
    }

    @Test
    fun `app backgrounded (ON_STOP) forces poster — the decoder must not outlive the launcher`() {
        assertEquals(
            MotionWallpaperPolicy.Decision.POSTER,
            MotionWallpaperPolicy.decide(allClear.copy(appVisible = false)),
        )
    }

    @Test
    fun `STATIC style forces poster — the user asked for a still background`() {
        assertEquals(
            MotionWallpaperPolicy.Decision.POSTER,
            MotionWallpaperPolicy.decide(allClear.copy(style = WaveStyle.STATIC)),
        )
    }

    @Test
    fun `REDUCED_STATIC style forces poster`() {
        assertEquals(
            MotionWallpaperPolicy.Decision.POSTER,
            MotionWallpaperPolicy.decide(allClear.copy(style = WaveStyle.REDUCED_STATIC)),
        )
    }

    // ── The two remaining WaveStyles modulate playback, never bypass the gate ──

    @Test
    fun `REDUCED style plays slowed (half speed), not frozen`() {
        assertEquals(
            MotionWallpaperPolicy.Decision.PLAY_REDUCED,
            MotionWallpaperPolicy.decide(allClear.copy(style = WaveStyle.REDUCED)),
        )
    }

    @Test
    fun `freeze inputs beat REDUCED style — poster still wins`() {
        assertEquals(
            MotionWallpaperPolicy.Decision.POSTER,
            MotionWallpaperPolicy.decide(allClear.copy(style = WaveStyle.REDUCED, throttled = true)),
        )
    }

    // ── Invalid render states degrade to the poster, never a black screen ──────

    @Test
    fun `motion path without a poster is unrenderable - falls back to poster`() {
        // Invariant enforced at the write sites: motion is never set without its poster. Reading
        // that invalid state must degrade to "no motion" rather than try to recover.
        assertEquals(
            MotionWallpaperPolicy.Decision.POSTER,
            MotionWallpaperPolicy.decide(allClear.copy(hasPoster = false)),
        )
    }

    @Test
    fun `no motion file at all is poster (caller should not have composed the motion branch)`() {
        assertEquals(
            MotionWallpaperPolicy.Decision.POSTER,
            MotionWallpaperPolicy.decide(allClear.copy(hasMotion = false)),
        )
    }

    // ── Combinations of independent freeze inputs all stay POSTER ──────────────

    @Test
    fun `covered plus backgrounded stays poster`() {
        assertEquals(
            MotionWallpaperPolicy.Decision.POSTER,
            MotionWallpaperPolicy.decide(allClear.copy(covered = true, appVisible = false)),
        )
    }

    @Test
    fun `every single-input freeze from the all-clear state stays poster`() {
        val freezes = listOf(
            allClear.copy(covered = true),
            allClear.copy(throttled = true),
            allClear.copy(appVisible = false),
            allClear.copy(style = WaveStyle.STATIC),
            allClear.copy(style = WaveStyle.REDUCED_STATIC),
            allClear.copy(hasPoster = false),
            allClear.copy(hasMotion = false),
        )
        freezes.forEach { input ->
            assertEquals(
                "expected POSTER for $input",
                MotionWallpaperPolicy.Decision.POSTER,
                MotionWallpaperPolicy.decide(input),
            )
        }
    }
}
