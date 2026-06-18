package com.playfieldportal.launcher.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel

// Wraps XMBShellContainer and intercepts the Settings long-press to show the debug menu.
// This file only exists in the debug source set — release builds use XMBShellContainer directly.
@Composable
fun DebugAwareXMBHost(
    xmbShellContainer: @Composable (onSettingsLongPress: () -> Unit) -> Unit,
) {
    var showDebugMenu by remember { mutableStateOf(false) }

    if (showDebugMenu) {
        DebugMenuScreen(
            onDismiss = { showDebugMenu = false },
        )
    } else {
        xmbShellContainer { showDebugMenu = true }
    }
}
