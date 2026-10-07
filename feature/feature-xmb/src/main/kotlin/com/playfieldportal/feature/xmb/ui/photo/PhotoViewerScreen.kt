package com.playfieldportal.feature.xmb.ui.photo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.Photo
import com.playfieldportal.core.domain.model.TouchGesture
import com.playfieldportal.core.ui.components.ControllerPromptBar
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.core.ui.components.PfpModalSpec
import com.playfieldportal.core.ui.components.rememberPfpModalHost
import com.playfieldportal.core.ui.components.TouchPromptBar
import com.playfieldportal.core.ui.components.TouchPromptItem
import com.playfieldportal.core.ui.components.XmbHeaderPill
import com.playfieldportal.core.ui.components.XmbKebabTouchButton
import com.playfieldportal.core.ui.components.XmbMediaPillScrim
import com.playfieldportal.core.ui.theme.menuCursorEdge
import com.playfieldportal.core.ui.components.PspContextMenuOverlay
import com.playfieldportal.core.ui.theme.themedSubText
import com.playfieldportal.core.ui.theme.themedText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Neutral dark surfaces stay fixed; accent colors come from the active theme.
private val ViewerBg = Color(0xFF000000)
private val TextPrimary = Color(0xFFEEEEEE)
private val TextMuted = Color(0xAAEEEEEE)
private val PanelBg = Color(0xF0101018)

/**
 * PSP-style fullscreen photo viewer: just the image on black, all UI hidden until toggled.
 * Confirm toggles the controls, Back exits, the context-menu button opens Options, the bumpers
 * step previous/next, and the D-pad/stick pans when zoomed — the help row names those actions and
 * the shared resolver draws whichever buttons the user's pad binds them to. The Options menu
 * carries the wallpaper workflow (preview → apply), rotate/zoom, info, and Remove From Library.
 */
@Composable
fun PhotoViewerScreen(
    photoId: String,
    libraryId: String?,
    onBack: () -> Unit,
    openWallpaperPreview: Boolean = false,
    openLockScreenConfirm: Boolean = false,
    pendingGamepadAction: GamepadAction? = null,
    onGamepadActionConsumed: () -> Unit = {},
    // Touch header pills shown only when the last input was touch (AUTO), like the XMB App Drawer
    // button; a tap on the photo reports back via [onTouchInput] (and toggles the controls layer).
    showTouchControls: Boolean = true,
    touchSensitivity: Float = 1f,
    onTouchInput: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: PhotoViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(photoId, libraryId, openWallpaperPreview, openLockScreenConfirm) {
        viewModel.load(photoId, libraryId, openWallpaperPreview, openLockScreenConfirm)
    }
    // Reset `closed` after handling it — the ViewModel is retained across open/close, so a stale
    // closed=true would otherwise instantly re-close the viewer the next time it's opened.
    LaunchedEffect(state.closed) { if (state.closed) { onBack(); viewModel.onClosedHandled() } }
    // Information and the removal prompt are the shared modals. Their cursor lives in the host, so
    // a press goes there first while one is up and only otherwise reaches the viewer.
    val modal = rememberPfpModalHost(
        spec = photoViewerModalSpec(
            state = state,
            onDismissInfo = viewModel::closeInfo,
            onConfirmRemove = viewModel::confirmRemove,
            onCancelRemove = viewModel::cancelRemove,
            onConfirmLockScreen = viewModel::confirmLockScreen,
            onCancelLockScreen = viewModel::cancelLockScreen,
        ),
        // Touch mode has the buttons themselves to tap; the glyph hints are for the pad.
        showHints = !showTouchControls,
    )
    LaunchedEffect(pendingGamepadAction) {
        val action = pendingGamepadAction ?: return@LaunchedEffect
        if (!modal.intercept(action)) viewModel.handleGamepadAction(action)
        onGamepadActionConsumed()
    }

    if (state.isLoading) {
        Box(modifier.fillMaxSize().background(ViewerBg)) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = menuCursorEdge())
        }
        return
    }
    val photo = state.photo ?: run { onBack(); return }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ViewerBg)
            .photoViewerGestures(
                zoomed = state.zoomed,
                stepScale = touchSensitivity,
                onTap = { onTouchInput(); viewModel.toggleControls() },
                onStep = { direction -> onTouchInput(); viewModel.step(direction) },
                onTransform = viewModel::onGesture,
            ),
    ) {
        AsyncImage(
            model = photo.uri,
            contentDescription = photo.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = state.zoom,
                    scaleY = state.zoom,
                    translationX = state.panX,
                    translationY = state.panY,
                    rotationZ = state.rotationDegrees.toFloat(),
                ),
        )

        var helperFlashVisible by remember { mutableStateOf(showTouchControls) }
        LaunchedEffect(photo.id, showTouchControls) {
            helperFlashVisible = showTouchControls
            if (showTouchControls) {
                kotlinx.coroutines.delay(2600)
                helperFlashVisible = false
            }
        }

        // ── Auto-fading title card ──────────────────────────────────────────
        // Centred title that appears on each new image, then disappears after a short delay
        // (Title → delay → just the image → next image → Title …). Independent of the tap controls.
        var titleFlashVisible by remember { mutableStateOf(true) }
        LaunchedEffect(photo.id) {
            titleFlashVisible = true
            kotlinx.coroutines.delay(2200)
            titleFlashVisible = false
        }
        AnimatedVisibility(
            visible = titleFlashVisible && !state.wallpaperPreviewVisible && !state.showOptions,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(600)),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp),
        ) {
            Text(
                text = photo.displayName,
                color = themedText(TextPrimary),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier
                    .background(Color(0x66000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }

        // ── Minimal controls, hidden by default ─────────────────────────────
        if (state.controlsVisible && !state.wallpaperPreviewVisible) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    // Extra side padding clears the Back/Options corner buttons.
                    .padding(horizontal = 70.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    listOfNotNull(
                        "${state.index + 1} / ${state.photos.size}",
                        photo.resolutionLabel,
                        photo.displayDateMs?.let { fmtDate(it) },
                    ).joinToString("  ·  "),
                    color = themedSubText(TextMuted), fontSize = 12.sp,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                if (showTouchControls) {
                    TouchPromptBar(
                        items = listOf(
                            TouchPromptItem(listOf(TouchGesture.SWIPE_LEFT, TouchGesture.SWIPE_RIGHT), "Prev / Next"),
                            TouchPromptItem(TouchGesture.TAP, "Hide Controls"),
                        ),
                        labelColor = themedSubText(TextMuted),
                        labelStyle = TextStyle(fontSize = 12.sp),
                        glyphSize = 16.dp,
                        arrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                    )
                } else {
                    ControllerPromptBar(
                        items = listOf(
                            ControllerPromptItem(
                                listOf(GamepadAction.PREV_CATEGORY, GamepadAction.NEXT_CATEGORY),
                                "Prev / Next",
                            ),
                            ControllerPromptItem(GamepadAction.SELECT, "Hide Controls"),
                            ControllerPromptItem(GamepadAction.OPEN_CONTEXT_MENU, "Options"),
                            ControllerPromptItem(GamepadAction.BACK, "Back"),
                        ),
                        labelColor = themedSubText(TextMuted),
                        labelStyle = TextStyle(fontSize = 12.sp),
                        glyphSize = 16.dp,
                        arrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                        // Idles in and blinks out like the crossbar pill (see shouldShowMediaHint).
                        modifier = Modifier.alpha(com.playfieldportal.core.ui.components.idleHintAlpha(com.playfieldportal.feature.xmb.ui.LocalMediaHintVisible.current)),
                    )
                }
            }
            // Header pills matching the detail screens: Back top-left, Options top-right — the
            // touch counterparts of B and Y. Shown while the controls layer is visible AND the last
            // input was touch (a controller press hides them, like the XMB App Drawer button).
            if (showTouchControls) {
                XmbHeaderPill(
                    label = "Back",
                    leadingGlyph = "◀",
                    onClick = { viewModel.handleGamepadAction(GamepadAction.BACK) },
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
        }

        // ── Options menu — the shared themed context menu, like every other context menu ──
        if (state.showOptions) {
            PspContextMenuOverlay(
                title = "Options",
                rows = photoOptionRows(state),
                selectedIndex = state.optionsIndex,
                onRowActivated = { viewModel.activate(state.optionsActions[it]) },
                onDismiss = viewModel::closeOptions,
            )
        }

        // ── Wallpaper preview: the photo as it would look, with confirm/cancel ──
        if (state.wallpaperPreviewVisible) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Set as launcher wallpaper?", color = themedText(TextPrimary), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("It replaces the XMB wave background.", color = themedSubText(TextMuted), fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                WallpaperPreviewActions(
                    showTouchControls = showTouchControls,
                    applying = state.applyingWallpaper,
                    onApply = viewModel::confirmWallpaper,
                    onCancel = viewModel::cancelWallpaperPreview,
                )
            }
        }

        state.actionMessage?.let { msg ->
            Text(
                msg,
                color = themedText(TextPrimary),
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 60.dp)
                    .background(PanelBg, RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LaunchedEffect(msg) { kotlinx.coroutines.delay(2500); viewModel.dismissMessage() }
        }
        if (showTouchControls && helperFlashVisible && !state.controlsVisible && !state.wallpaperPreviewVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                TouchPromptBar(
                    items = listOf(
                        TouchPromptItem(listOf(TouchGesture.SWIPE_LEFT, TouchGesture.SWIPE_RIGHT), "Prev / Next"),
                        TouchPromptItem(TouchGesture.TAP, "Show Controls"),
                    ),
                    labelColor = themedSubText(TextMuted),
                    labelStyle = TextStyle(fontSize = 12.sp),
                    glyphSize = 16.dp,
                    arrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                )
            }
        }

        // Information and the removal prompt. Last, so it draws over everything else.
        modal.Content()
    }
}

/**
 * The wallpaper preview's Apply / Cancel: pills under touch, the pad prompts otherwise — one input
 * family at a time. Both are inert while [applying], as the buttons they replace were.
 */
@Composable
internal fun WallpaperPreviewActions(
    showTouchControls: Boolean,
    applying: Boolean,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    if (showTouchControls) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
            XmbHeaderPill(
                label = if (applying) "Applying…" else "Apply",
                onClick = { if (!applying) onApply() },
                background = XmbMediaPillScrim,
            )
            XmbHeaderPill(
                label = "Cancel",
                onClick = { if (!applying) onCancel() },
                background = XmbMediaPillScrim,
            )
        }
    } else {
        ControllerPromptBar(
            items = listOf(
                ControllerPromptItem(GamepadAction.SELECT, "Apply"),
                ControllerPromptItem(GamepadAction.BACK, "Cancel"),
            ),
            labelColor = themedSubText(TextMuted),
            labelStyle = TextStyle(fontSize = 12.sp),
            glyphSize = 16.dp,
            arrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
        )
    }
}

/**
 * Which shared modal the viewer is showing, if any — checked in the same order the view model
 * checks these states, so the modal on screen is the one being driven. The wallpaper preview owns
 * the screen while it is up, so nothing is shown over it.
 *
 * Internal and free of composition so the mapping from UI state to modal can be tested directly.
 */
internal fun photoViewerModalSpec(
    state: PhotoViewerUiState,
    onDismissInfo: () -> Unit,
    onConfirmRemove: () -> Unit,
    onCancelRemove: () -> Unit,
    onConfirmLockScreen: () -> Unit = {},
    onCancelLockScreen: () -> Unit = {},
): PfpModalSpec? {
    val photo = state.photo ?: return null
    if (state.applyingWallpaper || state.wallpaperPreviewVisible) return null
    return when {
        state.confirmRemove -> PfpModalSpec.Confirm(
            key = "remove:${photo.id}",
            title = "Remove from Library",
            message = "\"${photo.displayName}\" will be removed from this library. The photo on disk is not deleted.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = onConfirmRemove,
            onCancel = onCancelRemove,
        )
        state.confirmLockScreen -> PfpModalSpec.Confirm(
            key = "lock:${photo.id}",
            title = "Set as Lock Screen",
            message = "\"${photo.displayName}\" becomes the device's lock screen image, cropped to fit the screen.",
            confirmLabel = "Set",
            onConfirm = onConfirmLockScreen,
            onCancel = onCancelLockScreen,
        )
        state.infoVisible -> PfpModalSpec.Notice(
            key = "info:${photo.id}",
            title = photo.displayName,
            message = photoInfoLines(photo).joinToString("\n"),
            buttonLabel = "Close",
            onDismiss = onDismissInfo,
        )
        else -> null
    }
}

// What is known about the file, one fact per line; unknown values are left out.
private fun photoInfoLines(photo: Photo): List<String> = buildList {
    photo.resolutionLabel?.let { add("Resolution: $it") }
    photo.dateTaken?.let { add("Taken: ${fmtDate(it)}") }
    photo.lastModified?.let { add("Modified: ${fmtDate(it)}") }
    photo.sizeBytes?.let { add("Size: ${fmtSize(it)}") }
    photo.mimeType?.let { add("Type: $it") }
    photo.relativePath?.let { add("Location: $it") }
    add("File: ${photo.displayName}")
}

private fun fmtDate(ms: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(ms))

private fun fmtSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) "%.1f MB".format(mb) else "%.0f KB".format(bytes / 1024.0)
}
