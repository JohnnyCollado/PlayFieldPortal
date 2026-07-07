package com.playfieldportal.feature.xmb.ui.photo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.Photo
import com.playfieldportal.core.ui.components.XmbHeaderPill
import com.playfieldportal.core.ui.theme.menuCursorEdge
import com.playfieldportal.feature.xmb.ui.DetailContextMenu
import com.playfieldportal.feature.xmb.ui.DetailMenuRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Neutral dark surfaces stay fixed; accent colors come from the active theme.
private val ViewerBg = Color(0xFF000000)
private val TextPrimary = Color(0xFFEEEEEE)
private val TextMuted = Color(0xAAEEEEEE)
private val PanelBg = Color(0xF0101018)

// Header pills float over the photo itself, so they need a real scrim: the default 12% white
// pill fill disappears on bright images (Prev/Next were unreadable on light photos).
private val MediaPillBg = Color(0x99000000)

/**
 * PSP-style fullscreen photo viewer: just the image on black, all UI hidden until toggled.
 * A = toggle controls · B = back · Y = options · L1/R1 = previous/next · D-pad/stick pans when
 * zoomed. The Options menu carries the wallpaper workflow (preview → apply), rotate/zoom, info,
 * and Remove From Library.
 */
@Composable
fun PhotoViewerScreen(
    photoId: String,
    libraryId: String?,
    onBack: () -> Unit,
    openWallpaperPreview: Boolean = false,
    pendingGamepadAction: GamepadAction? = null,
    onGamepadActionConsumed: () -> Unit = {},
    // Touch header pills shown only when the last input was touch (AUTO), like the XMB App Drawer
    // button; a tap on the photo reports back via [onTouchInput] (and toggles the controls layer).
    showTouchControls: Boolean = true,
    onTouchInput: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: PhotoViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(photoId, libraryId, openWallpaperPreview) {
        viewModel.load(photoId, libraryId, openWallpaperPreview)
    }
    // Reset `closed` after handling it — the ViewModel is retained across open/close, so a stale
    // closed=true would otherwise instantly re-close the viewer the next time it's opened.
    LaunchedEffect(state.closed) { if (state.closed) { onBack(); viewModel.onClosedHandled() } }
    LaunchedEffect(pendingGamepadAction) {
        val action = pendingGamepadAction ?: return@LaunchedEffect
        viewModel.handleGamepadAction(action)
        onGamepadActionConsumed()
    }

    if (state.isLoading) {
        Box(modifier.fillMaxSize().background(ViewerBg)) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = menuCursorEdge())
        }
        return
    }
    val photo = state.photo ?: run { onBack(); return }

    // Pinch-zoom / drag for touch users; the same clamped transform the D-pad path drives.
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        viewModel.onGesture(zoomChange, panChange.x, panChange.y)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ViewerBg)
            .transformable(transformState)
            // Plain tap toggles the controls — no ripple, the whole screen is the target.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onTouchInput(); viewModel.toggleControls() },
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
                color = TextPrimary,
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
                    color = TextMuted, fontSize = 12.sp,
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
                HelpHint("L1/R1", "Prev / Next")
                Spacer(Modifier.width(18.dp))
                HelpHint("A", "Hide Controls")
                Spacer(Modifier.width(18.dp))
                HelpHint("Y", "Options")
                Spacer(Modifier.width(18.dp))
                HelpHint("B", "Back")
            }
            // Header pills matching the detail screens: Back top-left, Options top-right — the
            // touch counterparts of B and Y. Shown while the controls layer is visible AND the last
            // input was touch (a controller press hides them, like the XMB App Drawer button).
            if (showTouchControls) {
                XmbHeaderPill(
                    label = "Back",
                    leadingGlyph = "◀",
                    onClick = { viewModel.handleGamepadAction(GamepadAction.BACK) },
                    background = MediaPillBg,
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                )
                XmbHeaderPill(
                    label = "Options",
                    onClick = viewModel::openOptions,
                    background = MediaPillBg,
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                )
                // Touch prev/next — the counterparts of L1/R1, as header pills matching Back /
                // Options, centred on each side. Dimmed and inert at the ends. Part of the controls
                // layer, so a tap on the photo hides them with everything else. Only shown with >1 photo.
                if (state.photos.size > 1) {
                    val hasPrev = state.index > 0
                    val hasNext = state.index < state.photos.size - 1
                    XmbHeaderPill(
                        label = "Prev",
                        leadingGlyph = "‹",
                        onClick = { if (hasPrev) { onTouchInput(); viewModel.step(-1) } },
                        background = MediaPillBg,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 16.dp)
                            .alpha(if (hasPrev) 1f else 0.35f),
                    )
                    XmbHeaderPill(
                        label = "Next  ›",
                        onClick = { if (hasNext) { onTouchInput(); viewModel.step(+1) } },
                        background = MediaPillBg,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 16.dp)
                            .alpha(if (hasNext) 1f else 0.35f),
                    )
                }
            }
        }

        // ── Options menu — the shared themed context menu, like every other context menu ──
        if (state.showOptions) {
            DetailContextMenu(
                title = "Options",
                rows = PhotoViewerAction.entries.map { action ->
                    DetailMenuRow(
                        label = action.label,
                        isDestructive = action == PhotoViewerAction.REMOVE,
                    )
                },
                selectedIndex = state.optionsIndex,
                onRowClick = { viewModel.activate(PhotoViewerAction.entries[it]) },
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
                Text("Set as launcher wallpaper?", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("It replaces the XMB wave background. A = Apply · B = Cancel", color = TextMuted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = viewModel::confirmWallpaper, enabled = !state.applyingWallpaper) {
                        Text(if (state.applyingWallpaper) "Applying…" else "Apply", color = menuCursorEdge())
                    }
                    TextButton(onClick = viewModel::cancelWallpaperPreview, enabled = !state.applyingWallpaper) {
                        Text("Cancel", color = TextMuted)
                    }
                }
            }
        }

        // ── Info dialog ──────────────────────────────────────────────────────
        if (state.infoVisible) {
            InfoDialog(photo = photo, onDismiss = { viewModel.handleGamepadAction(GamepadAction.BACK) })
        }

        // ── Remove confirmation ──────────────────────────────────────────────
        if (state.confirmRemove) {
            AlertDialog(
                onDismissRequest = viewModel::cancelRemove,
                confirmButton = { TextButton(onClick = viewModel::confirmRemove) { Text("Remove") } },
                dismissButton = { TextButton(onClick = viewModel::cancelRemove) { Text("Cancel") } },
                title = { Text("Remove from library?") },
                text = { Text("\"${photo.displayName}\" will be removed from this library. The photo on disk is not deleted.") },
            )
        }

        state.actionMessage?.let { msg ->
            Text(
                msg,
                color = TextPrimary,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 60.dp)
                    .background(PanelBg, RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LaunchedEffect(msg) { kotlinx.coroutines.delay(2500); viewModel.dismissMessage() }
        }
    }
}

@Composable
private fun HelpHint(button: String, label: String) {
    Row {
        Text(button, color = menuCursorEdge(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
        Text(label, color = TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun InfoDialog(photo: Photo, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text(photo.displayName) },
        text = {
            Column {
                photo.resolutionLabel?.let { InfoRow("Resolution", it) }
                photo.dateTaken?.let { InfoRow("Taken", fmtDate(it)) }
                photo.lastModified?.let { InfoRow("Modified", fmtDate(it)) }
                photo.sizeBytes?.let { InfoRow("Size", fmtSize(it)) }
                photo.mimeType?.let { InfoRow("Type", it) }
                photo.relativePath?.let { InfoRow("Location", it) }
                InfoRow("File", photo.displayName)
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

private fun fmtDate(ms: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(ms))

private fun fmtSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) "%.1f MB".format(mb) else "%.0f KB".format(bytes / 1024.0)
}
