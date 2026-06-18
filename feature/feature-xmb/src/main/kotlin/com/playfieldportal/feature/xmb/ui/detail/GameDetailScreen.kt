package com.playfieldportal.feature.xmb.ui.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val OverlayBg   = Color(0xF0080808)
private val SurfaceBg   = Color(0x22FFFFFF)
private val TextPrimary = Color(0xFFEEEEEE)
private val TextMuted   = Color(0xFF888888)
private val AccentBlue  = Color(0xFF4A9EFF)

@Composable
fun GameDetailScreen(
    gameId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GameDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Track which artwork slot the picker was opened for
    var pendingArtworkType by remember { mutableStateOf<ArtworkType?>(null) }

    val artworkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val type = pendingArtworkType
        if (uri != null && type != null) viewModel.onCustomArtworkPicked(uri, type)
        pendingArtworkType = null
    }

    fun pickArtwork(type: ArtworkType) {
        pendingArtworkType = type
        artworkPicker.launch(arrayOf("image/*"))
    }

    // Load game when screen opens or gameId changes
    LaunchedEffect(gameId) { viewModel.loadGame(gameId) }

    // Collect one-shot launch intents
    LaunchedEffect(Unit) {
        viewModel.launchEffect.collect { intent ->
            context.startActivity(intent)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OverlayBg),
    ) {
        if (state.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color    = AccentBlue,
            )
        } else {
            val game     = state.game
            val platform = state.platform

            if (game == null) {
                Text(
                    text     = "Game not found",
                    color    = TextMuted,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    // ── Hero banner ───────────────────────────────────────────
                    HeroBanner(
                        heroUri      = game.heroUri,
                        artworkUri   = game.artworkUri,
                        logoUri      = game.logoUri,
                        accentColor  = platform?.accentColor?.let { Color(it) } ?: AccentBlue,
                    )

                    // ── Title + metadata ──────────────────────────────────────
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {

                        Text(
                            text       = game.title,
                            fontSize   = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color      = TextPrimary,
                        )

                        Spacer(Modifier.height(4.dp))

                        val platformLine = buildString {
                            append(platform?.name ?: game.platformId.uppercase())
                            game.releaseYear?.let { append("  ·  $it") }
                            game.genre?.let { append("  ·  $it") }
                        }
                        Text(text = platformLine, fontSize = 13.sp, color = TextMuted)

                        if (game.developer != null || game.publisher != null) {
                            Spacer(Modifier.height(2.dp))
                            val devLine = listOfNotNull(game.developer, game.publisher)
                                .joinToString("  /  ")
                            Text(text = devLine, fontSize = 12.sp, color = TextMuted)
                        }

                        Spacer(Modifier.height(16.dp))
                        Divider(color = Color(0x33FFFFFF))
                        Spacer(Modifier.height(12.dp))

                        // ── Stats row ─────────────────────────────────────────
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            StatChip(
                                label = "Play time",
                                value = game.totalPlayTimeMillis.formatPlayTime(),
                            )
                            StatChip(
                                label = "Last played",
                                value = game.lastPlayedAt?.formatLastPlayed() ?: "Never",
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                        Divider(color = Color(0x33FFFFFF))
                        Spacer(Modifier.height(16.dp))

                        // ── Action row ────────────────────────────────────────
                        ActionRow(
                            isFavorite             = game.isFavorite,
                            isFetchingArtwork      = state.isFetchingArtwork,
                            showCustomArtwork      = state.showCustomArtwork,
                            isProcessingCustomArtwork = state.isProcessingCustomArtwork,
                            onLaunch               = viewModel::launch,
                            onToggleFavorite       = viewModel::toggleFavorite,
                            onFetchArtwork         = viewModel::fetchArtwork,
                            onEditNote             = viewModel::startEditNote,
                            onToggleCustomArtwork  = viewModel::toggleCustomArtworkPanel,
                        )

                        // ── Custom artwork panel ──────────────────────────────
                        AnimatedVisibility(visible = state.showCustomArtwork) {
                            CustomArtworkPanel(
                                hasBoxArt = game.artworkUri != null,
                                hasHero   = game.heroUri != null,
                                hasLogo   = game.logoUri != null,
                                isWorking = state.isProcessingCustomArtwork,
                                onPickBoxArt  = { pickArtwork(ArtworkType.BOX_ART) },
                                onPickHero    = { pickArtwork(ArtworkType.HERO) },
                                onPickLogo    = { pickArtwork(ArtworkType.LOGO) },
                                onClearBoxArt = { viewModel.clearArtwork(ArtworkType.BOX_ART) },
                                onClearHero   = { viewModel.clearArtwork(ArtworkType.HERO) },
                                onClearLogo   = { viewModel.clearArtwork(ArtworkType.LOGO) },
                            )
                        }

                        // ── Feedback messages ─────────────────────────────────
                        state.artworkMessage?.let { msg ->
                            Spacer(Modifier.height(8.dp))
                            FeedbackBanner(msg, isError = !msg.contains("updated") && !msg.contains("already", ignoreCase = true)) {
                                viewModel.dismissArtworkMessage()
                            }
                        }

                        state.launchError?.let { err ->
                            Spacer(Modifier.height(8.dp))
                            FeedbackBanner(err, isError = true) { viewModel.dismissLaunchError() }
                        }

                        // ── Description ───────────────────────────────────────
                        game.description?.let { desc ->
                            Spacer(Modifier.height(16.dp))
                            Divider(color = Color(0x33FFFFFF))
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text      = desc,
                                fontSize  = 13.sp,
                                color     = TextMuted,
                                lineHeight = 20.sp,
                            )
                        }

                        // ── Note section ──────────────────────────────────────
                        AnimatedVisibility(visible = state.isEditingNote) {
                            NoteEditor(
                                text      = state.noteText,
                                onChange  = viewModel::onNoteChanged,
                                onSave    = viewModel::saveNote,
                                onCancel  = viewModel::cancelNote,
                            )
                        }

                        if (!state.isEditingNote && game.userNote != null) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text      = "\"${game.userNote}\"",
                                fontSize  = 13.sp,
                                fontStyle = FontStyle.Italic,
                                color     = TextMuted,
                            )
                        }

                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }

        // ── Back button ───────────────────────────────────────────────────
        IconButton(
            onClick  = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
        ) {
            Icon(
                imageVector        = Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint               = TextPrimary,
            )
        }
    }
}

// ── Hero banner ───────────────────────────────────────────────────────────────

@Composable
private fun HeroBanner(
    heroUri: String?,
    artworkUri: String?,
    logoUri: String?,
    accentColor: Color,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp),
    ) {
        // Hero background image
        if (heroUri != null) {
            AsyncImage(
                model              = heroUri,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
        } else {
            // Fallback gradient when no hero art
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(accentColor.copy(alpha = 0.4f), Color.Black)
                        )
                    )
            )
        }

        // Bottom gradient — fades hero into the content below
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, OverlayBg)
                    )
                )
        )

        // Platform accent color strip
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .align(Alignment.BottomCenter)
                .background(accentColor),
        )

        // Grid art (box art thumbnail — top-right)
        if (artworkUri != null) {
            AsyncImage(
                model              = artworkUri,
                contentDescription = "Box art",
                contentScale       = ContentScale.Fit,
                modifier           = Modifier
                    .size(width = 80.dp, height = 106.dp)
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(4.dp)),
            )
        }

        // Logo (transparent PNG — bottom-left)
        if (logoUri != null) {
            AsyncImage(
                model              = logoUri,
                contentDescription = "Game logo",
                contentScale       = ContentScale.Fit,
                modifier           = Modifier
                    .widthIn(max = 200.dp)
                    .height(60.dp)
                    .align(Alignment.BottomStart)
                    .padding(start = 20.dp, bottom = 12.dp),
            )
        }
    }
}

// ── Action row ────────────────────────────────────────────────────────────────

@Composable
private fun ActionRow(
    isFavorite: Boolean,
    isFetchingArtwork: Boolean,
    showCustomArtwork: Boolean,
    isProcessingCustomArtwork: Boolean,
    onLaunch: () -> Unit,
    onToggleFavorite: () -> Unit,
    onFetchArtwork: () -> Unit,
    onEditNote: () -> Unit,
    onToggleCustomArtwork: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onLaunch,
            colors  = ButtonDefaults.buttonColors(containerColor = AccentBlue),
            shape   = RoundedCornerShape(6.dp),
        ) {
            Icon(
                imageVector        = Icons.Default.PlayArrow,
                contentDescription = null,
                modifier           = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Launch", fontWeight = FontWeight.SemiBold)
        }

        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector        = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                tint               = if (isFavorite) Color(0xFFFF4D6D) else TextMuted,
            )
        }

        IconButton(onClick = onFetchArtwork, enabled = !isFetchingArtwork) {
            if (isFetchingArtwork) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AccentBlue, strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector        = Icons.Default.Image,
                    contentDescription = "Fetch artwork from SteamGridDB",
                    tint               = TextMuted,
                )
            }
        }

        // Custom artwork — palette icon, tinted blue when panel is open
        IconButton(onClick = onToggleCustomArtwork, enabled = !isProcessingCustomArtwork) {
            if (isProcessingCustomArtwork) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AccentBlue, strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector        = Icons.Default.Palette,
                    contentDescription = "Custom artwork",
                    tint               = if (showCustomArtwork) AccentBlue else TextMuted,
                )
            }
        }

        IconButton(onClick = onEditNote) {
            Icon(
                imageVector        = Icons.Default.Edit,
                contentDescription = "Edit note",
                tint               = TextMuted,
            )
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
    Column(modifier = Modifier.padding(top = 12.dp)) {
        OutlinedTextField(
            value         = text,
            onValueChange = onChange,
            label         = { Text("Note", color = TextMuted) },
            modifier      = Modifier.fillMaxWidth(),
            colors        = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = AccentBlue,
                unfocusedBorderColor = Color(0x44FFFFFF),
                focusedTextColor     = TextPrimary,
                unfocusedTextColor   = TextPrimary,
                cursorColor          = AccentBlue,
            ),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction      = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSave() }),
            maxLines        = 4,
        )
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) {
                Text("Cancel", color = TextMuted)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onSave) {
                Text("Save", color = AccentBlue, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ── Stat chip ─────────────────────────────────────────────────────────────────

@Composable
private fun StatChip(label: String, value: String) {
    Column {
        Text(text = label, fontSize = 10.sp, color = TextMuted)
        Text(text = value, fontSize = 14.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
    }
}

// ── Feedback banner ───────────────────────────────────────────────────────────

@Composable
private fun FeedbackBanner(message: String, isError: Boolean, onDismiss: () -> Unit) {
    val bg = if (isError) Color(0x33FF4D4D) else Color(0x3344CC88)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text     = message,
            color    = TextPrimary,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) {
            Text("✕", color = TextMuted, fontSize = 12.sp)
        }
    }
}

// ── Custom artwork panel ──────────────────────────────────────────────────────

@Composable
private fun CustomArtworkPanel(
    hasBoxArt: Boolean,
    hasHero: Boolean,
    hasLogo: Boolean,
    isWorking: Boolean,
    onPickBoxArt: () -> Unit,
    onPickHero: () -> Unit,
    onPickLogo: () -> Unit,
    onClearBoxArt: () -> Unit,
    onClearHero: () -> Unit,
    onClearLogo: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x1AFFFFFF))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text       = "Custom Artwork",
            fontSize   = 11.sp,
            color      = TextMuted,
            fontWeight = FontWeight.SemiBold,
        )

        ArtworkSlotRow(
            label        = "Box Art",
            hasImage     = hasBoxArt,
            isWorking    = isWorking,
            onPick       = onPickBoxArt,
            onClear      = onClearBoxArt,
        )
        ArtworkSlotRow(
            label        = "Hero Banner",
            hasImage     = hasHero,
            isWorking    = isWorking,
            onPick       = onPickHero,
            onClear      = onClearHero,
        )
        ArtworkSlotRow(
            label        = "Logo (transparent PNG)",
            hasImage     = hasLogo,
            isWorking    = isWorking,
            onPick       = onPickLogo,
            onClear      = onClearLogo,
        )
    }
}

@Composable
private fun ArtworkSlotRow(
    label: String,
    hasImage: Boolean,
    isWorking: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier          = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text     = label,
            fontSize = 13.sp,
            color    = if (hasImage) TextPrimary else TextMuted,
            modifier = Modifier.weight(1f),
        )

        if (hasImage) {
            // Green dot indicating an image is set
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF44CC88)),
            )
            Spacer(Modifier.width(6.dp))
        }

        TextButton(
            onClick  = onPick,
            enabled  = !isWorking,
            modifier = Modifier.height(32.dp),
        ) {
            Text(
                text     = if (hasImage) "Replace" else "Choose",
                fontSize = 12.sp,
                color    = AccentBlue,
            )
        }

        if (hasImage) {
            IconButton(
                onClick  = onClear,
                enabled  = !isWorking,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector        = Icons.Default.Delete,
                    contentDescription = "Clear $label",
                    tint               = TextMuted,
                    modifier           = Modifier.size(16.dp),
                )
            }
        }
    }
}

// ── Formatters ────────────────────────────────────────────────────────────────

private fun Long.formatPlayTime(): String = when {
    this < 60_000L        -> "< 1 min"
    this < 3_600_000L     -> "${this / 60_000} min"
    else -> {
        val h = this / 3_600_000L
        val m = (this % 3_600_000L) / 60_000L
        if (m > 0) "${h}h ${m}m" else "${h}h"
    }
}

private fun Long.formatLastPlayed(): String {
    val now  = System.currentTimeMillis()
    val diff = now - this
    return when {
        diff < 86_400_000L    -> "Today"
        diff < 172_800_000L   -> "Yesterday"
        diff < 604_800_000L   -> "${diff / 86_400_000L} days ago"
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(this))
    }
}
