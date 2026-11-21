package com.playfieldportal.feature.xmb.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.playfieldportal.core.ui.components.PspContextMenuOverlay
import com.playfieldportal.core.ui.components.PspMenuRow
import com.playfieldportal.core.ui.sound.LocalMenuSounds
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.text.asString
import com.playfieldportal.feature.xmb.viewmodel.XMBContextMenu

// ── Context menu overlay — appears on Y/Triangle press ───────────────────────
//
// Thin adapter over the shared PSP-style panel in core-ui (PspContextMenuOverlay),
// so the XMB menu and settings-screen menus (e.g. Themes) render identically.
//
// Controller nav is handled by XMBViewModel.dispatchGamepadAction when
// activeContextMenu != null. The shared panel handles touch/click interaction.

/** The shared panel's rows for this menu — a straight field-for-field mapping. */
internal fun XMBContextMenu.toPspRows(res: Resources): List<PspMenuRow> = items.map {
    PspMenuRow(
        label = it.label.resolve(res),
        isDestructive = it.isDestructive,
        checked = it.checked,
        value = it.value?.resolve(res),
        header = it.header?.resolve(res),
        opensMenu = it.opensMenu,
        silent = it.silent,
    )
}

@Composable
fun ContextMenuOverlay(
    menu: XMBContextMenu,
    onItemActivated: (index: Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PspContextMenuOverlay(
        title         = menu.title.asString(),
        rows          = menu.toPspRows(uiResources()),
        selectedIndex = menu.selectedIndex,
        onRowActivated = onItemActivated,
        onDismiss     = onDismiss,
        modifier      = modifier,
    )
}
