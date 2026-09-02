package com.playfieldportal.feature.appbar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.lightBackgroundAnchors
import com.playfieldportal.core.ui.preview.CombinedPreviews
import com.playfieldportal.core.ui.preview.PfpPreview
import com.playfieldportal.core.ui.theme.PFPColors
import com.playfieldportal.core.ui.theme.StorefrontColors
import com.playfieldportal.core.ui.theme.deriveStorefrontColors
import com.playfieldportal.core.ui.theme.menuCursorEdge
import com.playfieldportal.feature.appbar.appdrawer.AppDrawerCategoryTabs
import com.playfieldportal.feature.appbar.appdrawer.AppDrawerGrid
import com.playfieldportal.feature.appbar.appdrawer.AppDrawerHeader
import com.playfieldportal.feature.appbar.appdrawer.AppDrawerHintBar
import com.playfieldportal.feature.appbar.appdrawer.AppDrawerOptions
import com.playfieldportal.feature.appbar.appdrawer.UninstallConfirmDialog

// ── PSP-era grid App Drawer ───────────────────────────────────────────────────
//
// Grid-centric and artwork-first: a header/breadcrumb, a horizontal category tab row, and a
// 6-column application grid over an accent-derived gradient (see deriveStorefrontColors). The
// controller hint pill floats as an overlay so grid geometry never shifts when it fades in/out;
// the pre-redesign storefront layout (vertical rail + command bar) is preserved for the future
// RSS Channels feature in the appbar/storefront package.

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
            when {
                // An inner drawer overlay (options menu / uninstall confirm) is up: BACK goes to
                // the drawer ViewModel, which pops that overlay. XMBViewModel forwards every
                // action — including BACK — to the drawer, so BACK here NEVER closes the drawer
                // itself while an overlay is open.
                overlayOpen -> viewModel.handleGamepadAction(pendingGamepadAction)
                // BACK on the plain grid closes the drawer (its only controller escape).
                pendingGamepadAction == GamepadAction.BACK -> onBack()
                pendingGamepadAction == GamepadAction.CHANGE_SORT -> {
                    // X / Square — toggle search (App Drawer remap). Deliberately NOT routed
                    // through onSearchToggle: that path reports touch input, and this is
                    // controller input.
                    searchActive = !searchActive
                    if (!searchActive) viewModel.setSearchQuery("")
                }
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

    AppDrawerContent(
        state = state,
        searchActive = searchActive,
        showControllerHint = showControllerHint,
        // The back breadcrumb is a touch target; controller BACK closes the drawer at the XMB
        // layer (never through this lambda), so reporting touch here is always accurate.
        onBack = {
            onTouchInteraction()
            onBack()
        },
        onSearchQueryChange = { viewModel.setSearchQuery(it) },
        onSearchToggle = { active ->
            onTouchInteraction()
            searchActive = active
            if (!active) viewModel.setSearchQuery("")
        },
        onSearchDone = { keyboard?.hide() },
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
                        AppDrawerGrid(
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

        // ── Controller hint pill (overlay — content geometry never shifts) ──
        // Same fade-in timing and ExitTransition.None convention as the XMB's own hint
        // (XMBShell.kt): the pill cuts out instantly on any input and fades in after the idle
        // delay. Suppressed while a drawer-level overlay (options menu / confirm) is up.
        AnimatedVisibility(
            visible = showControllerHint && state.menuApp == null && state.confirmUninstall == null,
            enter = fadeIn(tween(200)),
            exit = ExitTransition.None,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            AppDrawerHintBar(
                modifier = Modifier.padding(bottom = 20.dp),
            )
        }

        // ── Overlays ──────────────────────────────────────────────────────
        state.menuApp?.let { app ->
            AppDrawerOptions(
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
                colors = sf,
            )
        }
    }
}

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
        onConfirmUninstall = {},
        onCancelUninstall = {},
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
