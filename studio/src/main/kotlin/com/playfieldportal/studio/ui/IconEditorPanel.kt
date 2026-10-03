package com.playfieldportal.studio.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.studio.IconPicker
import com.playfieldportal.studio.PickerGroup
import com.playfieldportal.studio.PickerQuery
import com.playfieldportal.studio.StudioState
import com.playfieldportal.studio.StudioViewModel
import com.playfieldportal.studio.io.FileDialogs
import com.playfieldportal.studio.io.IconPackReport
import com.playfieldportal.studio.preview.PreviewRenderer
import com.playfieldportal.studio.ui.sections.MutedText
import com.playfieldportal.themekit.CustomizableIcons
import com.playfieldportal.themekit.IconSlot
import java.awt.Frame
import java.io.File
import java.net.URI

private val IMAGE_EXTENSIONS = setOf("png", "gif", "jpg", "jpeg", "bmp", "webp")
private const val CELL_DP = 72
private const val GRID_HEIGHT_DP = 300

/**
 * Icons section: a searchable, filterable grid of every customizable slot (128), a card for the
 * selected one, drag-and-drop of image files onto cells, and icon-pack import / template export.
 *
 * [onScreenKeys] is the "On screen" filter's slot set — the preview feeds it (see
 * [IconPicker.onScreenKeys]) so the filter follows what the preview is showing.
 */
@Composable
fun IconEditorPanel(
    state: StudioState,
    viewModel: StudioViewModel,
    window: Frame,
    onScreenKeys: Set<String>,
) {
    var search by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf<PickerGroup?>(null) }
    var onScreen by rememberSaveable { mutableStateOf(false) }
    var customizedOnly by rememberSaveable { mutableStateOf(false) }
    var newOnly by rememberSaveable { mutableStateOf(false) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<IconPackReport?>(null) }

    val customized = state.iconOverrides.keys + state.sysiconOverrides.keys
    val query = PickerQuery(search, group, onScreen, customizedOnly, newOnly)
    val shown = remember(query, customized, onScreenKeys) { IconPicker.filter(query, customized, onScreenKeys) }
    val counts = remember { IconPicker.counts() }
    val selected = selectedKey?.let(CustomizableIcons::byKey)

    fun replace(slot: IconSlot) {
        FileDialogs.openFile(window, "Icon image for ${slot.displayName}", IMAGE_EXTENSIONS)
            ?.let { viewModel.setIconOverride(slot.key, it) }
    }

    fun exportTemplate(slot: IconSlot) {
        val file = FileDialogs.saveFile(window, "Template for ${slot.displayName}", "${slot.key}.png", "png") ?: return
        val message = runCatching { file.writeBytes(PreviewRenderer.rasterizeDefaultIcon(slot.key, slot.templateSizePx)) }
            .fold({ "Template written to ${file.name}" }, { "Template export failed: ${it.message}" })
        viewModel.update { it.copy(statusMessage = message) }
    }

    fun importPack(source: File?) {
        source?.let { viewModel.importIconPack(it) { done -> report = done } }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text("Search ${CustomizableIcons.ALL.size} slots: battery, PS3, voice…", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        ChipRow {
            FilterChip(
                selected = group == null,
                onClick = { group = null },
                label = { Text("All ${CustomizableIcons.ALL.size}", fontSize = 12.sp) },
            )
            PickerGroup.entries.forEach { g ->
                FilterChip(
                    selected = group == g,
                    onClick = { group = if (group == g) null else g },
                    label = { Text("${g.label} ${counts[g]}", fontSize = 12.sp) },
                )
            }
        }
        ChipRow {
            FilterChip(selected = onScreen, onClick = { onScreen = !onScreen }, label = { Text("On screen", fontSize = 12.sp) })
            FilterChip(
                selected = customizedOnly,
                onClick = { customizedOnly = !customizedOnly },
                label = { Text("Customized ${customized.size}", fontSize = 12.sp) },
            )
            FilterChip(
                selected = newOnly,
                onClick = { newOnly = !newOnly },
                label = { Text("New ${IconPicker.NEW_KEYS.size}", fontSize = 12.sp) },
            )
        }
        MutedText("Showing ${shown.size} of ${CustomizableIcons.ALL.size}", fontSize = 11)

        if (shown.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(GRID_HEIGHT_DP.dp), contentAlignment = Alignment.Center) {
                MutedText("No slot matches. Clear the search or a filter.")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(CELL_DP.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().height(GRID_HEIGHT_DP.dp),
            ) {
                items(shown, key = { it.key }) { slot ->
                    SlotCell(
                        slot = slot,
                        state = state,
                        selected = slot.key == selectedKey,
                        onSelect = { selectedKey = slot.key },
                        onDropFile = { file ->
                            selectedKey = slot.key
                            viewModel.setIconOverride(slot.key, file)
                        },
                    )
                }
            }
        }

        if (selected != null) {
            SlotCard(
                slot = selected,
                state = state,
                onReplace = { replace(selected) },
                onReset = { viewModel.clearIconOverride(selected.key) },
                onExportTemplate = { exportTemplate(selected) },
            )
        } else {
            MutedText("Select a slot to see it at real size, replace it, or export its template.")
        }

        HorizontalDivider()
        MutedText("Drop an image file (png, gif, jpg, bmp, webp) onto any slot to replace its icon.", fontSize = 11)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            var packMenu by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { packMenu = true }, enabled = !state.busy) { Text("Import icon pack… ▾", fontSize = 12.sp) }
                DropdownMenu(expanded = packMenu, onDismissRequest = { packMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Pack folder…") },
                        onClick = { packMenu = false; importPack(FileDialogs.pickDirectory("Icon pack folder")) },
                    )
                    DropdownMenuItem(
                        text = { Text("Pack .zip…") },
                        onClick = { packMenu = false; importPack(FileDialogs.openFile(window, "Icon pack zip", setOf("zip"))) },
                    )
                }
            }
            OutlinedButton(
                enabled = !state.busy,
                onClick = {
                    FileDialogs.pickDirectory("Folder for icon templates")?.let { dir ->
                        viewModel.exportIconTemplates(dir, PreviewRenderer::rasterizeDefaultIcon)
                    }
                },
            ) { Text("Export templates…", fontSize = 12.sp) }
            if (customized.isNotEmpty()) {
                TextButton(onClick = viewModel::clearAllIconOverrides) { Text("Reset all", fontSize = 12.sp) }
            }
        }
        MutedText(
            "Packs are folders or zips of <slot key>.png / .gif, the names the templates use. Multi-frame GIFs animate on the " +
                "launcher when focused; they must stay within 512px, 120 frames and 10 seconds.",
            fontSize = 11,
        )
    }

    report?.let { done -> PackReportDialog(done) { report = null } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
private fun PackReportDialog(report: IconPackReport, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = onDismiss) { Text("OK") } },
        title = { Text(if (report.error == null) "Icon pack imported" else "Icon pack refused") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.height(260.dp).verticalScroll(rememberScrollState()),
            ) {
                IconPicker.packSummary(report).forEachIndexed { i, line ->
                    Text(line, fontSize = if (i == 0) 13.sp else 12.sp)
                }
            }
        },
    )
}

/** One grid cell: checkerboard, art, a dot when customized, a NEW marker, and the selection outline. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun SlotCell(
    slot: IconSlot,
    state: StudioState,
    selected: Boolean,
    onSelect: () -> Unit,
    onDropFile: (File) -> Unit,
) {
    val custom = state.iconBitmaps[slot.key] ?: state.sysiconBitmaps[slot.key]
    val isNew = slot.key in IconPicker.NEW_KEYS
    var hovering by remember { mutableStateOf(false) }
    val currentDrop by rememberUpdatedState(onDropFile)
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { hovering = true }
            override fun onExited(event: DragAndDropEvent) { hovering = false }
            override fun onEnded(event: DragAndDropEvent) { hovering = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovering = false
                val dropped = (event.dragData() as? DragData.FilesList)?.readFiles()?.firstOrNull() ?: return false
                val file = runCatching { File(URI(dropped)) }.getOrNull() ?: return false
                currentDrop(file)
                return true
            }
        }
    }
    val outline = when {
        hovering -> Color(0xFFE0B96B)
        selected -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(CELL_DP.dp)
            .dragAndDropTarget(shouldStartDragAndDrop = { it.dragData() is DragData.FilesList }, target = target)
            .clickable(onClick = onSelect),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).border(2.dp, outline, RoundedCornerShape(8.dp)),
        ) {
            Checkerboard(56)
            SlotArt(slot, custom, Color.White, if (custom != null) 44 else 40)
            if (custom != null) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(7.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
            if (isNew) {
                Text(
                    "NEW",
                    fontSize = 7.sp,
                    color = Color(0xFF1B1F27),
                    modifier = Modifier.align(Alignment.BottomStart).padding(3.dp)
                        .background(Color(0xFFE0B96B), RoundedCornerShape(3.dp)).padding(horizontal = 2.dp),
                )
            }
        }
        Text(
            slot.displayName,
            fontSize = 9.sp,
            maxLines = 2,
            lineHeight = 11.sp,
            textAlign = TextAlign.Center,
            color = if (custom != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
