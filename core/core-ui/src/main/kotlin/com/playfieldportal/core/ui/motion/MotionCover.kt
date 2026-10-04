package com.playfieldportal.core.ui.motion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lets a full-screen layer that plays its own motion — the Display Settings wallpaper preview —
 * cover the shell's motion wallpaper, so two video decoders never run at once. The shell reads
 * [covered] as one more "covered" input; layers take a [hold] while they are up.
 *
 * Holds are counted, so one layer closing never uncovers another, and a hold releases only once.
 */
class MotionCover {
    private val count = MutableStateFlow(0)
    private val _covered = MutableStateFlow(false)
    val covered: StateFlow<Boolean> = _covered.asStateFlow()

    fun interface Hold { fun release() }

    fun hold(): Hold {
        update(+1)
        val released = AtomicBoolean(false)
        return Hold { if (released.compareAndSet(false, true)) update(-1) }
    }

    private fun update(delta: Int) {
        synchronized(this) {
            count.value = (count.value + delta).coerceAtLeast(0)
            _covered.value = count.value > 0
        }
    }

    companion object {
        /** The app-wide cover the shell reads (the wallpaper preview lives in another module). */
        val Shared = MotionCover()
    }
}
