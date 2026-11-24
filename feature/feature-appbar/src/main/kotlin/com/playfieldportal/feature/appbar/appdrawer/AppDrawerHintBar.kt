package com.playfieldportal.feature.appbar.appdrawer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.ControllerHintBar
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.feature.appbar.R

// ── Controller hint bar (idle pill) ───────────────────────────────────────────
//
// The App Drawer's contextual helper: the same shared pill [ControllerHintBar] (see feature-xmb's
// ContextMenuHint) names the drawer's actions once the user has been idle with a controller.
// Glyphs are resolved from the action set by GamepadMappings, so they honour the user's
// controller family and X/Y swap — no hard-coded letters.
//
// Rendered as a permanent footer row at the bottom of the drawer's Column (below the grid);
// visibility is alpha-driven by the caller, so the slot's height is reserved whether or not the
// pill is showing and content geometry never changes when it fades in or out.

@Composable
internal fun AppDrawerHintBar(
    modifier: Modifier = Modifier,
) {
    ControllerHintBar(
        items = listOf(
            ControllerPromptItem(GamepadAction.PREV_CATEGORY, stringResource(R.string.apps_hint_prev)),
            ControllerPromptItem(GamepadAction.NEXT_CATEGORY, stringResource(R.string.apps_hint_next)),
            ControllerPromptItem(GamepadAction.BACK, stringResource(R.string.apps_hint_back)),
            ControllerPromptItem(GamepadAction.SELECT, stringResource(R.string.apps_hint_launch)),
            ControllerPromptItem(GamepadAction.OPEN_CONTEXT_MENU, stringResource(R.string.apps_hint_options)),
            ControllerPromptItem(GamepadAction.CHANGE_SORT, stringResource(R.string.apps_hint_search)),
        ),
        // Darker than the shared pill's default (black @ 50%) so the drawer's brighter
        // background and busy artwork don't wash the fill out into a pale grey — but still
        // translucent enough that the background reads through it. The XMB's own pill keeps its
        // lighter default over the darker XMB backdrop.
        background = Color.Black.copy(alpha = 0.70f),
        modifier = modifier,
    )
}
