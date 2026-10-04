package com.playfieldportal.feature.xmb.viewmodel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The idle-hint clock ticks only while the launcher is visible. It used to wake every 500 ms
 * forever — behind games and with the screen off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VisibleTickerTest {

    @Test
    fun `ticks at its period while visible`() = runTest {
        val visible = MutableStateFlow(true)
        var ticks = 0
        val job = launch { tickWhileVisible(visible, periodMs = 500) { ticks++ } }
        advanceTimeBy(2_100)
        runCurrent()
        assertEquals(4, ticks)
        job.cancel()
    }

    @Test
    fun `does not tick while hidden, and resumes when visible again`() = runTest {
        val visible = MutableStateFlow(true)
        var ticks = 0
        val job = launch { tickWhileVisible(visible, periodMs = 500) { ticks++ } }
        advanceTimeBy(1_100); runCurrent()
        assertEquals(2, ticks)

        visible.value = false
        advanceTimeBy(60_000); runCurrent()
        // At most the tick already in flight when it was hidden is dropped, never delivered.
        assertEquals(2, ticks)

        visible.value = true
        advanceTimeBy(1_100); runCurrent()
        assertEquals(4, ticks)
        job.cancel()
    }
}
