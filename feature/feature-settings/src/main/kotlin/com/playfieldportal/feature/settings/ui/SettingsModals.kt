package com.playfieldportal.feature.settings.ui

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Resources
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
/**
 * The resources a settings screen resolves modal and menu text from. Modal specs and menu rows are
 * plain-String types, built in plain functions, so the screen resolves their text here; reading the
 * configuration makes the screen rebuild them when the language changes.
 */
@Composable
internal fun rememberSettingsResources(): Resources {
    LocalConfiguration.current
    return LocalContext.current.resources
}

@Composable
internal fun rememberSettingsModal(spec: PfpModalSpec?): PfpModalHost =
    rememberPfpModalHost(spec, showHints = LocalSettingsShowControllerHint.current)
