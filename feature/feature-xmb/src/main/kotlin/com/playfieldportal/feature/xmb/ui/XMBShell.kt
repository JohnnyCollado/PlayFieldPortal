package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.ui.theme.PFPTheme
import com.playfieldportal.feature.appbar.AppDrawerScreen
import com.playfieldportal.feature.appbar.AppFilter
import com.playfieldportal.feature.settings.ui.SettingsNavHost
import com.playfieldportal.feature.xmb.preview.PreviewData
import com.playfieldportal.feature.xmb.ui.app.AppDetailScreen
import com.playfieldportal.feature.xmb.ui.detail.GameDetailScreen
import com.playfieldportal.feature.xmb.viewmodel.XMBUiState
import com.playfieldportal.feature.xmb.viewmodel.XMBViewModel

@Composable
fun XMBShellContainer(
    viewModel: XMBViewModel = hiltViewModel(),
    onSettingsLongPress: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()

    XMBShell(
        uiState = uiState,
        onCategorySelected = viewModel::onCategorySelected,
        onItemSelected = viewModel::onItemSelected,
        onItemLongPress = viewModel::onItemLongPress,
        onPlatformLongPress = viewModel::onPlatformLongPress,
        onUserInteraction = viewModel::onUserInteraction,
        onBootComplete = viewModel::onBootSequenceComplete,
        onSettingsLongPress = onSettingsLongPress,
        onCloseSettingsScreen = viewModel::onCloseSettingsScreen,
        onSettingsActionConsumed = viewModel::consumeSettingsAction,
        onCloseAppDrawer = viewModel::onCloseAppDrawer,
        onDrawerActionConsumed = viewModel::consumeDrawerAction,
        onCloseGameDetail = viewModel::onCloseGameDetail,
        onGameDetailActionConsumed = viewModel::consumeGameDetailAction,
        onCloseAppDetail = viewModel::onCloseAppDetail,
        onAppDetailActionConsumed = viewModel::consumeAppDetailAction,
        onContextMenuItemActivated = viewModel::onContextMenuItemActivatedAt,
        onContextMenuDismiss = viewModel::closeContextMenu,
        onOpenColorSchemePicker = viewModel::openColorSchemePicker,
        onColorSchemeHighlightedAt = viewModel::onColorSchemeHighlightedAt,
        onColorSchemeConfirm = viewModel::confirmColorSchemePicker,
        onColorSchemeCancel = viewModel::cancelColorSchemePicker,
        onConfirmAppRename = viewModel::onConfirmAppRename,
        onCancelAppRename = viewModel::onCancelAppRename,
        onConfirmCollectionName = viewModel::onConfirmCollectionName,
        onCancelCollectionName = viewModel::onCancelCollectionName,
        onAppPickerActivatedAt = viewModel::onAppPickerActivatedAt,
        onAppPickerConfirm = viewModel::onAppPickerConfirm,
        onAppPickerDismiss = viewModel::closeAppPicker,
        onGamePickerConfirm = viewModel::confirmGamePicker,
        onGamePickerDismiss = viewModel::closeGamePicker,
        onGamePickerActionConsumed = viewModel::consumeGamePickerAction,
        onDismissInfoDialog = viewModel::dismissInfoDialog,
    )
}

@Composable
fun XMBShell(
    uiState: XMBUiState,
    onCategorySelected: (Int) -> Unit = {},
    onItemSelected: (Int) -> Unit = {},
    onItemLongPress: (Int) -> Unit = {},
    onPlatformLongPress: (Int) -> Unit = {},
    onUserInteraction: () -> Unit = {},
    onBootComplete: () -> Unit = {},
    onSettingsLongPress: () -> Unit = {},
    onCloseSettingsScreen: () -> Unit = {},
    onSettingsActionConsumed: () -> Unit = {},
    onCloseAppDrawer: () -> Unit = {},
    onDrawerActionConsumed: () -> Unit = {},
    onCloseGameDetail: () -> Unit = {},
    onGameDetailActionConsumed: () -> Unit = {},
    onCloseAppDetail: () -> Unit = {},
    onAppDetailActionConsumed: () -> Unit = {},
    onContextMenuItemActivated: (Int) -> Unit = {},
    onContextMenuDismiss: () -> Unit = {},
    onOpenColorSchemePicker: () -> Unit = {},
    onColorSchemeHighlightedAt: (Int) -> Unit = {},
    onColorSchemeConfirm: () -> Unit = {},
    onColorSchemeCancel: () -> Unit = {},
    onConfirmAppRename: (String) -> Unit = {},
    onCancelAppRename: () -> Unit = {},
    onConfirmCollectionName: (String) -> Unit = {},
    onCancelCollectionName: () -> Unit = {},
    onAppPickerActivatedAt: (Int) -> Unit = {},
    onAppPickerConfirm: () -> Unit = {},
    onAppPickerDismiss: () -> Unit = {},
    onGamePickerConfirm: (Set<Long>, Set<Long>) -> Unit = { _, _ -> },
    onGamePickerDismiss: () -> Unit = {},
    onGamePickerActionConsumed: () -> Unit = {},
    onDismissInfoDialog: () -> Unit = {},
) {
    PFPTheme(colors = uiState.themeColors) {
        Box(modifier = Modifier.fillMaxSize()) {
            XmbBackground(
                waveStyle           = uiState.waveStyle,
                customWallpaperPath = uiState.customWallpaperPath,
                modifier            = Modifier.fillMaxSize(),
            )

            // Per-game background art (XMB hover): reads only artworkUri — the dedicated
            // background slot. heroUri is reserved for the Game Detail hero banner.
            val selectedBg = uiState.currentItems.getOrNull(uiState.selectedItemIndex)
                ?.artworkUri
            Crossfade(targetState = selectedBg, animationSpec = tween(320), label = "xmbGameBackground") { bg ->
                if (bg != null) {
                    Box(Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = bg,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.horizontalGradient(
                                    0.0f to Color(0xCC05050C),
                                    0.5f to Color(0x9905050C),
                                    1.0f to Color(0xE605050C),
                                )
                            )
                        )
                    }
                }
            }

            XmbPspStatusStrip(
                modifier = Modifier.align(Alignment.TopCenter),
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 44.dp),
            ) {
                XMBCategoryBar(
                    categories = uiState.categories,
                    selectedIndex = uiState.selectedCategoryIndex,
                    onCategorySelected = onCategorySelected,
                    onCategoryLongPress = { index ->
                        val id = uiState.categories.getOrNull(index)?.id
                        if (id == BuiltInCategory.SETTINGS) onSettingsLongPress()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(128.dp),
                )

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    // Line the subitem column up with the XMB crossbar: the category bar anchors
                    // its selected slot to XmbLeftAnchor, so anchor the item column's left edge to
                    // that same x. The selected category and its subitems share one vertical line.
                    val startPad = XmbLeftAnchor

                    AnimatedContent(
                        targetState = uiState.selectedCategoryIndex,
                        transitionSpec = {
                            (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 8 })
                                .togetherWith(fadeOut(tween(160)) + slideOutVertically(tween(180)) { -it / 10 })
                                .using(SizeTransform(clip = false))
                        },
                        label = "xmbCategoryItems",
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .padding(start = startPad, end = 24.dp, top = 6.dp),
                    ) { categoryIndex ->
                        // During a category transition AnimatedContent briefly composes BOTH the
                        // outgoing and incoming lists. Only the settled (current) category may
                        // render the selection highlight — otherwise the outgoing copy shows a
                        // duplicate enlarged row that slides away (a "second cursor").
                        XMBItemList(
                            items = uiState.currentItems,
                            selectedIndex = if (categoryIndex == uiState.selectedCategoryIndex) uiState.selectedItemIndex else -1,
                            onItemSelected = onItemSelected,
                            onItemLongPress = onItemLongPress,
                            iconStyle = uiState.iconStyle,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            if (uiState.showBootSequence) {
                BootSequenceOverlay(onComplete = onBootComplete)
            }

            // The Settings screen is suppressed while the color-scheme picker is open so
            // the live wave preview shows through behind the picker (PSP-style).
            if (uiState.colorSchemePicker == null) {
                uiState.activeSettingsScreen?.let { screenId ->
                    SettingsNavHost(
                        screenId = screenId,
                        onBack = onCloseSettingsScreen,
                        pendingGamepadAction = uiState.pendingSettingsAction,
                        onGamepadActionConsumed = onSettingsActionConsumed,
                        onOpenColorSchemePicker = onOpenColorSchemePicker,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            uiState.activeAppDrawerFilter?.let { filterName ->
                val initialFilter = runCatching { AppFilter.valueOf(filterName) }
                    .getOrDefault(AppFilter.ALL)
                AppDrawerScreen(
                    initialFilter = initialFilter,
                    onBack = onCloseAppDrawer,
                    pendingGamepadAction = uiState.pendingDrawerAction,
                    onGamepadActionConsumed = onDrawerActionConsumed,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            uiState.activeContextMenu?.let { menu ->
                ContextMenuOverlay(
                    menu = menu,
                    onItemActivated = onContextMenuItemActivated,
                    onDismiss = onContextMenuDismiss,
                )
            }

            uiState.colorSchemePicker?.let { picker ->
                ColorSchemePickerOverlay(
                    state = picker,
                    onHighlightedAt = onColorSchemeHighlightedAt,
                    onConfirm = onColorSchemeConfirm,
                    onDismiss = onColorSchemeCancel,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            uiState.renameAppTarget?.let {
                AppRenameDialog(
                    currentLabel = uiState.renameAppCurrent.orEmpty(),
                    onConfirm = onConfirmAppRename,
                    onCancel = onCancelAppRename,
                )
            }

            uiState.collectionNameDialog?.let { dialog ->
                CollectionNameDialog(
                    title = dialog.title,
                    initialText = dialog.initialText,
                    onConfirm = onConfirmCollectionName,
                    onCancel = onCancelCollectionName,
                )
            }

            uiState.infoDialog?.let { dialog ->
                InfoDialog(
                    title = dialog.title,
                    message = dialog.message,
                    onDismiss = onDismissInfoDialog,
                )
            }

            uiState.appPicker?.let { picker ->
                InstalledAppPicker(
                    state = picker,
                    onActivateAt = onAppPickerActivatedAt,
                    onConfirm = onAppPickerConfirm,
                    onDismiss = onAppPickerDismiss,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            uiState.gamePickerCategoryId?.let {
                GamePickerScreen(
                    onConfirm = onGamePickerConfirm,
                    onCancel = onGamePickerDismiss,
                    pendingGamepadAction = uiState.pendingGamePickerAction,
                    onGamepadActionConsumed = onGamePickerActionConsumed,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            uiState.activeGameId?.let { gameId ->
                GameDetailScreen(
                    gameId = gameId,
                    onBack = onCloseGameDetail,
                    pendingGamepadAction = uiState.pendingGameDetailAction,
                    onGamepadActionConsumed = onGameDetailActionConsumed,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            uiState.activeAppId?.let { appId ->
                AppDetailScreen(
                    gameId = appId,
                    onBack = onCloseAppDetail,
                    pendingGamepadAction = uiState.pendingAppDetailAction,
                    onGamepadActionConsumed = onAppDetailActionConsumed,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

}

@Composable
private fun AppRenameDialog(
    currentLabel: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember(currentLabel) { mutableStateOf(currentLabel) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Rename Shortcut") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun CollectionNameDialog(
    title: String,
    initialText: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("e.g. RPGs, Currently Playing") },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun InfoDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Preview(name = "XMB - Default (Games)", widthDp = 960, heightDp = 540, showBackground = true)
@Composable
private fun PreviewXMBDefault() {
    XMBShell(uiState = PreviewData.defaultState)
}

@Preview(name = "XMB - Empty Library", widthDp = 960, heightDp = 540, showBackground = true)
@Composable
private fun PreviewXMBEmpty() {
    XMBShell(uiState = PreviewData.emptyLibraryState)
}

@Preview(name = "XMB - Boot Sequence", widthDp = 960, heightDp = 540, showBackground = true)
@Composable
private fun PreviewXMBBoot() {
    XMBShell(uiState = PreviewData.bootState)
}
