package com.playfieldportal.studio

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.playfieldportal.studio.ui.StudioApp

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "PlayField Theme Studio",
        state = rememberWindowState(size = DpSize(1280.dp, 760.dp)),
    ) {
        val scope = rememberCoroutineScope()
        val viewModel = remember { StudioViewModel(scope) }
        StudioApp(viewModel = viewModel, window = window)
    }
}
