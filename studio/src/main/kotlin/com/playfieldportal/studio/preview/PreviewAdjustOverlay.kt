package com.playfieldportal.studio.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.studio.PreviewAdjustStore
import com.playfieldportal.themekit.XmbLayoutAdjust
import java.util.Locale
import kotlin.math.roundToInt

/*
 * "Adjust on preview": a replica of feature-xmb XmbLayoutAdjustOverlay drawn over the preview. The
 * draft lives here (not in the store) until Save, so Cancel has nothing to revert; the preview shows
 * the draft live while the overlay is open. The values are the Studio's preview-only layout.
 */

data class AdjustOverlayState(
    val draft: XmbLayoutAdjust,
    val slidersVisible: Boolean = false,
)

sealed interface AdjustAction {
    data object MoveLeft : AdjustAction
    data object MoveRight : AdjustAction
    data object MoveUp : AdjustAction
    data object MoveDown : AdjustAction
    data object ScaleDown : AdjustAction
    data object ScaleUp : AdjustAction
    data object Reset : AdjustAction
    data object ToggleSliders : AdjustAction
    data object Save : AdjustAction
    data object Cancel : AdjustAction
    data class SetScale(val value: Float) : AdjustAction
    data class SetHorizontal(val value: Float) : AdjustAction
    data class SetVertical(val value: Float) : AdjustAction
}

/** What an action did: the overlay carries on, closed with a saved draft, or closed without one. */
sealed interface AdjustStep {
    data class Editing(val state: AdjustOverlayState) : AdjustStep
    data class Saved(val adjust: XmbLayoutAdjust) : AdjustStep
    data object Cancelled : AdjustStep
}

object AdjustOverlay {
    // XMBViewModel.nudgeXmbLayout*: 1 % per horizontal / vertical step, 0.02 per scale step.
    private const val POSITION_NUDGE = 0.01f
    private const val SCALE_NUDGE = PreviewAdjustStore.SCALE_STEP

    /** Starts from what the preview currently uses: the saved preview layout. */
    fun open(store: PreviewAdjustStore): AdjustOverlayState = AdjustOverlayState(store.adjust.value)

    fun actionFor(key: Key): AdjustAction? = when (key) {
        Key.DirectionLeft -> AdjustAction.MoveLeft
        Key.DirectionRight -> AdjustAction.MoveRight
        Key.DirectionUp -> AdjustAction.MoveUp
        Key.DirectionDown -> AdjustAction.MoveDown
        Key.Q -> AdjustAction.ScaleDown
        Key.E -> AdjustAction.ScaleUp
        Key.R -> AdjustAction.Reset
        Key.S -> AdjustAction.ToggleSliders
        Key.Enter, Key.NumPadEnter -> AdjustAction.Save
        Key.Escape -> AdjustAction.Cancel
        else -> null
    }

    fun step(state: AdjustOverlayState, action: AdjustAction): AdjustStep {
        val d = state.draft
        fun edit(draft: XmbLayoutAdjust) = AdjustStep.Editing(state.copy(draft = draft))
        return when (action) {
            AdjustAction.MoveLeft -> edit(d.copy(barLeftFraction = left(d.barLeftFraction - POSITION_NUDGE, d)))
            AdjustAction.MoveRight -> edit(d.copy(barLeftFraction = left(d.barLeftFraction + POSITION_NUDGE, d)))
            AdjustAction.MoveUp -> edit(d.copy(barTopFraction = top(d.barTopFraction - POSITION_NUDGE, d)))
            AdjustAction.MoveDown -> edit(d.copy(barTopFraction = top(d.barTopFraction + POSITION_NUDGE, d)))
            AdjustAction.ScaleDown -> edit(d.copy(scale = scale(d.scale - SCALE_NUDGE, d)))
            AdjustAction.ScaleUp -> edit(d.copy(scale = scale(d.scale + SCALE_NUDGE, d)))
            is AdjustAction.SetScale -> edit(d.copy(scale = scale(action.value, d)))
            is AdjustAction.SetHorizontal -> edit(d.copy(barLeftFraction = left(action.value, d)))
            is AdjustAction.SetVertical -> edit(d.copy(barTopFraction = top(action.value, d)))
            AdjustAction.Reset -> edit(XmbLayoutAdjust.DEFAULT)
            AdjustAction.ToggleSliders -> AdjustStep.Editing(state.copy(slidersVisible = !state.slidersVisible))
            AdjustAction.Save -> AdjustStep.Saved(d)
            AdjustAction.Cancel -> AdjustStep.Cancelled
        }
    }

    // The store's own grid and bounds, so a saved draft is exactly what the store would hold.
    private fun scale(v: Float, d: XmbLayoutAdjust) = PreviewAdjustStore.snap(
        v, PreviewAdjustStore.SCALE_MIN, PreviewAdjustStore.SCALE_MAX, PreviewAdjustStore.SCALE_STEP,
    ) ?: d.scale

    private fun left(v: Float, d: XmbLayoutAdjust) = PreviewAdjustStore.snap(
        v, PreviewAdjustStore.LEFT_MIN, PreviewAdjustStore.LEFT_MAX, PreviewAdjustStore.POSITION_STEP,
    ) ?: d.barLeftFraction

    private fun top(v: Float, d: XmbLayoutAdjust) = PreviewAdjustStore.snap(
        v, PreviewAdjustStore.TOP_MIN, PreviewAdjustStore.TOP_MAX, PreviewAdjustStore.POSITION_STEP,
    ) ?: d.barTopFraction

    /** Writes a saved draft to the store and turns "use my layout" on: a layout you saved is one you want to see. */
    fun commit(store: PreviewAdjustStore, adjust: XmbLayoutAdjust) {
        store.setScale(adjust.scale)
        store.setLeft(adjust.barLeftFraction)
        store.setTop(adjust.barTopFraction)
        store.setEnabled(true)
    }

    /** The live read-out, as the launcher prints it. */
    fun readout(draft: XmbLayoutAdjust): String =
        "Scale ${String.format(Locale.ROOT, "%.2f", draft.scale)}x    " +
            "Horizontal ${(draft.barLeftFraction * 100).roundToInt()}%    " +
            "Vertical ${(draft.barTopFraction * 100).roundToInt()}%"
}

// ── Rendering ────────────────────────────────────────────────────────────────

private val Accent = Color(0xFF3A82F6)
private val Muted = Color(0xFFB9C6DC)

/** XmbLayoutAdjustOverlay: a consuming scrim over the frame and a bottom panel with read-out, prompts and controls. */
@Composable
fun AdjustOverlayPanel(state: AdjustOverlayState, onAction: (AdjustAction) -> Unit) {
    val draft = state.draft
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // Keeps the editor modal: taps above the panel never reach the cross behind it.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x22000000))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { /* swallow */ },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .background(Color(0xF20B1220), RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Adjust XMB Layout", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(AdjustOverlay.readout(draft), color = Muted, fontSize = 13.sp)
            // The launcher's controller prompts, then the keyboard equivalents this window answers to.
            Text(
                "✥ Move    L1 R1 Scale    △ Reset    ▢ Sliders    ✕ Save    ○ Cancel",
                color = Muted.copy(alpha = 0.6f),
                fontSize = 11.sp,
            )
            Text(
                "Keys: arrows move · Q E scale · R reset · S sliders · Enter save · Esc cancel",
                color = Muted.copy(alpha = 0.6f),
                fontSize = 11.sp,
            )
            if (state.slidersVisible) {
                AxisSlider("Scale", draft.scale, PreviewAdjustStore.SCALE_MIN, PreviewAdjustStore.SCALE_MAX) {
                    onAction(AdjustAction.SetScale(it))
                }
                AxisSlider("Horizontal", draft.barLeftFraction, PreviewAdjustStore.LEFT_MIN, PreviewAdjustStore.LEFT_MAX) {
                    onAction(AdjustAction.SetHorizontal(it))
                }
                AxisSlider("Vertical", draft.barTopFraction, PreviewAdjustStore.TOP_MIN, PreviewAdjustStore.TOP_MAX) {
                    onAction(AdjustAction.SetVertical(it))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val outlined = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                OutlinedButton(onClick = { onAction(AdjustAction.ToggleSliders) }, colors = outlined) {
                    Text(if (state.slidersVisible) "Hide Sliders" else "Sliders")
                }
                OutlinedButton(onClick = { onAction(AdjustAction.Reset) }, colors = outlined) { Text("Reset") }
                Box(Modifier.width(1.dp)) // spacer flex
                OutlinedButton(onClick = { onAction(AdjustAction.Cancel) }, colors = outlined) { Text("Cancel") }
                Button(
                    onClick = { onAction(AdjustAction.Save) },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun AxisSlider(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column {
        Text(label, color = Muted, fontSize = 12.sp)
        Slider(
            value = value.coerceIn(min, max),
            onValueChange = onChange,
            valueRange = min..max,
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
        )
    }
}
