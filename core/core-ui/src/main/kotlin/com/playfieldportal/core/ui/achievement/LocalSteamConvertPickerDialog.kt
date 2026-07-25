package com.playfieldportal.core.ui.achievement

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One row in the convert picker; position in the list is the toggle key. */
data class LocalSteamConvertRow(
    val folderName: String,
    val appId: String,
    val selected: Boolean,
)

/**
 * The "convert detected games?" multi-select picker, shared by every scan surface (the XMB Windows
 * card and the Library Manager). Pure UI: the caller supplies the rows and the toggle/confirm/cancel
 * callbacks, so one dialog serves both surfaces. See `feature-achievements`
 * `LocalSteamConvertPickerController`.
 *
 * Lists games that carry a `steam_settings/steam_appid.txt` but no `achievements.json`; [onConfirm]
 * converts every checked game, [onCancel] dismisses without writing anything.
 */
@Composable
fun LocalSteamConvertPickerDialog(
    rows: List<LocalSteamConvertRow>,
    onToggle: (index: Int) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val selectedCount = rows.count { it.selected }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Install Goldberg achievements?") },
        text = {
            Column {
                Text(
                    "These games have Steam-emu setup but no achievement list yet. " +
                        "Pick the ones to convert — PFP writes their achievement data and swaps in " +
                        "the Goldberg emulator so it can track unlocks.",
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onSelectAll) { Text("Select all") }
                    TextButton(onClick = onSelectNone) { Text("Select none") }
                }
                Column(
                    Modifier
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    rows.forEachIndexed { index, row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(index) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = row.selected, onCheckedChange = { onToggle(index) })
                            Column(Modifier.padding(start = 4.dp)) {
                                Text(row.folderName, fontWeight = FontWeight.Medium)
                                Text("appid ${row.appId}", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = selectedCount > 0) {
                Text(if (selectedCount > 0) "Convert ($selectedCount)" else "Convert")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
