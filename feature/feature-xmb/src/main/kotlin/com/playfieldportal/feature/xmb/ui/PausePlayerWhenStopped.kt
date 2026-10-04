package com.playfieldportal.feature.xmb.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.Player

/**
 * Pauses [player] when the launcher stops (a game or another app in front, or the screen off). The
 * launcher's composition survives ON_STOP, so without this an open video kept decoding — with its
 * sound — behind whatever the user went to. It does not resume on its own: coming back finds the
 * video paused where it was, like any player.
 */
@Composable
fun PausePlayerWhenStopped(player: Player) {
    LifecycleStartEffect(player) {
        onStopOrDispose {
            // Also runs on dispose, after which the owner releases the player; pausing a released
            // player is a no-op, and the guard keeps a misbehaving one from crashing the UI.
            runCatching { player.pause() }
        }
    }
}
