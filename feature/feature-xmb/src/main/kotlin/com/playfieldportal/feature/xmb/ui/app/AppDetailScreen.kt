package com.playfieldportal.feature.xmb.ui.app

import android.graphics.drawable.Drawable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.feature.xmb.ui.collection.CollectionPickerPanel
import com.playfieldportal.feature.xmb.ui.detail.ArtworkType
import com.playfieldportal.feature.xmb.ui.detail.displayLabel

private val TextPrimary = Color(0xFFEEEEEE)
private val TextMuted   = Color(0xAAEEEEEE)
private val AccentBlue  = Color(0xFF4A9EFF)
private val ActionFill    = Color(0xFF1B1B26)
private val ActionFocused = Color(0xFF574DDB)
private val ActionBorder  = Color(0xFF8F7CFF)
private val PageBg = Color(0xFF06060C)
private val HeroBannerHeight: Dp = 220.dp

@Composable
fun AppDetailScreen(
    gameId: Long,
    onBack: () -> Unit,
    pendingGamepadAction: GamepadAction? = null,
    onGamepadActionConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AppDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    var pendingArtworkType by remember { mutableStateOf<ArtworkType?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val type = pendingArtworkType
        if (uri != null && type != null) viewModel.onLocalFilePicked(uri, type)
        pendingArtworkType = null
    }

    LaunchedEffect(gameId) {
        viewModel.prepareForOpen()
        viewModel.loadApp(gameId)
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
        filePicker.launch(arrayOf("image/png", "image/jpeg", "image/webp"))
        viewModel.consumeLocalFilePick()
    }

    val scrollState = rememberScrollState()

    // Each action button is ~62dp tall (13dp×2 padding + text + 8dp gap between items).
    // Scroll so the focused button stays in view when navigating with the controller.
    val actionRowScrollStep = with(androidx.compose.ui.platform.LocalDensity.current) { 70.dp.roundToPx() }
    LaunchedEffect(state.mainFocus) {
        if (!state.showArtworkPicker) {
            scrollState.animateScrollTo(state.mainFocus * actionRowScrollStep)
        }
    }

    if (state.isLoading) {
        Box(modifier.fillMaxSize().background(PageBg)) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = AccentBlue)
        }
        return
    }

    val game = state.game ?: return

    Box(modifier = modifier.fillMaxSize().background(PageBg)) {
        // Accent gradient
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(AccentBlue.copy(alpha = 0.12f), PageBg))
            )
        )

        // ── Fixed structure: hero + header stay put, only buttons scroll ──────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 920.dp)
                .align(Alignment.Center),
        ) {
            // Hero banner — fixed, never scrolls
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(HeroBannerHeight)
                    .background(AccentBlue.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                if (game.heroUri != null) {
                    AsyncImage(
                        model = game.heroUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000)))
                        )
                    )
                }
            }

            // App header — fixed below hero, never scrolls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 28.dp, end = 28.dp, top = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AppIconPreview(
                    packageName   = game.packageName ?: "",
                    customIconUri = game.iconUri,
                    modifier      = Modifier.size(width = 144.dp, height = 80.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        game.displayTitle,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                    )
                    game.packageName?.takeIf { it.isNotBlank() }?.let { pkg ->
                        Text(pkg, fontSize = 12.sp, color = TextMuted, maxLines = 1)
                    }
                }
            }

            // Action buttons — scrollable, fills remaining space
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(start = 28.dp, end = 28.dp, bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppActionButton(
                    icon    = ">",
                    label   = "Launch App",
                    focused = state.mainFocus == 0,
                    onClick = viewModel::launchApp,
                )
                AppActionButton(
                    icon     = "C",
                    label    = "Add to Collection",
                    sublabel = "Add this app to a collection",
                    focused  = state.mainFocus == 1,
                    onClick  = viewModel::onCollectionsClicked,
                )
                AppActionButton(
                    icon     = "T",
                    label    = "Change Display Name",
                    sublabel = game.userTitleOverride?.let { "\"$it\"" } ?: "Using default name",
                    focused  = state.mainFocus == 2,
                    onClick  = viewModel::startEditingName,
                )
                AppActionButton(
                    icon     = "I",
                    label    = "Change Game Icon",
                    sublabel = if (game.iconUri != null) "Custom icon set" else "Using native icon",
                    focused  = state.mainFocus == 3,
                    onClick  = { viewModel.openArtworkPickerFor(ArtworkType.ICON) },
                )
                AppActionButton(
                    icon     = "H",
                    label    = "Change Hero Banner",
                    sublabel = if (game.heroUri != null) "Custom banner set" else "None",
                    focused  = state.mainFocus == 4,
                    onClick  = { viewModel.openArtworkPickerFor(ArtworkType.HERO) },
                )
                AppActionButton(
                    icon     = "B",
                    label    = "Change Background",
                    sublabel = if (game.artworkUri != null) "Custom background set" else "None",
                    focused  = state.mainFocus == 5,
                    onClick  = { viewModel.openArtworkPickerFor(ArtworkType.BACKGROUND) },
                )
                AppActionButton(
                    icon        = "X",
                    label       = "Reset All Artwork",
                    focused     = state.mainFocus == 6,
                    destructive = true,
                    onClick     = viewModel::clearAllArtwork,
                )

                state.artworkMessage?.let {
                    Text(it, color = AccentBlue, fontSize = 12.sp)
                }
            }
        }

        // Back label — always visible over hero
        Text(
            "< Back",
            color = TextMuted,
            fontSize = 13.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .clickable { viewModel.close() },
        )

        // Name editor overlay
        AnimatedVisibility(
            visible = state.isEditingName,
            enter   = fadeIn(),
            exit    = fadeOut(),
        ) {
            Box(
                Modifier.fillMaxSize().background(Color(0xCC000000)),
                contentAlignment = Alignment.Center,
            ) {
                AppNameEditor(
                    text      = state.nameText,
                    onChange  = viewModel::onNameTextChanged,
                    onSave    = viewModel::confirmNameEdit,
                    onReset   = viewModel::resetNameToDefault,
                    onCancel  = viewModel::cancelNameEdit,
                )
            }
        }

        // Artwork picker overlay
        AnimatedVisibility(
            visible = state.showArtworkPicker,
            enter   = fadeIn(),
            exit    = fadeOut(),
        ) {
            AppArtworkPicker(
                state       = state,
                onSelectArt = viewModel::onSgdbArtSelected,
                onPickLocal = { viewModel.requestLocalFilePick(state.artworkPickerType) },
                onClear     = { viewModel.clearArtwork(state.artworkPickerType) },
                onClose     = viewModel::closeArtworkPicker,
            )
        }

        // Add-to-collection overlay
        AnimatedVisibility(
            visible = state.collectionPicker.visible,
            enter   = fadeIn(),
            exit    = fadeOut(),
        ) {
            CollectionPickerPanel(
                ui                  = state.collectionPicker,
                onRowClick          = viewModel::onCollectionRowClick,
                onClose             = viewModel::closeCollectionPicker,
                onCreateTextChanged = viewModel::onCreateCollectionTextChanged,
                onConfirmCreate     = viewModel::confirmCreateCollection,
                onCancelCreate      = viewModel::cancelCreateCollection,
            )
        }
    }
}

// ── App icon preview (PSP rect if custom, native drawable otherwise) ───────────

@Composable
private fun AppIconPreview(
    packageName: String,
    customIconUri: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF12121C))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            !customIconUri.isNullOrBlank() -> AsyncImage(
                model              = customIconUri,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
            packageName.isNotBlank() -> NativeAppIcon(
                packageName = packageName.orEmpty(),
                modifier    = Modifier.size(48.dp),
            )
            else -> Text("No Icon", color = TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun NativeAppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val drawable: Drawable? = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
    }
    if (drawable != null) {
        val bitmap = remember(drawable) { runCatching { drawable.toBitmap() }.getOrNull() }
        if (bitmap != null) {
            Image(
                bitmap             = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier           = modifier,
            )
        }
    }
}

// ── Action button ─────────────────────────────────────────────────────────────

@Composable
private fun AppActionButton(
    icon: String,
    label: String,
    focused: Boolean,
    onClick: () -> Unit,
    sublabel: String? = null,
    destructive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) ActionFocused.copy(alpha = 0.5f) else ActionFill)
            .then(if (focused) Modifier.border(1.5.dp, ActionBorder, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp, horizontal = 14.dp),
    ) {
        Text(icon, fontSize = 16.sp, color = if (destructive) Color(0xFFFF8A8A) else TextPrimary)
        Column {
            Text(
                label,
                color    = if (destructive) Color(0xFFFF8A8A) else TextPrimary,
                fontSize = 15.sp,
                maxLines = 1,
            )
            if (!sublabel.isNullOrBlank()) {
                Text(sublabel, color = TextMuted, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

// ── Artwork picker overlay ────────────────────────────────────────────────────

@Composable
private fun AppArtworkPicker(
    state: AppDetailUiState,
    onSelectArt: (String) -> Unit,
    onPickLocal: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 300.dp, max = 480.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 520.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xF20A0A14))
                .clickable(enabled = false) {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Change ${state.artworkPickerType.displayLabel}",
                    color      = TextPrimary,
                    fontSize   = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onClose) {
                    Text("Close", color = TextMuted, fontSize = 12.sp)
                }
            }

            when {
                state.artworkIsProcessing -> {
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier    = Modifier.size(16.dp),
                            color       = AccentBlue,
                            strokeWidth = 2.dp,
                        )
                        Text("Saving…", color = TextMuted, fontSize = 12.sp)
                    }
                }
                state.artworkPickerLoading -> {
                    Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = AccentBlue, strokeWidth = 2.dp)
                    }
                }
                state.artworkPickerError != null -> {
                    Text("SteamGridDB", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(state.artworkPickerError, color = Color(0xFFFF6B6B), fontSize = 12.sp)
                }
                state.artworkPickerItems.isNotEmpty() -> {
                    Text("SteamGridDB", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    val lazyState = rememberLazyListState()
                    LaunchedEffect(state.artworkPickerFocus) {
                        if (state.artworkPickerItems.isNotEmpty()) {
                            lazyState.animateScrollToItem(state.artworkPickerFocus.coerceAtLeast(0))
                        }
                    }
                    LazyRow(
                        state                   = lazyState,
                        horizontalArrangement   = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(state.artworkPickerItems) { index, art ->
                            val isFocused = state.artworkPickerFocus == index
                            AsyncImage(
                                model              = art.thumbUrl ?: art.url,
                                contentDescription = null,
                                contentScale       = ContentScale.Crop,
                                modifier           = Modifier
                                    .size(width = 88.dp, height = 60.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .border(
                                        width  = if (isFocused) 2.dp else 1.dp,
                                        color  = if (isFocused) ActionBorder else Color(0x33FFFFFF),
                                        shape  = RoundedCornerShape(4.dp),
                                    )
                                    .clickable { onSelectArt(art.url) },
                            )
                        }
                    }
                }
            }

            // Local file + clear row
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PickerChip(
                    label     = "Pick Local File",
                    onClick   = onPickLocal,
                    modifier  = Modifier.weight(1f),
                )
                PickerChip(
                    label     = when (state.artworkPickerType) {
                        ArtworkType.ICON -> "Restore Native Icon"
                        else             -> "Remove Custom Art"
                    },
                    destructive = true,
                    onClick     = onClear,
                    modifier    = Modifier.weight(1f),
                )
            }

            Text(
                "◄ ►  Browse   A  Pick   B  Back",
                color    = TextMuted.copy(alpha = 0.5f),
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun AppNameEditor(
    text: String,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth(0.9f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xF20A0A14))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Change Display Name", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Sets the display name used in the launcher and for artwork scraping.",
            color    = TextMuted,
            fontSize = 11.sp,
        )
        OutlinedTextField(
            value          = text,
            onValueChange  = onChange,
            label          = { Text("Display Name", color = TextMuted) },
            modifier       = Modifier.fillMaxWidth(),
            colors         = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = AccentBlue,
                unfocusedBorderColor = Color(0x44FFFFFF),
                focusedTextColor     = TextPrimary,
                unfocusedTextColor   = TextPrimary,
                cursorColor          = AccentBlue,
            ),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction      = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSave() }),
            singleLine      = true,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onReset) {
                Text("Reset to Default", color = TextMuted, fontSize = 12.sp)
            }
            Row {
                TextButton(onClick = onCancel) { Text("Cancel", color = TextMuted) }
                TextButton(onClick = onSave) {
                    Text("Save", color = AccentBlue, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun PickerChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(ActionFill)
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color    = if (destructive) Color(0xFFFF8A8A) else TextPrimary,
            fontSize = 12.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}
