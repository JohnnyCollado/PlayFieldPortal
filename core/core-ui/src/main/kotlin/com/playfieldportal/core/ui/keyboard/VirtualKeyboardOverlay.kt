package com.playfieldportal.core.ui.keyboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private val PanelBelowFieldGap = 8.dp
private val BottomInset = 16.dp
private val PanelToHintGap = 8.dp

/**
 * The height a [KeyboardPlacement.BOTTOM_CENTER] keyboard takes from the bottom of the screen:
 * panel (188 dp), the gap, the prompt pill and the bottom inset, rounded up. A screen keeps what
 * the user is typing into above this line, as `imePadding()` does for the system keyboard.
 */
val VirtualKeyboardBottomReserve = 256.dp

/**
 * The shell's keyboard layer, for every session the settings scaffold does not draw itself:
 * [KeyboardPlacement.BELOW_FIELD] hangs the panel under its field, right edges aligned, with the
 * prompt pill at the bottom-end; [KeyboardPlacement.BOTTOM_CENTER] centres panel and pill at the
 * bottom. Mount it above everything it can cover, outside any scaled-density scope.
 */
@Composable
fun VirtualKeyboardOverlay(controller: VirtualKeyboardController, modifier: Modifier = Modifier) {
    val session by controller.session.collectAsState()
    val open = session ?: return
    when (open.placement) {
        KeyboardPlacement.SETTINGS_FOOTER -> Unit
        KeyboardPlacement.BOTTOM_CENTER -> Box(modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = BottomInset),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(PanelToHintGap),
            ) {
                VirtualKeyboardPanel(open.keyboard)
                VirtualKeyboardHintBar()
            }
        }
        KeyboardPlacement.BELOW_FIELD -> Box(modifier.fillMaxSize()) {
            val anchor = open.anchor
            VirtualKeyboardPanel(
                open.keyboard,
                modifier = if (anchor == null) {
                    Modifier.align(Alignment.Center)
                } else {
                    Modifier.layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                        layout(placeable.width, placeable.height) {
                            placeable.place(
                                x = (anchor.right - placeable.width).roundToInt(),
                                y = (anchor.bottom + PanelBelowFieldGap.toPx()).roundToInt(),
                            )
                        }
                    }
                },
            )
            VirtualKeyboardHintBar(Modifier.align(Alignment.BottomEnd).padding(BottomInset))
        }
    }
}
