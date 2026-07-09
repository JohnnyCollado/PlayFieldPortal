package com.playfieldportal.feature.xmb.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.playfieldportal.core.ui.components.PspContextMenuOverlay
import com.playfieldportal.core.ui.components.PspMenuRow
import com.playfieldportal.feature.xmb.viewmodel.XMBContextMenu

// ── Context menu overlay — appears on Y/Triangle press ───────────────────────
//
// Thin adapter over the shared PSP-style panel in core-ui (PspContextMenuOverlay),
// so the XMB menu and settings-screen menus (e.g. Themes) render identically.
//
// Controller nav is handled by XMBViewModel.dispatchGamepadAction when
// activeContextMenu != null. The shared panel handles touch/click interaction.

@Composable
fun ContextMenuOverlay(
    menu: XMBContextMenu,
    onItemActivated: (index: Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PspContextMenuOverlay(
        title         = menu.title,
        rows          = menu.items.map { PspMenuRow(it.label, it.isDestructive, it.checked) },
        selectedIndex = menu.selectedIndex,
        onRowActivated = onItemActivated,
        onDismiss     = onDismiss,
        modifier      = modifier,
    )
}
