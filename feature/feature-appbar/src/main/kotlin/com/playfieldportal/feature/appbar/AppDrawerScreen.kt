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
import com.playfieldportal.core.domain.model.GamepadAction
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.Image
import com.google.accompanist.drawablepainter.DrawablePainter

private val DrawerBg      = Color(0xF0080808)   // near-black, wave visible beneath
private val DrawerAccent  = Color(0xFF4A90D9)
private val DrawerText    = Color.White
private val DrawerSubtext = Color(0xFFAAAAAA)
private val DrawerChip    = Color(0xFF1A1A2E)
private val DrawerSelected = Color(0xFF4A90D9).copy(alpha = 0.25f)

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

    LaunchedEffect(pendingGamepadAction) {
        if (pendingGamepadAction != null) {
            viewModel.handleGamepadAction(pendingGamepadAction)
            onGamepadActionConsumed()
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Apply initial filter once on first composition
    val appliedInitial = remember { mutableStateOf(false) }
    if (!appliedInitial.value) {
        viewModel.setFilter(initialFilter)
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DrawerBg),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Header ─────────────────────────────────────────────────────
            DrawerHeader(
                title        = state.activeFilter.label,
                appCount     = state.visibleApps.size,
                searchQuery  = state.searchQuery,
                searchActive = searchActive,
                onSearchToggle   = { searchActive = it; if (!it) viewModel.setSearchQuery("") },
                onSearchChange   = { viewModel.setSearchQuery(it) },
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
                            color    = DrawerAccent,
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
                            onAppSelected = { viewModel.onAppSelected(it) },
                            onAppLaunched = { viewModel.launchApp(it) },
                        )
                    }
                }
            }
        }
    }
}

// ── Header ────────────────────────────────────────────────────────────────────

@Composable
private fun DrawerHeader(
    title: String,
    appCount: Int,
    searchQuery: String,
    searchActive: Boolean,
    onSearchToggle: (Boolean) -> Unit,
    onSearchChange: (String) -> Unit,
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
                cursorBrush   = SolidColor(DrawerAccent),
                modifier      = Modifier
                    .width(240.dp)
                    .background(DrawerChip, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        Text(
            text     = if (searchActive) "✕" else "⌕",
            color    = DrawerAccent,
            fontSize = 18.sp,
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
            Text(
                text       = filter.label,
                color      = if (isActive) DrawerAccent else DrawerSubtext,
                fontSize   = 13.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                modifier   = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isActive) DrawerChip else Color.Transparent)
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
    onAppSelected: (Int) -> Unit,
    onAppLaunched: (String) -> Unit,
) {
    val gridState = rememberLazyGridState()

    LaunchedEffect(selectedIndex) {
        if (apps.isNotEmpty()) gridState.animateScrollToItem(selectedIndex)
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
                isSelected = index == selectedIndex,
                onClick    = { onAppSelected(index) },
                onLaunch   = { onAppLaunched(app.packageName) },
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
    onLaunch: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) DrawerSelected else Color.Transparent)
            .border(
                width = if (isSelected) 1.dp else 0.dp,
                color = if (isSelected) DrawerAccent.copy(alpha = 0.5f) else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            .combinedClickable(
                onClick    = onClick,
                onDoubleClick = onLaunch,
                onLongClick   = onLaunch,
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
                color    = DrawerAccent,
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
                color      = DrawerAccent,
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
