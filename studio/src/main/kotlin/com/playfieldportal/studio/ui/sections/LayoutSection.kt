package com.playfieldportal.studio.ui.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.studio.PreviewAdjustStore
import com.playfieldportal.studio.StudioState
import com.playfieldportal.studio.StudioViewModel
import kotlin.math.roundToInt

/** Slider notches between [min] and [max] for a grid of [step] (Compose counts the gaps, not the ends). */
private fun notches(min: Float, max: Float, step: Float): Int = ((max - min) / step).roundToInt() - 1

@Composable
fun LayoutSection(
    state: StudioState,
    viewModel: StudioViewModel,
    adjustStore: PreviewAdjustStore,
    onAdjustOnPreview: () -> Unit,
) {
    // The XMB's sizes are the device's own (Adjust XMB Layout), never the theme's: preview only here.
    SectionColumn {
        PreviewAdjustControls(adjustStore, onAdjustOnPreview)
    }
}

/** "Preview with my layout": mirrors the launcher's Adjust XMB Layout; preview only, never in the file. */
@Composable
private fun PreviewAdjustControls(store: PreviewAdjustStore, onAdjustOnPreview: () -> Unit) {
    val adjust by store.adjust.collectAsState()
    val enabled by store.enabled.collectAsState()
    HeadingWithInfo("Preview with my layout", "On the device these live in ${DeviceHints.ADJUST_LAYOUT}.")
    MutedText("Preview only — never saved in the theme. Remembered on this computer.", 11)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Switch(checked = enabled, onCheckedChange = store::setEnabled)
        Text("Use my layout in the preview", fontSize = 13.sp)
    }
    AdjustSlider("Scale", "×%.2f".format(adjust.scale), adjust.scale, PreviewAdjustStore.SCALE_MIN,
        PreviewAdjustStore.SCALE_MAX, PreviewAdjustStore.SCALE_STEP, enabled, store::setScale)
    AdjustSlider("Horizontal", percent(adjust.barLeftFraction), adjust.barLeftFraction, PreviewAdjustStore.LEFT_MIN,
        PreviewAdjustStore.LEFT_MAX, PreviewAdjustStore.POSITION_STEP, enabled, store::setLeft)
    AdjustSlider("Vertical", percent(adjust.barTopFraction), adjust.barTopFraction, PreviewAdjustStore.TOP_MIN,
        PreviewAdjustStore.TOP_MAX, PreviewAdjustStore.POSITION_STEP, enabled, store::setTop)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { store.applyBiblicallyAccurate(); store.setEnabled(true) }) {
            Text("Biblically Accurate preview", fontSize = 12.sp)
        }
        OutlinedButton(onClick = store::reset) { Text("Reset", fontSize = 12.sp) }
    }
    // The launcher's Adjust XMB Layout, drawn over the preview: arrows / Q E / sliders, Save or Cancel.
    OutlinedButton(onClick = onAdjustOnPreview) { Text("Adjust on preview", fontSize = 12.sp) }
}

private fun percent(fraction: Float): String = "${(fraction * 100).roundToInt()}%"

@Composable
private fun AdjustSlider(
    label: String,
    valueText: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    Column {
        Text("$label — $valueText", fontSize = 12.sp)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = min..max,
            steps = notches(min, max, step),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
