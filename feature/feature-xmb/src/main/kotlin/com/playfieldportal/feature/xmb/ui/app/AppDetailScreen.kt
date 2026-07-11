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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.ui.theme.menuCursorEdge
import com.playfieldportal.feature.xmb.ui.DetailContextMenu
import com.playfieldportal.feature.xmb.ui.DetailMenuRow
import com.playfieldportal.feature.xmb.ui.collection.CollectionPickerPanel
import com.playfieldportal.feature.xmb.ui.detail.ArtworkType
import com.playfieldportal.feature.xmb.ui.detail.ConsoleButton
import com.playfieldportal.feature.xmb.ui.detail.DetailBreadcrumb
import com.playfieldportal.feature.xmb.ui.detail.HeroCard
import com.playfieldportal.feature.xmb.ui.detail.IconTile
import com.playfieldportal.feature.xmb.ui.detail.SquareActionButton
import com.playfieldportal.feature.xmb.ui.detail.displayLabel

// Neutral dark surfaces stay fixed; accent/focus colors come from the active theme via
// menuCursorFill()/menuCursorEdge() so this screen follows the chosen color scheme.
private val TextPrimary = Color(0xFFEEEEEE)
private val TextMuted   = Color(0xAAEEEEEE)
private val ActionFill    = Color(0xFF1B1B26)
private val PageBg = Color(0xFF06060C)

@Composable
fun AppDetailScreen(
    gameId: Long,
    onBack: () -> Unit,
    collectionCategoryId: String = "games",
    pendingGamepadAction: GamepadAction? = null,
    onGamepadActionConsumed: () -> Unit = {},
    // Touch header pill shown only when the last input was touch (AUTO), like the XMB App Drawer
    // button; any touch on the screen reports back via [onTouchInput].
    showTouchControls: Boolean = true,
    onTouchInput: () -> Unit = {},
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

    LaunchedEffect(gameId, collectionCategoryId) {
        viewModel.prepareForOpen()
        viewModel.setCollectionCategory(collectionCategoryId)
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

    if (state.isLoading) {
        Box(modifier.fillMaxSize().background(PageBg)) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = menuCursorEdge())
        }
        return
    }

    val game = state.game ?: return
    val pfpColors = LocalPFPColors.current

    // Same translucent theme-gradient backdrop as the Music browser, so the XMB wave stays visible
    // behind and all full-screen menus read consistently.
    Box(
        modifier = modifier
            .fillMaxSize()
            // Any touch marks the input source as touch (revealing the header pill) without
            // consuming the event.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); onTouchInput() } }
            .background(
                Brush.verticalGradient(
                    0f to pfpColors.backgroundTop.copy(alpha = 0.72f),
                    1f to pfpColors.backgroundBottom.copy(alpha = 0.90f),
                )
            ),
    ) {
        // Same hero-card structure as Game Detail: breadcrumb → hero card → icon + actions.
        // No metadata/description — apps only Launch, edit artwork, and manage options.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 920.dp)
                .align(Alignment.TopCenter)
                .padding(start = 28.dp, end = 28.dp, bottom = 22.dp),
        ) {
            DetailBreadcrumb(
                title    = "Apps",
                subtitle = "Android App",
                onBack   = viewModel::close,
            )

            // The banner is the app's Background (heroes are a game-only surface).
            HeroCard(
                uri         = game.artworkUri ?: game.heroUri,
                title       = game.displayTitle,
                platform    = game.packageName.orEmpty(),
                accentColor = pfpColors.accentColor,
            )

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // Customized icon when set, the package's own icon otherwise.
                IconTile(uri = null, title = game.displayTitle) {
                    AppIconPreview(
                        packageName   = game.packageName ?: "",
                        customIconUri = game.iconUri,
                        modifier      = Modifier.fillMaxSize(),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ConsoleButton(
                        label = "Launch",
                        icon = Icons.Filled.PlayArrow,
                        focused = state.mainFocus == 0,
                        fill = Color(0xFFF2F2F2),
                        textColor = Color(0xFF0A0A12),
                        onClick = viewModel::launchApp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SquareActionButton(
                            icon = Icons.Filled.Settings,
                            contentDescription = "Options",
                            focused = state.mainFocus == 1,
                            onClick = viewModel::openOptions,
                        )
                        SquareActionButton(
                            icon = Icons.Filled.Brush,
                            contentDescription = "Edit Artwork",
                            focused = state.mainFocus == 2,
                            onClick = viewModel::openArtworkMenu,
                        )
                    }
                    state.artworkMessage?.let {
                        Text(it, color = menuCursorEdge(), fontSize = 12.sp)
                    }
                }
            }
        }

        AnimatedVisibility(state.showOptions, enter = fadeIn(), exit = fadeOut()) {
            DetailContextMenu(
                title = "Options",
                rows = AppDetailOption.OPTIONS_MENU.map { DetailMenuRow(it.label, it.isDestructive) },
                selectedIndex = state.optionsIndex,
                onRowClick = { viewModel.activateOption(AppDetailOption.OPTIONS_MENU[it]) },
                onDismiss = viewModel::closeMenus,
            )
        }

        AnimatedVisibility(state.showArtworkMenu, enter = fadeIn(), exit = fadeOut()) {
            DetailContextMenu(
                title = "Artwork",
                rows = AppDetailOption.ARTWORK_MENU.map { DetailMenuRow(it.label, it.isDestructive) },
                selectedIndex = state.optionsIndex,
                onRowClick = { viewModel.activateOption(AppDetailOption.ARTWORK_MENU[it]) },
                onDismiss = viewModel::closeMenus,
            )
        }

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
                            color       = menuCursorEdge(),
                            strokeWidth = 2.dp,
                        )
                        Text("Saving…", color = TextMuted, fontSize = 12.sp)
                    }
                }
                state.artworkPickerLoading -> {
                    Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = menuCursorEdge(), strokeWidth = 2.dp)
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
                                        color  = if (isFocused) menuCursorEdge() else Color(0x33FFFFFF),
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
                focusedBorderColor   = menuCursorEdge(),
                unfocusedBorderColor = Color(0x44FFFFFF),
                focusedTextColor     = TextPrimary,
                unfocusedTextColor   = TextPrimary,
                cursorColor          = menuCursorEdge(),
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
                    Text("Save", color = menuCursorEdge(), fontWeight = FontWeight.SemiBold)
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
