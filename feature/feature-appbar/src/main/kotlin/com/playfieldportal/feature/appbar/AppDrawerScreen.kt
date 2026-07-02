package com.playfieldportal.feature.appbar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.playfieldportal.core.domain.model.GamepadAction
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
// compose-ui 1.6.x (Compose BOM 2024.06) only provides the platform LocalLifecycleOwner.
// The androidx.lifecycle.compose variant requires compose-ui 1.7+, so reading it crashes
// with "LocalLifecycleOwner not present". Use the platform one until the BOM is upgraded.
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.foundation.Image
import androidx.compose.runtime.snapshotFlow
import com.google.accompanist.drawablepainter.DrawablePainter
import com.playfieldportal.core.ui.theme.menuCursorEdge
import com.playfieldportal.core.ui.theme.menuCursorFill
import kotlinx.coroutines.flow.distinctUntilChanged

// Neutral surfaces stay fixed; accent/highlight colors come from the active theme via
// menuCursorFill()/menuCursorEdge() so the drawer follows the chosen color scheme.
private val DrawerText    = Color.White
private val DrawerSubtext = Color(0xFFAAAAAA)
private val DrawerChip    = Color(0xFF1A1A2E)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppDrawerScreen(
    onBack: () -> Unit,
    initialFilter: AppFilter = AppFilter.ALL,
    pendingGamepadAction: GamepadAction? = null,
    onGamepadActionConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AppDrawerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var searchActive by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Y toggles the search field. Other actions drive grid navigation as before. (BACK never
    // reaches here — XMBViewModel intercepts it to close the drawer.)
    LaunchedEffect(pendingGamepadAction) {
        if (pendingGamepadAction != null) {
            timber.log.Timber.d("AppDrawer: action=$pendingGamepadAction filter=${viewModel.uiState.value.activeFilter}")
            val overlayOpen = state.menuApp != null || state.confirmUninstall != null
            // Y toggles search only when no mini menu / dialog is up — otherwise it's forwarded so
            // the menu can consume it (Y/hold closes the menu).
            if (!overlayOpen && pendingGamepadAction == GamepadAction.BUTTON_Y) {
                searchActive = !searchActive
                if (!searchActive) viewModel.setSearchQuery("")
            } else {
                viewModel.handleGamepadAction(pendingGamepadAction)
            }
            onGamepadActionConsumed()
        }
    }

    // When search turns on, focus the field and open the keyboard; off hides it. Wait a couple
    // of frames first because the field mounts inside an AnimatedVisibility that lags a frame.
    LaunchedEffect(searchActive) {
        if (searchActive) {
            withFrameNanos {}
            withFrameNanos {}
            runCatching { searchFocus.requestFocus() }
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Apply initial filter once on first composition. Also clear any stale search query so the
    // drawer always opens showing the full (unfiltered) list — the ViewModel is retained across
    // open/close, and the search field starts hidden.
    val appliedInitial = remember { mutableStateOf(false) }
    if (!appliedInitial.value) {
        viewModel.setFilter(initialFilter)
        viewModel.setSearchQuery("")
        appliedInitial.value = true
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val pfpColors = com.playfieldportal.core.ui.theme.LocalPFPColors.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to pfpColors.backgroundTop.copy(alpha = 0.94f),
                    1f to pfpColors.backgroundBottom.copy(alpha = 0.94f),
                )
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Header ─────────────────────────────────────────────────────
            DrawerHeader(
                title        = state.activeFilter.label,
                appCount     = state.visibleApps.size,
                searchQuery  = state.searchQuery,
                searchActive = searchActive,
                searchFocus  = searchFocus,
                onSearchToggle   = { searchActive = it; if (!it) viewModel.setSearchQuery("") },
                onSearchChange   = { viewModel.setSearchQuery(it) },
                onSearchDone     = { keyboard?.hide() },
                onBack           = onBack,
            )

            // ── Filter tabs ────────────────────────────────────────────────
            FilterTabRow(
                activeFilter = state.activeFilter,
                onFilterSelected = { viewModel.setFilter(it) },
            )

            // ── Content ────────────────────────────────────────────────────
            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.isLoading -> {
                        CircularProgressIndicator(
                            color    = menuCursorEdge(),
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }

                    state.visibleApps.isEmpty() -> {
                        EmptyDrawerMessage(
                            filter          = state.activeFilter,
                            hasQuery        = state.searchQuery.isNotBlank(),
                            hasUsageAccess  = state.hasUsageAccess,
                            onGrantUsageAccess = viewModel::openUsageAccessSettings,
                            modifier        = Modifier.align(Alignment.Center),
                        )
                    }

                    else -> {
                        AppGrid(
                            apps          = state.visibleApps,
                            selectedIndex = state.selectedIndex,
                            usingTouch    = state.usingTouch,
                            onAppTapped   = { viewModel.onAppTapped(it) },
                            onAppLaunched = { viewModel.launchApp(it) },
                            onAppMenu     = { viewModel.openAppMenu(it) },
                            onTouchBrowse = { viewModel.onTouchBrowse(it) },
                        )
                    }
                }
            }
        }

        // ── Long-press mini menu (App Info / Uninstall) ────────────────────────
        state.menuApp?.let { app ->
            AppMiniMenu(
                app           = app,
                actions       = state.menuActions,
                selectedIndex = state.menuIndex,
                onAction      = { viewModel.onMenuAction(it) },
                onDismiss     = { viewModel.closeAppMenu() },
            )
        }

        // ── Uninstall confirmation (guard rail) ────────────────────────────────
        state.confirmUninstall?.let { app ->
            UninstallConfirmDialog(
                app       = app,
                onConfirm = { viewModel.confirmUninstall() },
                onCancel  = { viewModel.cancelUninstall() },
            )
        }
    }
}

// ── Long-press mini menu ────────────────────────────────────────────────────────

@Composable
private fun AppMiniMenu(
    app: InstalledApp,
    actions: List<AppMenuAction>,
    selectedIndex: Int,
    onAction: (AppMenuAction) -> Unit,
    onDismiss: () -> Unit,
) {
    // Full-screen scrim; tap outside dismisses. The menu itself is centered.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(280.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xF0141420))
                .padding(vertical = 10.dp),
        ) {
            Text(
                text       = app.label,
                color      = DrawerText,
                fontSize   = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                modifier   = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            )
            actions.forEachIndexed { i, action ->
                val destructive = action == AppMenuAction.UNINSTALL
                Text(
                    text     = action.label,
                    color    = when {
                        i == selectedIndex && destructive -> Color(0xFFFF6B6B)
                        i == selectedIndex                -> Color.White
                        destructive                       -> Color(0xFFE06666)
                        else                              -> DrawerSubtext
                    },
                    fontSize = 15.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (i == selectedIndex) com.playfieldportal.core.ui.theme.menuCursorFill() else Color.Transparent)
                        .clickable { onAction(action) }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun UninstallConfirmDialog(
    app: InstalledApp,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text("Uninstall", color = Color(0xFFFF6B6B))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onCancel) { Text("Cancel") }
        },
        title = { Text("Uninstall ${app.label}?") },
        text = { Text("This removes ${app.label} from your device. Android will ask you to confirm.") },
    )
}

// ── Header ────────────────────────────────────────────────────────────────────

@Composable
private fun DrawerHeader(
    title: String,
    appCount: Int,
    searchQuery: String,
    searchActive: Boolean,
    searchFocus: FocusRequester,
    onSearchToggle: (Boolean) -> Unit,
    onSearchChange: (String) -> Unit,
    onSearchDone: () -> Unit,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Back / title
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text     = "◀",
                color    = DrawerSubtext,
                fontSize = 16.sp,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(end = 16.dp),
            )
            Column {
                Text(
                    text       = title,
                    color      = DrawerText,
                    fontSize   = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text     = "$appCount apps",
                    color    = DrawerSubtext,
                    fontSize = 12.sp,
                )
            }
        }

        // Search
        AnimatedVisibility(
            visible = searchActive,
            enter   = fadeIn(),
            exit    = fadeOut(),
        ) {
            BasicTextField(
                value         = searchQuery,
                onValueChange = onSearchChange,
                singleLine    = true,
                textStyle     = TextStyle(color = DrawerText, fontSize = 14.sp),
                cursorBrush   = SolidColor(menuCursorEdge()),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearchDone() }, onDone = { onSearchDone() }),
                decorationBox = { inner ->
                    Box {
                        if (searchQuery.isEmpty()) {
                            Text("Search apps…", color = DrawerSubtext, fontSize = 14.sp)
                        }
                        inner()
                    }
                },
                modifier      = Modifier
                    .width(240.dp)
                    .focusRequester(searchFocus)
                    .background(DrawerChip, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        // Touch toggle + controller hint: navigating with a pad, press Y to open search.
        Text(
            text     = if (searchActive) "✕" else "Y  ⌕",
            color    = menuCursorEdge(),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { onSearchToggle(!searchActive) },
        )
    }
}

// ── Filter tab row ────────────────────────────────────────────────────────────

@Composable
private fun FilterTabRow(
    activeFilter: AppFilter,
    onFilterSelected: (AppFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppFilter.values().forEach { filter ->
            val isActive = filter == activeFilter
            // Active tab: white label on a theme-tinted chip (the shared menu-cursor treatment),
            // so the highlight follows the active color scheme.
            Text(
                text       = filter.label,
                color      = if (isActive) Color.White else DrawerSubtext,
                fontSize   = 13.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                modifier   = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isActive) menuCursorFill() else Color.Transparent)
                    .border(
                        width = if (isActive) 1.dp else 0.dp,
                        color = if (isActive) menuCursorEdge() else Color.Transparent,
                        shape = RoundedCornerShape(6.dp),
                    )
                    .clickable { onFilterSelected(filter) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

// ── App grid ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppGrid(
    apps: List<InstalledApp>,
    selectedIndex: Int,
    usingTouch: Boolean,
    onAppTapped: (Int) -> Unit,
    onAppLaunched: (String) -> Unit,
    onAppMenu: (InstalledApp) -> Unit,
    onTouchBrowse: (Int) -> Unit,
) {
    val gridState = rememberLazyGridState()

    // Auto-scroll follows the cursor only in controller mode — while browsing by touch the silent
    // cursor updates must never fight the user's finger.
    LaunchedEffect(selectedIndex, usingTouch) {
        if (!usingTouch && apps.isNotEmpty()) gridState.animateScrollToItem(selectedIndex)
    }

    // When a FINGER scroll settles, park the cursor on the tile nearest the viewport centre so a
    // switch to the d-pad picks up where the finger left off. Keyed off drag interactions — never
    // off isScrollInProgress alone, which programmatic (controller-driven) scrolls also flip.
    var fingerScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(gridState) {
        gridState.interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start) {
                fingerScrolled = true
                // Enter touch mode immediately so the cursor hides mid-drag.
                onTouchBrowse(gridState.firstVisibleItemIndex)
            }
        }
    }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (!scrolling && fingerScrolled) {
                    fingerScrolled = false
                    val info = gridState.layoutInfo
                    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                    val nearest = info.visibleItemsInfo.minByOrNull { item ->
                        val itemCenter = item.offset.y + item.size.height / 2
                        kotlin.math.abs(itemCenter - center)
                    }?.index
                    if (nearest != null) onTouchBrowse(nearest)
                }
            }
    }

    LazyVerticalGrid(
        state               = gridState,
        columns             = GridCells.Fixed(GRID_COLUMNS),
        contentPadding      = PaddingValues(horizontal = 32.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement   = Arrangement.spacedBy(16.dp),
        modifier            = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(apps) { index, app ->
            AppGridItem(
                app        = app,
                // The cursor highlight is controller-only; fingers don't need one.
                isSelected = !usingTouch && index == selectedIndex,
                // Single tap launches (moving focus there first so a later d-pad session resumes
                // here). Matches the controller SELECT behaviour and standard launcher expectations.
                onClick    = { onAppTapped(index); onAppLaunched(app.packageName) },
                onMenu     = { onAppTapped(index); onAppMenu(app) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppGridItem(
    app: InstalledApp,
    isSelected: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) com.playfieldportal.core.ui.theme.menuCursorFill() else Color.Transparent)
            .border(
                width = if (isSelected) 1.dp else 0.dp,
                color = if (isSelected) com.playfieldportal.core.ui.theme.menuCursorEdge() else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            // Single tap launches; long-press opens the app's mini menu.
            .combinedClickable(
                onClick     = onClick,
                onLongClick = onMenu,
            )
            .padding(vertical = 10.dp, horizontal = 8.dp),
    ) {
        // App icon
        Image(
            painter  = DrawablePainter(app.icon),
            contentDescription = app.label,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp)),
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text      = app.label,
            color     = DrawerText,
            fontSize  = 11.sp,
            maxLines  = 2,
            overflow  = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )

        // Emulator badge
        if (app.isEmulator) {
            Text(
                text     = "EMU",
                color    = menuCursorEdge(),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyDrawerMessage(
    filter: AppFilter,
    hasQuery: Boolean,
    hasUsageAccess: Boolean,
    onGrantUsageAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier            = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text      = when {
                hasQuery          -> "No apps match your search"
                filter == AppFilter.GAMES     -> "No games found"
                filter == AppFilter.EMULATORS -> "No emulators installed"
                filter == AppFilter.RECENT && !hasUsageAccess -> "Usage access needed"
                filter == AppFilter.RECENT    -> "No recently used apps yet"
                else              -> "No apps installed"
            },
            color    = DrawerSubtext,
            fontSize = 16.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text     = when {
                hasQuery          -> "Try a different search term"
                filter == AppFilter.GAMES     -> "Apps marked as games in the Play Store appear here"
                filter == AppFilter.EMULATORS -> "Install RetroArch, PPSSPP, or another emulator"
                filter == AppFilter.RECENT && !hasUsageAccess -> "Grant access so PFP can sort apps by last used time"
                else              -> ""
            },
            color    = DrawerSubtext.copy(alpha = 0.6f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier  = Modifier.padding(horizontal = 48.dp),
        )

        if (filter == AppFilter.RECENT && !hasUsageAccess) {
            Spacer(Modifier.height(16.dp))
            Text(
                text       = "Open Usage Access",
                color      = menuCursorEdge(),
                fontSize   = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier   = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(DrawerChip)
                    .clickable { onGrantUsageAccess() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
