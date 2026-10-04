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
        ChoiceRow("Auto (from the wallpaper)", state.accentAuto) { viewModel.setAccentAuto(true) }
        ChoiceRow("Custom", !state.accentAuto) { viewModel.setAccentAuto(false) }
        MutedText(
            if (state.accentAuto) "Each new wallpaper or video sets the accent. Pick a color to keep your own."
            else "Your accent stays when the wallpaper or video changes.",
            11,
        )
        // Picking a swatch or typing a color makes the accent Custom.
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

        SectionHeading("Main text color")
        MutedText("Titles and labels: the crossbar, list rows and status strip, and every screen.", 11)
        ChoiceRow("Auto (built-in colors)", state.textColor is TextColorChoice.Auto) {
            viewModel.setTextColor(TextColorChoice.Auto)
        }
        ChoiceRow("Custom", state.textColor is TextColorChoice.Custom) {
            viewModel.setTextColor(TextColorChoice.Custom(WHITE))
        }
        val textColor = state.textColor
        if (textColor is TextColorChoice.Custom) {
            ColorField("Main text color", textColor.argb) { viewModel.setTextColor(TextColorChoice.Custom(it)) }
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

        HorizontalDivider()

        SectionHeading("Subtext color")
        MutedText("Subtitles, sublabels and values.", 11)
        ChoiceRow("Same as main text", state.subTextColor is TextColorChoice.Auto) {
            viewModel.setSubTextColor(TextColorChoice.Auto)
        }
        ChoiceRow("Custom", state.subTextColor is TextColorChoice.Custom) {
            viewModel.setSubTextColor(TextColorChoice.Custom((textColor as? TextColorChoice.Custom)?.argb ?: WHITE))
        }
        val subTextColor = state.subTextColor
        if (subTextColor is TextColorChoice.Custom) {
            ColorField("Subtext color", subTextColor.argb) { viewModel.setSubTextColor(TextColorChoice.Custom(it)) }
        }
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
