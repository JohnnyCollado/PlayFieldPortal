package com.playfieldportal.feature.xmb.ui.collection

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.playfieldportal.core.ui.components.PfpModalSpec
import com.playfieldportal.core.ui.components.PspContextMenuOverlay
import com.playfieldportal.core.ui.components.PspMenuRow
import com.playfieldportal.feature.xmb.R

// ── Shared, controller-navigable "Add to Card" picker ────────────────────────
// Used by both the console Game Detail and the Android App Detail screens. The owning
// ViewModel holds the state and routes D-Pad/A/B input; this renders it, on the same side panel as
// Options (it is one level down from Options ▸ Add to Card), and forwards taps.

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
    // The New Collection name entry is up. The host screen shows it as the shared text entry modal
    // (see collectionNameModalSpec), which owns the text being typed.
    val showCreateDialog: Boolean = false,
    // Opened from Options ▸ Add to Card, so Back returns there rather than closing.
    val fromOptions: Boolean = false,
) {
    val rowCount: Int get() = options.size + 1
    val isCreateRow: Boolean get() = selectedIndex >= options.size
}

/** The picker's rows: one per card, its membership checked, then New Card, which opens a name entry. */
internal fun collectionPickerRows(options: List<CollectionPickerOption>, newCardLabel: String): List<PspMenuRow> =
    options.map { PspMenuRow(label = it.name, checked = it.checked) } +
        PspMenuRow(label = newCardLabel, opensMenu = true)

/**
 * Add to Card on the shared Options panel. [onDismiss] is a tap outside, which closes the whole menu;
 * Back is the host's, through its view model, and returns to Options when it came from there.
 */
@Composable
fun CollectionPickerPanel(
    ui: CollectionPickerUi,
    onRowClick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    PspContextMenuOverlay(
        title = stringResource(R.string.xmbu_common_add_to_card),
        rows = collectionPickerRows(ui.options, stringResource(R.string.xmbu_collection_picker_new_row)),
        selectedIndex = ui.selectedIndex,
        onRowActivated = onRowClick,
        onDismiss = onDismiss,
    )
}

/**
 * The picker's New Collection name entry, as the shared text entry modal — the one spec both
 * screens that host the picker show, so the prompt reads the same from Game Detail and App Detail.
 */
fun collectionNameModalSpec(
    onCreate: (String) -> Unit,
    onCancel: () -> Unit,
    res: Resources,
): PfpModalSpec.TextEntry = PfpModalSpec.TextEntry(
    key = "new_collection",
    title = res.getString(R.string.xmbu_collection_new_title),
    placeholder = res.getString(R.string.xmbu_collection_new_placeholder),
    confirmLabel = res.getString(R.string.xmbu_collection_new_create),
    onConfirm = onCreate,
    onCancel = onCancel,
)
