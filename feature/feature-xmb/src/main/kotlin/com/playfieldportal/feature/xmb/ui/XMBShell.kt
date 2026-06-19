package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.feature.xmb.preview.PreviewData
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
        onPlatformLongPress   = viewModel::onPlatformLongPress,
        onUserInteraction     = viewModel::onUserInteraction,
        onBootComplete        = viewModel::onBootSequenceComplete,
        onTaskBadgeTapped     = { viewModel.onTaskTrayVisibility(true) },
        onDismissTaskTray     = viewModel::onDismissTaskTray,
        onSettingsLongPress   = onSettingsLongPress,
        onCloseSettingsScreen = viewModel::onCloseSettingsScreen,
        onCloseAppDrawer           = viewModel::onCloseAppDrawer,
        onDrawerActionConsumed     = viewModel::consumeDrawerAction,
        onCloseGameDetail          = viewModel::onCloseGameDetail,
        onGameDetailActionConsumed = viewModel::consumeGameDetailAction,
        onContextMenuItemActivated = viewModel::onContextMenuItemActivatedAt,
        onContextMenuDismiss       = viewModel::closeContextMenu,
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
    onPlatformLongPress: (Int) -> Unit = {},
    onUserInteraction: () -> Unit = {},
    onBootComplete: () -> Unit = {},
    onTaskBadgeTapped: () -> Unit = {},
    onDismissTaskTray: () -> Unit = {},
    onSettingsLongPress: () -> Unit = {},
    onCloseSettingsScreen: () -> Unit = {},
    onCloseAppDrawer: () -> Unit = {},
    onDrawerActionConsumed: () -> Unit = {},
    onCloseGameDetail: () -> Unit = {},
    onGameDetailActionConsumed: () -> Unit = {},
    onContextMenuItemActivated: (Int) -> Unit = {},
    onContextMenuDismiss: () -> Unit = {},
) {
    // Derive game background from the selected item when browsing a game list
    // (not when a detail overlay is open — the overlay has its own background)
    val selectedItem  = uiState.currentItems.getOrNull(uiState.selectedItemIndex)
    val bgArtworkUri  = selectedItem?.artworkUri?.takeIf {
        uiState.activeGameId == null && uiState.activeSettingsScreen == null &&
        uiState.activeAppDrawerFilter == null
    }
    val adjacentItems = if (bgArtworkUri != null) {
        listOfNotNull(
            uiState.currentItems.getOrNull(uiState.selectedItemIndex - 1)
                ?.takeIf { it.artworkUri != null },
            uiState.currentItems.getOrNull(uiState.selectedItemIndex + 1)
                ?.takeIf { it.artworkUri != null },
        )
    } else emptyList()

    PFPTheme(colors = uiState.themeColors) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // ── Game background artwork (when a game is selected in the list) ─
        AnimatedVisibility(
            visible  = bgArtworkUri != null,
            enter    = fadeIn(tween(400)),
            exit     = fadeOut(tween(300)),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (bgArtworkUri != null) {
                AsyncImage(
                    model              = bgArtworkUri,
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize(),
                )
            }
        }

        // ── Gradient overlay when game art is showing ─────────────────────
        if (bgArtworkUri != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0.0f  to Color(0xCC000000),
                                0.4f  to Color(0x88000000),
                                1.0f  to Color(0xDD000000),
                            )
                        )
                    )
            )
        }

        // ── Wave background (shown when no game art, or blended behind) ──
        if (bgArtworkUri == null) {
            XMBWave(
                modifier   = Modifier.fillMaxSize(),
                renderMode = uiState.waveRenderMode,
            )
        }

        // ── Adjacent game thumbnail strip (upper-left) ────────────────────
        if (adjacentItems.isNotEmpty()) {
            Column(
                modifier            = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 20.dp, top = 72.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                adjacentItems.take(2).forEach { adj ->
                    AsyncImage(
                        model              = adj.artworkUri,
                        contentDescription = adj.title,
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier
                            .size(width = 66.dp, height = 44.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(3.dp))
                            .background(Color(0x33000000)),
                    )
                }
            }
        }

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
                categories          = uiState.categories,
                selectedIndex       = uiState.selectedCategoryIndex,
                onCategorySelected  = onCategorySelected,
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
                    .padding(top = 16.dp, start = 80.dp)
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
                initialFilter        = initialFilter,
                onBack               = onCloseAppDrawer,
                pendingGamepadAction = uiState.pendingDrawerAction,
                onGamepadActionConsumed = onDrawerActionConsumed,
                modifier             = Modifier.fillMaxSize(),
            )
        }

        // ── Context menu overlay (Y / Triangle) ──────────────────────────
        uiState.activeContextMenu?.let { menu ->
            ContextMenuOverlay(
                menu             = menu,
                onItemActivated  = onContextMenuItemActivated,
                onDismiss        = onContextMenuDismiss,
            )
        }

        // ── Game Detail overlay ───────────────────────────────────────────
        uiState.activeGameId?.let { gameId ->
            GameDetailScreen(
                gameId                  = gameId,
                onBack                  = onCloseGameDetail,
                pendingGamepadAction    = uiState.pendingGameDetailAction,
                onGamepadActionConsumed = onGameDetailActionConsumed,
                modifier                = Modifier.fillMaxSize(),
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
