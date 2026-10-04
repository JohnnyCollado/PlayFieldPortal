package com.playfieldportal.core.ui.motion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A full-screen layer that plays its own motion (the Display Settings wallpaper preview) covers the
 * shell's motion wallpaper, so only one decoder runs. Holds are counted, so two never clear each other.
 */
class MotionCoverTest {

    @Test
    fun `a hold covers until it is released`() {
        val cover = MotionCover()
        assertFalse(cover.covered.value)
        val hold = cover.hold()
        assertTrue(cover.covered.value)
        hold.release()
        assertFalse(cover.covered.value)
    }

    @Test
    fun `one holder releasing does not uncover another`() {
        val cover = MotionCover()
        val a = cover.hold()
        val b = cover.hold()
        a.release()
        assertTrue(cover.covered.value)
        b.release()
        assertFalse(cover.covered.value)
    }

    @Test
    fun `releasing twice is harmless`() {
        val cover = MotionCover()
        val a = cover.hold()
        val b = cover.hold()
        a.release()
        a.release()
        assertTrue(cover.covered.value)
        b.release()
        assertFalse(cover.covered.value)
    }
}
