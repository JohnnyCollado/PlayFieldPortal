package com.playfieldportal.feature.xmb.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.playfieldportal.core.domain.model.ControllerIcon
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.core.ui.detail.DetailRowSpacing
import com.playfieldportal.core.ui.detail.PfpDetailBackground
import com.playfieldportal.core.ui.detail.PfpDetailBreadcrumb
import com.playfieldportal.core.ui.detail.PfpDetailField
import com.playfieldportal.core.ui.detail.PfpDetailFieldBand
import com.playfieldportal.core.ui.detail.PfpDetailHelperFooter
import com.playfieldportal.core.ui.detail.PfpDetailHeroBanner
import com.playfieldportal.core.ui.detail.PfpDetailIconTile
import com.playfieldportal.core.ui.detail.PfpDetailLaunchButton
import com.playfieldportal.core.ui.detail.PfpDetailMediaTile
import com.playfieldportal.core.ui.detail.PfpDetailProgressRow
import com.playfieldportal.core.ui.detail.PfpDetailQuickAction
import com.playfieldportal.core.ui.detail.PfpDetailScaffold
import com.playfieldportal.core.ui.detail.PfpDetailSectionLabel
import com.playfieldportal.core.ui.detail.PfpDetailTextRow
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.ui.theme.menuCursorEdge
import com.playfieldportal.core.ui.theme.menuCursorFill
import com.playfieldportal.feature.xmb.ui.DetailContextMenu
import com.playfieldportal.feature.xmb.ui.DetailMenuRow
import com.playfieldportal.feature.xmb.ui.collection.CollectionPickerPanel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import timber.log.Timber

// The Game Detail page: a controller-first, console-style information page on the shell's accent
// surface. Its structure is the shared core-ui detail scaffold — breadcrumb header, scrolling body
// of full-width rows, permanent helper footer — so App Detail renders the same frame.
//
// Navigation is the shared core-navigation engine (see GameDetailNav): every controller-actionable
// element is a stable semantic node, movement follows reported geometry, and focus-driven scrolling
// replaces the old fixed page-scroll steps. Touch taps route through the same nodes, so a tap and a
// Cross press can never do different things.

private val TextPrimary = Color(0xFFEEEEEE)
private val TextMuted = Color(0xAAB8C6E0)
private val PlayGreen = Color(0xFF45C46A)
private val ActionFail = Color(0xFFFF8A8A)

/** Descriptions longer than this get a Confirm-to-expand affordance. */
private const val OVERVIEW_EXPAND_THRESHOLD = 190

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameDetailScreen(
    gameId: Long,
    onBack: () -> Unit,
    pendingGamepadAction: GamepadAction? = null,
    onGamepadActionConsumed: () -> Unit = {},
    // Show the touch header pills only when the last input was touch (AUTO), like the XMB's
    // contextual App Drawer button; any touch on the screen reports back via [onTouchInput].
    showTouchControls: Boolean = true,
    onTouchInput: () -> Unit = {},
    onOpenShibaCoins: (Long) -> Unit = {},
    // Direct-launch mode: fire the Play action as soon as the game loads. The screen still
    // opens underneath (all launch plumbing lives in the ViewModel) and is what the user
    // returns to when they exit the game.
    autoLaunch: Boolean = false,
    // When set (from the XMB context menu's "Choose Disc"), opens the detail page with this
    // disc pre-selected instead of the set's primary — the disc an auto-launch then boots.
    initialDiscId: Long? = null,
    modifier: Modifier = Modifier,
    viewModel: GameDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(gameId, initialDiscId) {
        viewModel.prepareForOpen()
        viewModel.loadGame(gameId, initialDiscId)
    }
    // Launch-on-open (direct-launch confirm): fire the Play action once THIS game's row is
    // loaded. Keyed on the loaded game's id — not a loaded/unloaded flag — because the retained
    // ViewModel still holds the previously viewed game on reopen, and a boolean key made the
    // effect fire against that stale row (launching the last ROM instead of the selected one).
    if (autoLaunch) {
        val loadedGameId = state.game?.id
        LaunchedEffect(loadedGameId) {
            // Direct-launch auto-fire: the XMB icon confirm already handled the launch sound.
            if (loadedGameId == gameId) viewModel.launch(playSound = false)
        }
    }
    // Seamless direct launch: the page stays invisible (the XMB remains on screen) until the
    // emulator actually covers the launcher — ON_PAUSE fires exactly when another activity
    // comes in front — so confirm goes straight into the game with no detail-page flash, yet
    // this page is what greets the user when they exit back out. A failed launch reveals the
    // page immediately so its error is never trapped behind an invisible screen. Saveable and
    // keyed on the game so process death or a new game resets the gate correctly.
    var revealed by rememberSaveable(gameId) { mutableStateOf(!autoLaunch) }
    if (!revealed) {
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) revealed = true
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(state.launchError) {
            if (state.launchError != null) revealed = true
        }
    }
    // B1: Game Detail no longer calls startActivity itself — the ViewModel funnels the resolved
    // intent through the shared LaunchDispatcher (named failures, outcome recording, foreground
    // verification). Failures land in launchError, revealed by the effect above.
    LaunchedEffect(state.closed) {
        if (state.closed) {
            viewModel.prepareForOpen()
            onBack()
        }
    }
    // Confirm on the Shiba Coins row opens the dedicated screen over this page.
    LaunchedEffect(state.openCoins) {
        if (state.openCoins) {
            onOpenShibaCoins(gameId)
            viewModel.onOpenCoinsConsumed()
        }
    }
    // While the Artwork Studio is open, its screen consumes the actions instead.
    LaunchedEffect(pendingGamepadAction) {
        if (pendingGamepadAction != null && !state.showArtworkStudio) {
            viewModel.handleGamepadAction(pendingGamepadAction)
            onGamepadActionConsumed()
        }
    }
    // Everything above (load, launch, input, close effects) keeps running while hidden.
    if (!revealed) return

    if (state.isLoading) {
        PfpDetailBackground(modifier = modifier.fillMaxSize()) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = menuCursorEdge())
        }
        return
    }

    val game = state.game
    if (game == null) {
        // A missing row must still be a way out: the page shows its own dead end and tells the
        // engine it is laid out, so navigation can never be left permanently un-ready.
        LaunchedEffect(Unit) { viewModel.onPageLaidOut() }
        PfpDetailScaffold(
            modifier = modifier,
            header = {
                PfpDetailBreadcrumb(crumbs = listOf("Library", "Game"), onBack = onBack)
            },
        ) {
            Spacer(Modifier.height(24.dp))
            Text("This game is no longer in your library.", color = TextPrimary, fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            Text("Press Back to return to the library.", color = TextMuted, fontSize = 13.sp)
        }
        return
    }

    // The Artwork Studio fully REPLACES the detail page while open — nothing shows or reacts
    // behind it; closing restores the page exactly where it was (state is untouched).
    if (state.showArtworkStudio) {
        ArtworkStudioScreen(
            gameId = gameId,
            onClose = viewModel::onArtworkStudioClosed,
            pendingGamepadAction = pendingGamepadAction,
            onGamepadActionConsumed = onGamepadActionConsumed,
            showTouchControls = showTouchControls,
            onTouchInput = onTouchInput,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    GameDetailContent(
        state = state,
        game = game,
        onBack = onBack,
        showTouchControls = showTouchControls,
        onTouchInput = onTouchInput,
        viewModel = viewModel,
        modifier = modifier,
    )
}

// ── Page ──────────────────────────────────────────────────────────────────────

@Composable
private fun GameDetailContent(
    state: GameDetailUiState,
    game: Game,
    onBack: () -> Unit,
    showTouchControls: Boolean,
    onTouchInput: () -> Unit,
    viewModel: GameDetailViewModel,
    modifier: Modifier = Modifier,
) {
    val pfpColors = LocalPFPColors.current
    val accentColor = state.platform?.accentColor?.let { Color(it) } ?: pfpColors.accentColor
    val focus = state.navFocusKey

    val pageScrollState = rememberScrollState()
    val mediaListState = rememberLazyListState()
    // One requester per node, created on first measure, plus the root-space Y of every node. Both
    // are what turn "the cursor moved" into "the page shows the cursor": navigation stays
    // coordinate-free and the screen owns the geometry.
    val requesterFor = remember { mutableStateMapOf<String, BringIntoViewRequester>() }
    val nodeY = remember { mutableStateMapOf<String, Float>() }
    // The page top is not a node (the hero is deliberately not a controller-focus target), but it
    // is a scroll target: focusing Launch or a quick action returns the page to the hero.
    val pageTopRequester = remember { BringIntoViewRequester() }

    // Report geometry upward whenever the layout settles. The ViewModel feeds it to the engine,
    // which is what makes UP/DOWN follow the visual rows (and what lets a node that disappears
    // hand its focus to whatever took its place on screen).
    LaunchedEffect(Unit) {
        snapshotFlow { nodeY.toMap() }
            .distinctUntilChanged()
            .collect { viewModel.onNodeGeometry(it) }
    }
    // The first usable graph is on screen: open the navigation gate. Input before this is ignored
    // by the engine rather than buffered, so a press during load can never fire late.
    LaunchedEffect(game.id) {
        snapshotFlow { nodeY.keys.toSet() }
            .filter { it.isNotEmpty() }
            .first()
        viewModel.onPageLaidOut()
    }

    // Focus-driven scrolling, replacing the old fixed page-scroll steps. The helper footer is a real
    // layout row (not an overlay), so the body's viewport already excludes it — "above the footer"
    // needs no extra math.
    LaunchedEffect(focus) {
        val key = focus ?: return@LaunchedEffect
        val mediaIndex = state.detailMedia.indexOfFirst { GameDetailKeys.media(mediaStableId(it)) == key }
        val target = if (mediaIndex >= 0) GameDetailKeys.MEDIA else key
        // The hero is not a node, so bringing Launch into view alone parks the page just above
        // Launch and the hero can never be reached again. The top band scrolls to the page top.
        val inTopBand = key in TopBandKeys
        val requester = requesterFor[target]
        if (!inTopBand && requester == null) return@LaunchedEffect
        // Hold the recovery lock for the alignment: repeated direction presses during the scroll are
        // dropped, never queued, so a held stick cannot outrun the page.
        viewModel.onScrollAlignmentChanged(true)
        try {
            // ONE bring-into-view call, deliberately never a branch of an if/else and never a
            // ScrollState.animateScrollTo. A scroll call that lands in a branch has its Unit
            // result materialized as a `checkcast kotlin.Unit`, and ScrollState.animateScrollTo is
            // compiled as a *discarded* Float-returning animateScrollBy — so the moment such a
            // scroll really suspends, the resumed Float hits that cast and the page dies with
            // "Float cannot be cast to kotlin.Unit" (see GameDetailScrollTest).
            // The page top is its own bring-into-view target instead, so returning to the hero uses
            // the same path as bringing any other node into view.
            (if (inTopBand) pageTopRequester else requester)?.bringIntoView()
            if (mediaIndex >= 0) {
                mediaListState.animateScrollToItem(mediaIndex)
            }
        } finally {
            viewModel.onScrollAlignmentChanged(false)
        }
    }

    PfpDetailScaffold(
        modifier = modifier
            // Any touch anywhere marks the input source as touch without consuming the event, so
            // scrolling and buttons keep working while the controller cursor hides.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); onTouchInput() } },
        scrollState = pageScrollState,
        header = {
            PfpDetailBreadcrumb(
                crumbs = listOf("Library", state.platform?.name ?: game.platformId.uppercase(), game.displayTitle),
                onBack = onBack,
            )
        },
        footer = {
            PfpDetailHelperFooter(
                items = gameDetailHelperItems(state),
                visible = !showTouchControls && state.cursorVisible,
            )
        },
        // Overlays live here rather than in the scrolling body: they must cover the whole page and
        // cannot be scrolled away. Each one pushes its own navigation context, so the page graph
        // behind it is paused and hands back its exact cursor on close.
        overlay = { GameDetailOverlays(state = state, game = game, viewModel = viewModel) },
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).bringIntoViewRequester(pageTopRequester))
        Spacer(Modifier.height(16.dp))

        PfpDetailHeroBanner(
            artworkUri = game.heroUri ?: game.artworkUri,
            title = game.displayTitle,
            platform = state.platform?.name ?: game.platformId.uppercase(),
            accentColor = accentColor,
            facts = listOfNotNull(
                game.lastPlayedAt?.let { "Last played ${relativeDays(it)}" },
                game.totalPlayTimeMillis.takeIf { it > 0 }?.let { "Play time ${formatPlayTime(it)}" },
                game.kindLabel(),
            ),
            favorite = game.isFavorite,
        )

        Spacer(Modifier.height(DetailRowSpacing + 6.dp))

        // ── Primary actions ───────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            PfpDetailIconTile(
                uri = game.iconUri ?: game.logoUri ?: game.artworkUri,
                title = game.displayTitle,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PfpDetailLaunchButton(
                    label = "Launch",
                    icon = Icons.Filled.PlayArrow,
                    focused = focus == GameDetailKeys.LAUNCH,
                    fill = PlayGreen,
                    onClick = { viewModel.onNodeTapped(GameDetailKeys.LAUNCH) },
                    modifier = Modifier.detailNode(GameDetailKeys.LAUNCH, requesterFor, nodeY),
                )
                Row(
                    modifier = Modifier.detailNode(GameDetailKeys.ACTIONS, requesterFor, nodeY),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PfpDetailQuickAction(
                        label = if (game.isFavorite) "Unfavorite" else "Favorite",
                        icon = if (game.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        focused = focus == GameDetailKeys.FAVORITE,
                        available = true,
                        onClick = { viewModel.onNodeTapped(GameDetailKeys.FAVORITE) },
                        contentDescription = if (game.isFavorite) "Remove from favorites" else "Add to favorites",
                        modifier = Modifier.weight(1f),
                    )
                    PfpDetailQuickAction(
                        label = "Artwork",
                        icon = Icons.Filled.Brush,
                        focused = focus == GameDetailKeys.ARTWORK,
                        available = true,
                        onClick = { viewModel.onNodeTapped(GameDetailKeys.ARTWORK) },
                        contentDescription = "Edit artwork",
                        modifier = Modifier.weight(1f),
                    )
                    PfpDetailQuickAction(
                        label = "Manual",
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        focused = focus == GameDetailKeys.MANUAL,
                        // Kept visible when unavailable (the page's action set never moves), drained
                        // and out of the controller graph, but still tappable so touching it can
                        // explain why — the same behaviour as the pre-redesign button.
                        available = state.hasManual,
                        onClick = { viewModel.onNodeTapped(GameDetailKeys.MANUAL) },
                        contentDescription = if (state.hasManual) "Open manual" else "Manual, unavailable",
                        modifier = Modifier.weight(1f),
                    )
                    // Emulator configuration is meaningless for package-backed entries, so the
                    // action is omitted for them entirely.
                    if (state.showEmulatorAction) {
                        PfpDetailQuickAction(
                            label = "Emulator",
                            icon = Icons.Filled.SportsEsports,
                            focused = focus == GameDetailKeys.EMULATOR_ACTION,
                            available = true,
                            onClick = { viewModel.onNodeTapped(GameDetailKeys.EMULATOR_ACTION) },
                            contentDescription = "Change emulator",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (state.launchError != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.launchError!!, color = ActionFail, fontSize = 12.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Get help",
                            color = menuCursorFill(),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { viewModel.requestLaunchHelp() },
                        )
                    }
                } else (state.actionMessage ?: state.artworkMessage)?.let {
                    Text(it, color = menuCursorEdge(), fontSize = 12.sp)
                }
            }
        }

        // ── Discs (multi-disc sets only) ──────────────────────────────────
        if (state.showDiscPicker) {
            Spacer(Modifier.height(DetailRowSpacing))
            DiscRow(
                members = state.discMembers,
                selectedId = state.selectedDiscId,
                focusedKey = focus,
                requesterFor = requesterFor,
                nodeY = nodeY,
                onSelect = { id -> viewModel.onNodeTapped(GameDetailKeys.disc(id)) },
                rowModifier = Modifier.detailNode(GameDetailKeys.DISCS, requesterFor, nodeY),
            )
        }

        // ── Shiba Coins (never for Android entries — they can have no achievements) ──
        if (game.platformId != "android") {
            Spacer(Modifier.height(DetailRowSpacing))
            val coins = state.coins
            PfpDetailProgressRow(
                label = "Shiba Coins",
                value = coins?.let { "${it.earned.total} / ${it.total.total}" } ?: "Not tracked yet",
                secondary = coins?.let { "${(it.progress * 100).toInt()}%" },
                progress = coins?.progress ?: 0f,
                focused = focus == GameDetailKeys.COINS,
                disclosure = true,
                onClick = { viewModel.onNodeTapped(GameDetailKeys.COINS) },
                modifier = Modifier.detailNode(GameDetailKeys.COINS, requesterFor, nodeY),
            )
        }

        // ── Overview ──────────────────────────────────────────────────────
        Spacer(Modifier.height(DetailRowSpacing))
        val description = game.description?.takeIf { it.isNotBlank() } ?: "No description available."
        val expandable = description.length > OVERVIEW_EXPAND_THRESHOLD
        PfpDetailTextRow(
            label = "Overview",
            text = description,
            expanded = state.descriptionExpanded,
            focused = focus == GameDetailKeys.OVERVIEW,
            onClick = if (expandable) ({ viewModel.onNodeTapped(GameDetailKeys.OVERVIEW) }) else null,
            modifier = Modifier.detailNode(GameDetailKeys.OVERVIEW, requesterFor, nodeY),
        )

        // ── Game information ──────────────────────────────────────────────
        if (state.showInfoBand) {
            Spacer(Modifier.height(DetailRowSpacing))
            GameInformationBand(
                game = game,
                state = state,
                focusedKey = focus,
                requesterFor = requesterFor,
                nodeY = nodeY,
                viewModel = viewModel,
            )
        }

        // ── Media strip ───────────────────────────────────────────────────
        if (state.detailMedia.isNotEmpty()) {
            Spacer(Modifier.height(DetailRowSpacing + 6.dp))
            PfpDetailSectionLabel("Media Preview")
            Spacer(Modifier.height(8.dp))
            LazyRow(
                state = mediaListState,
                modifier = Modifier.detailNode(GameDetailKeys.MEDIA, requesterFor, nodeY),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(
                    items = state.detailMedia,
                    // Stable identity: a re-scrape or a reorder must not move the cursor to a
                    // different asset.
                    key = { _, media -> GameDetailKeys.media(mediaStableId(media)) },
                ) { _, media ->
                    val key = GameDetailKeys.media(mediaStableId(media))
                    PfpDetailMediaTile(
                        uri = media.uri,
                        isVideo = media.isVideo,
                        focused = focus == key,
                        posterFallbackUri = state.detailMedia.firstOrNull { !it.isVideo }?.uri
                            ?: game.heroUri ?: game.artworkUri,
                        contentDescription = if (media.isVideo) "Play video" else "Screenshot",
                        onClick = { viewModel.onNodeTapped(key) },
                        modifier = Modifier.detailNode(key, requesterFor, nodeY),
                    )
                }
            }
        }

        Spacer(Modifier.height(DetailRowSpacing))
    }
}

// ── Overlays ──────────────────────────────────────────────────────────────────

@Composable
private fun GameDetailOverlays(
    state: GameDetailUiState,
    game: Game,
    viewModel: GameDetailViewModel,
) {
    Box(Modifier.fillMaxSize()) {
        state.imageViewerUri?.let { imageUri ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.96f))
                    .clickable(onClick = viewModel::closeImageViewer),
            ) {
                coil3.compose.AsyncImage(
                    model = com.playfieldportal.core.ui.image.rememberArtworkModel(imageUri),
                    contentDescription = "Media preview",
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                )
            }
        }

        if (state.showVideoPlayer && state.videoUri != null) {
            GameVideoOverlay(
                videoUri = state.videoUri!!,
                onClose  = viewModel::closeVideoPlayer,
            )
        }

        AnimatedVisibility(state.showOptions, enter = fadeIn(), exit = fadeOut()) {
            DetailContextMenu(
                title = "Options",
                rows = state.visibleActions.map { action ->
                    DetailMenuRow(
                        label = action.dynamicLabel(game.isFavorite, state.isFetchingArtwork),
                        isDestructive = action == DetailAction.REMOVE,
                    )
                },
                selectedIndex = state.optionsIndex,
                onRowClick = { viewModel.onOptionRowTapped(state.visibleActions[it]) },
                onDismiss = viewModel::closeOptions,
            )
        }

        AnimatedVisibility(state.showEmulatorPicker, enter = fadeIn(), exit = fadeOut()) {
            EmulatorPickerPanel(
                options      = state.emulatorPickerOptions,
                selectedId   = game.emulatorPackage,
                focusedIndex = state.emulatorPickerIndex,
                onPick       = viewModel::onEmulatorPickTapped,
                onClose      = viewModel::closeEmulatorPicker,
            )
        }

        AnimatedVisibility(state.metadataPreview != null, enter = fadeIn(), exit = fadeOut()) {
            state.metadataPreview?.let { preview ->
                MetadataPreviewPanel(
                    ui             = preview,
                    focusFill      = menuCursorFill(),
                    focusEdge      = menuCursorEdge(),
                    onSelectPolicy = viewModel::selectMetadataPolicy,
                    onCycleSource  = viewModel::cycleMetadataSource,
                    onToggleField  = viewModel::toggleMetadataField,
                    onApply        = viewModel::applyMetadataPreview,
                    onClose        = viewModel::closeMetadataPreview,
                )
            }
        }

        AnimatedVisibility(state.collectionPicker.visible, enter = fadeIn(), exit = fadeOut()) {
            CollectionPickerPanel(
                ui                  = state.collectionPicker,
                onRowClick          = viewModel::onCollectionRowClick,
                onClose             = viewModel::closeCollectionPicker,
                onCreateTextChanged = viewModel::onCreateCollectionTextChanged,
                onConfirmCreate     = viewModel::confirmCreateCollection,
                onCancelCreate      = viewModel::cancelCreateCollection,
            )
        }

        AnimatedVisibility(state.isEditingNote, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                NoteEditor(state.noteText, viewModel::onNoteChanged, viewModel::saveNote, viewModel::cancelNote)
            }
        }

        AnimatedVisibility(state.isEditingTitle, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                TitleEditor(
                    text       = state.titleText,
                    onChange   = viewModel::onTitleChanged,
                    onSave     = viewModel::saveTitle,
                    onReset    = viewModel::resetTitleToDefault,
                    onCancel   = viewModel::cancelTitleEdit,
                )
            }
        }

        // Topmost overlay — the ViewModel routes all gamepad input here while it's open.
        state.manualViewerUri?.let { source ->
            ManualViewerOverlay(
                source      = source,
                title       = "${game.displayTitle} — Manual",
                page        = state.manualPage,
                scrollSteps = state.manualScrollSteps,
                onPageCount = viewModel::setManualPageCount,
                onPrevPage  = viewModel::manualPrevPage,
                onNextPage  = viewModel::manualNextPage,
                onClose     = viewModel::closeManualViewer,
            )
        }

        if (state.confirmRemove) {
            AlertDialog(
                onDismissRequest = viewModel::cancelRemove,
                title = { Text("Remove ${game.title}?") },
                text = { Text("Removes this game from your library. ROM/app files are not deleted.") },
                confirmButton = { TextButton(onClick = viewModel::confirmRemoveGame) { Text("Remove") } },
                dismissButton = { TextButton(onClick = viewModel::cancelRemove) { Text("Cancel") } },
            )
        }
    }
}

// ── Disc row ──────────────────────────────────────────────────────────────────

/**
 * One stable node per disc member. Selecting a disc persists it as the preferred disc (the existing
 * repository behaviour) and never moves focus to an unrelated element — the engine keeps the cursor
 * on the node the user confirmed.
 */
@Composable
private fun DiscRow(
    members: List<Game>,
    selectedId: Long?,
    focusedKey: String?,
    requesterFor: MutableMap<String, BringIntoViewRequester>,
    nodeY: MutableMap<String, Float>,
    onSelect: (Long) -> Unit,
    rowModifier: Modifier = Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PfpDetailSectionLabel("Discs")
        LazyRow(
            modifier = rowModifier,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(
                items = members,
                key = { _, member -> GameDetailKeys.disc(member.id) },
            ) { _, member ->
                val key = GameDetailKeys.disc(member.id)
                val isFocused = focusedKey == key
                val isSelected = selectedId == member.id
                val label = member.discNumber?.let { "Disc $it" } ?: "Playlist"
                // Preference is communicated by named "Preferred" text and the edge, not by colour
                // alone.
                Column(
                    modifier = Modifier
                        .widthIn(min = 116.dp)
                        .detailNode(key, requesterFor, nodeY)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) menuCursorFill().copy(alpha = 0.2f) else Color(0x40101018))
                        .border(
                            width = if (isFocused) 2.dp else 1.dp,
                            color = when {
                                isFocused -> menuCursorEdge()
                                isSelected -> menuCursorEdge().copy(alpha = 0.55f)
                                else -> Color.White.copy(alpha = 0.14f)
                            },
                            shape = RoundedCornerShape(8.dp),
                        )
                        .clickable(role = Role.Button) { onSelect(member.id) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(label, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = when {
                            member.isMissing -> "Missing"
                            isSelected -> "Preferred"
                            else -> "Available"
                        },
                        color = if (member.isMissing) ActionFail else TextMuted,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

// ── Game information band ─────────────────────────────────────────────────────

/**
 * The structured information band. Fields wrap from one horizontal band into multiple rows as the
 * page narrows, and absent values are omitted rather than filled with "Unknown".
 *
 * The emulator field is the band's one inline action: reached with RIGHT, confirmed to change the
 * emulator for this game only. Package-backed entries never render it.
 */
@Composable
private fun GameInformationBand(
    game: Game,
    state: GameDetailUiState,
    focusedKey: String?,
    requesterFor: MutableMap<String, BringIntoViewRequester>,
    nodeY: MutableMap<String, Float>,
    viewModel: GameDetailViewModel,
) {
    val resolved = state.resolvedLaunch
    PfpDetailFieldBand(
        focused = focusedKey == GameDetailKeys.INFO,
        modifier = Modifier.detailNode(GameDetailKeys.INFO, requesterFor, nodeY),
    ) {
        game.releaseYear?.let { year ->
            PfpDetailField(label = "Released", value = year.toString())
        }
        game.developer?.takeIf { it.isNotBlank() }?.let {
            PfpDetailField(label = "Developer", value = it)
        }
        // Publisher only when it adds information (it often equals the developer).
        game.publisher?.takeIf { !it.isNullOrBlank() && !it.equals(game.developer, ignoreCase = true) }?.let {
            PfpDetailField(label = "Publisher", value = it)
        }
        game.genre?.takeIf { it.isNotBlank() }?.let {
            PfpDetailField(label = "Genre", value = it)
        }
        game.lastPlayedAt?.let {
            PfpDetailField(label = "Last played", value = relativeDays(it))
        }
        if (game.totalPlayTimeMillis > 0) {
            PfpDetailField(label = "Play time", value = formatPlayTime(game.totalPlayTimeMillis))
        }
        if (!state.isPackageBacked && resolved != null) {
            PfpDetailField(
                label = "Emulator",
                value = resolved.profile.name,
                secondary = listOfNotNull(
                    resolved.coreName?.let { "Core: $it" },
                    resolved.source.label.lowercase(),
                ).joinToString("  ·  ").takeIf { it.isNotBlank() },
                focused = focusedKey == GameDetailKeys.EMULATOR_INFO,
                disclosure = true,
                onClick = { viewModel.onNodeTapped(GameDetailKeys.EMULATOR_INFO) },
                modifier = Modifier.detailNode(GameDetailKeys.EMULATOR_INFO, requesterFor, nodeY),
            )
        }
    }
}

// ── Helper footer ─────────────────────────────────────────────────────────────

/**
 * The contextual helper footer: only the actions that are actually available, named for what they
 * do in the current context (the design's Confirm/Options/Back on the base page, "Apply" in the
 * metadata overlay, "Remove"/"Cancel" on the removal prompt).
 *
 * Pure function of the state so it can be unit-tested without a composition.
 */
internal fun gameDetailHelperItems(state: GameDetailUiState): List<ControllerPromptItem> = when {
    state.imageViewerUri != null || state.showVideoPlayer ->
        listOf(
            ControllerPromptItem(GamepadAction.SELECT, "Close"),
            ControllerPromptItem(GamepadAction.BACK, "Back"),
        )
    state.manualViewerUri != null ->
        listOf(
            ControllerPromptItem.fixed(ControllerIcon.DPAD_ALL, "Scroll"),
            ControllerPromptItem(GamepadAction.PREV_CATEGORY, "Prev page"),
            ControllerPromptItem(GamepadAction.NEXT_CATEGORY, "Next page"),
            ControllerPromptItem(GamepadAction.BACK, "Close"),
        )
    state.confirmRemove ->
        listOf(
            ControllerPromptItem(GamepadAction.SELECT, "Remove"),
            ControllerPromptItem(GamepadAction.BACK, "Cancel"),
        )
    state.isEditingNote || state.isEditingTitle ->
        listOf(ControllerPromptItem(GamepadAction.BACK, "Cancel"))
    state.metadataPreview != null ->
        listOf(
            ControllerPromptItem.fixed(ControllerIcon.DPAD_ALL, "Navigate"),
            ControllerPromptItem(GamepadAction.SELECT, "Apply / Toggle"),
            ControllerPromptItem(GamepadAction.NAVIGATE_LEFT, "Policy"),
            ControllerPromptItem(GamepadAction.PREV_CATEGORY, "Source"),
            ControllerPromptItem(GamepadAction.BACK, "Close"),
        )
    state.showEmulatorPicker ->
        listOf(
            ControllerPromptItem.fixed(ControllerIcon.DPAD_ALL, "Navigate"),
            ControllerPromptItem(GamepadAction.SELECT, "Choose"),
            ControllerPromptItem(GamepadAction.BACK, "Cancel"),
        )
    state.collectionPicker.visible ->
        listOf(
            ControllerPromptItem.fixed(ControllerIcon.DPAD_ALL, "Navigate"),
            ControllerPromptItem(GamepadAction.SELECT, "Toggle"),
            ControllerPromptItem(GamepadAction.BACK, "Close"),
        )
    state.showOptions ->
        listOf(
            ControllerPromptItem.fixed(ControllerIcon.DPAD_ALL, "Navigate"),
            ControllerPromptItem(GamepadAction.SELECT, "Select"),
            ControllerPromptItem(GamepadAction.BACK, "Close"),
        )
    else -> listOf(
        ControllerPromptItem(GamepadAction.SELECT, confirmLabelFor(state)),
        ControllerPromptItem(GamepadAction.OPEN_CONTEXT_MENU, "Options"),
        ControllerPromptItem(GamepadAction.BACK, "Back"),
    )
}

/** What Confirm means on the page itself, given the focused node. */
private fun confirmLabelFor(state: GameDetailUiState): String {
    val focus = state.navFocusKey ?: return "Launch"
    val media = state.detailMedia.firstOrNull { GameDetailKeys.media(mediaStableId(it)) == focus }
    return when {
        media != null -> if (media.isVideo) "Play" else "Preview"
        focus == GameDetailKeys.COINS -> "View"
        focus == GameDetailKeys.OVERVIEW ->
            if (state.descriptionExpanded) "Collapse" else "Read more"
        focus.startsWith("game-detail:disc:") -> "Choose disc"
        focus == GameDetailKeys.FAVORITE ->
            if (state.game?.isFavorite == true) "Unfavorite" else "Favorite"
        focus == GameDetailKeys.ARTWORK -> "Edit artwork"
        focus == GameDetailKeys.MANUAL -> "Open manual"
        focus == GameDetailKeys.EMULATOR_ACTION || focus == GameDetailKeys.EMULATOR_INFO -> "Change emulator"
        else -> "Launch"
    }
}

/** Launch and the quick actions sit directly under the hero, so focusing any of them shows the page top. */
private val TopBandKeys = setOf(
    GameDetailKeys.LAUNCH,
    GameDetailKeys.ACTIONS,
    GameDetailKeys.FAVORITE,
    GameDetailKeys.ARTWORK,
    GameDetailKeys.MANUAL,
    GameDetailKeys.EMULATOR_ACTION,
)

/**
 * The shared "page node" binding: a bring-into-view target for focus-driven scrolling plus the
 * node's root-space Y for geometry-driven movement.
 */
private fun Modifier.detailNode(
    key: String,
    requesterFor: MutableMap<String, BringIntoViewRequester>,
    nodeY: MutableMap<String, Float>,
): Modifier = this
    .bringIntoViewRequester(requesterFor.getOrPut(key) { BringIntoViewRequester() })
    .onGloballyPositioned { coordinates -> nodeY[key] = coordinates.positionInRoot().y }

// What this library entry actually is — shown in the hero facts so all three entry kinds share one
// screen without losing their identity.
private fun Game.kindLabel(): String = when {
    shortcutId != null || launchIntentUri != null -> "PC Shortcut"
    romPath == null && packageName != null        -> "Game App"
    else                                          -> "ROM"
}

private fun formatPlayTime(millis: Long): String {
    val minutes = millis / 60_000
    return when {
        minutes < 1     -> "Under a minute"
        minutes < 60    -> "$minutes min"
        else            -> "${minutes / 60} h ${minutes % 60} min"
    }
}

private fun relativeDays(epochMillis: Long): String {
    val days = ((System.currentTimeMillis() - epochMillis) / 86_400_000L).coerceAtLeast(0)
    return when (days) {
        0L   -> "Today"
        1L   -> "Yesterday"
        else -> "$days days ago"
    }
}

private val OptionsPanelMaxHeight: Dp = 440.dp
private val OptionsRowScrollStep: Dp = 58.dp

private fun DetailAction.dynamicLabel(favorite: Boolean, refreshing: Boolean): String = when (this) {
    DetailAction.FAVORITE -> if (favorite) "Unfavorite" else "Favorite"
    DetailAction.REFRESH -> if (refreshing) "Refreshing..." else "Refresh"
    else -> label
}

@Composable
private fun NoteEditor(text: String, onChange: (String) -> Unit, onSave: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xF20A0A14))
            .padding(16.dp),
    ) {
        Text("Edit Note", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = onChange,
            label = { Text("Note", color = TextMuted) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = menuCursorEdge(),
                unfocusedBorderColor = Color(0x44FFFFFF),
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = menuCursorEdge(),
            ),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave() }),
            maxLines = 4,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("Cancel", color = TextMuted) }
            TextButton(onClick = onSave) { Text("Save", color = menuCursorEdge(), fontWeight = FontWeight.SemiBold) }
        }
    }
}

// ── Title Editor ─────────────────────────────────────────────────────────────

@Composable
private fun TitleEditor(
    text: String,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xF20A0A14))
            .padding(16.dp),
    ) {
        Text("Edit Title", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Changes the display name only — the ROM file is not renamed.",
            color = TextMuted,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = onChange,
            label = { Text("Display Title", color = TextMuted) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = menuCursorEdge(),
                unfocusedBorderColor = Color(0x44FFFFFF),
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = menuCursorEdge(),
            ),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSave() }),
            singleLine = true,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onReset) { Text("Reset to Default", color = TextMuted, fontSize = 12.sp) }
            Row {
                TextButton(onClick = onCancel) { Text("Cancel", color = TextMuted) }
                TextButton(onClick = onSave) { Text("Save", color = menuCursorEdge(), fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

// ── Emulator Picker Panel ─────────────────────────────────────────────────────

@Composable
private fun EmulatorPickerPanel(
    options: List<com.playfieldportal.core.domain.model.EmulatorProfile>,
    selectedId: String?,
    focusedIndex: Int,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 460.dp)
                .fillMaxWidth(0.86f)
                .heightIn(max = OptionsPanelMaxHeight)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xF20A0A14))
                .clickable(enabled = false) {}
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Choose Emulator",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Up/Down  Navigate  •  Select  Confirm  •  B  Cancel",
                color = TextMuted.copy(alpha = 0.5f),
                fontSize = 10.sp,
            )

            val scrollState = rememberScrollState()
            val stepPx = with(androidx.compose.ui.platform.LocalDensity.current) { OptionsRowScrollStep.roundToPx() }
            LaunchedEffect(focusedIndex) { scrollState.animateScrollTo(focusedIndex * stepPx) }

            Column(
                modifier = Modifier.verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                options.forEachIndexed { index, profile ->
                    val isFocused  = focusedIndex == index
                    val isSelected = selectedId != null && (profile.id == selectedId || profile.packageName == selectedId)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    isFocused  -> menuCursorFill()
                                    isSelected -> menuCursorFill().copy(alpha = 0.17f)
                                    else       -> Color(0xFF1B1B26)
                                }
                            )
                            .then(
                                if (isFocused) Modifier.border(1.5.dp, menuCursorEdge(), RoundedCornerShape(8.dp))
                                else if (isSelected) Modifier.border(1.dp, menuCursorEdge().copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                else Modifier
                            )
                            .clickable { onPick(profile.id) }
                            .padding(vertical = 12.dp, horizontal = 12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(profile.name, color = TextPrimary, fontSize = 14.sp, maxLines = 1)
                            Text(
                                profile.packageName,
                                color = TextMuted,
                                fontSize = 11.sp,
                                maxLines = 1,
                            )
                        }
                        if (isSelected) {
                            com.playfieldportal.core.ui.components.PfpCheckMark(PlayGreen, Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

// Fullscreen built-in player for the game's video snap: standard transport controls, black
// backdrop, tap outside or Back closes. Player is released the moment the overlay leaves
// composition.
@Composable
private fun GameVideoOverlay(videoUri: String, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var videoSize by remember(videoUri) { mutableStateOf<androidx.media3.common.VideoSize?>(null) }
    val player = remember(videoUri) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(videoUri))
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) { videoSize = size }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_ENDED) onClose()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.96f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        // TextureView (not PlayerView/SurfaceView): composites inside this translucent overlay
        // like any composable — a SurfaceView hole would render behind it and show black.
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                android.view.TextureView(ctx).also { view ->
                    player.setVideoTextureView(view)
                    view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                        applyFit(v as android.view.TextureView, videoSize)
                    }
                }
            },
            update = { view -> applyFit(view, videoSize) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// Letterboxed fit: scale the frame to the largest size inside the view at its own aspect.
private fun applyFit(view: android.view.TextureView, size: androidx.media3.common.VideoSize?) {
    val vw = size?.width?.toFloat() ?: return
    val vh = size.height.toFloat()
    if (vw <= 0f || vh <= 0f || view.width == 0 || view.height == 0) return
    val viewW = view.width.toFloat()
    val viewH = view.height.toFloat()
    val scale = minOf(viewW / vw, viewH / vh)
    val matrix = android.graphics.Matrix().apply {
        setScale((vw * scale) / viewW, (vh * scale) / viewH, viewW / 2f, viewH / 2f)
    }
    view.setTransform(matrix)
}
