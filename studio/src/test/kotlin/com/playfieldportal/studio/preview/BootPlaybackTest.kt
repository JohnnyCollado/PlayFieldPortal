package com.playfieldportal.studio.preview

import com.playfieldportal.studio.preview.GameBootTimeline.amplitudeAt
import com.playfieldportal.themekit.UiMediaLimits
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** TS-33: the boot / GameBoot playback state machine and the built-in sequences' timelines. */
class BootPlaybackTest {

    private val clipA = File("a.mp4")
    private val clipB = File("b.mp4")

    @Test
    fun `nothing plays at first`() {
        assertFalse(BootPlaybackState.IDLE.isPlaying)
    }

    @Test
    fun `play starts the chosen kind with its clip or the built-in`() {
        val custom = BootPlayback.play(BootKind.BOOT, clipA)
        assertEquals(BootKind.BOOT, custom.kind)
        assertEquals(clipA, custom.clip)
        assertEquals(null, BootPlayback.play(BootKind.GAMEBOOT, null).clip)
    }

    @Test
    fun `playing another kind replaces the running one`() {
        assertTrue(BootPlayback.play(BootKind.GAMEBOOT, null).isPlaying)
        assertEquals(BootPlaybackState(BootKind.BOOT, clipA), BootPlayback.play(BootKind.BOOT, clipA))
    }

    @Test
    fun `pressing the playing button stops it, the other one switches`() {
        val playing = BootPlayback.play(BootKind.BOOT, null)
        assertEquals(BootPlaybackState.IDLE, BootPlayback.toggle(playing, BootKind.BOOT, null))
        assertEquals(BootKind.GAMEBOOT, BootPlayback.toggle(playing, BootKind.GAMEBOOT, null).kind)
        assertEquals(BootKind.BOOT, BootPlayback.toggle(BootPlaybackState.IDLE, BootKind.BOOT, null).kind)
    }

    @Test
    fun `finish returns to idle`() {
        assertEquals(BootPlaybackState.IDLE, BootPlayback.finish())
    }

    @Test
    fun `a running clip stops when the document stops holding it`() {
        val playing = BootPlayback.play(BootKind.BOOT, clipA)
        // Unchanged: keeps playing.
        assertEquals(playing, BootPlayback.reconcile(playing) { if (it == BootKind.BOOT) clipA else null })
        // Cleared, replaced, or a different document: stops.
        assertEquals(BootPlaybackState.IDLE, BootPlayback.reconcile(playing) { null })
        assertEquals(BootPlaybackState.IDLE, BootPlayback.reconcile(playing) { if (it == BootKind.BOOT) clipB else null })
    }

    @Test
    fun `a built-in sequence stops when a clip appears for its kind`() {
        val builtIn = BootPlayback.play(BootKind.GAMEBOOT, null)
        assertEquals(builtIn, BootPlayback.reconcile(builtIn) { null })
        assertEquals(BootPlaybackState.IDLE, BootPlayback.reconcile(builtIn) { clipA })
    }

    @Test
    fun `an idle state is left alone`() {
        assertEquals(BootPlaybackState.IDLE, BootPlayback.reconcile(BootPlaybackState.IDLE) { clipA })
    }

    @Test
    fun `slot keys match the theme media slots`() {
        assertEquals("boot_video", BootKind.BOOT.slotKey)
        assertEquals("gameboot_video", BootKind.GAMEBOOT.slotKey)
    }

    @Test
    fun `custom clips play up to the launcher's cap and built-ins run their own length`() {
        assertEquals(UiMediaLimits.BOOT_MAX_MS, BootPlayback.clipCapMs(BootKind.BOOT))
        assertEquals(UiMediaLimits.GAMEBOOT_CLIP_MAX_MS, BootPlayback.clipCapMs(BootKind.GAMEBOOT))
        assertEquals(3_600L, BootPlayback.builtInMs(BootKind.BOOT))
        assertEquals(5_000L, BootPlayback.builtInMs(BootKind.GAMEBOOT))
    }

    @Test
    fun `clips and gameboot hide the frame throughout, the built-in boot until its fade-out`() {
        val clip = File("boot.mp4")
        for (ms in listOf(0L, 3_000L, 9_000L)) {
            assertTrue(BootPlayback.coversFrame(BootKind.BOOT, clip, ms))
            assertTrue(BootPlayback.coversFrame(BootKind.GAMEBOOT, clip, ms))
            assertTrue(BootPlayback.coversFrame(BootKind.GAMEBOOT, null, ms))
        }
        assertTrue(BootPlayback.coversFrame(BootKind.BOOT, null, 0L))
        assertTrue(BootPlayback.coversFrame(BootKind.BOOT, null, 2_999L))
        assertEquals(1f, BootTimeline.at(2_999L).overlayAlpha, "fully opaque up to the fade-out")
        assertFalse(BootPlayback.coversFrame(BootKind.BOOT, null, 3_000L))
        assertFalse(BootPlayback.coversFrame(BootKind.BOOT, null, 3_600L))
    }

    // ── Boot: logo scale, then alpha, hold, overlay fade ─────────────────────

    @Test
    fun `the boot logo scales up, fades in, holds, then the overlay dissolves`() {
        val start = BootTimeline.at(0)
        assertEquals(0.92f, start.logoScale, 1e-4f)
        assertEquals(0f, start.logoAlpha, 1e-4f)
        assertEquals(1f, start.overlayAlpha, 1e-4f)

        val scaled = BootTimeline.at(800)
        assertEquals(1f, scaled.logoScale, 1e-4f)
        assertEquals(0f, scaled.logoAlpha, 1e-4f, "alpha only starts once the scale has finished (sequential, as on the device)")

        val shown = BootTimeline.at(1_600)
        assertEquals(1f, shown.logoAlpha, 1e-4f)
        assertEquals(1f, BootTimeline.at(3_000).overlayAlpha, 1e-4f)

        val end = BootTimeline.at(BootTimeline.BOOT_TOTAL_MS)
        assertEquals(0f, end.overlayAlpha, 1e-4f)
        assertEquals(BootTimeline.FADE_IN_MS * 2 + BootTimeline.HOLD_MS + BootTimeline.FADE_OUT_MS, BootTimeline.BOOT_TOTAL_MS)
    }

    @Test
    fun `the boot overlay only ever fades out`() {
        var last = 1f
        for (ms in 0L..BootTimeline.BOOT_TOTAL_MS step 50) {
            val a = BootTimeline.at(ms).overlayAlpha
            assertTrue(a <= last + 1e-6f)
            last = a
        }
    }

    // ── GameBoot: the launcher's own numbers ─────────────────────────────────

    @Test
    fun `gameboot is five seconds`() {
        assertEquals(5_000L, GameBootTimeline.SEQUENCE_MS)
    }

    @Test
    fun `gameboot comes up out of black across the sound's rise and sinks at the end`() {
        assertEquals(0f, GameBootTimeline.rampAt(900f), 1e-6f)
        assertEquals(0.5f, GameBootTimeline.rampAt(1_300f), 1e-6f)
        assertEquals(1f, GameBootTimeline.rampAt(1_700f), 1e-6f)
        assertEquals(0f, GameBootTimeline.sinkAt(4_400f), 1e-6f)
        assertEquals(0.5f, GameBootTimeline.sinkAt(4_700f), 1e-6f)
        assertEquals(1f, GameBootTimeline.sinkAt(5_000f), 1e-6f)
    }

    @Test
    fun `loudness is the measured envelope`() {
        assertEquals(0.86f, GameBootTimeline.loudnessAt(2_050f), 1e-6f)
        assertEquals(1.00f, GameBootTimeline.loudnessAt(3_100f), 1e-6f)
        assertEquals(0.935f, GameBootTimeline.loudnessAt(3_000f), 1e-4f)
        assertEquals(0f, GameBootTimeline.loudnessAt(5_000f), 1e-6f)
        assertEquals(0f, GameBootTimeline.loudnessAt(9_999f), 1e-6f)
    }

    @Test
    fun `the three sweeps light only inside their windows`() {
        val (scout, main, answer) = GameBootTimeline.SWEEPS
        assertEquals(0f, scout.amplitudeAt(1_000f), 0f)
        assertTrue(scout.amplitudeAt(2_050f) > 0f)
        assertEquals(0.935f, main.amplitudeAt(3_000f), 1e-3f, "mid-window of the full pass: loudness x sin(pi/2) x 1.0")
        assertEquals(0f, main.amplitudeAt(3_600f), 0f)
        assertEquals(0f, answer.amplitudeAt(4_000f), 0f)
        assertTrue(answer.amplitudeAt(4_250f) in 0f..0.26f)
    }
}
