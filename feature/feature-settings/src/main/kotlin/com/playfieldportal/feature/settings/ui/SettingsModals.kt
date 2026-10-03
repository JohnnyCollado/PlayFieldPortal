package com.playfieldportal.feature.settings.ui

import androidx.compose.runtime.Composable
import com.playfieldportal.core.ui.components.PfpModalHost
import com.playfieldportal.core.ui.components.PfpModalSpec
import com.playfieldportal.core.ui.components.rememberPfpModalHost

// ── The shared modals, as a settings screen uses them ─────────────────────────
//
//     val modal = rememberSettingsModal(
//         if (state.confirmClearVisible) PfpModalSpec.Confirm(...) else null
//     )
//     SettingsScaffold(..., modalOpen = modal.open, onInterceptAction = modal.intercept) { ... }
//     modal.Content()
//
// The host itself is core-ui's; all a settings screen adds is that the controller hints follow the
// settings layer's own "show hints" state, like the scaffold's footer does.
@Composable
internal fun rememberSettingsModal(spec: PfpModalSpec?): PfpModalHost =
    rememberPfpModalHost(spec, showHints = LocalSettingsShowControllerHint.current)
