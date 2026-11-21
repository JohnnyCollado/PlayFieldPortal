package com.playfieldportal.core.ui.sound

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Hands the window's view to [player] so the device's haptic ticks have somewhere to be delivered
 * (`View.performHapticFeedback` needs no `VIBRATE` permission). Place once near the root of the
 * app's content; it does nothing visible.
 */
@Composable
fun HapticViewHost(player: MenuSoundPlayer) {
    val view = LocalView.current
    DisposableEffect(view, player) {
        player.attachHapticView(view)
        onDispose { player.detachHapticView(view) }
    }
}
