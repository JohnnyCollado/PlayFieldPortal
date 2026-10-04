package com.playfieldportal.feature.xmb.viewmodel

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive

/**
 * Calls [tick] every [periodMs] while [visible] is true, and sleeps without waking while it is false.
 * A tick that comes due after the launcher was hidden is dropped, never delivered late.
 */
internal suspend fun tickWhileVisible(visible: StateFlow<Boolean>, periodMs: Long, tick: suspend () -> Unit) {
    while (currentCoroutineContext().isActive) {
        visible.first { it }
        delay(periodMs)
        if (visible.value) tick()
    }
}

/**
 * Whether a music playback emission is worth copying into the XMB state. While the launcher is
 * hidden, a change that is only the half-second position tick is skipped — nothing on screen shows
 * it, and copying it rebuilt the whole UI state twice a second behind games.
 */
internal fun shouldApplyMusicUpdate(
    previous: com.playfieldportal.feature.xmb.music.MusicPlaybackState?,
    next: com.playfieldportal.feature.xmb.music.MusicPlaybackState,
    hostVisible: Boolean,
): Boolean = hostVisible || previous == null || previous.copy(positionMs = 0) != next.copy(positionMs = 0)
