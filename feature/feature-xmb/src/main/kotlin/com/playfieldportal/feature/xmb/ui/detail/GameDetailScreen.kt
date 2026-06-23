package com.playfieldportal.feature.xmb.ui.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import timber.log.Timber

private val TextPrimary = Color(0xFFEEEEEE)
private val TextMuted = Color(0xAAEEEEEE)
private val AccentBlue = Color(0xFF4A9EFF)
private val PlayGreen = Color(0xFF45C46A)
private val ActionFill = Color(0xFF1B1B26)
private val ActionFocused = Color(0xFF574DDB)
private val ActionBorder = Color(0xFF8F7CFF)
private val PageBg = Color(0xFF06060C)

@Composable
fun GameDetailScreen(
    gameId: Long,
    onBack: () -> Unit,
    pendingGamepadAction: GamepadAction? = null,
    onGamepadActionConsumed: () -> Unit = {},
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
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = AccentBlue)
        }
        return
    }

    val game = state.game ?: return
    val platform = state.platform
    val accentColor = platform?.accentColor?.let { Color(it) } ?: AccentBlue

    Box(modifier = modifier.fillMaxSize().background(PageBg)) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(accentColor.copy(alpha = 0.16f), PageBg))
            )
        )

        // Hero banner — edge-to-edge, no horizontal padding, no rounded corners
        HeroArt(
            uri = game.heroUri,
            title = game.title,
            accentColor = accentColor,
            modifier = Modifier
                .fillMaxWidth()
                .height(HeroBannerHeight)
                .align(Alignment.TopCenter),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 920.dp)
                .align(Alignment.Center)
                .padding(start = 28.dp, end = 28.dp, top = 0.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Spacer reserves the hero banner area so content begins below it
            Spacer(Modifier.height(HeroBannerHeight))

            GameSummary(
                title = game.title,
                platform = platform?.name ?: game.platformId.uppercase(),
                releaseYear = game.releaseYear,
                genre = game.genre,
                emulator = state.emulatorName,
                developer = game.developer,
                publisher = game.publisher,
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ConsoleButton(
                    label = "Play",
                    symbol = ">",
                    focused = state.mainFocus == 0,
                    fill = PlayGreen,
                    textColor = Color(0xFF06140A),
                    onClick = viewModel::onPlayClicked,
                )
                ConsoleButton(
                    label = "Options",
                    symbol = "*",
                    focused = state.mainFocus == 1,
                    fill = ActionFill,
                    textColor = TextPrimary,
                    onClick = viewModel::onOptionsClicked,
                )
                (state.launchError ?: state.actionMessage ?: state.artworkMessage)?.let {
                    Text(
                        it,
                        color = AccentBlue,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Text(
            "<  Back",
            color = TextMuted,
            fontSize = 13.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .clickable { onBack() },
        )

        AnimatedVisibility(state.showOptions, enter = fadeIn(), exit = fadeOut()) {
            OptionsPanel(
                favorite = game.isFavorite,
                refreshing = state.isFetchingArtwork,
                focusedIndex = state.optionsIndex,
                onAction = viewModel::onOptionClicked,
                onClose = viewModel::closeOptions,
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

@Composable
private fun HeroArt(
    uri: String?,
    title: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.background(accentColor.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        if (uri != null) {
            AsyncImage(uri, title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000))))
            )
        } else {
            Text(title, color = TextMuted, fontSize = 20.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun GameSummary(
    title: String,
    platform: String,
    releaseYear: Int?,
    genre: String?,
    emulator: String?,
    developer: String?,
    publisher: String?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1)
        CompactMeta(platform)
        releaseYear?.let { CompactMeta(it.toString()) }
        if (!genre.isNullOrBlank()) CompactMeta(genre)
        CompactMeta("Emulator: ${emulator ?: "Not set"}")
        if (!developer.isNullOrBlank()) CompactMeta("Developer: $developer")
        if (!publisher.isNullOrBlank()) CompactMeta("Publisher: $publisher")
    }
}

@Composable
private fun CompactMeta(value: String) {
    Text(value, color = TextMuted, fontSize = 14.sp, maxLines = 1)
}

@Composable
private fun ConsoleButton(
    label: String,
    symbol: String,
    focused: Boolean,
    fill: Color,
    textColor: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(fill)
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .focusable()
            .padding(vertical = 15.dp, horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(symbol, color = textColor, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(32.dp))
        Text(label, color = textColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun OptionsPanel(
    favorite: Boolean,
    refreshing: Boolean,
    focusedIndex: Int,
    onAction: (DetailAction) -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        val optionsScrollState = rememberScrollState()
        val optionScrollStepPx = with(LocalDensity.current) { OptionsRowScrollStep.roundToPx() }
        LaunchedEffect(focusedIndex) {
            optionsScrollState.animateScrollTo(focusedIndex * optionScrollStepPx)
        }
        Column(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 420.dp)
                .fillMaxWidth(0.86f)
                .heightIn(max = OptionsPanelMaxHeight)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xF20A0A14))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Options", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Column(
                modifier = Modifier.verticalScroll(optionsScrollState),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DetailAction.entries.forEachIndexed { index, action ->
                    ActionButton(
                        icon = action.iconFor(favorite),
                        label = action.dynamicLabel(favorite, refreshing),
                        focused = focusedIndex == index,
                        destructive = action == DetailAction.REMOVE,
                        onClick = { onAction(action) },
                    )
                }
            }
        }
    }
}

private val HeroBannerHeight: Dp = 220.dp
private val OptionsPanelMaxHeight: Dp = 440.dp
private val OptionsRowScrollStep: Dp = 58.dp

@Composable
private fun ActionButton(
    icon: String,
    label: String,
    focused: Boolean,
    destructive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) ActionFocused.copy(alpha = 0.5f) else ActionFill)
            .then(if (focused) Modifier.border(1.5.dp, ActionBorder, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 12.dp),
    ) {
        Text(icon, fontSize = 18.sp, modifier = Modifier.width(30.dp))
        Text(
            label,
            color = if (destructive) Color(0xFFFF8A8A) else TextPrimary,
            fontSize = 14.sp,
            maxLines = 1,
        )
    }
}

private fun DetailAction.iconFor(favorite: Boolean): String = when (this) {
    DetailAction.FAVORITE -> if (favorite) "*" else "+"
    DetailAction.ARTWORK -> "*"
    DetailAction.SAVES -> "S"
    DetailAction.EMULATOR -> "E"
    DetailAction.MANUAL -> "M"
    DetailAction.REFRESH -> "R"
    DetailAction.RENAME -> "T"
    DetailAction.EDIT -> "N"
    DetailAction.LOCATION -> "L"
    DetailAction.REMOVE -> "!"
}

private fun DetailAction.dynamicLabel(favorite: Boolean, refreshing: Boolean): String = when (this) {
    DetailAction.FAVORITE -> if (favorite) "Unfavorite" else "Favorite"
    DetailAction.REFRESH -> if (refreshing) "Refreshing..." else "Refresh"
    else -> label
}

// ── Artwork Manager Panel ─────────────────────────────────────────────────────
//
// Three-zone controller-navigable UI:
//   TYPE_TABS  — L/R cycles artwork type (Icon / Hero / Background)
//   SOURCE_ROW — L/R scrolls through 6 sources (SGDB / Scraper / IGDB / TGDB / Local / Clear)
//   ART_GRID   — L/R moves between thumbnails; SELECT picks
//
// Back moves up one zone; pressing Back at TYPE_TABS closes the manager.
// Data sources (SGDB, Scraper, IGDB, TGDB) populate the art grid.
// Action sources (Local, Clear) fire immediately on SELECT/tap.

private val ArtworkSourceLabels = listOf("SGDB", "Scraper", "IGDB", "TGDB", "Local", "Clear")

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
                            .background(if (isActive) ActionFocused.copy(alpha = 0.4f) else ActionFill)
                            .then(
                                if (isCursor)  Modifier.border(2.dp, ActionBorder, RoundedCornerShape(6.dp))
                                else if (isActive) Modifier.border(1.dp, ActionBorder.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
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
                            .background(if (isFocused) ActionFocused.copy(alpha = 0.4f) else ActionFill)
                            .then(if (isFocused) Modifier.border(1.5.dp, ActionBorder, RoundedCornerShape(6.dp)) else Modifier)
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
                        CircularProgressIndicator(Modifier.size(24.dp), color = AccentBlue, strokeWidth = 2.dp)
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
                                            color  = if (isFocused) ActionBorder else Color(0x33FFFFFF),
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
                    CircularProgressIndicator(Modifier.size(14.dp), color = AccentBlue, strokeWidth = 2.dp)
                    Text("Saving…", color = TextMuted, fontSize = 12.sp)
                }
            }
            state.artworkMessage?.let {
                Text(it, color = AccentBlue, fontSize = 12.sp)
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
                focusedBorderColor = AccentBlue,
                unfocusedBorderColor = Color(0x44FFFFFF),
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = AccentBlue,
            ),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave() }),
            maxLines = 4,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("Cancel", color = TextMuted) }
            TextButton(onClick = onSave) { Text("Save", color = AccentBlue, fontWeight = FontWeight.SemiBold) }
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
                focusedBorderColor = AccentBlue,
                unfocusedBorderColor = Color(0x44FFFFFF),
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = AccentBlue,
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
                TextButton(onClick = onSave) { Text("Save", color = AccentBlue, fontWeight = FontWeight.SemiBold) }
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
                                    isFocused  -> ActionFocused.copy(alpha = 0.5f)
                                    isSelected -> ActionFocused.copy(alpha = 0.25f)
                                    else       -> ActionFill
                                }
                            )
                            .then(
                                if (isFocused) Modifier.border(1.5.dp, ActionBorder, RoundedCornerShape(8.dp))
                                else if (isSelected) Modifier.border(1.dp, ActionBorder.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
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
