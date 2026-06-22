package com.playfieldportal.feature.xmb.ui.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.feature.artwork.api.SgdbArtItem

private val TextPrimary = Color(0xFFEEEEEE)
private val TextMuted = Color(0xAAEEEEEE)
private val AccentBlue = Color(0xFF4A9EFF)
private val PlayGreen = Color(0xFF45C46A)
private val ActionFill = Color(0xFF1B1B26)
private val ActionFocused = Color(0xFF574DDB)
private val ActionBorder = Color(0xFF8F7CFF)
private val PageBg = Color(0xFF06060C)

// Page section item indices in the LazyColumn (used for focus auto-scroll).
private const val ITEM_ACTIONS = 3
private const val ITEM_SCREENSHOTS = 6

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
        if (uri != null && type != null) viewModel.onCustomArtworkPicked(uri, type)
        pendingArtworkType = null
    }

    LaunchedEffect(gameId) { viewModel.loadGame(gameId) }
    LaunchedEffect(Unit) { viewModel.launchEffect.collect { intent -> context.startActivity(intent) } }
    LaunchedEffect(state.closed) { if (state.closed) onBack() }
    LaunchedEffect(pendingGamepadAction) {
        if (pendingGamepadAction != null) {
            viewModel.handleGamepadAction(pendingGamepadAction)
            onGamepadActionConsumed()
        }
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

    val listState = rememberLazyListState()
    // Auto-scroll the focused section into view. Play maps to the top so opening the page
    // never jumps; only an explicit move to Actions/Screenshots scrolls.
    LaunchedEffect(state.focusSection) {
        val target = when (state.focusSection) {
            DetailSection.PLAY -> 0
            DetailSection.ACTIONS -> ITEM_ACTIONS
            DetailSection.SCREENSHOTS -> ITEM_SCREENSHOTS
        }
        listState.animateScrollToItem(target)
    }

    Box(modifier = modifier.fillMaxSize().background(PageBg)) {
        // Static accent-tinted backdrop for depth. (Avoids a runtime blur over the async hero,
        // which flickers as the image loads.)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(accentColor.copy(alpha = 0.16f), PageBg))
            )
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 920.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 0 — Header
            item {
                Spacer(Modifier.height(28.dp))
                Text(game.title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = TextPrimary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                val meta = buildString {
                    append(platform?.name ?: game.platformId.uppercase())
                    game.releaseYear?.let { append("  •  $it") }
                }
                Text(meta, fontSize = 14.sp, color = TextMuted, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
            }

            // 1 — Hero art
            item {
                val heroUri = game.heroUri ?: game.artworkUri
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(accentColor.copy(alpha = 0.18f))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (heroUri != null) {
                        AsyncImage(heroUri, game.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Text(game.title, color = TextMuted, fontSize = 18.sp)
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            // 2 — Play (primary)
            item {
                PlayButton(
                    focused = state.focusSection == DetailSection.PLAY,
                    onClick = { viewModel.focusPlay(); viewModel.launch() },
                )
                state.launchError?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Color(0xFFFF6B6B), fontSize = 12.sp)
                }
                Spacer(Modifier.height(16.dp))
            }

            // 3 — Action buttons grid
            item {
                ActionGrid(
                    favorite = game.isFavorite,
                    refreshing = state.isFetchingArtwork,
                    focusedSection = state.focusSection == DetailSection.ACTIONS,
                    focusedIndex = state.actionIndex,
                    onAction = viewModel::onActionClicked,
                )
                state.actionMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = AccentBlue, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                state.artworkMessage?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = AccentBlue, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(20.dp))
            }

            // 4 — Metadata
            item {
                MetadataBlock(
                    lastPlayed = game.lastPlayedAt.formatLastPlayed(),
                    playTime = game.totalPlayTimeMillis.formatPlayTime(),
                    emulator = state.emulatorName,
                )
                Spacer(Modifier.height(16.dp))
            }

            // 5 — Description
            item {
                if (!game.description.isNullOrBlank() || !game.userNote.isNullOrBlank()) {
                    SectionHeader("Description")
                    game.description?.let { Text(it, color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp) }
                    game.userNote?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("\"$it\"", color = TextMuted, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }

            // 6 — Screenshots / media
            item {
                if (state.mediaUris.isNotEmpty()) {
                    SectionHeader("Screenshots")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(state.mediaUris) { index, uri ->
                            val focused = state.focusSection == DetailSection.SCREENSHOTS && state.screenshotIndex == index
                            AsyncImage(
                                model = uri,
                                contentDescription = "Screenshot ${index + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(width = 220.dp, height = 124.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(
                                        width = if (focused) 2.dp else 1.dp,
                                        color = if (focused) ActionBorder else Color(0x33FFFFFF),
                                        shape = RoundedCornerShape(8.dp),
                                    )
                                    .clickable { viewModel.openMediaViewer(index) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(36.dp))
            }
        }

        // Back affordance
        Text(
            "◀  Back",
            color = TextMuted,
            fontSize = 13.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .clickable { onBack() },
        )

        // ── Overlays ───────────────────────────────────────────────────────────
        AnimatedVisibility(state.isEditingNote, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                NoteEditor(state.noteText, viewModel::onNoteChanged, viewModel::saveNote, viewModel::cancelNote)
            }
        }

        AnimatedVisibility(state.showCustomArtwork, enter = fadeIn(), exit = fadeOut()) {
            CustomArtworkPanel(
                state = state,
                onPickFile = { type -> pendingArtworkType = type; artworkPicker.launch(arrayOf("image/*")) },
                onBrowseSgdb = viewModel::openSgdbPicker,
                onPickSgdb = viewModel::pickSgdbArtwork,
                onCloseSgdb = viewModel::closeSgdbPicker,
                onClear = viewModel::clearArtwork,
                onClose = viewModel::toggleCustomArtworkPanel,
            )
        }

        if (state.showMediaViewer) {
            MediaViewer(
                uri = state.mediaUris.getOrNull(state.mediaViewerIndex),
                onClose = viewModel::closeMediaViewer,
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

// ── Play button ─────────────────────────────────────────────────────────────────

@Composable
private fun PlayButton(focused: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) PlayGreen else PlayGreen.copy(alpha = 0.85f))
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("▶  Play", color = Color(0xFF06140A), fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

// ── Action grid ─────────────────────────────────────────────────────────────────

@Composable
private fun ActionGrid(
    favorite: Boolean,
    refreshing: Boolean,
    focusedSection: Boolean,
    focusedIndex: Int,
    onAction: (DetailAction) -> Unit,
) {
    val rows = DetailAction.entries.chunked(3)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { action ->
                    val index = DetailAction.entries.indexOf(action)
                    ActionButton(
                        icon = action.iconFor(favorite),
                        label = action.dynamicLabel(favorite, refreshing),
                        focused = focusedSection && focusedIndex == index,
                        destructive = action == DetailAction.REMOVE,
                        onClick = { onAction(action) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Pad short final row so widths stay aligned.
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ActionButton(
    icon: String,
    label: String,
    focused: Boolean,
    destructive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) ActionFocused.copy(alpha = 0.5f) else ActionFill)
            .then(if (focused) Modifier.border(1.5.dp, ActionBorder, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 6.dp),
    ) {
        Text(icon, fontSize = 18.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = if (destructive) Color(0xFFFF8A8A) else TextPrimary,
            fontSize = 12.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

private fun DetailAction.iconFor(favorite: Boolean): String = when (this) {
    DetailAction.FAVORITE -> if (favorite) "★" else "☆"
    DetailAction.SETTINGS -> "⚙"
    DetailAction.SAVES -> "💾"
    DetailAction.EMULATOR -> "🎮"
    DetailAction.MANUAL -> "📖"
    DetailAction.REFRESH -> "🔄"
    DetailAction.EDIT -> "✎"
    DetailAction.LOCATION -> "📁"
    DetailAction.REMOVE -> "🗑"
}

private fun DetailAction.dynamicLabel(favorite: Boolean, refreshing: Boolean): String = when (this) {
    DetailAction.FAVORITE -> if (favorite) "Unfavorite" else "Favorite"
    DetailAction.REFRESH -> if (refreshing) "Refreshing…" else "Refresh"
    else -> label
}

// ── Metadata ─────────────────────────────────────────────────────────────────────

@Composable
private fun MetadataBlock(lastPlayed: String, playTime: String, emulator: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x22FFFFFF))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MetaRow("Last Played", lastPlayed)
        MetaRow("Play Time", playTime)
        MetaRow("Emulator", emulator ?: "Not set")
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = TextMuted, fontSize = 13.sp, modifier = Modifier.width(120.dp))
        Text(value, color = TextPrimary, fontSize = 13.sp)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        color = AccentBlue,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
}

// ── Media viewer ─────────────────────────────────────────────────────────────────

@Composable
private fun MediaViewer(uri: String?, onClose: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xF2000000)).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        if (uri != null) {
            AsyncImage(uri, "Media", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(24.dp))
        }
        Text("◀  Back", color = TextMuted, fontSize = 13.sp, modifier = Modifier.align(Alignment.TopStart).padding(16.dp))
    }
}

// ── Custom artwork panel (migrated from the old options menu) ─────────────────────

@Composable
private fun CustomArtworkPanel(
    state: GameDetailUiState,
    onPickFile: (ArtworkType) -> Unit,
    onBrowseSgdb: (ArtworkType) -> Unit,
    onPickSgdb: (String, ArtworkType) -> Unit,
    onCloseSgdb: () -> Unit,
    onClear: (ArtworkType) -> Unit,
    onClose: () -> Unit,
) {
    val game = state.game ?: return
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(min = 300.dp, max = 420.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xF20A0A14))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Game Settings — Artwork", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            listOf(
                Triple(ArtworkType.BOX_ART, "Box Art", game.artworkUri != null),
                Triple(ArtworkType.HERO, "Hero", game.heroUri != null),
                Triple(ArtworkType.LOGO, "Logo", game.logoUri != null),
            ).forEach { (type, label, hasImage) ->
                ArtworkSlotRow(
                    label = label,
                    hasImage = hasImage,
                    isWorking = state.isProcessingCustomArtwork,
                    onPick = { onPickFile(type) },
                    onBrowseSgdb = { onBrowseSgdb(type) },
                    onClear = { onClear(type) },
                )
                if (state.sgdbBrowsingType == type) {
                    SgdbBrowserRow(state.sgdbIsLoading, state.sgdbError, state.sgdbItems, { onPickSgdb(it, type) }, onCloseSgdb)
                }
            }
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("Done", color = AccentBlue) }
        }
    }
}

@Composable
private fun ArtworkSlotRow(
    label: String,
    hasImage: Boolean,
    isWorking: Boolean,
    onPick: () -> Unit,
    onBrowseSgdb: () -> Unit,
    onClear: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = if (hasImage) TextPrimary else TextMuted, modifier = Modifier.weight(1f))
        TextButton(onClick = onPick, enabled = !isWorking) { Text("File", fontSize = 11.sp, color = AccentBlue) }
        TextButton(onClick = onBrowseSgdb, enabled = !isWorking) { Text("SGDB", fontSize = 11.sp, color = Color(0xFF44BBFF)) }
        if (hasImage) {
            TextButton(onClick = onClear, enabled = !isWorking) { Text("✕", fontSize = 11.sp, color = TextMuted) }
        }
    }
}

@Composable
private fun SgdbBrowserRow(
    isLoading: Boolean,
    error: String?,
    items: List<SgdbArtItem>,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Color(0x22FFFFFF)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("SteamGridDB", fontSize = 11.sp, color = Color(0xFF44BBFF), modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("✕", fontSize = 11.sp, color = TextMuted) }
        }
        when {
            isLoading -> Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), color = AccentBlue, strokeWidth = 2.dp)
            }
            error != null -> Text(error, fontSize = 11.sp, color = Color(0xFFFF6B6B))
            items.isEmpty() -> Text("No artwork found", fontSize = 11.sp, color = TextMuted)
            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(items) { _, art ->
                    AsyncImage(
                        model = art.thumb ?: art.url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 72.dp, height = 58.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(3.dp))
                            .clickable { onPick(art.url) },
                    )
                }
            }
        }
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
        Text("Edit Metadata — Note", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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

// ── Formatters ────────────────────────────────────────────────────────────────────

private fun Long.formatPlayTime(): String = when {
    this < 60_000L -> "Never"
    this < 3_600_000L -> "${this / 60_000} min"
    else -> {
        val h = this / 3_600_000L
        val m = (this % 3_600_000L) / 60_000L
        if (m > 0) "${h}h ${m}m" else "${h}h"
    }
}

private fun Long?.formatLastPlayed(): String {
    if (this == null || this <= 0L) return "Never"
    val diff = System.currentTimeMillis() - this
    val days = diff / 86_400_000L
    val hours = diff / 3_600_000L
    val mins = diff / 60_000L
    return when {
        days > 0 -> "$days day${if (days == 1L) "" else "s"} ago"
        hours > 0 -> "$hours hour${if (hours == 1L) "" else "s"} ago"
        mins > 0 -> "$mins min ago"
        else -> "Just now"
    }
}
