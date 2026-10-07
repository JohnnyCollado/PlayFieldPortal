package com.playfieldportal.core.ui.orientation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.ScreenOrientationMode
import com.playfieldportal.core.ui.components.ControllerPrompt
import com.playfieldportal.core.ui.theme.deriveStorefrontColors

// ── Display ▸ Screen Orientation (issue #21) ─────────────────────────────────
//
// Landscape keeps the manifest's sensorLandscape lock. Follow Device lets the window turn portrait,
// so an automation watching the screen's rotation (Tasker switching launchers) sees it — and PFP,
// which is laid out for landscape only, covers every screen with this prompt until the device
// turns back. The UI underneath stays composed, so rotating back lands exactly where it was.

/** The window orientation [MainActivity] requests for [mode]. */
fun requestedOrientationFor(mode: ScreenOrientationMode): Int = when (mode) {
    ScreenOrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    // FULL_USER, not FULL_SENSOR: the system rotation lock still wins.
    ScreenOrientationMode.FOLLOW_DEVICE -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
}

/** Whether the rotate prompt covers PFP, given [configOrientation] (Configuration.orientation). */
fun showRotatePrompt(mode: ScreenOrientationMode, configOrientation: Int): Boolean =
    mode == ScreenOrientationMode.FOLLOW_DEVICE && configOrientation == Configuration.ORIENTATION_PORTRAIT

/** What a key does while the prompt is up. */
enum class RotatePromptKey {
    SWITCH_LAUNCHER, SWALLOW, IGNORE, PASS;

    /** A bound controller button: it hands the prompt's glyphs to the controller family. */
    val isControllerInput: Boolean get() = this == SWITCH_LAUNCHER || this == SWALLOW
}

/**
 * [action] is the key's bound action, or null when no controller binding claims it; [systemKey]
 * is KeyEvent.isSystem (volume, media, call). Confirm presses the prompt's one button; every
 * other bound button is swallowed so nothing drives the hidden UI; unbound system keys (volume)
 * pass through to the system, and any other unbound key (a keyboard's letters) is dropped.
 */
fun rotatePromptKey(action: GamepadAction?, systemKey: Boolean): RotatePromptKey = when (action) {
    null -> if (systemKey) RotatePromptKey.PASS else RotatePromptKey.IGNORE
    GamepadAction.SELECT -> RotatePromptKey.SWITCH_LAUNCHER
    else -> RotatePromptKey.SWALLOW
}

/**
 * The portrait cover. Opaque and input-eating: no touch reaches the UI underneath. Its one
 * button opens the system's home-app chooser, by tap or by the controller's Confirm.
 * [showControllerGlyph] is resolvedShowTouchButton's inverse — one input family at a time;
 * [onTouchInput] reports every finger on the cover so that family can follow it here too.
 */
@Composable
fun RotateToLandscapeScreen(
    onSwitchLauncher: () -> Unit,
    showControllerGlyph: Boolean,
    onTouchInput: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sf = deriveStorefrontColors()
    val currentOnTouchInput by rememberUpdatedState(onTouchInput)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(sf.backgroundDeep, sf.backgroundMid)))
            // Eat every pointer so nothing behind the cover reacts to a stray finger. Unconsumed
            // not required, so a tap on the button still reports the touch.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    currentOnTouchInput()
                    down.consume()
                }
            }
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
    ) {
        RotateGlyph(color = sf.textPrimary)
        Text("Rotate to landscape", color = sf.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Light)
        Text(
            "PlayField Portal runs in landscape. Turn your device sideways to continue.",
            color = sf.textSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(sf.searchField)
                .border(1.dp, sf.searchBorder, RoundedCornerShape(8.dp))
                .clickable(onClick = onSwitchLauncher)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text("⌂", color = sf.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text("Switch Launcher", color = sf.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (showControllerGlyph) {
                Spacer(Modifier.width(8.dp))
                ControllerPrompt(action = GamepadAction.SELECT, label = "", glyphSize = 18.dp, spacing = 0.dp)
            }
        }
    }
}

/** A faint portrait phone, the landscape phone it turns into, and the turning arrow. */
@Composable
private fun RotateGlyph(color: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.size(84.dp)) {
        val u = size.width / 84f
        val stroke = Stroke(width = 3f * u, cap = StrokeCap.Round)
        drawRoundRect(color.copy(alpha = 0.45f), Offset(30 * u, 14 * u), Size(24 * u, 42 * u), CornerRadius(4 * u), style = stroke)
        drawRoundRect(color, Offset(18 * u, 44 * u), Size(48 * u, 26 * u), CornerRadius(4 * u), style = stroke)
        drawArc(color, startAngle = -80f, sweepAngle = 70f, useCenter = false,
            topLeft = Offset(48 * u, 18 * u), size = Size(24 * u, 24 * u), style = stroke)
    }
}
