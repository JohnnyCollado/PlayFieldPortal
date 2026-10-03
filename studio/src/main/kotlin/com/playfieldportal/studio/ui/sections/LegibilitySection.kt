package com.playfieldportal.studio.ui.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.studio.StudioState
import com.playfieldportal.studio.StudioViewModel

/** (codec value, label) in display order; together they cover `ThemeLegibility.TEXT_VALUES`. */
val LEGIBILITY_TEXT_CHOICES: List<Pair<String, String>> = listOf(
    "auto" to "Automatic",
    "none" to "None",
    "shadow" to "Drop shadow",
    "outline" to "Outline",
    "plate" to "Contrast plate",
)

/** (codec value, label) in display order; together they cover `ThemeLegibility.ICON_VALUES`. */
val LEGIBILITY_ICON_CHOICES: List<Pair<String, String>> = listOf(
    "none" to "None",
    "offset_shadow" to "Offset shadow (dark)",
    "offset_shadow_light" to "Offset shadow (light)",
    "contour_dark" to "Contour dark",
    "contour_light" to "Contour light",
    "contour_auto" to "Contour auto",
)

@Composable
fun LegibilitySection(state: StudioState, viewModel: StudioViewModel) {
    val legibility = state.legibility
    // A theme that says nothing behaves as Automatic text and plain icons.
    val textStyle = legibility?.text ?: "auto"
    val iconStyle = legibility?.icon ?: "none"
    SectionColumn {
        SectionHeading("Text")
        ChoiceChips(LEGIBILITY_TEXT_CHOICES, textStyle) { if (it != textStyle) viewModel.setTextLegibility(it) }
        SectionHeading("Icons")
        ChoiceChips(LEGIBILITY_ICON_CHOICES, iconStyle) { if (it != iconStyle) viewModel.setIconLegibility(it) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Switch(
                checked = legibility?.solidUnfocusedIcons == true,
                onCheckedChange = viewModel::setSolidUnfocusedIcons,
            )
            Text("Solid unfocused icons", fontSize = 13.sp)
        }
        MutedText("Older launchers ignore these and show plain text and icons.", 11)
        if (state.wallpaperBusy && (legibility?.text ?: "auto") == "none") {
            HintText("This wallpaper is busy behind the labels — a text style helps them stay readable.")
        }
    }
}
