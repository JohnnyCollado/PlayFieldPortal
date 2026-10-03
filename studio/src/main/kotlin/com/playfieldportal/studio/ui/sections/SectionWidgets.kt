package com.playfieldportal.studio.ui.sections

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.studio.io.PtfConversion
import com.playfieldportal.studio.ui.HsvColorPicker
import com.playfieldportal.studio.ui.PRESET_ACCENTS

/** Where the device-side counterparts of Studio controls live; shown in ⓘ tooltips. */
object DeviceHints {
    const val ADJUST_LAYOUT = "Settings › Interface › Display › Adjust XMB Layout"
    const val SOUND = "Settings › Interface › Sound"
    const val BOOT = "Settings › Interface › Display › Show Boot Sequence / GameBoot"
    const val WAVE_FREEZE = "Battery Saver and Thermal Throttle (Settings › Interface › Display) freeze the animated wave on the device."
}

/** Scrolling column every section panel sits in. */
@Composable
fun SectionColumn(content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        content()
    }
}

@Composable
fun SectionHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

/** Non-blocking advice, never a gate. */
@Composable
fun HintText(text: String) {
    Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
}

@Composable
fun MutedText(text: String, fontSize: Int = 12) {
    Text(text, fontSize = fontSize.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The ⓘ glyph: hover to read where the matching setting lives on the device. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InfoGlyph(tooltip: String) {
    TooltipArea(
        tooltip = {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 4.dp,
            ) {
                Text(tooltip, fontSize = 12.sp, modifier = Modifier.padding(8.dp).width(240.dp))
            }
        },
    ) {
        Text("ⓘ", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A section title with an optional ⓘ beside it. */
@Composable
fun HeadingWithInfo(text: String, tooltip: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeading(text)
        InfoGlyph(tooltip)
    }
}

/** Single-select chips; [options] are (value, label). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceChips(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onPick(value) },
                label = { Text(label, fontSize = 12.sp) },
            )
        }
    }
}

/** "Pick…" toggle that expands the HSV picker under a hex field. */
@Composable
fun ExpandablePicker(argb: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text(if (open) "Hide color picker" else "Color picker…", fontSize = 12.sp)
    }
    if (open) HsvColorPicker(argb = argb, onChange = onChange)
}

/** The 12 preset accents, two rows of six. */
@Composable
fun SwatchGrid(selected: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PRESET_ACCENTS.chunked(6).forEach { rowSwatches ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowSwatches.forEach { preset ->
                    val isSelected = preset.argb == selected
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color(preset.argb), CircleShape)
                            .border(
                                width = if (isSelected) 3.dp else 1.dp,
                                color = if (isSelected) Color.White else Color(0x33FFFFFF),
                                shape = CircleShape,
                            )
                            .clickable { onPick(preset.argb) },
                    )
                }
            }
        }
    }
}

/** `#RRGGBB` field that only commits parseable values but lets the user type freely. */
@Composable
fun HexField(label: String, argb: Int, onValid: (Int) -> Unit) {
    var text by remember(argb) { mutableStateOf(PtfConversion.toHexRgb(argb)) }
    val parsed = PtfConversion.parseHexRgb(text)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                PtfConversion.parseHexRgb(it)?.let(onValid)
            },
            label = { Text(label) },
            singleLine = true,
            isError = parsed == null,
            modifier = Modifier.width(160.dp),
        )
        Box(Modifier.size(28.dp).background(Color(parsed ?: argb), CircleShape).border(1.dp, Color(0x33FFFFFF), CircleShape))
    }
}
