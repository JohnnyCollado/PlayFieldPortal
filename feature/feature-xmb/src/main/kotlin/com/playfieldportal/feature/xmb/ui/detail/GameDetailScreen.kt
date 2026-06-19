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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.playfieldportal.feature.artwork.api.SgdbArtItem
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.playfieldportal.core.domain.model.GamepadAction

private val TextPrimary  = Color(0xFFEEEEEE)
private val TextMuted    = Color(0xAAEEEEEE)
private val AccentBlue   = Color(0xFF4A9EFF)
private val MenuSelected = Color(0x55000000)  // PSP-style semi-dark highlight
private val MenuHoverBorder = Color(0x88FFFFFF)

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

    LaunchedEffect(Unit) {
        viewModel.launchEffect.collect { intent -> context.startActivity(intent) }
    }

    LaunchedEffect(pendingGamepadAction) {
        if (pendingGamepadAction != null) {
            viewModel.handleGamepadAction(pendingGamepadAction)
            onGamepadActionConsumed()
        }
    }

    if (state.isLoading) {
        Box(modifier.fillMaxSize()) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = AccentBlue)
        }
        return
    }

    val game = state.game ?: return
    val platform = state.platform
    val accentColor = platform?.accentColor?.let { Color(it) } ?: AccentBlue

    val menuLabels = listOf(
        "Start",
        if (game.isFavorite) "Remove Favorite" else "Add to Favorites",
        if (state.isFetchingArtwork) "Fetching Artwork…" else "Fetch Artwork",
        "Edit Note",
        "Custom Artwork",
        "Information",
    )

    Box(modifier = modifier.fillMaxSize()) {

        // ── Full-screen background artwork ────────────────────────────────
        val bgUri = game.heroUri ?: game.artworkUri
        if (bgUri != null) {
            AsyncImage(
                model              = bgUri,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(accentColor.copy(alpha = 0.35f), Color(0xFF050510))
                        )
                    )
            )
        }

        // ── Dark gradient overlay (heavy on right, lighter on left) ───────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0x55000000),
                            0.45f to Color(0x99000000),
                            1.0f to Color(0xEE000000),
                        )
                    )
                )
        )

        // ── Bottom gradient (title area readability) ───────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.45f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))
                )
        )

        // ── Left: box art + title block ───────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 48.dp, bottom = 48.dp)
                .widthIn(max = 340.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            // Box art (UMD-proportioned rectangle)
            if (game.artworkUri != null) {
                AsyncImage(
                    model              = game.artworkUri,
                    contentDescription = "Box art",
                    contentScale       = ContentScale.Fit,
                    modifier           = Modifier
                        .size(width = 88.dp, height = 124.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(4.dp)),
                )
                Spacer(Modifier.height(12.dp))
            }

            Text(
                text       = game.title,
                fontSize   = 26.sp,
                fontWeight = FontWeight.Bold,
                color      = TextPrimary,
            )
            Spacer(Modifier.height(4.dp))

            val metaLine = buildString {
                append(platform?.name ?: game.platformId.uppercase())
                game.releaseYear?.let { append("  ·  $it") }
            }
            Text(text = metaLine, fontSize = 13.sp, color = TextMuted)

            // Play-time stat
            val playTime = game.totalPlayTimeMillis.formatPlayTime()
            if (playTime.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(text = "Played: $playTime", fontSize = 12.sp, color = TextMuted)
            }

            // Information panel (description + note)
            AnimatedVisibility(visible = state.showInformation) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    game.description?.let { desc ->
                        Text(
                            text       = desc,
                            fontSize   = 12.sp,
                            color      = TextMuted,
                            lineHeight = 18.sp,
                            maxLines   = 4,
                        )
                    }
                    game.userNote?.let { note ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text      = "\"$note\"",
                            fontSize  = 12.sp,
                            fontStyle = FontStyle.Italic,
                            color     = TextMuted,
                        )
                    }
                }
            }

            // Artwork / launch feedback
            val feedbackMsg = state.artworkMessage ?: state.launchError
            if (feedbackMsg != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text     = feedbackMsg,
                    fontSize = 12.sp,
                    color    = if (state.launchError != null) Color(0xFFFF6B6B) else AccentBlue,
                )
            }

            // Note editor inline
            AnimatedVisibility(visible = state.isEditingNote, enter = fadeIn(), exit = fadeOut()) {
                NoteEditor(
                    text     = state.noteText,
                    onChange = viewModel::onNoteChanged,
                    onSave   = viewModel::saveNote,
                    onCancel = viewModel::cancelNote,
                )
            }
        }

        // ── Right: PSP-style action menu ─────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 72.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            menuLabels.forEachIndexed { index, label ->
                PspMenuItem(
                    text       = label,
                    isSelected = index == state.selectedMenuIndex,
                    onClick    = {
                        viewModel.selectMenuItem(index)
                        viewModel.activateSelectedMenuItem()
                    },
                )
            }
        }

        // ── Custom artwork panel overlay ──────────────────────────────────
        AnimatedVisibility(
            visible = state.showCustomArtwork,
            enter   = fadeIn(),
            exit    = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 72.dp, top = 160.dp),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 340.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xDD0A0A12))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(
                    Triple(ArtworkType.BOX_ART, "Box Art", game.artworkUri != null),
                    Triple(ArtworkType.HERO,    "Hero",    game.heroUri    != null),
                    Triple(ArtworkType.LOGO,    "Logo",    game.logoUri    != null),
                ).forEach { (type, label, hasImage) ->
                    ArtworkSlotRow(
                        label        = label,
                        hasImage     = hasImage,
                        isWorking    = state.isProcessingCustomArtwork,
                        onPick       = { pendingArtworkType = type; artworkPicker.launch(arrayOf("image/*")) },
                        onBrowseSgdb = { viewModel.openSgdbPicker(type) },
                        onClear      = { viewModel.clearArtwork(type) },
                    )
                    // SGDB browser expands inline below the active slot row
                    if (state.sgdbBrowsingType == type) {
                        SgdbBrowserRow(
                            isLoading = state.sgdbIsLoading,
                            error     = state.sgdbError,
                            items     = state.sgdbItems,
                            onPick    = { url -> viewModel.pickSgdbArtwork(url, type) },
                            onClose   = viewModel::closeSgdbPicker,
                        )
                    }
                }
            }
        }
    }
}

// ── PSP-style menu item ───────────────────────────────────────────────────────

@Composable
private fun PspMenuItem(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(260.dp)
            .clip(RoundedCornerShape(3.dp))
            .then(
                if (isSelected)
                    Modifier
                        .background(MenuSelected)
                        .border(1.dp, MenuHoverBorder, RoundedCornerShape(3.dp))
                else Modifier
            )
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(
            text       = text,
            fontSize   = 15.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color      = if (isSelected) TextPrimary else TextMuted,
        )
    }
}

// ── Artwork slot row ──────────────────────────────────────────────────────────

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
        if (hasImage) {
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(Color(0xFF44CC88)))
            Spacer(Modifier.width(4.dp))
        }
        TextButton(onClick = onPick, enabled = !isWorking) {
            Text(if (hasImage) "File" else "File", fontSize = 11.sp, color = AccentBlue)
        }
        TextButton(onClick = onBrowseSgdb, enabled = !isWorking) {
            Text("SGDB", fontSize = 11.sp, color = Color(0xFF44BBFF))
        }
        if (hasImage) {
            TextButton(onClick = onClear, enabled = !isWorking) {
                Text("✕", fontSize = 11.sp, color = TextMuted)
            }
        }
    }
}

// ── SteamGridDB inline browser ────────────────────────────────────────────────

@Composable
private fun SgdbBrowserRow(
    isLoading: Boolean,
    error: String?,
    items: List<SgdbArtItem>,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0x22FFFFFF))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("SteamGridDB", fontSize = 11.sp, color = Color(0xFF44BBFF), modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) {
                Text("✕", fontSize = 11.sp, color = TextMuted)
            }
        }
        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = AccentBlue, strokeWidth = 2.dp)
                }
            }
            error != null -> {
                Text(error, fontSize = 11.sp, color = Color(0xFFFF6B6B))
            }
            items.isEmpty() -> {
                Text("No artwork found", fontSize = 11.sp, color = TextMuted)
            }
            else -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(items) { art ->
                        AsyncImage(
                            model              = art.thumb ?: art.url,
                            contentDescription = null,
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier
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
}

// ── Note editor ───────────────────────────────────────────────────────────────

@Composable
private fun NoteEditor(
    text: String,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(modifier = Modifier.padding(top = 10.dp).widthIn(max = 300.dp)) {
        OutlinedTextField(
            value         = text,
            onValueChange = onChange,
            label         = { Text("Note", color = TextMuted) },
            modifier      = Modifier.fillMaxWidth(),
            colors        = OutlinedTextFieldDefaults.colors(
                focusedBorderColor    = AccentBlue,
                unfocusedBorderColor  = Color(0x44FFFFFF),
                focusedTextColor      = TextPrimary,
                unfocusedTextColor    = TextPrimary,
                cursorColor           = AccentBlue,
            ),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction      = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSave() }),
            maxLines        = 4,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("Cancel", color = TextMuted) }
            TextButton(onClick = onSave)   { Text("Save",   color = AccentBlue, fontWeight = FontWeight.SemiBold) }
        }
    }
}

// ── Formatters ────────────────────────────────────────────────────────────────

private fun Long.formatPlayTime(): String = when {
    this < 60_000L    -> ""
    this < 3_600_000L -> "${this / 60_000} min"
    else -> {
        val h = this / 3_600_000L
        val m = (this % 3_600_000L) / 60_000L
        if (m > 0) "${h}h ${m}m" else "${h}h"
    }
}
