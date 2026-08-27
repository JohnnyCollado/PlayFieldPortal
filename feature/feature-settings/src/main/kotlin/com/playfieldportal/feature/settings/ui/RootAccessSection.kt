package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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

    if (roots.isEmpty()) {
        SettingsRow(
            label = "No ROM roots configured",
            sublabel = "Add a folder below to start managing your library",
        )
    } else {
        // One compact, controller-friendly row per root: the path is the focusable picker row;
        // folder replaces it, and the red trash button removes it without adding extra navigation rows.
        roots.forEach { root ->
            SettingsRow(
                label = root.name,
                sublabel = when {
                    !root.linked -> "Access lost — choose the folder button to re-grant access"
                    root.consoles != null -> "Consoles: ${root.consoles}"
                    else -> "ROM root"
                },
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        IconButton(onClick = { onRelinkRoot(root) }) {
                            Icon(Icons.Default.Create, contentDescription = "Replace root folder", tint = SettingsAccent)
                        }
                        IconButton(onClick = { onRemoveRoot(root) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove root folder", tint = Color(0xFFE55353))
                        }
                    }
                },
                onClick = { onRelinkRoot(root) },
            )
        }
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
