package com.playfieldportal.feature.xmb.ui.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.Video
import com.playfieldportal.feature.xmb.video.VideoPlayerScreen

private val PageBg = Color(0xFF06060C)
private val Accent = Color(0xFF4A90D9)
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
    modifier: Modifier = Modifier,
    viewModel: VideoDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    val thumbnailPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.onThumbnailPicked(uri) }

    LaunchedEffect(videoId) { viewModel.loadVideo(videoId) }
    LaunchedEffect(state.closed) { if (state.closed) onBack() }
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

    if (state.isLoading) {
        Box(modifier.fillMaxSize().background(PageBg)) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
        }
        return
    }
    val video = state.video ?: run { onBack(); return }

    Box(modifier = modifier.fillMaxSize().background(PageBg)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Accent.copy(alpha = 0.14f), PageBg))))

        // Thumbnail banner
        video.effectiveThumbnailUri?.let { thumb ->
            AsyncImage(
                model = thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(260.dp).align(Alignment.TopCenter),
            )
            Box(
                Modifier.fillMaxWidth().height(260.dp).align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, PageBg))),
            )
        }

        Column(
            modifier = Modifier.fillMaxSize().widthIn(max = 920.dp).align(Alignment.Center)
                .padding(horizontal = 28.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(150.dp))

            Text(video.displayTitle, color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(metadataLine(video), color = TextMuted, fontSize = 13.sp)
            if (video.resumePositionMs > 0) {
                Text("Resume at ${fmtTime(video.resumePositionMs)}", color = Accent, fontSize = 12.sp)
            }

            Spacer(Modifier.height(6.dp))

            val primaries = state.primaryActions
            primaries.forEachIndexed { i, action ->
                DetailButton(
                    label = action.label,
                    symbol = if (action == VideoDetailAction.RESUME) "↺" else "▶",
                    focused = state.mainFocus == i,
                    fill = if (action == VideoDetailAction.PLAY) PlayGreen else ActionFill,
                    textColor = if (action == VideoDetailAction.PLAY) Color(0xFF06140A) else TextPrimary,
                    onClick = { viewModel.activate(action) },
                )
            }
            DetailButton(
                label = "Options",
                symbol = "⚙",
                focused = state.mainFocus == primaries.size,
                fill = ActionFill,
                textColor = TextPrimary,
                onClick = viewModel::openOptions,
            )
            state.actionMessage?.let {
                Text(it, color = Accent, fontSize = 12.sp)
                LaunchedEffect(it) { kotlinx.coroutines.delay(2500); viewModel.dismissMessage() }
            }
        }

        if (state.showOptions) {
            OptionsList(
                actions = state.optionsActions,
                selectedIndex = state.optionsIndex,
                onClick = viewModel::activate,
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
private fun DetailButton(
    label: String,
    symbol: String,
    focused: Boolean,
    fill: Color,
    textColor: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) fill else fill.copy(alpha = 0.55f))
            .then(if (focused) Modifier.border(2.dp, Color(0xFF8F7CFF), RoundedCornerShape(12.dp)) else Modifier)
            .padding(vertical = 14.dp, horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text("$symbol  $label", color = textColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun OptionsList(
    actions: List<VideoDetailAction>,
    selectedIndex: Int,
    onClick: (VideoDetailAction) -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(
            modifier = Modifier.padding(36.dp).width(300.dp)
                .background(Color(0xF0101018), RoundedCornerShape(14.dp)).padding(vertical = 12.dp),
        ) {
            Text("Options", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            actions.forEachIndexed { i, action ->
                Text(
                    action.label,
                    color = if (i == selectedIndex) Color.White else TextMuted,
                    fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth()
                        .background(if (i == selectedIndex) Color(0x334A90D9) else Color.Transparent)
                        .padding(horizontal = 20.dp, vertical = 11.dp),
                )
            }
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
) {
    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = { TextButton(onClick = onConfirm) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        title = { Text("Rename Title") },
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
