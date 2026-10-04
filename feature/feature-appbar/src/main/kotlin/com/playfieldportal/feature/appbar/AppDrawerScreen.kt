package com.playfieldportal.feature.appbar

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toDrawable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.lightBackgroundAnchors
import com.playfieldportal.core.ui.components.PfpModalSpec
import com.playfieldportal.core.ui.components.PspContextMenuOverlay
import com.playfieldportal.core.ui.components.PspMenuRow
import com.playfieldportal.core.ui.components.rememberPfpModalHost
import com.playfieldportal.core.ui.keyboard.KeyboardPlacement
import com.playfieldportal.core.ui.keyboard.isVirtualKeyboardOverlayOpen
import com.playfieldportal.core.ui.keyboard.rememberVirtualKeyboardEdit
import com.playfieldportal.core.ui.preview.CombinedPreviews
import com.playfieldportal.core.ui.preview.PfpPreview
import com.playfieldportal.core.ui.theme.PFPColors
import com.playfieldportal.core.ui.theme.StorefrontColors
import com.playfieldportal.core.ui.theme.deriveStorefrontColors
import com.playfieldportal.core.ui.theme.dimmed
import com.playfieldportal.core.ui.theme.menuCursorEdge
import com.playfieldportal.feature.appbar.appdrawer.AppDrawerCategoryTabs
import com.playfieldportal.feature.appbar.appdrawer.AppDrawerGrid
import com.playfieldportal.feature.appbar.appdrawer.AppDrawerHeader
import com.playfieldportal.feature.appbar.appdrawer.AppDrawerHintBar
import com.playfieldportal.feature.appbar.appdrawer.adaptiveArtworkSize

// ── PSP-era grid App Drawer ───────────────────────────────────────────────────
//
// Grid-centric and artwork-first: a header/breadcrumb, a horizontal category tab row, and a
// 6-column application grid over an accent-derived gradient (see deriveStorefrontColors). The
// controller hint pill is a permanent footer row below the grid that fades in/out via alpha, so
// the slot's height is reserved whether or not the pill is showing and grid geometry never
// shifts; the pre-redesign storefront layout (vertical rail + command bar) is preserved for the
// future RSS Channels feature in the appbar/storefront package.

// ── Entry point ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppDrawerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialFilter: AppFilter = AppFilter.ALL,
    pendingGamepadAction: GamepadAction? = null,
    onGamepadActionConsumed: () -> Unit = {},
    /** Idle-controller gate: when true (and no drawer overlay is open) the hint pill fades in. */
    showControllerHint: Boolean = false,
    /** Any touch interaction inside the drawer — reported to the XMB input-source tracker so a
     *  finger tap/browse suppresses the controller hint the same way it does on the XMB. */
    onTouchInteraction: () -> Unit = {},
    /** Edit App Details, Add to Card and Favorite belong to the XMB: the menu hands them up here. */
    onEditAppDetails: (packageName: String) -> Unit = {},
    onAddAppToCard: (packageName: String, label: String) -> Unit = { _, _ -> },
    onToggleAppFavorite: (packageName: String, label: String) -> Unit = { _, _ -> },
    viewModel: AppDrawerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // rememberUpdatedState: the collector below outlives recompositions, the callbacks may not.
    val editAppDetails by rememberUpdatedState(onEditAppDetails)
    val addAppToCard by rememberUpdatedState(onAddAppToCard)
    val toggleAppFavorite by rememberUpdatedState(onToggleAppFavorite)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is AppDrawerEvent.EditDetails -> editAppDetails(event.packageName)
                is AppDrawerEvent.AddToCard -> addAppToCard(event.packageName, event.label)
                is AppDrawerEvent.ToggleFavorite -> toggleAppFavorite(event.packageName, event.label)
            }
        }
    }
    var searchActive by remember { mutableStateOf(false) }
    // Bumped to bring the keyboard back to an open search that still holds text.
    var searchReopens by remember { mutableIntStateOf(0) }
    val keyboard = LocalSoftwareKeyboardController.current

    // X's rule (see drawerSearchButton). The magnifier keeps its plain toggle.
    fun pressSearchButton() {
        when (drawerSearchButton(searchActive, state.searchQuery)) {
            DrawerSearchButton.OPEN -> searchActive = true
            DrawerSearchButton.REOPEN -> searchReopens++
            DrawerSearchButton.CLOSE -> {
                searchActive = false
                viewModel.setSearchQuery("")
            }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Uninstall asks through the shared confirm modal. Its cursor lives in the host, so a press goes
    // there first while one is up and only otherwise reaches the drawer.
    val modal = rememberPfpModalHost(
        uninstallModalSpec(
            state = state,
            onConfirm = viewModel::confirmUninstall,
            onCancel = viewModel::cancelUninstall,
        ),
    )

    LaunchedEffect(pendingGamepadAction) {
        if (pendingGamepadAction != null) {
            val overlayOpen = state.menuApp != null || state.confirmUninstall != null
            when {
                modal.intercept(pendingGamepadAction) -> Unit
                // An inner drawer overlay (options menu / uninstall confirm) is up: BACK goes to
                // the drawer ViewModel, which pops that overlay. XMBViewModel forwards every
                // action — including BACK — to the drawer, so BACK here NEVER closes the drawer
                // itself while an overlay is open.
                overlayOpen -> viewModel.handleGamepadAction(pendingGamepadAction)
                // BACK on the plain grid closes the drawer (its only controller escape).
                pendingGamepadAction == GamepadAction.BACK -> onBack()
                // X / Square — the search button (App Drawer remap). Deliberately NOT routed
                // through onSearchToggle: that path reports touch input, and this is controller
                // input.
                pendingGamepadAction == GamepadAction.CHANGE_SORT -> pressSearchButton()
                else -> viewModel.handleGamepadAction(pendingGamepadAction)
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

    Box(modifier = modifier) {
        AppDrawerContent(
            state = state,
            searchActive = searchActive,
            searchReopens = searchReopens,
            showControllerHint = showControllerHint,
            // The back breadcrumb is a touch target; controller BACK closes the drawer at the XMB
            // layer (never through this lambda), so reporting touch here is always accurate.
            onBack = {
                onTouchInteraction()
                onBack()
            },
            onSearchQueryChange = { viewModel.setSearchQuery(it) },
            // Touch keeps its plain toggle: the reopen rule is for X, whose press PFP's keyboard has
            // already let go of by the time it reaches the drawer.
            onSearchToggle = { active ->
                onTouchInteraction()
                searchActive = active
                if (!active) viewModel.setSearchQuery("")
            },
            onSearchDone = { keyboard?.hide() },
            // BACK on PFP's keyboard: the search closes like X closes it — no touch report, the
            // controller is still in charge.
            onCloseSearch = {
                searchActive = false
                viewModel.setSearchQuery("")
            },
            onFilterSelected = { filter ->
                onTouchInteraction()
                viewModel.setFilter(filter)
            },
            onAppTapped = { index ->
                onTouchInteraction()
                viewModel.onAppTapped(index)
            },
            onAppLaunched = { viewModel.launchApp(it) },
            onAppMenu = { viewModel.openAppMenu(it) },
            onTouchBrowse = { index ->
                onTouchInteraction()
                viewModel.onTouchBrowse(index)
            },
            onMenuAction = { viewModel.onMenuAction(it) },
            onCloseMenu = { viewModel.closeAppMenu() },
            onGrantUsageAccess = { viewModel.openUsageAccessSettings() },
        )
        modal.Content()
    }
}

/**
 * The shared confirm for Uninstall, or null when none is asking. Destructive, so it opens on Cancel
 * and the press that opened it can never confirm. Internal and free of composition so the mapping
 * from UI state to modal can be tested directly.
 */
internal fun uninstallModalSpec(
    state: AppDrawerUiState,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
): PfpModalSpec? {
    val app = state.confirmUninstall ?: return null
    return PfpModalSpec.Confirm(
        key = "uninstall:${app.packageName}",
        title = "Uninstall ${app.label}?",
        message = "This removes ${app.label} from your device. Android will ask you to confirm.",
        confirmLabel = "Uninstall",
        destructive = true,
        onConfirm = onConfirm,
        onCancel = onCancel,
    )
}

// ── Main content layout ─────────────────────────────────────────────────────────

// Internal (not private) so Robolectric Compose UI tests can render the content directly with a
// synthesized state — the screen entry point needs a hiltViewModel.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun AppDrawerContent(
    state: AppDrawerUiState,
    searchActive: Boolean,
    showControllerHint: Boolean,
    onBack: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearchToggle: (Boolean) -> Unit,
    onSearchDone: () -> Unit,
    onFilterSelected: (AppFilter) -> Unit,
    onAppTapped: (Int) -> Unit,
    onAppLaunched: (String) -> Unit,
    onAppMenu: (InstalledApp) -> Unit,
    onTouchBrowse: (Int) -> Unit,
    onMenuAction: (AppMenuEntry) -> Unit,
    onCloseMenu: () -> Unit,
    onGrantUsageAccess: () -> Unit,
    modifier: Modifier = Modifier,
    onCloseSearch: () -> Unit = {},
    searchReopens: Int = 0,
) {
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val sf = deriveStorefrontColors()
    // PFP's keyboard for a search the controller opened: Done is the IME's Search key (the query
    // is already live), BACK closes the search.
    val searchEdit = rememberVirtualKeyboardEdit(
        text = state.searchQuery,
        onTextChange = onSearchQueryChange,
        placement = KeyboardPlacement.BOTTOM_CENTER,
        onDone = onSearchDone,
        onClose = onCloseSearch,
    )

    // PFP's keyboard opens first, so the field's own keyboard request is already held when focus
    // arrives.
    // Keyed on searchReopens too: the search button on an open search with text brings a keyboard
    // back instead of closing it.
    LaunchedEffect(searchActive, searchReopens) {
        if (searchActive) {
            withFrameNanos {}
            withFrameNanos {}
            val virtual = searchEdit.isOpen || searchEdit.start()
            if (virtual) withFrameNanos {}
            runCatching { searchFocus.requestFocus() }
            if (!virtual) keyboard?.show()
        } else {
            searchEdit.stop()
            keyboard?.hide()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Deep upper (header) region easing into the rich midtone grid region — accent
            // derived, at ~0.94 alpha so the XMB wave still reads through.
            .background(
                Brush.verticalGradient(
                    listOf(sf.backgroundDeep, sf.backgroundMid),
                )
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Header / breadcrumb bar ──────────────────────────────────
            AppDrawerHeader(
                categoryLabel = state.activeFilter.label,
                searchQuery = state.searchQuery,
                searchActive = searchActive,
                searchFocus = searchFocus,
                onSearchToggle = onSearchToggle,
                onSearchChange = onSearchQueryChange,
                onSearchDone = onSearchDone,
                onBack = onBack,
                colors = sf,
                searchEdit = searchEdit,
            )
            // Thin accent divider under the header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(sf.chromeDivider),
            )

            // ── Horizontal category tabs ────────────────────────────────
            AppDrawerCategoryTabs(
                activeFilter = state.activeFilter,
                filterCounts = state.filterCounts,
                onFilterSelected = onFilterSelected,
                colors = sf,
            )

            // ── Grid area ───────────────────────────────────────────────
            // BoxWithConstraints puts the viewport height in composition scope, so the adaptive
            // artwork size is resolved BEFORE the first tile composes — tiles render at their
            // final size on frame one, no resize jump.
            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                val artworkSize = adaptiveArtworkSize(maxHeight)
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
                        AppDrawerGrid(
                            apps = state.visibleApps,
                            selectedIndex = state.selectedIndex,
                            usingTouch = state.usingTouch,
                            artworkSize = artworkSize,
                            onAppTapped = onAppTapped,
                            onAppLaunched = onAppLaunched,
                            onAppMenu = onAppMenu,
                            onTouchBrowse = onTouchBrowse,
                            colors = sf,
                        )
                    }
                }
            }

            // ── Permanent footer: controller hint pill, alpha-faded in/out on the
            // idle-controller gate. Alpha (not AnimatedVisibility) keeps the bar measured at
            // its natural height in both states, so the slot never changes size and the grid
            // never shifts. The pill has no clickables, so a fully transparent bar swallowing
            // touches is not a concern. Fade-out is symmetric (unlike the XMB's instant cut-out)
            // but still short; fallback if device testing disagrees is a ~90 ms fade-out.
            val hintAlpha by animateFloatAsState(
                targetValue = if (showControllerHint && state.menuApp == null && state.confirmUninstall == null) 1f else 0f,
                animationSpec = tween(200),
                label = "appDrawerHint",
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                // PFP's keyboard brings its own prompts; the drawer's would stack under them.
                val keyboardOpen = isVirtualKeyboardOverlayOpen()
                AppDrawerHintBar(modifier = Modifier.alpha(if (keyboardOpen) 0f else hintAlpha))
            }
        }

        // ── Overlays ──────────────────────────────────────────────────────
        state.menuApp?.let { app ->
            val actions = state.menuRows
            PspContextMenuOverlay(
                title = app.label,
                rows = actions.map { it.toPspMenuRow() },
                selectedIndex = state.menuIndex,
                onRowActivated = { onMenuAction(actions[it]) },
                onDismiss = onCloseMenu,
            )
        }
    }
}

/** The panel's row for a menu entry: value, chevron, red and silent all carry over. */
internal fun AppMenuEntry.toPspMenuRow() = PspMenuRow(
    label = label,
    isDestructive = isDestructive,
    value = value,
    header = header,
    opensMenu = opensMenu,
    silent = silent,
)

// ── Empty state ─────────────────────────────────────────────────────────────────

@Composable
private fun EmptyDrawerMessage(
    filter: AppFilter,
    hasQuery: Boolean,
    hasUsageAccess: Boolean,
    onGrantUsageAccess: () -> Unit,
    colors: StorefrontColors,
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
            textAlign = TextAlign.Center,
        )
        val hint = when {
            hasQuery -> "Try a different search term"
            filter == AppFilter.GAMES -> "Apps marked as games in the Play Store appear here"
            filter == AppFilter.EMULATORS -> "Install RetroArch, PPSSPP, or another emulator"
            filter == AppFilter.RECENT && !hasUsageAccess -> "Grant access so PFP can sort apps by last used time"
            else -> null
        }
        // No hint (e.g. Recently Used with nothing used yet) → skip the spacer and the empty line,
        // otherwise they'd push the headline above the drawer's center.
        if (hint != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = hint,
                color = colors.textSecondary.dimmed(0.6f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 48.dp),
            )
        }
        if (filter == AppFilter.RECENT && !hasUsageAccess) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Open Usage Access",
                color = colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.searchField)
                    .border(1.dp, colors.searchBorder, RoundedCornerShape(2.dp))
                    .clickable { onGrantUsageAccess() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

// ── Preview ─────────────────────────────────────────────────────────────────────

@CombinedPreviews
@Composable
fun AppDrawerScreenPreview() {
    PfpPreview {
        AppDrawerPreviewContent()
    }
}

// Accent sweep: the same content re-themed over the presets' real waves, to eyeball that the
// drawer visibly changes hue and that text stays readable (Silver Mono / Golden Amber flip to
// dark text). One plain parameterless @Preview per accent rather than @PreviewParameter —
// parameterized previews are fragile across Studio/library-module combinations.
@Preview(name = "Classic Blue", group = "App Drawer Accents")
@Composable
fun AppDrawerScreenPreviewClassicBlue() {
    PfpPreview(colors = accentPreviewColors(0xFF0055AAL)) { AppDrawerPreviewContent() }
}

@Preview(name = "Sunset Orange", group = "App Drawer Accents")
@Composable
fun AppDrawerScreenPreviewSunsetOrange() {
    PfpPreview(colors = accentPreviewColors(0xFFFF8A3DL)) { AppDrawerPreviewContent() }
}

@Preview(name = "Fresh Green", group = "App Drawer Accents")
@Composable
fun AppDrawerScreenPreviewFreshGreen() {
    PfpPreview(colors = accentPreviewColors(0xFF36C26BL)) { AppDrawerPreviewContent() }
}

@Preview(name = "Sakura Pink", group = "App Drawer Accents")
@Composable
fun AppDrawerScreenPreviewSakuraPink() {
    PfpPreview(colors = accentPreviewColors(0xFFE87FB0L)) { AppDrawerPreviewContent() }
}

@Preview(name = "Silver Mono", group = "App Drawer Accents")
@Composable
fun AppDrawerScreenPreviewSilverMono() {
    PfpPreview(colors = accentPreviewColors(0xFFB8C4D0L)) { AppDrawerPreviewContent() }
}

@Preview(name = "Golden Amber", group = "App Drawer Accents")
@Composable
fun AppDrawerScreenPreviewGoldenAmber() {
    PfpPreview(colors = accentPreviewColors(0xFFE0A32EL)) { AppDrawerPreviewContent() }
}

@Composable
private fun AppDrawerPreviewContent() {
    val mockIcon = android.graphics.Color.LTGRAY.toDrawable()
    val mockApps = listOf(
        InstalledApp("com.android.chrome", "Chrome", mockIcon, isGame = false, isEmulator = false),
        InstalledApp("org.ppsspp.ppsspp", "PPSSPP", mockIcon, isGame = false, isEmulator = true),
        InstalledApp("com.retroarch", "RetroArch", mockIcon, isGame = false, isEmulator = true),
        InstalledApp(
            "com.google.android.youtube",
            "YouTube",
            mockIcon,
            isGame = false,
            isEmulator = false
        ),
        InstalledApp(
            "com.playfieldportal.launcher",
            "Play Field Portal",
            mockIcon,
            isGame = false,
            isEmulator = false
        ),
    )
    val mockCounts = AppFilter.entries.associateWith {
        when (it) {
            AppFilter.ALL -> 42
            AppFilter.GAMES -> 28
            AppFilter.EMULATORS -> 9
            AppFilter.RECENT -> 12
        }
    }
    val mockState = AppDrawerUiState(
        visibleApps = mockApps,
        activeFilter = AppFilter.ALL,
        selectedIndex = 1,
        filterCounts = mockCounts,
    )
    AppDrawerContent(
        state = mockState,
        searchActive = false,
        // Preview shows the overlay hint pill so the design can be inspected without a device.
        showControllerHint = true,
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
        onGrantUsageAccess = {},
    )
}

/** Rebuild the exact palette XmbColorScheme.resolve produces for [waveArgb] (white accent). */
private fun accentPreviewColors(waveArgb: Long): PFPColors {
    val (top, bottom) = lightBackgroundAnchors(waveArgb)
    return PFPColors(
        waveColor = Color(waveArgb),
        accentColor = Color.White,
        textPrimary = Color.White,
        textSecondary = Color.White.copy(alpha = 0.7f),
        backgroundOverlay = Color(0x88000000),
        selectedItem = Color.White,
        categoryBar = Color(0x00000000),
        backgroundTop = Color(top),
        backgroundBottom = Color(bottom),
    )
}

/** What the drawer's search button does next. */
internal enum class DrawerSearchButton { OPEN, REOPEN, CLOSE }

/**
 * X, the controller's search button. A closed search opens; an open one that still holds text
 * brings PFP's keyboard back for more typing rather than wiping it (its Done leaves the search open
 * with the keyboard down); an open, empty one closes.
 */
internal fun drawerSearchButton(searchActive: Boolean, query: String): DrawerSearchButton = when {
    !searchActive -> DrawerSearchButton.OPEN
    query.isNotBlank() -> DrawerSearchButton.REOPEN
    else -> DrawerSearchButton.CLOSE
}
