package com.playfieldportal.launcher

import androidx.compose.runtime.Composable
import com.playfieldportal.feature.xmb.ui.XMBShellContainer
import com.playfieldportal.launcher.debug.DebugAwareXMBHost

// Debug variant: wraps the shell so long-pressing Settings opens the debug menu.
@Composable
fun AppXmbHost() {
    DebugAwareXMBHost { onSettingsLongPress ->
        XMBShellContainer(onSettingsLongPress = onSettingsLongPress)
    }
}
