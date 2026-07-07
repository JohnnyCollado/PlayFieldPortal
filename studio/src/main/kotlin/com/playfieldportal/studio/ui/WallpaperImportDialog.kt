package com.playfieldportal.studio.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.studio.PendingWallpaper
import com.playfieldportal.studio.WallpaperPreset

/**
 * Crop/scale step of wallpaper import: pick a target size (PSP-native for imports, 16:9 for
 * modern handhelds, or leave the image untouched) before it lands in the theme.
 */
@Composable
fun WallpaperImportDialog(
    pending: PendingWallpaper,
    onConfirm: (WallpaperPreset) -> Unit,
    onCancel: () -> Unit,
) {
    var choice by remember { mutableStateOf(WallpaperPreset.HD) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Wallpaper size") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                pending.thumbnail?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        modifier = Modifier.heightIn(max = 140.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    "${pending.fileName} — ${pending.source.width}×${pending.source.height}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                WallpaperPreset.entries.forEach { preset ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = choice == preset, onClick = { choice = preset })
                        Column {
                            Text(preset.label, fontSize = 13.sp)
                            Text(
                                when (preset) {
                                    WallpaperPreset.ORIGINAL -> "${pending.source.width}×${pending.source.height}"
                                    else -> "center-cropped to ${preset.width}×${preset.height}"
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(choice) }) { Text("Import") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
