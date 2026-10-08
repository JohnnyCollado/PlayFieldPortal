package com.playfieldportal.studio.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.studio.IconPicker
import com.playfieldportal.studio.StudioState
import com.playfieldportal.studio.io.ImageCodecs
import com.playfieldportal.themekit.IconSlot
import com.playfieldportal.themekit.ThemeIconChoices

private const val GRID_COLUMNS = 6
private const val TILE_DP = 56
private const val DIALOG_GRID_HEIGHT_DP = 340

/**
 * "From theme…": every icon the open theme holds, as a 6-column grid, so one can be set on [slot].
 * "Theme icons" are the theme's own slot art; "More from this PSP theme" are the kept PSP body
 * images. A click calls [onPick] with the tile's source and the caller closes the dialog.
 */
@Composable
fun ThemeIconPickerDialog(
    slot: IconSlot,
    state: StudioState,
    onPick: (ThemeIconChoices.Source) -> Unit,
    onDismiss: () -> Unit,
) {
    val sections = remember(state.iconOverrides, state.ptfIcons) { IconPicker.fromThemeSections(state) }
    // PSP extras carry no pre-decoded bitmap; decode each once while the dialog shows the same set.
    val ptfBitmaps = remember(state.ptfIcons) { state.ptfIcons.mapValues { (_, bytes) -> ImageCodecs.toImageBitmap(bytes) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(IconPicker.fromThemeTitle(state.name, slot), fontSize = 16.sp) },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(GRID_COLUMNS),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().height(DIALOG_GRID_HEIGHT_DP.dp),
            ) {
                sections.forEach { section ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "title:${section.title}") {
                        Text(
                            section.title,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    items(section.choices, key = { it.source }) { choice ->
                        val bitmap = when (val source = choice.source) {
                            is ThemeIconChoices.Source.Slot -> state.iconBitmaps[source.key]
                            is ThemeIconChoices.Source.Ptf -> ptfBitmaps[source.ref]
                        }
                        ThemeIconTile(choice.label, bitmap) { onPick(choice.source) }
                    }
                }
            }
        },
    )
}

/** One tile: art over the checkerboard, and its label. */
@Composable
private fun ThemeIconTile(label: String, bitmap: ImageBitmap?, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(TILE_DP.dp).clip(RoundedCornerShape(8.dp))) {
            Checkerboard(TILE_DP)
            if (bitmap != null) Image(bitmap = bitmap, contentDescription = label, modifier = Modifier.size((TILE_DP - 8).dp))
        }
        Text(
            label,
            fontSize = 9.sp,
            maxLines = 2,
            lineHeight = 11.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
