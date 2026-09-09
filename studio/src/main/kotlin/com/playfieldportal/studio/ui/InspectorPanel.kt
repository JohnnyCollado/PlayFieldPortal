package com.playfieldportal.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
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
import com.playfieldportal.studio.IconColorChoice
import com.playfieldportal.studio.TextColorChoice
import com.playfieldportal.studio.StudioState
import com.playfieldportal.studio.StudioViewModel
import com.playfieldportal.studio.io.PtfConversion
import com.playfieldportal.themekit.PfpThemeManifest
import com.playfieldportal.themekit.PfpThemeSource

/** Theme tab: name, accent, icon color, wave style, wallpaper, motion, provenance. */
@Composable
fun InspectorPanel(
    state: StudioState,
    viewModel: StudioViewModel,
    onChooseWallpaper: () -> Unit,
    onChooseVideo: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::setName,
            label = { Text("Theme name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionLabel("Accent color")
        SwatchGrid(selected = state.accentArgb, onPick = viewModel::setAccent)
        HexField(
            label = "Custom accent",
            argb = state.accentArgb,
            onValid = viewModel::setAccent,
        )
        ExpandablePicker(argb = state.accentArgb, onChange = viewModel::setAccent)

        HorizontalDivider()

        SectionLabel("Icon color")
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = state.iconColor is IconColorChoice.Auto,
                onClick = { viewModel.setIconColor(IconColorChoice.Auto) },
            )
            Text("Auto (follows the theme)", fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = state.iconColor is IconColorChoice.Custom,
                onClick = { viewModel.setIconColor(IconColorChoice.Custom(0xFFFFFFFF.toInt())) },
            )
            Text("Custom", fontSize = 13.sp)
        }
        if (state.iconColor is IconColorChoice.Custom) {
            val customArgb = (state.iconColor as IconColorChoice.Custom).argb
            HexField(
                label = "Icon color",
                argb = customArgb,
                onValid = { viewModel.setIconColor(IconColorChoice.Custom(it)) },
            )
            ExpandablePicker(argb = customArgb, onChange = { viewModel.setIconColor(IconColorChoice.Custom(it)) })
            if (com.playfieldportal.themekit.WallpaperMetrics.luminance(customArgb) <
                com.playfieldportal.themekit.WallpaperMetrics.DARK_ICON_LUMINANCE
            ) {
                HintText("Dark icon color — icons may be hard to see over the wallpaper scrim.")
            }
        }

        HorizontalDivider()

        SectionLabel("Text color")
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = state.textColor is TextColorChoice.Auto,
                onClick = { viewModel.setTextColor(TextColorChoice.Auto) },
            )
            Text("Auto (follows the theme)", fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = state.textColor is TextColorChoice.Custom,
                onClick = { viewModel.setTextColor(TextColorChoice.Custom(0xFFFFFFFF.toInt())) },
            )
            Text("Custom", fontSize = 13.sp)
        }
        if (state.textColor is TextColorChoice.Custom) {
            val customArgb = (state.textColor as TextColorChoice.Custom).argb
            HexField(
                label = "Text color",
                argb = customArgb,
                onValid = { viewModel.setTextColor(TextColorChoice.Custom(it)) },
            )
            ExpandablePicker(argb = customArgb, onChange = { viewModel.setTextColor(TextColorChoice.Custom(it)) })
            // The preview renders this colour AS PICKED. The launcher may lightness-clamp it at
            // apply time when it fails 4.5:1 on the real backdrop — the contrast engine that
            // decides is Android-side today, so the desktop preview cannot show the adjusted
            // colour yet. See docs/plans/text-legibility-font-color-plan.md.
            HintText("Preview shows this colour as picked; the launcher may adjust it for contrast.")
        }

        HorizontalDivider()

        SectionLabel("Wave style")
        val styles = listOf(
            PfpThemeManifest.WAVE_ANIMATED to "Animated",
            PfpThemeManifest.WAVE_STATIC to "Static",
            PfpThemeManifest.WAVE_REDUCED to "Reduced",
        )
        // Plain toggle buttons instead of experimental M3 SegmentedButton — its selection
        // animation is a node-chain crash suspect on CMP 1.6.11 desktop.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            styles.forEach { (value, label) ->
                val selected = state.waveStyle == value
                if (selected) {
                    androidx.compose.material3.Button(onClick = {}) { Text(label, fontSize = 12.sp) }
                } else {
                    OutlinedButton(onClick = { viewModel.setWaveStyle(value) }) { Text(label, fontSize = 12.sp) }
                }
            }
        }
        Text(
            "The preview always shows a frozen wave; the style plays on the device.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        SectionLabel("Wallpaper")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onChooseWallpaper) { Text("Choose…") }
            if (state.wallpaperPng != null) {
                OutlinedButton(onClick = viewModel::restageEmbeddedWallpaper) { Text("Re-crop…") }
                OutlinedButton(onClick = viewModel::clearWallpaper) { Text("Clear") }
            }
        }
        Text(
            state.wallpaperFileName ?: if (state.wallpaperPng != null) "(embedded image)" else "None — live wave background",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.wallpaperBusy) {
            HintText("Busy wallpaper — labels may be hard to read. Softer, low-contrast images work best.")
        }

        HorizontalDivider()

        SectionLabel("Motion wallpaper")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onChooseVideo) { Text("Choose…") }
            if (state.motionFile != null) {
                OutlinedButton(onClick = viewModel::clearMotion) { Text("Clear") }
            }
        }
        Text(
            state.motionFileName ?: "None — still wallpaper only",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.motionFile != null) {
            // The still IS the video's poster: whenever playback is frozen on device — battery
            // saver, a game launching, or a Static wave style — the launcher shows it instead.
            // Without the hint, authors read the frozen poster as the video being broken.
            HintText("The still wallpaper above is the video's poster — it shows whenever playback is frozen (battery saver, a game, or a Static wave).")
        }
        if (state.motionFile != null && state.wallpaperPng == null) {
            HintText("No still wallpaper set — the motion entry will not be exported until one is.")
        }

        HorizontalDivider()

        SectionLabel("Layout")
        Text(
            "Crossbar position — ${(state.layout.barTopFraction * 100).toInt()}%",
            fontSize = 13.sp,
        )
        androidx.compose.material3.Slider(
            value = state.layout.barTopFraction,
            onValueChange = viewModel::setBarTopFraction,
            valueRange = com.playfieldportal.themekit.XmbLayoutSpecCodec.BAR_TOP_MIN..
                com.playfieldportal.themekit.XmbLayoutSpecCodec.BAR_TOP_MAX,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = viewModel::detectBarTop,
                enabled = state.wallpaperPng != null && !state.busy,
            ) { Text("Detect from wallpaper") }
            if (state.layout != com.playfieldportal.themekit.XmbLayoutSpec.DEFAULT) {
                OutlinedButton(onClick = viewModel::resetLayout) { Text("Reset") }
            }
        }
        HintText("Detect finds the dark band PSP wallpapers bake in and seats the crossbar on it.")

        val source = state.source
        if (source != null && source.type == PfpThemeSource.TYPE_PTF_IMPORT) {
            HorizontalDivider()
            Text(
                buildString {
                    append("Imported from ${source.file ?: "a PSP theme"}")
                    source.firmware?.let { append(" — firmware $it") }
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

/** Non-blocking legibility hint — advice, never a gate. */
@Composable
private fun HintText(text: String) {
    Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
}

/** "Pick…" toggle that expands the HSV picker under a hex field. */
@Composable
private fun ExpandablePicker(argb: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.material3.TextButton(onClick = { open = !open }) {
        Text(if (open) "Hide color picker" else "Color picker…", fontSize = 12.sp)
    }
    if (open) {
        HsvColorPicker(argb = argb, onChange = onChange)
    }
}

@Composable
private fun SwatchGrid(selected: Int, onPick: (Int) -> Unit) {
    // 12 presets, two rows of 6.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PRESET_ACCENTS.chunked(6).forEach { rowSwatches ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowSwatches.forEach { preset ->
                    val isSelected = preset.argb == selected
                    androidx.compose.foundation.layout.Box(
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
private fun HexField(label: String, argb: Int, onValid: (Int) -> Unit) {
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
        androidx.compose.foundation.layout.Box(
            Modifier.size(28.dp).background(Color(parsed ?: argb), CircleShape)
                .border(1.dp, Color(0x33FFFFFF), CircleShape),
        )
    }
}
