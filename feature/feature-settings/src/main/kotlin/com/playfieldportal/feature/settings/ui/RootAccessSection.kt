package com.playfieldportal.feature.settings.ui

import com.playfieldportal.feature.settings.R
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.playfieldportal.feature.settings.viewmodel.RootFolderRow

@Composable
fun RootAccessSection(
    groupTitle: String,
    // What the roots hold, for the empty and fallback rows: "ROM", "Music", "Photo", "Video".
    rootKind: String,
    // The same word as a string resource, for a screen that has one: it is what gets drawn.
    // [rootKind] stays for callers that only have the English word.
    @StringRes rootKindRes: Int? = null,
    roots: List<RootFolderRow>,
    addLabel: String,
    addSublabel: String,
    onAddRoot: () -> Unit,
    onRelinkRoot: (RootFolderRow) -> Unit,
    onRemoveRoot: (RootFolderRow) -> Unit,
    autoDetectLabel: String? = null,
    autoDetectSublabel: String? = null,
    onAutoDetect: (() -> Unit)? = null,
) {
    SettingsGroup(groupTitle)
    val kind = rootKindRes?.let { stringResource(it) } ?: rootKind

    if (roots.isEmpty()) {
        SettingsRow(
            label = stringResource(R.string.sets_root_none, kind),
            sublabel = stringResource(R.string.sets_root_none_help),
        )
    } else {
        roots.forEach { root ->
            DirectoryRow(
                label = root.name,
                sublabel = when {
                    !root.linked -> stringResource(R.string.sets_root_access_lost)
                    root.consoles != null -> stringResource(R.string.sets_root_consoles, root.consoles)
                    else -> stringResource(R.string.sets_root_kind_root, kind)
                },
                onEdit = { onRelinkRoot(root) },
                onRemove = { onRemoveRoot(root) },
            )
        }
    }

    SettingsRow(label = addLabel, sublabel = addSublabel, onClick = onAddRoot)

    if (autoDetectLabel != null && onAutoDetect != null) {
        SettingsRow(label = autoDetectLabel, sublabel = autoDetectSublabel, onClick = onAutoDetect)
    }
}

/** A non-selectable directory row with exactly two controller-reachable inline actions. */
@Composable
fun DirectoryRow(
    label: String,
    sublabel: String? = null,
    focusKey: String? = null,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val editLabel = stringResource(SettingsLabels.EDIT_FOLDER_RES)
    val removeLabel = stringResource(SettingsLabels.REMOVE_FOLDER_RES)
    SettingsRow(
        label = label,
        sublabel = sublabel,
        focusKey = focusKey,
        onClick = null,
        actions = listOf(
            SettingsRowAction(
                editLabel, onEdit,
                actionFocusBackgroundColor = lerp(SettingsAccent, Color.Black, 0.50f),
                shortLabel = stringResource(R.string.sets_root_edit_short),
            ) {
                Icon(
                    Icons.Default.Create,
                    contentDescription = editLabel,
                    tint = SettingsAccent,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
                        .padding(4.dp)
                )
            },
            SettingsRowAction(
                removeLabel, onRemove,
                actionFocusBackgroundColor = lerp(Color(0xFFE55353), Color.Black, 0.50f),
                shortLabel = stringResource(R.string.sets_root_remove_short),
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = removeLabel,
                    tint = Color(0xFFE55353),
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
                        .padding(4.dp)
                )
            },
        ),
    )
}
