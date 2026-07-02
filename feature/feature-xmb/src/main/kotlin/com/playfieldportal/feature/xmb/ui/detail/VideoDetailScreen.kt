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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.Video
import com.playfieldportal.core.ui.components.XmbHeaderPill
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.ui.theme.menuCursor
import com.playfieldportal.core.ui.theme.menuCursorEdge
import com.playfieldportal.feature.xmb.ui.DetailContextMenu
import com.playfieldportal.feature.xmb.ui.DetailMenuRow
import com.playfieldportal.feature.xmb.video.VideoPlayerScreen

// Neutral dark surfaces stay fixed; accent colors come from the active theme so this screen
// follows the chosen color scheme.
private val PageBg = Color(0xFF06060C)
private val PlayGreen = Color(0xFF45C46A)
private val ActionFill = Color(0xFF1B1B26)
private val TextPrimary = Color(0xFFEEEEEE)
private val TextMuted = Color(0xAAEEEEEE)

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
    // Detail-level input only when the player overlay isn't up (the player consumes input itself).
    LaunchedEffect(pendingGamepadAction, state.playing) {
        val action = pendingGamepadAction ?: return@LaunchedEffect
        if (!state.playing) {
            viewModel.handleGamepadAction(action)
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

            Text(video.displayTitle, color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(metadataLine(video), color = TextMuted, fontSize = 13.sp)
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

        // Header pills over the banner (touch only, per the last-input source) — hidden while the
        // fullscreen player is up (it draws over everything). Back closes the Options menu when it's
        // open, otherwise backs out; the Options pill opens the Options context menu (controller: Y).
        if (!state.playing && showTouchControls) {
            XmbHeaderPill(
                label = "Back",
                leadingGlyph = "◀",
                onClick = { if (state.showOptions) viewModel.closeOptions() else onBack() },
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
            )
            XmbHeaderPill(
                label = "Options",
                onClick = viewModel::openOptions,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            )
        }

        if (state.showOptions) {
            DetailContextMenu(
                title = "Options",
                rows = state.optionsActions.map { action ->
                    val label = if (action == VideoDetailAction.FAVORITE) {
                        if (video.isFavorite) "Remove from Favorites" else "Add to Favorites"
                    } else action.label
                    DetailMenuRow(label)
                },
                selectedIndex = state.optionsIndex,
                onRowClick = { viewModel.activate(state.optionsActions[it]) },
                onDismiss = viewModel::closeOptions,
            )
        }

        if (state.showPlaylistPicker) {
            PlaylistPicker(
                options = state.playlistOptions,
                selectedIndex = state.playlistPickerIndex,
                onRowClick = viewModel::onPlaylistRowClick,
            )
        }

        if (state.creatingPlaylist) {
            RenameDialog(
                title = "New Playlist",
                confirmLabel = "Create",
                text = state.newPlaylistName,
                onTextChange = viewModel::onNewPlaylistNameChange,
                onConfirm = viewModel::confirmCreatePlaylist,
                onCancel = viewModel::cancelCreatePlaylist,
            )
        }

        if (state.infoVisible) {
            InfoDialog(video = video, onDismiss = { viewModel.handleGamepadAction(GamepadAction.BACK) })
        }

        if (state.isEditingTitle) {
            RenameDialog(
                text = state.titleText,
                onTextChange = viewModel::onTitleChanged,
                onConfirm = viewModel::saveTitle,
                onCancel = viewModel::cancelTitleEdit,
            )
        }

        if (state.confirmRemove) {
            AlertDialog(
                onDismissRequest = { viewModel.handleGamepadAction(GamepadAction.BACK) },
                confirmButton = { TextButton(onClick = viewModel::confirmRemove) { Text("Remove") } },
                dismissButton = { TextButton(onClick = { viewModel.handleGamepadAction(GamepadAction.BACK) }) { Text("Cancel") } },
                title = { Text("Remove from library?") },
                text = { Text("\"${video.displayTitle}\" will be removed from this library. The file on disk is not deleted.") },
            )
        }

        // External-player launch error (real dialog, controller-dismissible via A/B).
        state.launchError?.let { err ->
            AlertDialog(
                onDismissRequest = viewModel::dismissLaunchError,
                confirmButton = { TextButton(onClick = viewModel::dismissLaunchError) { Text("OK") } },
                title = { Text("Can't play video") },
                text = { Text(err) },
            )
        }

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
            Text("Launching…", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(launch.playerLabel, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
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

@Composable
private fun PlaylistPicker(
    options: List<VideoPlaylistOption>,
    selectedIndex: Int,
    onRowClick: (Int) -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(
            modifier = Modifier.padding(36.dp).width(320.dp)
                .background(Color(0xF0101018), RoundedCornerShape(14.dp)).padding(vertical = 12.dp),
        ) {
            Text("Add to Playlist", color = menuCursorEdge(), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            options.forEachIndexed { i, opt ->
                Text(
                    (if (opt.checked) "● " else "○ ") + opt.name,
                    color = if (i == selectedIndex) Color.White else TextMuted,
                    fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onRowClick(i) }
                        .menuCursor(i == selectedIndex)
                        .padding(horizontal = 20.dp, vertical = 11.dp),
                )
            }
            val createIndex = options.size
            Text(
                "+ Create New Playlist",
                color = if (createIndex == selectedIndex) Color.White else menuCursorEdge(),
                fontSize = 15.sp,
                modifier = Modifier.fillMaxWidth()
                    .clickable { onRowClick(createIndex) }
                    .menuCursor(createIndex == selectedIndex)
                    .padding(horizontal = 20.dp, vertical = 11.dp),
            )
        }
    }
}

@Composable
private fun InfoDialog(video: Video, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text(video.displayTitle) },
        text = {
            Column {
                InfoRow("Duration", fmtTime(video.durationMs ?: 0))
                video.resolutionLabel?.let { InfoRow("Resolution", it) }
                video.codec?.let { InfoRow("Format", it) }
                video.mimeType?.let { InfoRow("Type", it) }
                video.sizeBytes?.let { InfoRow("Size", fmtSize(it)) }
                video.relativePath?.let { InfoRow("Location", it) }
                InfoRow("File", video.displayName)
            }
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextMuted, fontSize = 13.sp)
        Spacer(Modifier.width(12.dp))
        Text(value, color = TextPrimary, fontSize = 13.sp)
    }
}

@Composable
private fun RenameDialog(
    text: String,
    onTextChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    title: String = "Rename Title",
    confirmLabel: String = "Save",
) {
    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = onTextChange, singleLine = true) },
    )
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
