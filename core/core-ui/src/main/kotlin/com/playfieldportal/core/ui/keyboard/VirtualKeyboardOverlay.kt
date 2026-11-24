package com.playfieldportal.core.ui.keyboard

import android.content.res.Configuration
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
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
private val BottomInset = 8.dp
private val PanelToHintGap = 4.dp
private val HintBarHeight = 36.dp

/**
 * The share of the screen's height the system keyboard took when last seen, per orientation. PFP's
 * own keyboard sizes itself to the same share (2026-11-24, owner's call), so switching keyboards does
 * not move what is being typed into. Updated whenever a prompt sees the system keyboard; until then
 * the defaults stand.
 */
object SystemKeyboardShare {
    /** Gboard on the Odin 3 in landscape: 237dp of 468, measured 2026-11-24. */
    const val LANDSCAPE_DEFAULT = 0.506f
    /** A phone keyboard in portrait, about 0.36 of the height (an estimate, not measured). */
    const val PORTRAIT_DEFAULT = 0.36f

    var landscape by mutableFloatStateOf(LANDSCAPE_DEFAULT)
        private set
    var portrait by mutableFloatStateOf(PORTRAIT_DEFAULT)
        private set

    /** Records a system keyboard [keyboard] tall on a screen [screenHeight] tall. Ignores nonsense. */
    fun record(keyboard: Dp, screenHeight: Dp, isLandscape: Boolean) {
        if (keyboard <= 0.dp || screenHeight <= 0.dp) return
        val share = (keyboard / screenHeight).coerceIn(0.2f, 0.7f)
        if (isLandscape) landscape = share else portrait = share
    }

    fun of(isLandscape: Boolean): Float = if (isLandscape) landscape else portrait
}

/** The bottom keyboard on a screen: its key height, and the room it takes from the bottom. */
internal data class BottomKeyboardSize(val keyHeight: Dp, val reserve: Dp)

/**
 * The bottom keyboard sized to take [share] of [screenHeight], as the system keyboard does: inset,
 * hint bar on top (where Gboard has its toolbar) and four rows. Keys never go below
 * [VirtualKeyboardMetrics.MinMatchedKeyHeight], so on a short screen it can stand a little taller.
 */
internal fun bottomKeyboardSize(screenHeight: Dp, share: Float): BottomKeyboardSize {
    val chrome = BottomInset + HintBarHeight + PanelToHintGap
    val key = VirtualKeyboardMetrics.keyHeightFor(screenHeight * share - chrome)
    return BottomKeyboardSize(key, chrome + VirtualKeyboardMetrics.panelHeight(key))
}

@Composable
private fun currentBottomKeyboardSize(): BottomKeyboardSize {
    val config = LocalConfiguration.current
    val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
    return bottomKeyboardSize(config.screenHeightDp.dp, SystemKeyboardShare.of(landscape))
}

/**
 * The height a [KeyboardPlacement.BOTTOM_CENTER] keyboard takes from the bottom of the screen on
 * this screen. A screen keeps what the user is typing into above this line, as `imePadding()` does
 * for the system keyboard.
 */
@Composable
fun virtualKeyboardBottomReserve(): Dp = currentBottomKeyboardSize().reserve

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
                // Hints on top, where Gboard has its toolbar; keys sized to the system keyboard's height.
                VirtualKeyboardHintBar(Modifier.height(HintBarHeight))
                VirtualKeyboardPanel(open.keyboard, keyHeight = currentBottomKeyboardSize().keyHeight)
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
