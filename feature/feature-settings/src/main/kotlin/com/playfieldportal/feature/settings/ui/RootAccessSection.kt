package com.playfieldportal.feature.settings.ui

import androidx.compose.runtime.Composable
import com.playfieldportal.feature.settings.viewmodel.RootFolderRow

/**
 * The shared "Root Access" settings group: managed ROOT folders (one persisted SAF grant each),
 * ROM-root style. Each root shows its live grant status — tap to re-link (replace/re-grant, the
 * picker opens at the saved folder) — plus a Remove row, an Add row, and optional auto-detect.
 * Used by Library (ROM roots) and the Music/Video/Photo settings.
 */
@Composable
fun RootAccessSection(
    groupTitle: String,
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

    roots.forEach { root ->
        SettingsValueRow(
            label    = root.name,
            value    = if (root.linked) "Linked" else "Re-link",
            sublabel = when {
                !root.linked -> "Access lost — tap to re-grant (happens after a restore or reinstall)"
                root.consoles != null -> "Consoles: ${root.consoles} · tap to replace this root"
                else -> "Access OK · tap to replace this root"
            },
            onClick  = { onRelinkRoot(root) },
        )
        SettingsRow(
            label    = "Remove This Root",
            sublabel = "Stops using ${root.name}. Files on disk are kept.",
            onClick  = { onRemoveRoot(root) },
        )
    }

    SettingsRow(
        label    = addLabel,
        sublabel = addSublabel,
        onClick  = onAddRoot,
    )

    if (autoDetectLabel != null && onAutoDetect != null) {
        SettingsRow(
            label    = autoDetectLabel,
            sublabel = autoDetectSublabel,
            onClick  = onAutoDetect,
        )
    }
}
