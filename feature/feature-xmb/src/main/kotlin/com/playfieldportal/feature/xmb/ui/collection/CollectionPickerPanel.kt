package com.playfieldportal.feature.xmb.ui.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Shared, controller-navigable "Add to Collection" picker ──────────────────
// Used by both the console Game Detail and the Android App Detail screens. The owning
// ViewModel holds the state and routes D-Pad/A/B input; this renders it and forwards taps.

data class CollectionPickerOption(
    val id: Long,
    val name: String,
    val checked: Boolean,
)

data class CollectionPickerUi(
    val visible: Boolean = false,
    val options: List<CollectionPickerOption> = emptyList(),
    // 0..options.size — the last index (== options.size) is the "Create New Collection" row.
    val selectedIndex: Int = 0,
    val showCreateDialog: Boolean = false,
    val createText: String = "",
) {
    val rowCount: Int get() = options.size + 1
    val isCreateRow: Boolean get() = selectedIndex >= options.size
}

private val TextPrimary = Color(0xFFEEEEEE)
private val TextMuted = Color(0xAAEEEEEE)
private val RowFill = Color(0xFF1B1B26)
private val CheckGreen = Color(0xFF45C46A)

@Composable
fun CollectionPickerPanel(
    ui: CollectionPickerUi,
    onRowClick: (Int) -> Unit,
    onClose: () -> Unit,
    onCreateTextChanged: (String) -> Unit,
    onConfirmCreate: () -> Unit,
    onCancelCreate: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 460.dp)
                .fillMaxWidth(0.86f)
                .heightIn(max = 420.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xF20A0A14))
                .clickable(enabled = false) {}
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Add to Collection", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Up/Down  Navigate  •  Select  Toggle  •  B  Close",
                color = TextMuted.copy(alpha = 0.5f),
                fontSize = 10.sp,
            )

            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ui.options.forEachIndexed { index, option ->
                    PickerRow(
                        label = option.name,
                        trailingCheck = option.checked,
                        isFocused = ui.selectedIndex == index,
                        onClick = { onRowClick(index) },
                    )
                }
                // Final row — create a brand-new collection.
                PickerRow(
                    label = "＋  Create New Collection",
                    trailingCheck = false,
                    isFocused = ui.isCreateRow,
                    onClick = { onRowClick(ui.options.size) },
                )

                if (ui.options.isEmpty()) {
                    Text(
                        "No collections yet — create one to get started.",
                        color = TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                    )
                }
            }
        }
    }

    if (ui.showCreateDialog) {
        AlertDialog(
            onDismissRequest = onCancelCreate,
            title = { Text("New Collection") },
            text = {
                OutlinedTextField(
                    value = ui.createText,
                    onValueChange = onCreateTextChanged,
                    singleLine = true,
                    placeholder = { Text("e.g. RPGs, Currently Playing") },
                )
            },
            confirmButton = { TextButton(onClick = onConfirmCreate) { Text("Create") } },
            dismissButton = { TextButton(onClick = onCancelCreate) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PickerRow(
    label: String,
    trailingCheck: Boolean,
    isFocused: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) com.playfieldportal.core.ui.theme.menuCursorFill() else RowFill)
            .then(if (isFocused) Modifier.border(1.5.dp, com.playfieldportal.core.ui.theme.menuCursorEdge(), RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 12.dp),
    ) {
        Text(label, color = TextPrimary, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
        if (trailingCheck) {
            Text("✓", color = CheckGreen, fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }
}
