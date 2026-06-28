package com.playfieldportal.launcher

import androidx.compose.runtime.Composable
import com.playfieldportal.feature.xmb.ui.XMBShellContainer

// Release variant: no debug code — go straight to the shell.
@Composable
fun AppXmbHost() {
    XMBShellContainer()
}
