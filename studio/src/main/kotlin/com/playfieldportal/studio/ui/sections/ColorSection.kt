package com.playfieldportal.studio.ui.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.studio.IconColorChoice
import com.playfieldportal.studio.StudioState
import com.playfieldportal.studio.StudioViewModel
import com.playfieldportal.studio.TextColorChoice
import com.playfieldportal.themekit.WallpaperMetrics

private const val WHITE = 0xFFFFFFFF.toInt()

@Composable
fun ColorSection(state: StudioState, viewModel: StudioViewModel) {
    SectionColumn {
        SectionHeading("Accent color")
        SwatchGrid(selected = state.accentArgb, onPick = viewModel::setAccent)
        ColorField(label = "Custom accent", argb = state.accentArgb, onChange = viewModel::setAccent)

        HorizontalDivider()

        SectionHeading("Icon color")
        ChoiceRow("Auto (follows the theme)", state.iconColor is IconColorChoice.Auto) {
            viewModel.setIconColor(IconColorChoice.Auto)
        }
        ChoiceRow("White", (state.iconColor as? IconColorChoice.Custom)?.argb == WHITE) {
            viewModel.setIconColor(IconColorChoice.Custom(WHITE))
        }
        ChoiceRow("Custom", state.iconColor.let { it is IconColorChoice.Custom && it.argb != WHITE }) {
            viewModel.setIconColor(IconColorChoice.Custom(CUSTOM_START))
        }
        val iconColor = state.iconColor
        if (iconColor is IconColorChoice.Custom) {
            ColorField("Icon color", iconColor.argb) { viewModel.setIconColor(IconColorChoice.Custom(it)) }
            if (WallpaperMetrics.luminance(iconColor.argb) < WallpaperMetrics.DARK_ICON_LUMINANCE) {
                HintText("Dark icon color — icons may be hard to see over the wallpaper scrim.")
            }
        }

        HorizontalDivider()

        SectionHeading("Text color")
        ChoiceRow("Auto (follows the theme)", state.textColor is TextColorChoice.Auto) {
            viewModel.setTextColor(TextColorChoice.Auto)
        }
        ChoiceRow("Custom", state.textColor is TextColorChoice.Custom) {
            viewModel.setTextColor(TextColorChoice.Custom(WHITE))
        }
        val textColor = state.textColor
        if (textColor is TextColorChoice.Custom) {
            ColorField("Text color", textColor.argb) { viewModel.setTextColor(TextColorChoice.Custom(it)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Switch(
                checked = state.textColorExact == true,
                onCheckedChange = viewModel::setTextColorExact,
                enabled = textColor is TextColorChoice.Custom,
            )
            Text("Use my exact color", fontSize = 13.sp)
        }
        // The preview renders the colour AS PICKED; the launcher's contrast engine (Android-side)
        // may lightness-clamp it unless "exact" is on.
        HintText("Off: the launcher may adjust the color for contrast. The preview shows it as picked.")
    }
}

/** Starting point for "Custom" icon color: distinguishable from the White choice. */
private const val CUSTOM_START = 0xFFE0E0E0.toInt()

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, fontSize = 13.sp)
    }
}
