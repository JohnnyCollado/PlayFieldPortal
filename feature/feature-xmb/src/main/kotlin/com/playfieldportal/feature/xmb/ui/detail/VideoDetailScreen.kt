package com.playfieldportal.feature.xmb.ui.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.playfieldportal.core.domain.model.ControllerIcon
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.Video
import com.playfieldportal.core.ui.components.ControllerPromptBar
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.core.ui.components.PfpModalSpec
import com.playfieldportal.core.ui.components.PspContextMenuOverlay
import com.playfieldportal.core.ui.components.PspMenuRow
import com.playfieldportal.core.ui.components.rememberPfpModalHost
import com.playfieldportal.core.ui.components.XmbHeaderPill
import com.playfieldportal.core.ui.components.XmbKebabTouchButton
import com.playfieldportal.core.ui.components.XmbMediaPillScrim
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.ui.theme.menuCursorEdge
import com.playfieldportal.core.ui.theme.themedSubText
import com.playfieldportal.core.ui.theme.themedText
import com.playfieldportal.feature.xmb.video.VideoPlayerScreen

// Neutral dark surfaces stay fixed; accent colors come from the active theme so this screen
// follows the chosen color scheme.
private val PageBg = Color(0xFF06060C)
private val PlayGreen = Color(0xFF45C46A)
private val ActionFill = Color(0xFF1B1B26)
private val TextPrimary = Color(0xFFEEEEEE)
private val TextMuted = Color(0xAAEEEEEE)

// The controller footer's own height (14dp padding above and below a 16dp glyph row), and the
// space the scroll viewport gives up for it. One value so the two cannot drift apart.
private val FOOTER_HEIGHT = 44.dp

@UnstableApi
@Composable
fun VideoDetailScreen(
    videoId: String,
    onBack: () -> Unit,
    pendingGamepadAction: GamepadAction? = null,
    onGamepadActionConsumed: () -> Unit = {},
    // Touch Back pill shown only when the last input was touch (AUTO), like the XMB App Drawer
    // button; any touch on the screen reports back via [onTouchInput].
    showTouchControls: Boolean = true,
    onTouchInput: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: VideoDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    val thumbnailPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.onThumbnailPicked(uri) }

    LaunchedEffect(videoId) { viewModel.loadVideo(videoId) }
    // Reset `closed` after handling it: the ViewModel is retained across open/close, so a stale
    // closed=true would otherwise instantly re-close the detail the next time it's opened (needing
    // a second tap).
    LaunchedEffect(state.closed) { if (state.closed) { onBack(); viewModel.onClosedHandled() } }
    LaunchedEffect(state.pickThumbnail) {
        if (state.pickThumbnail) {
            thumbnailPicker.launch(arrayOf("image/png", "image/jpeg", "image/webp"))
            viewModel.consumeThumbnailPick()
        }
    }
    // The page's prompts and name entries are the shared modals. The cursor and the text being
    // typed live in the host, so a press goes there first while one is up and only otherwise
    // reaches the page.
    val modal = rememberPfpModalHost(
        spec = videoDetailModalSpec(
            state = state,
            onSaveTitle = viewModel::saveTitle,
            onCancelTitle = viewModel::cancelTitleEdit,
            onCreatePlaylist = viewModel::confirmCreatePlaylist,
            onCancelCreatePlaylist = viewModel::cancelCreatePlaylist,
            onDismissInfo = viewModel::closeInfo,
            onConfirmRemove = viewModel::confirmRemove,
            onCancelRemove = viewModel::cancelRemove,
            onDismissLaunchError = viewModel::dismissLaunchError,
        ),
        // Touch mode has the buttons themselves to tap; the glyph hints are for the pad.
        showHints = !showTouchControls,
    )
    // Detail-level input only when the player overlay isn't up (the player consumes input itself).
    LaunchedEffect(pendingGamepadAction, state.playing) {
        val action = pendingGamepadAction ?: return@LaunchedEffect
        if (!state.playing) {
            if (!modal.intercept(action)) viewModel.handleGamepadAction(action)
            onGamepadActionConsumed()
        }
    }

    // When PFP regains focus after an external hand-off, drop the launch overlay and refresh this
    // video's metadata (resume / last-watched) — no rescan, no focus/scroll reset.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onReturnedFromExternal()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Defensive timeout: if the hand-off never backgrounded us, don't let the overlay stick.
    LaunchedEffect(state.externalLaunch) {
        if (state.externalLaunch != null) {
            kotlinx.coroutines.delay(8000)
            viewModel.clearExternalOverlay()
        }
    }

    if (state.isLoading) {
        Box(modifier.fillMaxSize().background(PageBg)) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = menuCursorEdge())
        }
        return
    }
    val video = state.video ?: run { onBack(); return }
    val pfpColors = LocalPFPColors.current
    // Controller and touch are separate input families (see the Options convention in
    // ARCHITECTURE.md): touch users get the header pills and tap the buttons directly, so the
    // prompt footer is for the pad only. It names the page's own actions, so it goes away
    // whenever something is layered over the page and input is routed elsewhere.
    val showFooter = !showTouchControls && !state.hasOverlay
    // Reserved off the plain pad check, not [showFooter]: the space must not appear and vanish as
    // overlays come and go, which would shuffle the page behind the context menu's light scrim.
    val footerInset = !showTouchControls

    // Same translucent theme-gradient backdrop as the Music browser, so the XMB wave stays visible
    // behind and all full-screen menus read consistently.
    Box(
        modifier = modifier
            .fillMaxSize()
            // Any touch marks the input source as touch (revealing the Back pill) without consuming.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); onTouchInput() } }
            .background(
                Brush.verticalGradient(
                    0f to pfpColors.backgroundTop.copy(alpha = 0.72f),
                    1f to pfpColors.backgroundBottom.copy(alpha = 0.90f),
                )
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().widthIn(max = 920.dp).align(Alignment.Center)
                // Outside the scroll, so it shortens the VIEWPORT rather than padding the content.
                // DetailButton frames itself with a BringIntoViewRequester, which scrolls only
                // until the button's edge reaches the viewport bottom — with a full-height viewport
                // that lands the focused button underneath the footer, and content-side padding
                // cannot help because it scrolls away with the button.
                .padding(bottom = if (footerInset) FOOTER_HEIGHT else 0.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Clear the header pill row above.
            Spacer(Modifier.height(56.dp))

            // Prominent thumbnail card — sits below the pills, crisp (no fade), as the focal point.
            video.effectiveThumbnailUri?.let { thumb ->
                AsyncImage(
                    model = thumb,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .widthIn(max = 460.dp)
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(14.dp)),
                )
            }

            Text(video.displayTitle, color = themedText(TextPrimary), fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(metadataLine(video), color = themedSubText(TextMuted), fontSize = 13.sp)
            if (video.resumePositionMs > 0) {
                Text("Resume at ${fmtTime(video.resumePositionMs)}", color = menuCursorEdge(), fontSize = 12.sp)
            }

            Spacer(Modifier.height(6.dp))

            val primaries = state.primaryActions
            primaries.forEachIndexed { i, action ->
                // The lead action (Play when unwatched, Resume when watched) gets the prominent
                // green fill; Start from Beginning sits below it in the neutral style.
                val isLead = action == VideoDetailAction.PLAY || action == VideoDetailAction.RESUME
                DetailButton(
                    label = action.label,
                    icon = when (action) {
                        VideoDetailAction.RESUME  -> Icons.Filled.Replay
                        VideoDetailAction.RESTART -> Icons.Filled.SkipPrevious
                        else                      -> Icons.Filled.PlayArrow
                    },
                    focused = state.mainFocus == i,
                    fill = if (isLead) PlayGreen else ActionFill,
                    textColor = if (isLead) Color(0xFF06140A) else TextPrimary,
                    onClick = { viewModel.activate(action) },
                )
            }
            state.actionMessage?.let {
                Text(it, color = menuCursorEdge(), fontSize = 12.sp)
                LaunchedEffect(it) { kotlinx.coroutines.delay(2500); viewModel.dismissMessage() }
            }
        }

        // Header controls over the banner (touch only, per the last-input source) — hidden while
        // the fullscreen player is up (it draws over everything). Back closes the Options menu when
        // it's open, otherwise backs out; the kebab opens the Options context menu (controller: Y).
        // Back pill + kebab is the same header the photo viewer uses, so the media screens match.
        if (!state.playing && showTouchControls) {
            XmbHeaderPill(
                label = "Back",
                leadingGlyph = "◀",
                onClick = { if (state.showOptions) viewModel.closeOptions() else onBack() },
                background = XmbMediaPillScrim,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
            )
            XmbKebabTouchButton(
                onClick = viewModel::openOptions,
                background = XmbMediaPillScrim,
                size = 36.dp,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            )
        }

        // Controller help row — names the actions, not the buttons; the shared resolver draws
        // whichever pad the user has and honours their Confirm/Back swap, so this row cannot
        // disagree with what the pad actually does.
        if (showFooter) {
            // A fixed-height band rather than one sized by its contents: the viewport above gives
            // up exactly FOOTER_HEIGHT, and a bar that measured itself could outgrow that reserve.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(FOOTER_HEIGHT)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))),
                contentAlignment = Alignment.Center,
            ) {
                ControllerPromptBar(
                    items = listOfNotNull(
                        // Only honest with something to move between: an unwatched video has a
                        // single primary button and the D-pad does nothing.
                        ControllerPromptItem
                            .fixed(listOf(ControllerIcon.DPAD_UP, ControllerIcon.DPAD_DOWN), "Navigate")
                            .takeIf { state.primaryActions.size > 1 },
                        ControllerPromptItem(
                            GamepadAction.SELECT,
                            state.primaryActions.getOrNull(state.mainFocus)?.label ?: "Select",
                        ),
                        ControllerPromptItem(GamepadAction.OPEN_CONTEXT_MENU, "Options"),
                        ControllerPromptItem(GamepadAction.BACK, "Back"),
                    ),
                    labelColor = themedSubText(TextMuted),
                    labelStyle = TextStyle(fontSize = 12.sp),
                    glyphSize = 16.dp,
                    arrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.alpha(com.playfieldportal.core.ui.components.idleHintAlpha(com.playfieldportal.feature.xmb.ui.LocalMediaHintVisible.current)).padding(horizontal = 24.dp),
                )
            }
        }

        if (state.showOptions) {
            PspContextMenuOverlay(
                title = "Options",
                rows = videoOptionRows(state),
                selectedIndex = state.optionsIndex,
                onRowActivated = { viewModel.activate(state.optionsActions[it]) },
                onDismiss = viewModel::closeOptions,
            )
        }

        if (state.showPlaylistPicker) {
            PspContextMenuOverlay(
                title = "Add to Playlist",
                rows = state.playlistOptions.map { PspMenuRow(label = it.name, checked = it.checked) } +
                    PspMenuRow(label = "Create New Playlist"),
                selectedIndex = state.playlistPickerIndex,
                onRowActivated = viewModel::onPlaylistRowClick,
                onDismiss = viewModel::closePlaylistPicker,
            )
        }

        // New Playlist, Information, Edit Title, the removal prompt and an external-player
        // launch error — one at a time, over the page and its menus.
        modal.Content()

        // Themed launch overlay — shown while handing off to an external player; fades in, and is
        // dropped when PFP regains focus (or after the safety timeout).
        androidx.compose.animation.AnimatedVisibility(
            visible = state.externalLaunch != null,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
        ) {
            state.externalLaunch?.let { launch -> ExternalLaunchOverlay(launch) }
        }

        // Fullscreen player overlay.
        if (state.playing) {
            VideoPlayerScreen(
                videos = state.siblings.ifEmpty { listOf(video) },
                startIndex = state.siblings.indexOfFirst { it.id == video.id }.coerceAtLeast(0),
                startPositionMs = state.playStartPositionMs,
                onSaveResume = viewModel::saveResume,
                onExit = viewModel::onPlaybackExit,
                pendingGamepadAction = pendingGamepadAction,
                onGamepadActionConsumed = onGamepadActionConsumed,
                showTouchControls = showTouchControls,
                onTouchInput = onTouchInput,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ExternalLaunchOverlay(launch: com.playfieldportal.feature.xmb.ui.detail.ExternalLaunch) {
    val colors = com.playfieldportal.core.ui.theme.LocalPFPColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.backgroundTop, colors.backgroundBottom))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (launch.thumbnailUri != null) {
                AsyncImage(
                    model = launch.thumbnailUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 240.dp, height = 135.dp).clip(RoundedCornerShape(12.dp)),
                )
                Spacer(Modifier.height(20.dp))
            }
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
            Spacer(Modifier.height(16.dp))
            Text("Launching…", color = themedText(Color.White), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(launch.playerLabel, color = themedSubText(Color.White.copy(alpha = 0.7f)), fontSize = 14.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailButton(
    label: String,
    icon: ImageVector,
    focused: Boolean,
    fill: Color,
    textColor: Color,
    onClick: () -> Unit,
) {
    // Auto-scroll into view when a controller focuses this button, so the whole action list is
    // reachable inside the scrolling content even when the thumbnail pushes it below the fold.
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) bringIntoView.bringIntoView() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) fill else fill.copy(alpha = 0.55f))
            .then(if (focused) Modifier.border(2.dp, com.playfieldportal.core.ui.theme.menuCursorEdge(), RoundedCornerShape(12.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = textColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Which shared modal the page is showing, if any — checked in the same order the view model checks
 * these states, so the modal on screen is the one being driven.
 *
 * Internal and free of composition so the mapping from UI state to modal can be tested directly.
 */
internal fun videoDetailModalSpec(
    state: VideoDetailUiState,
    onSaveTitle: (String) -> Unit,
    onCancelTitle: () -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onCancelCreatePlaylist: () -> Unit,
    onDismissInfo: () -> Unit,
    onConfirmRemove: () -> Unit,
    onCancelRemove: () -> Unit,
    onDismissLaunchError: () -> Unit,
): PfpModalSpec? {
    val video = state.video ?: return null
    val launchError = state.launchError
    return when {
        launchError != null -> PfpModalSpec.Notice(
            key = "launch_error:$launchError",
            title = "Can't play video",
            message = launchError,
            onDismiss = onDismissLaunchError,
        )
        state.confirmRemove -> PfpModalSpec.Confirm(
            key = "remove:${video.id}",
            title = "Remove from Library",
            message = "\"${video.displayTitle}\" will be removed from this library. The file on disk is not deleted.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = onConfirmRemove,
            onCancel = onCancelRemove,
        )
        state.creatingPlaylist -> PfpModalSpec.TextEntry(
            key = "new_playlist:${video.id}",
            title = "New Playlist",
            placeholder = "Playlist name",
            confirmLabel = "Create",
            onConfirm = onCreatePlaylist,
            onCancel = onCancelCreatePlaylist,
        )
        state.infoVisible -> PfpModalSpec.Notice(
            key = "info:${video.id}",
            title = video.displayTitle,
            message = videoInfoLines(video).joinToString("\n"),
            buttonLabel = "Close",
            onDismiss = onDismissInfo,
        )
        state.isEditingTitle -> PfpModalSpec.TextEntry(
            key = "rename_title:${video.id}",
            title = "Edit Title",
            initial = video.displayTitle,
            // Blank goes back to the file name, which is what the empty field shows.
            placeholder = video.displayName,
            allowBlank = true,
            onConfirm = onSaveTitle,
            onCancel = onCancelTitle,
        )
        else -> null
    }
}

// What is known about the file, one fact per line; unknown values are left out.
private fun videoInfoLines(video: Video): List<String> = buildList {
    add("Duration: ${fmtTime(video.durationMs ?: 0)}")
    video.resolutionLabel?.let { add("Resolution: $it") }
    video.codec?.let { add("Format: $it") }
    video.mimeType?.let { add("Type: $it") }
    video.sizeBytes?.let { add("Size: ${fmtSize(it)}") }
    video.relativePath?.let { add("Location: $it") }
    add("File: ${video.displayName}")
}

private fun metadataLine(video: Video): String = buildList {
    video.durationMs?.let { add(fmtTime(it)) }
    video.resolutionLabel?.let { add(it) }
    video.codec?.let { add(it) }
}.joinToString("  ·  ").ifEmpty { video.displayName }

private fun fmtTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600; val m = (totalSec % 3600) / 60; val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun fmtSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) "%.1f GB".format(mb / 1024) else "%.0f MB".format(mb)
}
