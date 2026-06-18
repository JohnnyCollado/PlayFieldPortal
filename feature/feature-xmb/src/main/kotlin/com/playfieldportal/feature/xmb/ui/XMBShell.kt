package com.playfieldportal.feature.xmb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.ui.preview.PreviewData
import com.playfieldportal.core.ui.theme.PFPTheme
import com.playfieldportal.feature.appbar.AppDrawerScreen
import com.playfieldportal.feature.appbar.AppFilter
import com.playfieldportal.feature.settings.ui.SettingsNavHost
import com.playfieldportal.feature.xmb.ui.detail.GameDetailScreen
import com.playfieldportal.core.ui.wave.WaveRenderMode
import com.playfieldportal.core.ui.wave.XMBWave
import com.playfieldportal.feature.xmb.viewmodel.BackgroundTaskInfo
import com.playfieldportal.feature.xmb.viewmodel.XMBItem
import com.playfieldportal.feature.xmb.viewmodel.XMBUiState
import com.playfieldportal.feature.xmb.viewmodel.XMBViewModel

// ── Production entry point ────────────────────────────────────────────────────
// Wires the real ViewModel — used in MainActivity and DebugAwareXMBHost
@Composable
fun XMBShellContainer(
    viewModel: XMBViewModel = hiltViewModel(),
    // Debug builds pass a callback here to intercept Settings long-press
    onSettingsLongPress: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()

    XMBShell(
        uiState               = uiState,
        onCategorySelected    = viewModel::onCategorySelected,
        onItemSelected        = viewModel::onItemSelected,
        onItemLongPress       = viewModel::onItemLongPress,
        onUserInteraction     = viewModel::onUserInteraction,
        onBootComplete        = viewModel::onBootSequenceComplete,
        onTaskBadgeTapped     = { viewModel.onTaskTrayVisibility(true) },
        onDismissTaskTray     = viewModel::onDismissTaskTray,
        onSettingsLongPress   = onSettingsLongPress,
        onCloseSettingsScreen = viewModel::onCloseSettingsScreen,
        onCloseAppDrawer      = viewModel::onCloseAppDrawer,
        onCloseGameDetail     = viewModel::onCloseGameDetail,
    )
}

// ── Pure UI shell — accepts state and callbacks, no ViewModel dependency ──────
// Used directly in Compose previews and the debug menu
@Composable
fun XMBShell(
    uiState: XMBUiState,
    onCategorySelected: (Int) -> Unit = {},
    onItemSelected: (Int) -> Unit = {},
    onItemLongPress: (Int) -> Unit = {},
    onUserInteraction: () -> Unit = {},
    onBootComplete: () -> Unit = {},
    onTaskBadgeTapped: () -> Unit = {},
    onDismissTaskTray: () -> Unit = {},
    onSettingsLongPress: () -> Unit = {},
    onCloseSettingsScreen: () -> Unit = {},
    onCloseAppDrawer: () -> Unit = {},
    onCloseGameDetail: () -> Unit = {},
) {
    PFPTheme(colors = uiState.themeColors) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // ── Wave background ──────────────────────────────────────────────
        XMBWave(
            modifier = Modifier.fillMaxSize(),
            renderMode = uiState.waveRenderMode,
        )

        // ── XMB Chrome ───────────────────────────────────────────────────
        Column(modifier = Modifier.fillMaxSize()) {

            XMBStatusBar(
                backgroundTaskCount = uiState.activeBackgroundTasks,
                onTaskBadgeTapped   = onTaskBadgeTapped,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )

            XMBCategoryBar(
                categories         = uiState.categories,
                selectedIndex      = uiState.selectedCategoryIndex,
                onCategorySelected = onCategorySelected,
                onCategoryLongPress = { index ->
                    val id = uiState.categories.getOrNull(index)?.id
                    if (id == BuiltInCategory.SETTINGS) onSettingsLongPress()
                },
                modifier = Modifier.padding(top = 16.dp),
            )

            XMBItemList(
                items           = uiState.currentItems,
                selectedIndex   = uiState.selectedItemIndex,
                onItemSelected  = onItemSelected,
                onItemLongPress = onItemLongPress,
                iconStyle       = uiState.iconStyle,
                modifier = Modifier
                    .padding(top = 24.dp, start = 80.dp)
                    .weight(1f),
            )
        }

        // ── Boot sequence overlay ─────────────────────────────────────────
        if (uiState.showBootSequence) {
            BootSequenceOverlay(onComplete = onBootComplete)
        }

        // ── Background task tray ──────────────────────────────────────────
        if (uiState.showTaskTray) {
            BackgroundTaskTray(
                tasks     = uiState.backgroundTasks,
                onDismiss = onDismissTaskTray,
            )
        }

        // ── Settings sub-screen overlay ───────────────────────────────────
        uiState.activeSettingsScreen?.let { screenId ->
            SettingsNavHost(
                screenId  = screenId,
                onBack    = onCloseSettingsScreen,
                modifier  = Modifier.fillMaxSize(),
            )
        }

        // ── App Drawer overlay ────────────────────────────────────────────
        uiState.activeAppDrawerFilter?.let { filterName ->
            val initialFilter = runCatching { AppFilter.valueOf(filterName) }
                .getOrDefault(AppFilter.ALL)
            AppDrawerScreen(
                initialFilter = initialFilter,
                onBack        = onCloseAppDrawer,
                modifier      = Modifier.fillMaxSize(),
            )
        }

        // ── Game Detail overlay ───────────────────────────────────────────
        uiState.activeGameId?.let { gameId ->
            GameDetailScreen(
                gameId   = gameId,
                onBack   = onCloseGameDetail,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    } // end PFPTheme
}

// ── Compose Previews ──────────────────────────────────────────────────────────

@Preview(name = "XMB — Default (Games)", widthDp = 960, heightDp = 540, showBackground = true)
@Composable
private fun PreviewXMBDefault() {
    XMBShell(uiState = PreviewData.defaultState)
}

@Preview(name = "XMB — Empty Library", widthDp = 960, heightDp = 540, showBackground = true)
@Composable
private fun PreviewXMBEmpty() {
    XMBShell(uiState = PreviewData.emptyLibraryState)
}

@Preview(name = "XMB — With Task Tray", widthDp = 960, heightDp = 540, showBackground = true)
@Composable
private fun PreviewXMBWithTasks() {
    XMBShell(uiState = PreviewData.withTasksState)
}

@Preview(name = "XMB — Boot Sequence", widthDp = 960, heightDp = 540, showBackground = true)
@Composable
private fun PreviewXMBBoot() {
    XMBShell(uiState = PreviewData.bootState)
}
