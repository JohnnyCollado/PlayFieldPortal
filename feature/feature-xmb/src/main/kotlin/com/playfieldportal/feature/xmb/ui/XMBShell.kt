package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import com.playfieldportal.core.ui.theme.LocalPFPColors
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.ui.theme.PFPTheme
import com.playfieldportal.feature.appbar.AppDrawerScreen
import com.playfieldportal.feature.appbar.AppFilter
import com.playfieldportal.feature.settings.ui.SettingsNavHost
import com.playfieldportal.feature.xmb.preview.PreviewData
import androidx.media3.common.util.UnstableApi
import com.playfieldportal.feature.xmb.ui.app.AppDetailScreen
import com.playfieldportal.feature.xmb.ui.detail.GameDetailScreen
import com.playfieldportal.feature.xmb.ui.detail.VideoDetailScreen
import com.playfieldportal.feature.xmb.viewmodel.XMBUiState
import com.playfieldportal.feature.xmb.viewmodel.XMBViewModel

/**
 * Stateful entry point for the XMB home screen: collects [XMBViewModel.uiState] and wires the
 * ViewModel's callbacks into the stateless [XMBShell]. This is what the host activity renders.
 */
@Composable
fun XMBShellContainer(
    viewModel: XMBViewModel = hiltViewModel(),
    onSettingsLongPress: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()

    XMBShell(
        uiState = uiState,
        onCategorySelected = viewModel::onCategorySelected,
        onStepCategory = viewModel::stepCategory,
        onTouchBack = viewModel::onHomeBack,
        onOpenAppDrawer = viewModel::onOpenAppDrawer,
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
        onCloseVideoDetail = viewModel::onCloseVideoDetail,
        onVideoDetailActionConsumed = viewModel::consumeVideoDetailAction,
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
        onConfirmPlaylistName = viewModel::onConfirmPlaylistName,
        onCancelPlaylistName = viewModel::onCancelPlaylistName,
        onMusicTrackPickerActivatedAt = viewModel::onMusicTrackPickerActivatedAt,
        onMusicTrackPickerConfirm = viewModel::onMusicTrackPickerConfirm,
        onMusicTrackPickerDismiss = viewModel::closeMusicTrackPicker,
        onMusicBrowserQueryChange = viewModel::onMusicBrowserQueryChange,
        onMusicBrowserActivatedAt = viewModel::onMusicBrowserActivatedAt,
        onMusicBrowserLongPressAt = viewModel::onMusicBrowserLongPressAt,
        onMusicBrowserBack = viewModel::onMusicBrowserBack,
        onAppPickerActivatedAt = viewModel::onAppPickerActivatedAt,
        onAppPickerConfirm = viewModel::onAppPickerConfirm,
        onAppPickerDismiss = viewModel::closeAppPicker,
        onGamePickerConfirm = viewModel::confirmGamePicker,
        onGamePickerDismiss = viewModel::closeGamePicker,
        onGamePickerActionConsumed = viewModel::consumeGamePickerAction,
        onDismissInfoDialog = viewModel::dismissInfoDialog,
        onMusicPlayPause = viewModel::musicPlayPause,
        onMusicPrev = viewModel::musicPrev,
        onMusicNext = viewModel::musicNext,
        onMusicSeekTo = viewModel::musicSeekTo,
        onMusicPlayerBack = viewModel::closeMusicPlayer,
        onOpenAndroidLibraryPicker = viewModel::openAndroidLibraryPicker,
    )
}

@OptIn(UnstableApi::class)
@Composable
fun XMBShell(
    uiState: XMBUiState,
    onCategorySelected: (Int) -> Unit = {},
    onStepCategory: (Int) -> Unit = {},
    onTouchBack: () -> Unit = {},
    onOpenAppDrawer: () -> Unit = {},
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
    onCloseVideoDetail: () -> Unit = {},
    onVideoDetailActionConsumed: () -> Unit = {},
    onCloseAppDetail: () -> Unit = {},
    onAppDetailActionConsumed: () -> Unit = {},
    onContextMenuItemActivated: (Int) -> Unit = {},
    onContextMenuDismiss: () -> Unit = {},
    onMusicPlayPause: () -> Unit = {},
    onMusicPrev: () -> Unit = {},
    onMusicNext: () -> Unit = {},
    onMusicSeekTo: (Int) -> Unit = {},
    onMusicPlayerBack: () -> Unit = {},
    onOpenAndroidLibraryPicker: () -> Unit = {},
    onOpenColorSchemePicker: () -> Unit = {},
    onColorSchemeHighlightedAt: (Int) -> Unit = {},
    onColorSchemeConfirm: () -> Unit = {},
    onColorSchemeCancel: () -> Unit = {},
    onConfirmAppRename: (String) -> Unit = {},
    onCancelAppRename: () -> Unit = {},
    onConfirmCollectionName: (String) -> Unit = {},
    onCancelCollectionName: () -> Unit = {},
    onConfirmPlaylistName: (String) -> Unit = {},
    onCancelPlaylistName: () -> Unit = {},
    onMusicBrowserQueryChange: (String) -> Unit = {},
    onMusicBrowserActivatedAt: (Int) -> Unit = {},
    onMusicBrowserLongPressAt: (Int) -> Unit = {},
    onMusicBrowserBack: () -> Unit = {},
    onMusicTrackPickerActivatedAt: (Int) -> Unit = {},
    onMusicTrackPickerConfirm: () -> Unit = {},
    onMusicTrackPickerDismiss: () -> Unit = {},
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

            // Hide the XMB foreground (status strip + category bar + item list) while a fullscreen
            // menu (the music browser or a Settings screen) is open — only the wallpaper/wave
            // background shows behind it. Restored automatically when the menu closes.
            if (uiState.musicBrowser == null && uiState.activeSettingsScreen == null) {
            XmbPspStatusStrip(
                sortLabel = uiState.sortLabel,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 44.dp)
                    // Touch gestures on the home screen: a horizontal swipe steps the category
                    // selection (D-pad ◀ ▶ equivalent); a swipe that starts at the left edge goes
                    // Back (exit a folder, or open the app drawer at the root). Taps still pass
                    // through to the category/item rows; vertical list scrolling is unaffected.
                    .pointerInput(Unit) {
                        val edgePx      = 32.dp.toPx()
                        val thresholdPx = 64.dp.toPx()
                        var startX = 0f
                        var totalDx = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { offset -> startX = offset.x; totalDx = 0f },
                            onHorizontalDrag = { _, delta -> totalDx += delta },
                            onDragEnd = {
                                when {
                                    startX <= edgePx && totalDx > thresholdPx -> onTouchBack()
                                    totalDx <= -thresholdPx -> onStepCategory(1)   // swipe left → next
                                    totalDx >= thresholdPx  -> onStepCategory(-1)  // swipe right → previous
                                }
                            },
                        )
                    },
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    // The XMB cross: the crossbar sits toward the vertical centre so first-level items
                    // appear BOTH above it (scrolled-past, dissolving) and below it. The bar is drawn
                    // ON TOP of a full-height item column; the active item is anchored just under the
                    // caticon (anchorTop = barTop + bar height) and previous items rise up through the
                    // bar band to dissolve. The column's leading icon is shifted right so it lands on
                    // the same vertical line as the caticon (centred in its slot).
                    val catBarHeight = 112.dp
                    // Push the crossbar down so there is room for a full previous item ABOVE it.
                    val barTop = maxHeight * 0.26f
                    val anchorTop = barTop + catBarHeight
                    // Caticon centre minus the leading-icon inset ⇒ column shift that lands every
                    // item's icon centre on the caticon's vertical line (shared constant keeps the
                    // column offset and the row's scale pivot in lock-step).
                    val startPad = XmbLeftAnchor + (CategorySlotWidth / 2) - LEADING_ICON_CENTER

                    if (uiState.drillTitle != null) {
                        // Drilled into a Games sub-item: two-pane flyout. LEFT = the platform MEMORY
                        // CARDS (items) as the main-XMB cross, icon-only, ◀ after the active card.
                        // RIGHT = the GAME CARDS (rom icons), icon-only, centre-pinned on that ◀ line.
                        // The caticon bar keeps its drilled-in "hidden right".
                        XmbDrillFlyout(
                            siblings = uiState.drillSiblings,
                            siblingIndex = uiState.drillSiblingIndex,
                            items = uiState.currentItems,
                            selectedIndex = uiState.selectedItemIndex,
                            onItemSelected = onItemSelected,
                            onItemLongPress = onItemLongPress,
                            iconStyle = uiState.iconStyle,
                            barTopY = barTop,
                            belowTopY = anchorTop,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .fillMaxSize()
                                .padding(start = startPad, end = 24.dp),
                        )
                    } else {
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
                                .fillMaxSize()
                                .padding(start = startPad, end = 24.dp),
                        ) { categoryIndex ->
                            // During a category transition AnimatedContent briefly composes BOTH the
                            // outgoing and incoming lists. Only the settled (current) category may
                            // render the selection highlight — otherwise the outgoing copy shows a
                            // duplicate enlarged row that slides away (a "second cursor").
                            val itemSelectedIndex =
                                if (categoryIndex == uiState.selectedCategoryIndex) uiState.selectedItemIndex else -1
                            XMBItemList(
                                items = uiState.currentItems,
                                selectedIndex = itemSelectedIndex,
                                onItemSelected = onItemSelected,
                                onItemLongPress = onItemLongPress,
                                iconStyle = uiState.iconStyle,
                                scrollToTopToken = uiState.scrollToTopToken,
                                barTopY = barTop,
                                belowTopY = anchorTop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    // Category bar drawn ON TOP, pushed down to the crossbar line — the fixed pivot
                    // the first-level column appears to scroll beneath.
                    XMBCategoryBar(
                        categories = uiState.categories,
                        selectedIndex = uiState.selectedCategoryIndex,
                        onCategorySelected = onCategorySelected,
                        onCategoryLongPress = { index ->
                            val id = uiState.categories.getOrNull(index)?.id
                            if (id == BuiltInCategory.SETTINGS) onSettingsLongPress()
                        },
                        drilledIn = uiState.drillTitle != null,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(y = barTop)
                            .fillMaxWidth()
                            .height(catBarHeight),
                    )
                }
            }
            } // end: XMB foreground hidden while music browser is open

            // Bottom-right floating affordance. While drilled into a sub-item it becomes a Back
            // button (exits the sub-item); at the root it opens the app drawer. Hidden whenever an
            // overlay/dialog is up so it never floats over them.
            if (!uiState.hasBlockingOverlay) {
                val floatModifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 24.dp, end = 20.dp)
                if (uiState.isInSubItem) {
                    BackFloatingButton(onClick = onTouchBack, modifier = floatModifier)
                } else {
                    AppDrawerButton(onClick = onOpenAppDrawer, modifier = floatModifier)
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
                        onAddAndroidApps = onOpenAndroidLibraryPicker,
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

            // Fullscreen searchable music browser (Music / Playlist) — rendered before the player
            // and context menu so a track's options menu and the player draw on top of it.
            uiState.musicBrowser?.let { browser ->
                MusicBrowserScreen(
                    state = browser,
                    onQueryChange = onMusicBrowserQueryChange,
                    onActivateAt = onMusicBrowserActivatedAt,
                    onLongPressAt = onMusicBrowserLongPressAt,
                    onBack = onMusicBrowserBack,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // In-app music player — rendered before the context menu so the Y options menu
            // (Play in Background) draws on top of it.
            if (uiState.musicPlayerVisible) {
                MusicPlayerScreen(
                    state = uiState.musicPlayback,
                    onPlayPause = onMusicPlayPause,
                    onPrev = onMusicPrev,
                    onNext = onMusicNext,
                    onSeekTo = onMusicSeekTo,
                    onBack = onMusicPlayerBack,
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

            uiState.playlistNameDialog?.let { dialog ->
                CollectionNameDialog(
                    title = dialog.title,
                    initialText = dialog.initialText,
                    onConfirm = onConfirmPlaylistName,
                    onCancel = onCancelPlaylistName,
                )
            }

            uiState.infoDialog?.let { dialog ->
                InfoDialog(
                    title = dialog.title,
                    message = dialog.message,
                    onDismiss = onDismissInfoDialog,
                )
            }

            uiState.musicTrackPicker?.let { picker ->
                MusicTrackPicker(
                    state = picker,
                    onActivateAt = onMusicTrackPickerActivatedAt,
                    onConfirm = onMusicTrackPickerConfirm,
                    onDismiss = onMusicTrackPickerDismiss,
                    modifier = Modifier.fillMaxSize(),
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

            uiState.activeVideoId?.let { videoId ->
                VideoDetailScreen(
                    videoId = videoId,
                    onBack = onCloseVideoDetail,
                    pendingGamepadAction = uiState.pendingVideoDetailAction,
                    onGamepadActionConsumed = onVideoDetailActionConsumed,
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

/** Bottom-corner touch button that opens the app drawer — a 2×2 grid glyph drawn on a Canvas
 *  (no icon-library dependency). Controller users reach the drawer with BACK at the root instead. */
@Composable
private fun AppDrawerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Transparent background so the button blends into the wave; a thin accent ring + a soft dark
    // drop-shadow on the glyph keep it legible over both the dark top and bright bottom of the
    // gradient, and tie it to the active theme.
    val accent = LocalPFPColors.current.accentColor
    Box(
        modifier = modifier
            .size(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.5.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(26.dp)) {
            val cell = size.minDimension * 0.38f
            val gap = size.minDimension - 2 * cell
            val radius = CornerRadius(cell * 0.28f, cell * 0.28f)
            val shadowOffset = size.minDimension * 0.06f
            for (row in 0..1) {
                for (col in 0..1) {
                    val x = col * (cell + gap)
                    val y = row * (cell + gap)
                    // Soft dark shadow cell behind, then the bright glyph cell on top.
                    drawRoundRect(
                        color = Color(0x99000000),
                        topLeft = Offset(x + shadowOffset, y + shadowOffset),
                        size = Size(cell, cell),
                        cornerRadius = radius,
                    )
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(x, y),
                        size = Size(cell, cell),
                        cornerRadius = radius,
                    )
                }
            }
        }
    }
}

// Floating Back button shown (in place of the app-drawer button) while drilled into a sub-item.
@Composable
private fun BackFloatingButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalPFPColors.current.accentColor
    Box(
        modifier = modifier
            .size(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.5.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "◀",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            // Soft dark halo so the glyph reads on the bright lower gradient too.
            style = TextStyle(shadow = Shadow(Color(0xB3000000), Offset.Zero, 10f)),
        )
    }
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
