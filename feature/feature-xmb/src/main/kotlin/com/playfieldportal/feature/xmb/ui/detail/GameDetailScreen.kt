package com.playfieldportal.feature.xmb.ui.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.playfieldportal.core.domain.model.Game
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.ui.theme.menuCursorEdge
import com.playfieldportal.core.ui.theme.menuCursorFill
import com.playfieldportal.feature.xmb.ui.DetailContextMenu
import com.playfieldportal.feature.xmb.ui.DetailMenuRow
import com.playfieldportal.feature.xmb.ui.collection.CollectionPickerPanel
import timber.log.Timber

// The shared hero-card building blocks (breadcrumb, hero card, icon tile, console button,
// square action button) live in DetailComponents.kt — shared with the App Detail page.
// Neutral dark surfaces stay fixed; every accent/focus color comes from the active theme via
// menuCursorFill()/menuCursorEdge() so this screen follows the chosen color scheme.
private val TextPrimary = Color(0xFFEEEEEE)
private val TextMuted = Color(0xAAEEEEEE)
private val PlayGreen = Color(0xFF45C46A)
private val ActionFill = Color(0xFF1B1B26)
private val PageBg = Color(0xFF06060C)

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
    // Direct-launch mode: fire the Play action as soon as the game loads. The screen still
    // opens underneath (all launch plumbing lives in the ViewModel) and is what the user
    // returns to when they exit the game.
    autoLaunch: Boolean = false,
    modifier: Modifier = Modifier,
    viewModel: GameDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var pendingArtworkType by remember { mutableStateOf<ArtworkType?>(null) }
    val artworkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val type = pendingArtworkType
        if (uri != null && type != null) viewModel.onArtworkLocalPicked(uri, type)
        pendingArtworkType = null
    }

    LaunchedEffect(gameId) {
        viewModel.prepareForOpen()
        viewModel.loadGame(gameId)
    }
    // Launch-on-open ("Launch Game" / direct-launch tap): waits for the game row to load
    // (launch() no-ops on unloaded state), then fires exactly once per screen open.
    if (autoLaunch) {
        val gameLoaded = state.game != null
        LaunchedEffect(gameLoaded) {
            if (gameLoaded) viewModel.launch()
        }
    }
    LaunchedEffect(Unit) {
        viewModel.launchEffect.collect { intent ->
            viewModel.onLaunchIntentCollected()
            try {
                viewModel.onLaunchStartActivityReached()
                context.startActivity(intent)
                Timber.i("context.startActivity completed for launch intent")
            } catch (e: android.content.ActivityNotFoundException) {
                Timber.w(e, "Launch startActivity failed: emulator activity not found")
                viewModel.onLaunchFailed("Emulator not found. Is it installed?")
            } catch (e: SecurityException) {
                Timber.w(e, "Launch startActivity failed: permission denied")
                viewModel.onLaunchFailed("Permission denied launching emulator")
            } catch (e: Exception) {
                Timber.w(e, "Launch startActivity failed")
                viewModel.onLaunchFailed("Could not open emulator: ${e.message}")
            }
        }
    }
    LaunchedEffect(state.closed) {
        if (state.closed) {
            viewModel.prepareForOpen()
            onBack()
        }
    }
    LaunchedEffect(pendingGamepadAction) {
        if (pendingGamepadAction != null) {
            viewModel.handleGamepadAction(pendingGamepadAction)
            onGamepadActionConsumed()
        }
    }
    LaunchedEffect(state.artworkPendingLocal) {
        val type = state.artworkPendingLocal ?: return@LaunchedEffect
        pendingArtworkType = type
        artworkPicker.launch(arrayOf("image/png", "image/jpeg", "image/webp"))
        viewModel.consumeArtworkLocalPick()
    }

    if (state.isLoading) {
        Box(modifier.fillMaxSize().background(PageBg)) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = menuCursorEdge())
        }
        return
    }

    val game = state.game ?: return
    val platform = state.platform
    val pfpColors = LocalPFPColors.current
    val accentColor = platform?.accentColor?.let { Color(it) } ?: pfpColors.accentColor

    // Same translucent theme-gradient backdrop as the Music browser, so the XMB wave stays visible
    // behind and all full-screen menus read consistently.
    Box(
        modifier = modifier
            .fillMaxSize()
            // Any touch anywhere marks the input source as touch (revealing the header pills),
            // without consuming the event so buttons still work.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); onTouchInput() } }
            .background(
                Brush.verticalGradient(
                    0f to pfpColors.backgroundTop.copy(alpha = 0.72f),
                    1f to pfpColors.backgroundBottom.copy(alpha = 0.90f),
                )
            ),
    ) {
        // One shared layout for every entry kind (ROMs, game apps, PC shortcuts), modeled on the
        // hero-card detail mockup: breadcrumb → hero card → icon + play/actions → info + description.
        // Scrollable by touch, and by D-pad: DOWN past the button row advances pageScrollSteps
        // (see the ViewModel), which animates the same scroll state in fixed steps.
        val pageScrollState = rememberScrollState()
        val pageStepPx = with(LocalDensity.current) { PageScrollStep.roundToPx() }
        LaunchedEffect(state.pageScrollSteps) {
            // Eased tween instead of the default spring — the spring settles with a hard stop,
            // which made held-D-pad scrolling feel stiff and notchy.
            pageScrollState.animateScrollTo(
                (state.pageScrollSteps * pageStepPx).coerceAtMost(pageScrollState.maxValue),
                animationSpec = tween(durationMillis = 320, easing = LinearOutSlowInEasing),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 920.dp)
                .align(Alignment.TopCenter)
                .verticalScroll(pageScrollState)
                .padding(start = 28.dp, end = 28.dp, bottom = 22.dp),
        ) {
            DetailBreadcrumb(
                title    = platform?.name ?: game.platformId.uppercase(),
                subtitle = game.kindLabel(),
                onBack   = onBack,
            )

            HeroCard(
                uri         = game.heroUri ?: game.artworkUri,
                title       = game.displayTitle,
                platform    = platform?.name ?: game.platformId.uppercase(),
                accentColor = accentColor,
            )

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                IconTile(
                    uri   = game.iconUri ?: game.logoUri ?: game.artworkUri,
                    title = game.displayTitle,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ConsoleButton(
                        label = "Launch",
                        icon = Icons.Filled.PlayArrow,
                        focused = state.mainFocus == 0,
                        fill = PlayGreen,
                        textColor = Color(0xFF06210D),
                        onClick = viewModel::onPlayClicked,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SquareActionButton(
                            icon = Icons.Filled.Settings,
                            contentDescription = "Options",
                            focused = state.mainFocus == 1,
                            onClick = viewModel::onOptionsClicked,
                        )
                        SquareActionButton(
                            icon = Icons.Filled.Brush,
                            contentDescription = "Edit Artwork",
                            focused = state.mainFocus == 2,
                            onClick = viewModel::openArtworkManager,
                        )
                        SquareActionButton(
                            icon = Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = "Manual",
                            focused = state.mainFocus == 3,
                            onClick = viewModel::onManualClicked,
                        )
                    }
                    (state.launchError ?: state.actionMessage ?: state.artworkMessage)?.let {
                        Text(it, color = menuCursorEdge(), fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                GameInfoList(
                    releaseYear = game.releaseYear,
                    developer   = game.developer,
                    publisher   = game.publisher,
                    genre       = game.genre,
                    lastPlayedAt = game.lastPlayedAt,
                    playTimeMillis = game.totalPlayTimeMillis,
                    // Package-backed gaming apps launch by package/shortcut — no emulator meta.
                    emulator    = if (state.isPackageBacked) null else (state.emulatorName ?: "Not set"),
                    modifier    = Modifier.weight(0.42f),
                )
                DescriptionPanel(
                    description = game.description,
                    modifier    = Modifier.weight(0.58f),
                )
            }

            // MEDIA PREVIEW — Steam-store-style strip: video tiles first, then screenshots.
            // Confirm/tap a tile to preview (video plays via the user's player choice,
            // images open the fullscreen viewer).
            if (state.detailMedia.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text("MEDIA PREVIEW", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                val mediaLazyState = rememberLazyListState()
                LaunchedEffect(state.mediaFocus) {
                    if (state.mediaFocus >= 0) mediaLazyState.animateScrollToItem(state.mediaFocus)
                }
                LazyRow(
                    state = mediaLazyState,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(state.detailMedia) { index, media ->
                        val focused = state.mediaFocus == index
                        Box(
                            modifier = Modifier
                                .size(width = 214.dp, height = 120.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF11111A))
                                .border(
                                    width = if (focused) 2.dp else 1.dp,
                                    color = if (focused) menuCursorEdge() else Color.White.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(10.dp),
                                )
                                .clickable { viewModel.openMediaAt(index) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (media.isVideo) {
                                // Poster: fall back to the hero/screenshot look — a dark tile
                                // with a play glyph, like a store trailer card.
                                AsyncImage(
                                    model = state.detailMedia.firstOrNull { !it.isVideo }?.uri
                                        ?: game.heroUri ?: game.artworkUri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = "Play video",
                                    tint = Color.White,
                                    modifier = Modifier.size(44.dp),
                                )
                            } else {
                                AsyncImage(
                                    model = media.uri,
                                    contentDescription = "Screenshot",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Fullscreen image preview (Steam-style) — Confirm/Back or tap closes.
        state.imageViewerUri?.let { imageUri ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.96f))
                    .clickable(onClick = viewModel::closeImageViewer),
            ) {
                AsyncImage(
                    model = imageUri,
                    contentDescription = "Media preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                )
            }
        }

        // Built-in fullscreen snap player (used when no external video player is pinned).
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
                onRowClick = { viewModel.onOptionClicked(state.visibleActions[it]) },
                onDismiss = viewModel::closeOptions,
            )
        }

        AnimatedVisibility(state.showEmulatorPicker, enter = fadeIn(), exit = fadeOut()) {
            EmulatorPickerPanel(
                options      = state.emulatorPickerOptions,
                selectedId   = game.emulatorPackage,
                focusedIndex = state.emulatorPickerIndex,
                onPick       = viewModel::confirmEmulatorPick,
                onMove       = viewModel::onEmulatorPickerMove,
                onClose      = viewModel::closeEmulatorPicker,
            )
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

        AnimatedVisibility(state.showArtworkManager, enter = fadeIn(), exit = fadeOut()) {
            ArtworkManagerPanel(
                state = state,
                game = game,
                onTabSelected = viewModel::setArtworkTab,
                onActivateSourceAt = viewModel::activateSourceAt,
                onPickArtwork = viewModel::pickSgdbArtwork,
                onClose = viewModel::closeArtworkManager,
            )
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

// What this library entry actually is — shown as the breadcrumb subtitle so all three entry
// kinds share one screen without losing their identity.
private fun Game.kindLabel(): String = when {
    shortcutId != null || launchIntentUri != null -> "PC Shortcut"
    romPath == null && packageName != null        -> "Game App"
    else                                          -> "ROM"
}

@Composable
private fun GameInfoList(
    releaseYear: Int?,
    developer: String?,
    publisher: String?,
    genre: String?,
    lastPlayedAt: Long?,
    playTimeMillis: Long,
    emulator: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(10.dp)),
    ) {
        releaseYear?.let           { InfoRow(Icons.Filled.CalendarToday, it.toString()) }
        if (!developer.isNullOrBlank()) InfoRow(Icons.Filled.Person, developer)
        // Publisher shown only when it adds information (often equals the developer).
        if (!publisher.isNullOrBlank() && !publisher.equals(developer, ignoreCase = true)) {
            InfoRow(Icons.Filled.Person, "Published by $publisher")
        }
        if (!genre.isNullOrBlank())     InfoRow(Icons.Filled.SportsEsports, genre)
        lastPlayedAt?.let          { InfoRow(Icons.Filled.Schedule, "Last Played: ${relativeDays(it)}") }
        if (playTimeMillis > 0)         InfoRow(Icons.Filled.Schedule, "Play Time: ${formatPlayTime(playTimeMillis)}")
        emulator?.let              { InfoRow(Icons.Filled.Monitor, "Emulator: $it") }
    }
}

private fun formatPlayTime(millis: Long): String {
    val minutes = millis / 60_000
    return when {
        minutes < 1     -> "Under a minute"
        minutes < 60    -> "$minutes min"
        else            -> "${minutes / 60} h ${minutes % 60} min"
    }
}

@Composable
private fun InfoRow(icon: ImageVector, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(12.dp))
        Text(value, color = TextPrimary, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
private fun DescriptionPanel(description: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("DESCRIPTION", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            description?.takeIf { it.isNotBlank() } ?: "No description available.",
            color = TextMuted,
            fontSize = 14.sp,
            lineHeight = 21.sp,
        )
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
private val PageScrollStep: Dp = 220.dp   // bigger stride — fewer presses to reach the bottom

private fun DetailAction.dynamicLabel(favorite: Boolean, refreshing: Boolean): String = when (this) {
    DetailAction.FAVORITE -> if (favorite) "Unfavorite" else "Favorite"
    DetailAction.REFRESH -> if (refreshing) "Refreshing..." else "Refresh"
    else -> label
}

// ── Artwork Manager Panel ─────────────────────────────────────────────────────
//
// Three-zone controller-navigable UI:
//   TYPE_TABS  — L/R cycles artwork type (Icon / Hero / Background)
//   SOURCE_ROW — L/R scrolls through 5 sources (SGDB / TGDB / IGDB / Local / Clear)
//   ART_GRID   — L/R moves between thumbnails; SELECT picks
//
// Back moves up one zone; pressing Back at TYPE_TABS closes the manager.
// Data sources (SGDB, TGDB, IGDB) populate the art grid.
// Action sources (Local, Clear) fire immediately on SELECT/tap.

private val ArtworkSourceLabels = listOf("ScreenScraper", "SGDB", "TGDB", "IGDB", "Local", "Clear")

@Composable
private fun ArtworkManagerPanel(
    state: GameDetailUiState,
    game: Game,
    onTabSelected: (ArtworkType) -> Unit,
    onActivateSourceAt: (Int) -> Unit,
    onPickArtwork: (String, ArtworkType) -> Unit,
    onClose: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 300.dp, max = 460.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 620.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xF20A0A14))
                .clickable(enabled = false) {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Artwork Manager", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onClose) { Text("Done", color = TextMuted, fontSize = 12.sp) }
            }

            // ── Zone 1: Type tabs ───────────────────────────────────────────
            val tabZoneFocused = state.artworkFocus == ArtworkManagerFocus.TYPE_TABS
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ArtworkType.entries.forEach { type ->
                    val isActive  = state.artworkTab == type
                    val isCursor  = tabZoneFocused && isActive
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isActive) menuCursorFill() else ActionFill)
                            .then(
                                if (isCursor)  Modifier.border(2.dp, menuCursorEdge(), RoundedCornerShape(6.dp))
                                else if (isActive) Modifier.border(1.dp, menuCursorEdge().copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                                else Modifier
                            )
                            .clickable { onTabSelected(type) }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            type.displayLabel,
                            color      = if (isActive) TextPrimary else TextMuted,
                            fontSize   = 12.sp,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines   = 1,
                        )
                    }
                }
            }

            // ── Artwork preview for active tab ──────────────────────────────
            ArtworkPreview(game = game, type = state.artworkTab)

            // ── Zone 2: Source row (scrollable LazyRow, 6 chips) ────────────
            val sourceZoneFocused = state.artworkFocus == ArtworkManagerFocus.SOURCE_ROW
            val sourceLazyState   = rememberLazyListState()
            LaunchedEffect(state.artworkSourceFocus) {
                sourceLazyState.animateScrollToItem(state.artworkSourceFocus)
            }
            LazyRow(
                state                 = sourceLazyState,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(ArtworkSourceLabels) { index, label ->
                    val isFocused     = sourceZoneFocused && state.artworkSourceFocus == index
                    val isDestructive = index == SOURCE_CLEAR
                    Box(
                        modifier = Modifier
                            .width(72.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isFocused) menuCursorFill() else ActionFill)
                            .then(if (isFocused) Modifier.border(1.5.dp, menuCursorEdge(), RoundedCornerShape(6.dp)) else Modifier)
                            .clickable(enabled = !state.artworkIsProcessing) { onActivateSourceAt(index) }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            color      = when {
                                isDestructive -> Color(0xFFFF8A8A)
                                isFocused     -> TextPrimary
                                else          -> TextMuted
                            },
                            fontSize   = 11.sp,
                            fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines   = 1,
                            textAlign  = TextAlign.Center,
                        )
                    }
                }
            }

            // ── Zone 3: Art grid (all data sources share this grid) ─────────
            when {
                state.artworkPickerLoading -> {
                    Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = menuCursorEdge(), strokeWidth = 2.dp)
                    }
                }
                state.artworkPickerError != null -> {
                    Text(state.artworkPickerError, color = Color(0xFFFF6B6B), fontSize = 12.sp)
                }
                state.artworkPickerItems.isNotEmpty() -> {
                    val gridFocused = state.artworkFocus == ArtworkManagerFocus.ART_GRID
                    val lazyState   = rememberLazyListState()
                    LaunchedEffect(state.artworkPickerIndex) {
                        if (state.artworkPickerItems.isNotEmpty())
                            lazyState.animateScrollToItem(state.artworkPickerIndex.coerceAtLeast(0))
                    }
                    LazyRow(
                        state                 = lazyState,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(state.artworkPickerItems) { index, art ->
                            val isFocused = gridFocused && state.artworkPickerIndex == index
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                AsyncImage(
                                    model              = art.thumbUrl ?: art.url,
                                    contentDescription = art.label,
                                    contentScale       = ContentScale.Crop,
                                    modifier           = Modifier
                                        .size(width = 88.dp, height = 60.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .border(
                                            width  = if (isFocused) 2.dp else 1.dp,
                                            color  = if (isFocused) menuCursorEdge() else Color(0x33FFFFFF),
                                            shape  = RoundedCornerShape(4.dp),
                                        )
                                        .clickable { onPickArtwork(art.url, state.artworkTab) },
                                )
                                if (!art.label.isNullOrBlank()) {
                                    Text(
                                        art.label,
                                        color    = if (isFocused) TextPrimary else TextMuted,
                                        fontSize = 9.sp,
                                        maxLines = 1,
                                        textAlign = TextAlign.Center,
                                        modifier  = Modifier.width(88.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Processing / status
            if (state.artworkIsProcessing) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = menuCursorEdge(), strokeWidth = 2.dp)
                    Text("Saving…", color = TextMuted, fontSize = 12.sp)
                }
            }
            state.artworkMessage?.let {
                Text(it, color = menuCursorEdge(), fontSize = 12.sp)
            }

            // Controller hint
            Text(
                "L/R  Navigate  •  Down  Enter zone  •  Up  Back  •  B  Close",
                color    = TextMuted.copy(alpha = 0.5f),
                fontSize = 10.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// Displays the current artwork saved for the active tab, with a context label.
@Composable
private fun ArtworkPreview(game: Game, type: ArtworkType) {
    val uri = when (type) {
        ArtworkType.ICON       -> game.iconUri
        ArtworkType.HERO       -> game.heroUri
        ArtworkType.BACKGROUND -> game.artworkUri
    }
    val contextLabel = when (type) {
        ArtworkType.ICON       -> "XMB game grid  •  PSP Rectangle (144 × 80)"
        ArtworkType.HERO       -> "Game Detail screen  •  Hero banner (16:9)"
        ArtworkType.BACKGROUND -> "XMB hover state  •  Full-bleed background"
    }
    val previewHeight = when (type) {
        ArtworkType.ICON       -> 60.dp
        ArtworkType.HERO       -> 100.dp
        ArtworkType.BACKGROUND -> 72.dp
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(previewHeight)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF12121C))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (uri != null) {
                AsyncImage(
                    model              = uri,
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize(),
                )
            } else {
                Text("No ${type.displayLabel} set", color = TextMuted, fontSize = 12.sp)
            }
        }
        Text(contextLabel, color = TextMuted.copy(alpha = 0.65f), fontSize = 10.sp)
    }
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
    onMove: (Int) -> Unit,
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
            val stepPx = with(LocalDensity.current) { OptionsRowScrollStep.roundToPx() }
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
                                    else       -> ActionFill
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
                            Text("✓", color = PlayGreen, fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp))
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
    val context = LocalContext.current
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
