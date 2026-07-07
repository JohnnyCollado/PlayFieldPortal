package com.playfieldportal.studio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.studio.StudioState
import com.playfieldportal.studio.StudioViewModel
import com.playfieldportal.studio.preview.StudioIconSet
import com.playfieldportal.themekit.IconSlot
import com.playfieldportal.themekit.IconSlots

/**
 * Icons tab: every themeable slot, grouped like the XMB itself. Click a slot to import a
 * replacement image; overridden slots get a reset. "Export icon templates" hands users the
 * default silhouettes to edit in any image editor.
 */
@Composable
fun IconEditorPanel(
    state: StudioState,
    viewModel: StudioViewModel,
    onImportInto: (slotKey: String) -> Unit,
    onExportTemplates: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Text(
            "Click a slot to replace its icon. Custom icons ship in the theme exactly as you draw them; untouched slots keep the built-in glyph and follow the theme's icon color.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onExportTemplates) { Text("Export icon templates…") }
            if (state.iconOverrides.isNotEmpty()) {
                TextButton(onClick = viewModel::clearAllIconOverrides) { Text("Reset all") }
            }
        }

        val groups = listOf(
            IconSlot.Group.CATEGORY_BAR to "Category bar",
            IconSlot.Group.ITEMS to "Item glyphs",
            IconSlot.Group.STATUS to "Status strip",
        )
        groups.forEach { (group, title) ->
            HorizontalDivider()
            SectionLabel(title)
            IconSlots.ALL.filter { it.group == group }.chunked(4).forEach { rowSlots ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowSlots.forEach { slot ->
                        SlotCell(
                            slot = slot,
                            state = state,
                            onClick = { onImportInto(slot.key) },
                            onReset = { viewModel.clearIconOverride(slot.key) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SlotCell(slot: IconSlot, state: StudioState, onClick: () -> Unit, onReset: () -> Unit) {
    val overridden = slot.key in state.iconBitmaps
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
        ) {
            Checkerboard()
            val custom = state.iconBitmaps[slot.key]
            if (custom != null) {
                Image(bitmap = custom, contentDescription = slot.displayName, modifier = Modifier.size(44.dp))
            } else {
                Image(
                    painter = StudioIconSet.defaultPainter(slot.key),
                    contentDescription = slot.displayName,
                    colorFilter = ColorFilter.tint(Color.White, BlendMode.SrcIn),
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Text(
            slot.displayName,
            fontSize = 9.sp,
            maxLines = 2,
            lineHeight = 11.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = if (overridden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (overridden) {
            TextButton(onClick = onReset, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("Reset", fontSize = 9.sp)
            }
        }
    }
}

/** Alpha-checkerboard backdrop so transparent icon regions are visible. */
@Composable
private fun Checkerboard() {
    Canvas(Modifier.size(56.dp)) {
        val cell = 8f
        val cols = (size.width / cell).toInt() + 1
        val rows = (size.height / cell).toInt() + 1
        for (r in 0 until rows) for (c in 0 until cols) {
            drawRect(
                color = if ((r + c) % 2 == 0) Color(0xFF3A3A3A) else Color(0xFF2C2C2C),
                topLeft = Offset(c * cell, r * cell),
                size = Size(cell, cell),
            )
        }
    }
}
