package com.playfieldportal.feature.xmb.ui.app

import android.graphics.drawable.Drawable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.playfieldportal.core.ui.image.rememberArtworkModel
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.theme.menuCursorEdge
import com.playfieldportal.core.ui.components.PspContextMenuOverlay
import com.playfieldportal.core.ui.theme.themedSubText
import com.playfieldportal.core.ui.theme.themedText
import com.playfieldportal.feature.xmb.ui.collection.CollectionPickerPanel
import com.playfieldportal.feature.xmb.ui.collection.collectionNameModalSpec
import com.playfieldportal.core.ui.components.PfpModalSpec
import com.playfieldportal.core.ui.components.rememberPfpModalHost
import com.playfieldportal.feature.xmb.ui.detail.ArtworkType
import com.playfieldportal.feature.xmb.ui.detail.displayLabel
import com.playfieldportal.core.ui.detail.DetailRowSpacing
import com.playfieldportal.core.ui.detail.PfpDetailBreadcrumb
import com.playfieldportal.core.ui.detail.detailPalette
import com.playfieldportal.core.ui.detail.PfpDetailBackground
import com.playfieldportal.core.ui.detail.LocalDetailViewportHeight
import com.playfieldportal.core.ui.detail.detailHeroHeightFor
import com.playfieldportal.core.ui.detail.PfpDetailHelperFooter
import com.playfieldportal.core.ui.detail.PfpDetailHeroBanner
import com.playfieldportal.core.ui.detail.PfpDetailIconTile
import com.playfieldportal.core.ui.detail.PfpDetailLaunchButton
import com.playfieldportal.core.ui.detail.PfpDetailQuickAction
import com.playfieldportal.core.ui.detail.PfpDetailScaffold
import com.playfieldportal.core.ui.components.ControllerPromptItem

// Neutral dark surfaces stay fixed; accent/focus colors come from the active theme via
// menuCursorFill()/menuCursorEdge() so this screen follows the chosen color scheme.
private val TextPrimary = Color(0xFFEEEEEE)
private val TextMuted   = Color(0xAAEEEEEE)
private val ActionFill    = Color(0xFF1B1B26)

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
    // Change Display Name and New Collection are the shared text entry modal. Its cursor and the
    // text being typed live in the host, so a press goes there first while one is up and only
    // otherwise reaches the page.
    val modal = rememberPfpModalHost(
        spec = appDetailModalSpec(
            state = state,
            onSaveName = viewModel::confirmNameEdit,
            onCancelName = viewModel::cancelNameEdit,
            onCreateCollection = viewModel::confirmCreateCollection,
            onCancelCreateCollection = viewModel::cancelCreateCollection,
            onConfirmReset = viewModel::confirmReset,
            onCancelReset = viewModel::cancelReset,
        ),
        // Touch mode has the buttons themselves to tap; the glyph hints are for the pad.
        showHints = !showTouchControls,
    )
    LaunchedEffect(pendingGamepadAction) {
        if (pendingGamepadAction != null) {
            if (!modal.intercept(pendingGamepadAction)) viewModel.handleGamepadAction(pendingGamepadAction)
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
        PfpDetailBackground(modifier = modifier.fillMaxSize()) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = detailPalette().focus)
        }
        return
    }

    val game = state.game ?: return

    // Same translucent theme-gradient backdrop as the Music browser, so the XMB wave stays visible
    // behind and all full-screen menus read consistently.
    // The same detail frame as Game Detail (core-ui's shared scaffold): breadcrumb header, accent
    // surface, scrolling body of full-width rows, permanent helper footer. Apps have no metadata or
    // description — just Launch, artwork and options — so the body is simply shorter.
    PfpDetailScaffold(
        modifier = modifier
            // Any touch marks the input source as touch (revealing the header pill) without
            // consuming the event.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); onTouchInput() } },
        header = {
            PfpDetailBreadcrumb(
                title = "Apps",
                subtitle = "Android App",
                onBack = viewModel::close,
            )
        },
        footer = {
            PfpDetailHelperFooter(
                items = appDetailHelperItems(state.mainFocus),
                // A shared modal draws its own hints under its card.
                visible = !showTouchControls && !modal.open,
            )
        },
        overlay = {
        AnimatedVisibility(state.showOptions, enter = fadeIn(), exit = fadeOut()) {
            val options = viewModel.menuRows(state.menuGroup)
            PspContextMenuOverlay(
                title = if (state.menuGroup == AppDetailMenuGroup.ARTWORK) "Artwork" else "Options",
                rows = appDetailMenuRows(options, state.game?.isFavorite == true),
                selectedIndex = state.optionsIndex,
                onRowActivated = { viewModel.activateOption(options[it]) },
                onDismiss = viewModel::closeMenus,
            )
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
            )
        }

        // Change Display Name and New Collection. Last, so it draws over every other overlay.
        modal.Content()
        },
    ) {
        Spacer(Modifier.height(16.dp))

        // The banner is the app's Background (heroes are a game-only surface).
        PfpDetailHeroBanner(
            artworkUri  = game.artworkUri ?: game.heroUri,
            title       = game.displayTitle,
            platform    = game.packageName.orEmpty(),
            height      = detailHeroHeightFor(LocalDetailViewportHeight.current, messageLine = state.artworkMessage != null),
        )

        Spacer(Modifier.height(DetailRowSpacing + 6.dp))

        // Same primary-action region as Game Detail: icon tile, Launch, quick actions beneath.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Customized icon when set, the package's own icon otherwise.
            PfpDetailIconTile(uri = null, title = game.displayTitle) {
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
                PfpDetailLaunchButton(
                    label     = "Launch",
                    icon      = Icons.Filled.PlayArrow,
                    focused   = state.mainFocus == 0,
                    fill      = Color(0xFFF2F2F2),
                    textColor = Color(0xFF0A0A12),
                    onClick   = viewModel::launchApp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PfpDetailQuickAction(
                        label     = "Options",
                        icon      = Icons.Filled.Settings,
                        focused   = state.mainFocus == 1,
                        available = true,
                        onClick   = viewModel::openOptions,
                        contentDescription = "Options",
                        modifier  = Modifier.weight(1f),
                    )
                    PfpDetailQuickAction(
                        label     = "Artwork",
                        icon      = Icons.Filled.Brush,
                        focused   = state.mainFocus == 2,
                        available = true,
                        onClick   = viewModel::openArtworkMenu,
                        contentDescription = "Edit artwork",
                        modifier  = Modifier.weight(1f),
                    )
                }
                state.artworkMessage?.let {
                    Text(it, color = detailPalette().focus, fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(DetailRowSpacing))
    }
}

/**
 * The App Detail page's helper prompts. Apps have no emulator, coins or media, so the footer is the
 * same three actions in every context, with Select naming the focused button — but it still
 * reserves its row, so nothing shifts when it fades for touch input.
 */
internal fun appDetailHelperItems(mainFocus: Int): List<ControllerPromptItem> = listOf(
    ControllerPromptItem(GamepadAction.SELECT, when (mainFocus) { 0 -> "Launch"; 1 -> "Options"; else -> "Artwork" }),
    ControllerPromptItem(GamepadAction.OPEN_CONTEXT_MENU, "Options"),
    ControllerPromptItem(GamepadAction.BACK, "Back"),
)

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
                model              = rememberArtworkModel(customIconUri),
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
                    color      = themedText(TextPrimary),
                    fontSize   = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onClose) {
                    Text("Close", color = themedSubText(TextMuted), fontSize = 12.sp)
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
                        Text("Saving…", color = themedSubText(TextMuted), fontSize = 12.sp)
                    }
                }
                state.artworkPickerLoading -> {
                    Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = menuCursorEdge(), strokeWidth = 2.dp)
                    }
                }
                state.artworkPickerError != null -> {
                    Text("SteamGridDB", color = themedSubText(TextMuted), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(state.artworkPickerError, color = Color(0xFFFF6B6B), fontSize = 12.sp)
                }
                state.artworkPickerItems.isNotEmpty() -> {
                    Text("SteamGridDB", color = themedSubText(TextMuted), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    val lazyState = rememberLazyListState()
                    LaunchedEffect(state.artworkPickerFocus) {
                        if (state.artworkPickerItems.isNotEmpty()) {
                            lazyState.animateScrollToItem(state.artworkPickerFocus.coerceIn(0, state.artworkPickerItems.lastIndex))
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
                color    = themedSubText(TextMuted.copy(alpha = 0.5f)),
                fontSize = 10.sp,
            )
        }
    }
}

/**
 * Which shared modal the page is showing, if any — checked in the same order the view model checks
 * these states, so the modal on screen is the one being driven. Clearing the display name drops
 * the override; the placeholder shows the name that comes back.
 *
 * Internal and free of composition so the mapping from UI state to modal can be tested directly.
 */
internal fun appDetailModalSpec(
    state: AppDetailUiState,
    onSaveName: (String) -> Unit,
    onCancelName: () -> Unit,
    onCreateCollection: (String) -> Unit,
    onCancelCreateCollection: () -> Unit,
    onConfirmReset: () -> Unit,
    onCancelReset: () -> Unit,
): PfpModalSpec? {
    val app = state.game ?: return null
    return when {
        state.isEditingName -> PfpModalSpec.TextEntry(
            key = "display_name:${app.id}",
            title = "Edit Title",
            initial = app.displayTitle,
            placeholder = app.scrapedTitle ?: app.title,
            allowBlank = true,
            onConfirm = onSaveName,
            onCancel = onCancelName,
        )
        state.collectionPicker.showCreateDialog ->
            collectionNameModalSpec(onCreate = onCreateCollection, onCancel = onCancelCreateCollection)
        // Opens on Cancel, so a stray Confirm press on the controller dismisses.
        state.confirmReset -> PfpModalSpec.Confirm(
            key = "reset_artwork:${app.id}",
            title = "Reset All Artwork?",
            message = "The custom icon and background for ${app.displayTitle} are removed and the " +
                "app's own icon comes back.",
            confirmLabel = "Reset",
            destructive = true,
            onConfirm = onConfirmReset,
            onCancel = onCancelReset,
        )
        else -> null
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
