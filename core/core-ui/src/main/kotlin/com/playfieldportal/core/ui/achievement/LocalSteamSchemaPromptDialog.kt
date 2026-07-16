package com.playfieldportal.core.ui.achievement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * The per-game "generate the missing achievement schema?" prompt, shared by every scan surface
 * (the XMB Windows card and the Library Manager). Pure UI: the caller supplies the current game and
 * three decisions, so one dialog serves both surfaces. See
 * `feature-achievements` `LocalSteamSchemaPromptController`.
 *
 * [onNo] skips this game, [onYes] generates its schema, and [onYesToAll] approves the rest of the
 * current scan without further prompts.
 */
@Composable
fun LocalSteamSchemaPromptDialog(
    folderName: String,
    appId: String,
    index: Int,
    total: Int,
    onNo: () -> Unit,
    onYes: () -> Unit,
    onYesToAll: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onNo,
        title = { Text("Generate achievement schema?") },
        text = {
            Text(
                "\"$folderName\" (appid $appId) has no achievement list yet, so the emulator " +
                    "can't record its achievements. Generate one from the Steam Web API now?" +
                    if (total > 1) "\n\nGame $index of $total missing a schema." else "",
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (total > 1) {
                    TextButton(onClick = onYesToAll) { Text("Yes to All") }
                }
                TextButton(onClick = onYes) { Text("Yes") }
            }
        },
        dismissButton = { TextButton(onClick = onNo) { Text("No") } },
    )
}
