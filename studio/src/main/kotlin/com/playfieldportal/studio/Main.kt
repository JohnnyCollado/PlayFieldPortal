package com.playfieldportal.studio

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.playfieldportal.studio.ui.StudioApp
import com.playfieldportal.studio.ui.handleShellKey

fun main() = application {
    val scope = rememberCoroutineScope()
    val viewModel = remember { StudioViewModel(scope) }
    Window(
        onCloseRequest = ::exitApplication,
        title = "PlayField Theme Studio",
        // Opens maximized; restoring falls back to the 1280 x 760 frame.
        state = rememberWindowState(placement = WindowPlacement.Maximized, size = DpSize(1280.dp, 760.dp)),
        // Undo/redo shortcuts: only reached when no focused control (e.g. a text field) took the key.
        onKeyEvent = { event -> handleShellKey(event, viewModel) },
    ) {
        StudioApp(viewModel = viewModel, window = window)
    }
}
