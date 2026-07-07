package com.playfieldportal.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.studio.StudioDialog
import com.playfieldportal.studio.StudioViewModel
import com.playfieldportal.studio.io.FileDialogs
import com.playfieldportal.studio.preview.PreviewRenderer
import com.playfieldportal.studio.preview.XmbPreviewCanvas
import com.playfieldportal.studio.preview.toPreviewModel
import com.playfieldportal.themekit.PfpThemeCodec
import java.awt.Frame

@Composable
fun StudioApp(viewModel: StudioViewModel, window: Frame) {
    val state by viewModel.state.collectAsState()

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // ── Toolbar ──
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    OutlinedButton(onClick = viewModel::newTheme) { Text("New") }
                    OutlinedButton(onClick = {
                        FileDialogs.openFile(window, "Open theme", setOf("ptf", "ctf", PfpThemeCodec.FILE_EXTENSION))
                            ?.let(viewModel::openFile)
                    }) { Text("Open…") }
                    OutlinedButton(onClick = {
                        FileDialogs.openFile(window, "Import wallpaper", setOf("png", "jpg", "jpeg", "bmp", "webp"))
                            ?.let(viewModel::importWallpaper)
                    }) { Text("Wallpaper…") }
                    OutlinedButton(onClick = {
                        FileDialogs.saveFile(
                            window, "Export theme",
                            suggestedName = "${state.name.ifBlank { "theme" }}.${PfpThemeCodec.FILE_EXTENSION}",
                            extension = PfpThemeCodec.FILE_EXTENSION,
                        )?.let { file -> viewModel.exportTo(file) { s -> PreviewRenderer.renderPreviewPng(s) } }
                    }) { Text("Export…") }
                    OutlinedButton(onClick = {
                        val input = FileDialogs.pickDirectory("Folder with .ptf files") ?: return@OutlinedButton
                        val output = FileDialogs.pickDirectory("Output folder for .pfptheme files") ?: return@OutlinedButton
                        viewModel.batchConvert(input, output) { bundle ->
                            runCatching { PreviewRenderer.renderPreviewPng(bundle) }.getOrNull()
                        }
                    }) { Text("Batch convert…") }

                    Box(Modifier.weight(1f))
                    if (state.busy) {
                        // Deliberately static (no spinner): CMP 1.6.11's frame-animated M3
                        // indicators have crashed the desktop node chain (see studio README note).
                        Text("Working…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider()

                // ── Preview + inspector ──
                Row(Modifier.weight(1f)) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.weight(1f).fillMaxHeight().background(Color(0xFF141414)).padding(16.dp),
                    ) {
                        XmbPreviewCanvas(state.toPreviewModel(), Modifier.fillMaxSize())
                    }
                    VerticalDivider()
                    Column(Modifier.width(360.dp).fillMaxHeight()) {
                        var tab by remember { mutableStateOf(0) }
                        // Plain buttons instead of M3 TabRow: its animated indicator (composed
                        // modifier on the frame clock) is a crash suspect on CMP 1.6.11 desktop.
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            TabButton("Theme", selected = tab == 0, modifier = Modifier.weight(1f)) { tab = 0 }
                            TabButton("Icons", selected = tab == 1, modifier = Modifier.weight(1f)) { tab = 1 }
                        }
                        HorizontalDivider()
                        when (tab) {
                            0 -> InspectorPanel(
                                state = state,
                                viewModel = viewModel,
                                onChooseWallpaper = {
                                    FileDialogs.openFile(window, "Import wallpaper", setOf("png", "jpg", "jpeg", "bmp", "webp"))
                                        ?.let(viewModel::importWallpaper)
                                },
                            )
                            1 -> IconEditorPanel(
                                state = state,
                                viewModel = viewModel,
                                onImportInto = { key ->
                                    FileDialogs.openFile(window, "Icon image", setOf("png", "jpg", "jpeg", "bmp", "webp"))
                                        ?.let { viewModel.setIconOverride(key, it) }
                                },
                                onExportTemplates = {
                                    FileDialogs.pickDirectory("Folder for icon templates")?.let { dir ->
                                        viewModel.exportIconTemplates(dir, PreviewRenderer::rasterizeDefaultIcon)
                                    }
                                },
                            )
                        }
                    }
                }
                HorizontalDivider()

                // ── Status line ──
                Text(
                    text = state.batchProgress?.let { "Converting ${it.current}  (${it.done}/${it.total})" }
                        ?: state.statusMessage
                        ?: "",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            StudioDialogs(viewModel)
        }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick, modifier = modifier) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun StudioDialogs(viewModel: StudioViewModel) {
    val state by viewModel.state.collectAsState()
    when (val dialog = state.dialog) {
        null -> Unit
        StudioDialog.CxmbRejected -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            confirmButton = { Button(onClick = viewModel::dismissDialog) { Text("OK") } },
            title = { Text("CXMB theme") },
            text = {
                Text(
                    "This file is a CXMB custom firmware theme (.ctf). Those replace PSP system " +
                        "files rather than describing wallpaper and colors, so they can't be " +
                        "converted. Official PSP themes (.ptf) open fine.",
                )
            },
        )
        is StudioDialog.Error -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            confirmButton = { Button(onClick = viewModel::dismissDialog) { Text("OK") } },
            title = { Text("Something went wrong") },
            text = { Text(dialog.message) },
        )
        is StudioDialog.BatchDone -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            confirmButton = { Button(onClick = viewModel::dismissDialog) { Text("OK") } },
            title = { Text("Batch conversion finished") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val s = dialog.summary
                    Text("Converted: ${s.converted.size}")
                    if (s.skippedCxmb.isNotEmpty()) {
                        Text("Skipped (CXMB): ${s.skippedCxmb.size} — ${s.skippedCxmb.joinToString()}")
                    }
                    if (s.failed.isNotEmpty()) {
                        Text("Failed: ${s.failed.size}")
                        s.failed.forEach { (name, reason) -> Text("  • $name — $reason", fontSize = 12.sp) }
                    }
                }
            },
        )
    }
}
