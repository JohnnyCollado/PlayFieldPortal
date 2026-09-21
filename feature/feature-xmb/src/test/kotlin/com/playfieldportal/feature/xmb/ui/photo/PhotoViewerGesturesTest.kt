package com.playfieldportal.feature.xmb.ui.photo

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoViewerGesturesTest {
    private val commit = 72f
    private val fling = 420f

    @Test fun `short swipe does not step`() {
        assertEquals(0, photoStepFromSwipe(71f, commit, 0f, fling))
        assertEquals(0, photoStepFromSwipe(-71f, commit, 0f, fling))
    }

    @Test fun `distance commits by direction`() {
        assertEquals(-1, photoStepFromSwipe(72f, commit, 0f, fling))
        assertEquals(1, photoStepFromSwipe(-72f, commit, 0f, fling))
        assertEquals(1, photoStepFromSwipe(-900f, commit, 0f, fling))
    }

    @Test fun `a long drag never skips photos`() {
        assertEquals(1, photoStepFromSwipe(-99999f, commit, 0f, fling))
        assertEquals(-1, photoStepFromSwipe(99999f, commit, 0f, fling))
    }

    @Test fun `fast fling commits below distance`() {
        assertEquals(1, photoStepFromSwipe(-20f, commit, -500f, fling))
        assertEquals(-1, photoStepFromSwipe(20f, commit, 500f, fling))
        assertEquals(0, photoStepFromSwipe(20f, commit, 300f, fling))
    }
}
