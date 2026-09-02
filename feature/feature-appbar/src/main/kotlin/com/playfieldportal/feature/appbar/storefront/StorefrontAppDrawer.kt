@file:Suppress("unused")
// Preserved snapshot of the PSP-Storefront App Drawer (the pre-redesign AppDrawerScreen.kt),
// moved verbatim into its own package so the redesigned drawer could take the original file. It is
// intentionally unreferenced: this storefront layout — vertical category rail, content pane, pinned
// command bar — is earmarked as the visual foundation for a future RSS Channels feature (see
// assets/UI/ui samples/PFP_App_Drawer_PSP_Era_Redesign_Design_Doc.md §21) and is kept compiling and
// previewable so it cannot rot while unused. It still consumes deriveStorefrontColors(), so the
// accent-driven palette rework (see StorefrontColors.kt) carries into it for free.
package com.playfieldportal.feature.appbar.storefront

import com.playfieldportal.feature.appbar.AppDrawerUiState
import com.playfieldportal.feature.appbar.AppDrawerViewModel
import com.playfieldportal.feature.appbar.AppFilter
import com.playfieldportal.feature.appbar.AppMenuAction
import com.playfieldportal.feature.appbar.GRID_COLUMNS
import com.playfieldportal.feature.appbar.InstalledApp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toDrawable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.drawablepainter.DrawablePainter
import com.playfieldportal.core.ui.components.ControllerPromptBar
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.R
import com.playfieldportal.core.ui.preview.CombinedPreviews
import com.playfieldportal.core.ui.preview.PfpPreview
import com.playfieldportal.core.ui.theme.deriveStorefrontColors
import com.playfieldportal.core.ui.theme.menuCursorEdge
import kotlinx.coroutines.flow.distinctUntilChanged

// ── Layout constants ────────────────────────────────────────────────────────────
private val RAIL_WIDTH = 180.dp
private val HEADER_HEIGHT = 56.dp
private val FOOTER_HEIGHT = 48.dp
private val TILE_CORNER = 2.dp
private val TILE_BORDER = 1.dp

// ── Entry point (preserved, unreferenced — see file header) ────────────────────

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun StorefrontAppDrawerScreen(
    onBack: () -> Unit,
    initialFilter: AppFilter = AppFilter.ALL,
    pendingGamepadAction: GamepadAction? = null,
    onGamepadActionConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AppDrawerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var searchActive by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    // Platform variant on purpose: this Compose BOM doesn't provide the lifecycle-runtime-compose
    // LocalLifecycleOwner (reading it crashes at composition — see XMBShell's note on the same
    // workaround), so the deprecated platform local is the one that is actually populated.
    @Suppress("DEPRECATION")
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(pendingGamepadAction) {
        if (pendingGamepadAction != null) {
            val overlayOpen = state.menuApp != null || state.confirmUninstall != null
            if (!overlayOpen && (pendingGamepadAction == GamepadAction.CHANGE_SORT)) {
                // X / Square — toggle search (App Drawer remap)
                searchActive = !searchActive
                if (!searchActive) viewModel.setSearchQuery("")
            } else {
                viewModel.handleGamepadAction(pendingGamepadAction)
            }
            onGamepadActionConsumed()
        }
    }

    val appliedInitial = remember { mutableStateOf(false) }
    if (!appliedInitial.value) {
        viewModel.setFilter(initialFilter)
        viewModel.setSearchQuery("")
        appliedInitial.value = true
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AppDrawerContent(
        state = state,
        searchActive = searchActive,
        onBack = onBack,
        onSearchQueryChange = { viewModel.setSearchQuery(it) },
        onSearchToggle = { searchActive = it; if (!it) viewModel.setSearchQuery("") },
        onSearchDone = { keyboard?.hide() },
        onFilterSelected = { viewModel.setFilter(it) },
        onAppTapped = { viewModel.onAppTapped(it) },
        onAppLaunched = { viewModel.launchApp(it) },
        onAppMenu = { viewModel.openAppMenu(it) },
        onTouchBrowse = { viewModel.onTouchBrowse(it) },
        onMenuAction = { viewModel.onMenuAction(it) },
        onCloseMenu = { viewModel.closeAppMenu() },
        onConfirmUninstall = { viewModel.confirmUninstall() },
        onCancelUninstall = { viewModel.cancelUninstall() },
        onGrantUsageAccess = { viewModel.openUsageAccessSettings() },
        modifier = modifier,
    )
}

// ── Main content layout ─────────────────────────────────────────────────────────

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AppDrawerContent(
    state: AppDrawerUiState,
    searchActive: Boolean,
    onBack: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearchToggle: (Boolean) -> Unit,
    onSearchDone: () -> Unit,
    onFilterSelected: (AppFilter) -> Unit,
    onAppTapped: (Int) -> Unit,
    onAppLaunched: (String) -> Unit,
    onAppMenu: (InstalledApp) -> Unit,
    onTouchBrowse: (Int) -> Unit,
    onMenuAction: (AppMenuAction) -> Unit,
    onCloseMenu: () -> Unit,
    onConfirmUninstall: () -> Unit,
    onCancelUninstall: () -> Unit,
    onGrantUsageAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val sf = deriveStorefrontColors()

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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to sf.chromeTop.copy(alpha = 0.94f),
                    0.35f to sf.chromeBottom.copy(alpha = 0.94f),
                    1f to sf.chromeTop.copy(alpha = 0.94f),
                )
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Header / breadcrumb bar ──────────────────────────────────────
            StorefrontHeader(
                categoryLabel = state.activeFilter.label,
                searchQuery = state.searchQuery,
                searchActive = searchActive,
                searchFocus = searchFocus,
                onSearchToggle = onSearchToggle,
                onSearchChange = onSearchQueryChange,
                onSearchDone = onSearchDone,
                onBack = onBack,
                colors = sf,
            )
            // Thin cyan divider under header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(sf.chromeDivider),
            )

            // ── Category rail + Content area ─────────────────────────────────
            Row(modifier = Modifier.weight(1f)) {
                CategoryRail(
                    activeFilter = state.activeFilter,
                    filterCounts = state.filterCounts,
                    onFilterSelected = onFilterSelected,
                    colors = sf,
                )

                // Thin vertical divider between rail and content
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(sf.chromeDivider.copy(alpha = 0.45f)),
                )

                // ── Content area ─────────────────────────────────────────────
                Column(modifier = Modifier.weight(1f)) {
                    ContentHeader(
                        label = state.activeFilter.label.uppercase(),
                        count = state.visibleApps.size,
                        colors = sf,
                    )

                    Box(modifier = Modifier.weight(1f)) {
                        when {
                            state.isLoading -> {
                                CircularProgressIndicator(
                                    color = menuCursorEdge(),
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }
                            state.visibleApps.isEmpty() -> {
                                EmptyDrawerMessage(
                                    filter = state.activeFilter,
                                    hasQuery = state.searchQuery.isNotBlank(),
                                    hasUsageAccess = state.hasUsageAccess,
                                    onGrantUsageAccess = onGrantUsageAccess,
                                    colors = sf,
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }
                            else -> {
                                AppGrid(
                                    apps = state.visibleApps,
                                    selectedIndex = state.selectedIndex,
                                    usingTouch = state.usingTouch,
                                    onAppTapped = onAppTapped,
                                    onAppLaunched = onAppLaunched,
                                    onAppMenu = onAppMenu,
                                    onTouchBrowse = onTouchBrowse,
                                    colors = sf,
                                )
                            }
                        }
                    }
                }
            }

            // Thin divider above footer
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(sf.footerDivider),
            )

            // ── Controller command bar ───────────────────────────────────────
            ControllerCommandBar(colors = sf)
        }

        // ── Overlays ──────────────────────────────────────────────────────────
        state.menuApp?.let { app ->
            AppMiniMenu(
                app = app,
                actions = state.menuActions,
                selectedIndex = state.menuIndex,
                onAction = onMenuAction,
                onDismiss = onCloseMenu,
                colors = sf,
            )
        }

        state.confirmUninstall?.let { app ->
            UninstallConfirmDialog(
                app = app,
                onConfirm = onConfirmUninstall,
                onCancel = onCancelUninstall,
            )
        }
    }
}

// ── Header / breadcrumb bar ─────────────────────────────────────────────────────

@Composable
private fun StorefrontHeader(
    categoryLabel: String,
    searchQuery: String,
    searchActive: Boolean,
    searchFocus: FocusRequester,
    onSearchToggle: (Boolean) -> Unit,
    onSearchChange: (String) -> Unit,
    onSearchDone: () -> Unit,
    onBack: () -> Unit,
    colors: com.playfieldportal.core.ui.theme.StorefrontColors,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT)
            .background(
                Brush.verticalGradient(0f to colors.chromeTop, 1f to colors.chromeBottom)
            )
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Breadcrumb: ‹ Android › category
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = "\u2039",
                color = colors.textSecondary,
                fontSize = 18.sp,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(end = 8.dp),
            )
            Text(
                text = "Android",
                color = colors.textSecondary,
                fontSize = 14.sp,
                modifier = Modifier.clickable { onBack() }.padding(end = 6.dp),
            )
            Text(
                text = "\u203A",
                color = colors.textSecondary.copy(alpha = 0.6f),
                fontSize = 14.sp,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                text = categoryLabel,
                color = colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // Search field (animated)
        AnimatedVisibility(visible = searchActive, enter = fadeIn(), exit = fadeOut()) {
            BasicTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                singleLine = true,
                textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
                cursorBrush = SolidColor(colors.chromeDivider),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearchDone() }, onDone = { onSearchDone() }),
                decorationBox = { inner ->
                    Box {
                        if (searchQuery.isEmpty()) Text(
                            "Search\u2026",
                            color = colors.textSecondary.copy(alpha = 0.6f),
                            fontSize = 14.sp,
                        )
                        inner()
                    }
                },
                modifier = Modifier
                    .width(220.dp)
                    .focusRequester(searchFocus)
                    .background(colors.searchField, RoundedCornerShape(TILE_CORNER))
                    .border(TILE_BORDER, colors.searchBorder, RoundedCornerShape(TILE_CORNER))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        Spacer(Modifier.width(16.dp))

        // Header actions: Search icon + View placeholder
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Search button (magnifying glass)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable {
                    onSearchToggle(!searchActive)
                },
            ) {
                Canvas(modifier = Modifier.size(18.dp)) {
                    val strokeW = 1.8f.dp.toPx()
                    val cx = size.width * 0.42f
                    val cy = size.height * 0.42f
                    val r = size.width * 0.30f
                    // Lens circle
                    drawCircle(
                        color = colors.textSecondary,
                        radius = r,
                        center = androidx.compose.ui.geometry.Offset(cx, cy),
                        style = Stroke(strokeW),
                    )
                    // Handle line
                    val handleStart = androidx.compose.ui.geometry.Offset(
                        cx + r * 0.70f,
                        cy + r * 0.70f,
                    )
                    val handleEnd = androidx.compose.ui.geometry.Offset(
                        cx + r * 0.70f + size.width * 0.22f,
                        cy + r * 0.70f + size.height * 0.22f,
                    )
                    drawLine(
                        color = colors.textSecondary,
                        start = handleStart,
                        end = handleEnd,
                        strokeWidth = strokeW,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
                Spacer(Modifier.width(5.dp))
                Text("Search", color = colors.textSecondary, fontSize = 13.sp)
            }

            }
    }
}

// ── Category navigation rail ────────────────────────────────────────────────────

@Composable
private fun CategoryRail(
    activeFilter: AppFilter,
    filterCounts: Map<AppFilter, Int>,
    onFilterSelected: (AppFilter) -> Unit,
    colors: com.playfieldportal.core.ui.theme.StorefrontColors,
) {
    Column(
        modifier = Modifier
            .width(RAIL_WIDTH)
            .fillMaxHeight()
            .background(colors.railBackground)
            .padding(vertical = 4.dp),
    ) {
        AppFilter.values().forEach { filter ->
            val isActive = filter == activeFilter
            val bgColor = if (isActive) colors.categorySelected else colors.categoryInactive
            val textColor = if (isActive) colors.textPrimary else colors.textSecondary
            val count = filterCounts[filter] ?: 0

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(TILE_CORNER))
                    .background(bgColor)
                    .then(
                        if (isActive) Modifier.border(TILE_BORDER, colors.categorySelectedEdge, RoundedCornerShape(TILE_CORNER))
                        else Modifier
                    )
                    .clickable { onFilterSelected(filter) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = filter.label.uppercase(),
                    color = textColor,
                    fontSize = 12.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                )
                if (count > 0) {
                    Text(
                        text = count.toString(),
                        color = if (isActive) colors.categorySelectedEdge else colors.textSecondary.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

// ── Content header ──────────────────────────────────────────────────────────────

@Composable
private fun ContentHeader(
    label: String,
    count: Int,
    colors: com.playfieldportal.core.ui.theme.StorefrontColors,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = colors.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "$count APPS",
            color = colors.textSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
    // Thin horizontal rule under the section header
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .padding(horizontal = 24.dp)
            .background(colors.chromeDivider.copy(alpha = 0.35f)),
    )
}

// ── Application grid ────────────────────────────────────────────────────────────

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
    colors: com.playfieldportal.core.ui.theme.StorefrontColors,
) {
    val gridState = rememberLazyGridState()

    LaunchedEffect(selectedIndex, usingTouch) {
        // Clamp: a stale cursor past the end (list shrank mid-scroll) must not crash the grid.
        if (!usingTouch && apps.isNotEmpty()) gridState.animateScrollToItem(selectedIndex.coerceIn(0, apps.lastIndex))
    }

    var fingerScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(gridState) {
        gridState.interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start) {
                fingerScrolled = true
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
        state = gridState,
        columns = GridCells.Fixed(GRID_COLUMNS),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(apps) { index, app ->
            AppGridItem(
                app = app,
                isSelected = !usingTouch && index == selectedIndex,
                onClick = { onAppTapped(index); onAppLaunched(app.packageName) },
                onMenu = { onAppTapped(index); onAppMenu(app) },
                colors = colors,
            )
        }
    }
}

// ── Application tile ────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppGridItem(
    app: InstalledApp,
    isSelected: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    colors: com.playfieldportal.core.ui.theme.StorefrontColors,
) {
    val bgColor = if (isSelected) colors.tileSelected else colors.tileNormal
    val borderColor = if (isSelected) colors.tileSelectedEdge else colors.chromeDivider.copy(alpha = 0.25f)
    val innerBorderColor = if (isSelected) colors.tileSelectedInner else Color.Transparent

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(TILE_CORNER))
            .background(bgColor)
            .border(TILE_BORDER, borderColor, RoundedCornerShape(TILE_CORNER))
            .then(
                if (isSelected) Modifier.border(
                    1.dp,
                    innerBorderColor,
                    RoundedCornerShape((TILE_CORNER + TILE_BORDER).value.dp),
                )
                else Modifier
            )
            .combinedClickable(onClick = onClick, onLongClick = onMenu)
            .padding(vertical = 10.dp, horizontal = 6.dp),
    ) {
        Image(
            painter = DrawablePainter(app.icon),
            contentDescription = app.label,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(TILE_CORNER)),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = app.label,
            color = colors.textPrimary,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = 13.sp,
        )
    }
}

// ── Controller command bar ──────────────────────────────────────────────────────

@Composable
private fun ControllerCommandBar(
    colors: com.playfieldportal.core.ui.theme.StorefrontColors,
) {
    // Actions, not buttons: ControllerPromptBar resolves each to whichever face
    // button the user's Confirm/Back and X/Y settings currently bind it to.
    ControllerPromptBar(
        items = listOf(
            ControllerPromptItem(GamepadAction.PREV_CATEGORY, "Prev Category"),
            ControllerPromptItem(GamepadAction.NEXT_CATEGORY, "Next Category"),
            ControllerPromptItem(GamepadAction.BACK, "Back"),
            ControllerPromptItem(GamepadAction.SELECT, "Launch"),
            ControllerPromptItem(GamepadAction.OPEN_CONTEXT_MENU, "Options"),
            ControllerPromptItem(GamepadAction.CHANGE_SORT, "Search"),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(FOOTER_HEIGHT)
            .background(colors.footerBackground.copy(alpha = 0.94f))
            .padding(horizontal = 24.dp),
        labelColor = colors.textSecondary,
        labelStyle = TextStyle(fontSize = 12.sp),
    )
}

// ── Mini menu overlay ───────────────────────────────────────────────────────────

@Composable
private fun AppMiniMenu(
    app: InstalledApp,
    actions: List<AppMenuAction>,
    selectedIndex: Int,
    onAction: (AppMenuAction) -> Unit,
    onDismiss: () -> Unit,
    colors: com.playfieldportal.core.ui.theme.StorefrontColors,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.overlayDim)
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(280.dp)
                .clip(RoundedCornerShape(TILE_CORNER))
                .background(colors.menuPanel)
                .border(TILE_BORDER, colors.chromeDivider.copy(alpha = 0.4f), RoundedCornerShape(TILE_CORNER))
                .padding(vertical = 8.dp),
        ) {
            // Menu title
            Text(
                text = app.label,
                color = colors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            // Thin divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .padding(horizontal = 12.dp)
                    .background(colors.chromeDivider.copy(alpha = 0.25f)),
            )
            Spacer(Modifier.height(4.dp))
            // Action rows
            actions.forEachIndexed { i, action ->
                val destructive = action == AppMenuAction.UNINSTALL
                val isSelected = i == selectedIndex
                Text(
                    text = action.label,
                    color = when {
                        isSelected && destructive -> colors.destructive
                        isSelected -> colors.textPrimary
                        destructive -> colors.destructive.copy(alpha = 0.7f)
                        else -> colors.textSecondary
                    },
                    fontSize = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isSelected) colors.menuRowSelected else Color.Transparent)
                        .clickable { onAction(action) }
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                )
            }
        }
    }
}

// ── Uninstall confirmation dialog ───────────────────────────────────────────────

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

// ── Empty state ─────────────────────────────────────────────────────────────────

@Composable
private fun EmptyDrawerMessage(
    filter: AppFilter,
    hasQuery: Boolean,
    hasUsageAccess: Boolean,
    onGrantUsageAccess: () -> Unit,
    colors: com.playfieldportal.core.ui.theme.StorefrontColors,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = when {
                hasQuery -> "No apps match your search"
                filter == AppFilter.GAMES -> "No games found"
                filter == AppFilter.EMULATORS -> "No emulators installed"
                filter == AppFilter.RECENT && !hasUsageAccess -> "Usage access needed"
                filter == AppFilter.RECENT -> "No recently used apps yet"
                else -> "No apps installed"
            },
            color = colors.textSecondary,
            fontSize = 16.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                hasQuery -> "Try a different search term"
                filter == AppFilter.GAMES -> "Apps marked as games in the Play Store appear here"
                filter == AppFilter.EMULATORS -> "Install RetroArch, PPSSPP, or another emulator"
                filter == AppFilter.RECENT && !hasUsageAccess -> "Grant access so PFP can sort apps by last used time"
                else -> ""
            },
            color = colors.textSecondary.copy(alpha = 0.6f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 48.dp),
        )
        if (filter == AppFilter.RECENT && !hasUsageAccess) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Open Usage Access",
                color = colors.chromeDivider,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(TILE_CORNER))
                    .background(colors.searchField)
                    .border(TILE_BORDER, colors.searchBorder, RoundedCornerShape(TILE_CORNER))
                    .clickable { onGrantUsageAccess() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

// ── Preview ─────────────────────────────────────────────────────────────────────

@CombinedPreviews
@Composable
fun StorefrontAppDrawerPreview() {
    val mockIcon = android.graphics.Color.LTGRAY.toDrawable()
    val mockApps = listOf(
        InstalledApp("com.android.chrome", "Chrome", mockIcon, isGame = false, isEmulator = false),
        InstalledApp("org.ppsspp.ppsspp", "PPSSPP", mockIcon, isGame = false, isEmulator = true),
        InstalledApp("com.retroarch", "RetroArch", mockIcon, isGame = false, isEmulator = true),
        InstalledApp("com.google.android.youtube", "YouTube", mockIcon, isGame = false, isEmulator = false),
        InstalledApp("com.playfieldportal.launcher", "Play Field Portal", mockIcon, isGame = false, isEmulator = false),
    )
    val mockCounts = AppFilter.values().associateWith { when (it) {
        AppFilter.ALL -> 42
        AppFilter.GAMES -> 28
        AppFilter.EMULATORS -> 9
        AppFilter.RECENT -> 12
    } }
    val mockState = AppDrawerUiState(
        visibleApps = mockApps,
        activeFilter = AppFilter.ALL,
        selectedIndex = 1,
        filterCounts = mockCounts,
    )
    PfpPreview {
        AppDrawerContent(
            state = mockState,
            searchActive = false,
            onBack = {},
            onSearchQueryChange = {},
            onSearchToggle = {},
            onSearchDone = {},
            onFilterSelected = {},
            onAppTapped = {},
            onAppLaunched = {},
            onAppMenu = {},
            onTouchBrowse = {},
            onMenuAction = {},
            onCloseMenu = {},
            onConfirmUninstall = {},
            onCancelUninstall = {},
            onGrantUsageAccess = {},
        )
    }
}
