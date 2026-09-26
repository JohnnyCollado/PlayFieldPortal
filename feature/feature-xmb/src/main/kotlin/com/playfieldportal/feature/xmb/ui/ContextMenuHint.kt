package com.playfieldportal.feature.xmb.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.playfieldportal.core.domain.model.ControllerDisplayType
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.ControllerHintBar
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.core.ui.components.ControllerPromptStyle
import com.playfieldportal.core.ui.components.LocalControllerPromptStyle
import com.playfieldportal.core.ui.preview.CombinedPreviews
import com.playfieldportal.core.ui.preview.PfpPreview

// ── Idle hint pill ────────────────────────────────────────────────────────────
//
// A small black rounded pill shown in the XMB's bottom-right (stacked above the App
// Drawer button) after the user has been idle for a moment. It names *actions*, so the
// glyphs track both the controller display style and a swapped X/Y layout, mirroring the
// reference PSP UI.
//
// The pill carries up to three prompts:
//   [ {CHANGE_SORT} Sort  {OPEN_CONTEXT_MENU} Options  {HOME} Notifications ]
// Sort appears only where an X/Square press really re-sorts the list on screen
// (XMBUiState.canSortCurrentList), and Options only where the focused item really has a
// context menu (XMBUiState.focusedItemHasContextMenu). Both are conditional because a pill
// promising an action that does nothing is worse than a smaller pill.
//
// Visibility is driven entirely by XMBUiState.showContextMenuHint (the shell/detail screens
// fade it in but remove it immediately when it becomes ineligible); this composable only renders
// its content.
//
// The pill chrome itself is the shared core-ui [ControllerHintBar] — the App Drawer renders the
// same pill for its own actions (see feature-appbar's AppDrawerHintBar).

/**
 * Where an idle hint pill sits, anywhere in the launcher: bottom-right, against the screen edge.
 *
 * Shared rather than repeated so every surface that fades one in — the crossbar, the notification
 * panel — puts it in the same place. A helper that moves depending on which screen raised it is a
 * helper the eye has to hunt for, which is most of the value gone.
 */
val HintPillEndPadding = 20.dp
val HintPillBottomPadding = 24.dp

/** Raised clear of the touch App Drawer button on the surfaces where that button is also up. */
val HintPillBottomPaddingAboveDrawerButton = 76.dp

@Composable
fun ContextMenuHint(
    modifier: Modifier = Modifier,
    /** Show the Sort half — the current list responds to CHANGE_SORT. */
    showSort: Boolean = false,
    /**
     * What that half is called. "Sort" everywhere the press cycles the order; "Filter" on the
     * Games column, where it opens a menu of Search and Sort instead. The pill names actions, so
     * a press that stopped cycling has to stop saying it cycles.
     */
    sortLabel: String = "Sort",
    /** Show the Options half — the focused item has a context menu. */
    showOptions: Boolean = true,
    /**
     * Show the Notifications half — START opens the panel.
     *
     * Last, and unconditional on the XMB: unlike Sort and Options it does not depend on what has
     * focus, and it is the only affordance for a surface that is otherwise advertised by one
     * small bell. On a controller the bell is a plain readout with no press target, so this pill
     * is where the panel is discovered at all.
     */
    showNotifications: Boolean = true,
) {
    val items = buildList {
        if (showSort) add(ControllerPromptItem(GamepadAction.CHANGE_SORT, sortLabel))
        if (showOptions) add(ControllerPromptItem(GamepadAction.OPEN_CONTEXT_MENU, "Options"))
        if (showNotifications) add(ControllerPromptItem(GamepadAction.HOME, "Notifications"))
    }
    ControllerHintBar(items = items, modifier = modifier)
}

// ── Previews ──────────────────────────────────────────────────────────────────

@CombinedPreviews
@Composable
fun ContextMenuHintPreview() {
    PfpPreview {
        // One pill per family, each given its own ambient style so the preview
        // still shows △ / Y / X side by side now that the hint reads context.
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (family in ControllerDisplayType.entries) {
                CompositionLocalProvider(
                    LocalControllerPromptStyle provides ControllerPromptStyle(family = family),
                ) {
                    ContextMenuHint(showSort = true, showOptions = true)
                }
                Spacer(Modifier.size(8.dp))
            }
        }
    }
}
