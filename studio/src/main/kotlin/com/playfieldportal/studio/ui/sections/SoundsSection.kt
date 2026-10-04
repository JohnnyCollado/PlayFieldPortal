package com.playfieldportal.studio.ui.sections

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.studio.StudioState
import com.playfieldportal.studio.StudioViewModel
import com.playfieldportal.studio.io.FileDialogs
import com.playfieldportal.studio.io.SoundPlayer
import com.playfieldportal.themekit.MediaDurationProbe
import com.playfieldportal.themekit.ThemeMediaSlots
import com.playfieldportal.themekit.UiMediaLimits
import java.awt.Frame
import java.util.Locale

private const val AMBIENCE_KEY = "ambience_audio"

/** (media slot key, label) in display order: the five menu sounds, then the looping ambience. */
val SOUND_ROWS: List<Pair<String, String>> = listOf(
    "sound_scroll" to "Navigation",
    "sound_back" to "Back / cancel",
    "sound_confirm" to "Confirm / apply",
    "sound_error" to "Error / invalid",
    "sound_notification" to "Notification",
    AMBIENCE_KEY to "Ambience (loops)",
)

/** Every format a theme sound slot takes can be auditioned here ([SoundPlayer] decodes through FFmpeg). */
const val PLAYS_ON_DEVICE = "Plays on the device"

/** What one sound row shows. [extension] null means the slot is empty and the built-in sound is used. */
data class SoundRow(
    val key: String,
    val label: String,
    val builtIn: Boolean,
    val fileName: String?,
    /** Clip length as a share of the slot's cap, 0..1 (full when over it, empty when unknown). */
    val fraction: Float,
    val lengthLine: String,
    val overCap: Boolean,
    val canPlay: Boolean,
    /** Tooltip for a play button that is disabled because the format can't play here; else null. */
    val playHint: String?,
)

fun soundRow(key: String, label: String, extension: String?, lengthMs: Long?): SoundRow {
    val slot = ThemeMediaSlots.slot(key)
    val cap = slot?.spec?.hardMaxMs ?: 0L
    if (extension == null) {
        return SoundRow(key, label, true, null, 0f, if (cap > 0) "Up to ${formatDuration(cap)}" else "", false, false, null)
    }
    val ext = extension.lowercase()
    val playable = ext in ThemeMediaSlots.AUDIO_EXTENSIONS
    return SoundRow(
        key = key,
        label = label,
        builtIn = false,
        fileName = (slot?.entryName(ext) ?: "$key.$ext").substringAfterLast('/'),
        fraction = if (lengthMs == null || cap <= 0) 0f else (lengthMs.toFloat() / cap).coerceIn(0f, 1f),
        lengthLine = lengthMs?.let { "${formatDuration(it)} of ${formatDuration(cap)}" } ?: "Length unknown",
        overCap = lengthMs != null && cap > 0 && lengthMs > cap,
        canPlay = playable,
        playHint = PLAYS_ON_DEVICE.takeUnless { playable },
    )
}

/** "0.25 s", "1.5 s", "10 s", "10 min" — whole minutes only switch to minutes. */
fun formatDuration(ms: Long): String = when {
    ms >= 60_000 && ms % 60_000 == 0L -> "${ms / 60_000} min"
    ms % 1000 == 0L -> "${ms / 1000} s"
    else -> String.format(Locale.ROOT, "%.2f", ms / 1000.0).trimEnd('0').trimEnd('.') + " s"
}

/** "512 B", "2 KB", "1.4 MB". */
fun sizeText(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
    else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
}

@Composable
fun SoundsSection(state: StudioState, viewModel: StudioViewModel, window: Frame) {
    var playing by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    // Leaving the section (or closing the window) must not leave a looping ambience running.
    DisposableEffect(Unit) { onDispose { SoundPlayer.stop() } }

    SectionColumn {
        HeadingWithInfo("Menu sounds", "Volumes stay on the device: ${DeviceHints.SOUND}")
        MutedText("Anything you leave empty keeps the built-in sound. Clips must fit each row's cap.", 11)
        SOUND_ROWS.forEach { (key, label) ->
            val file = state.mediaFiles[key]
            val extension = file?.extension?.lowercase()
            val lengthMs = remember(file) {
                file?.let { MediaDurationProbe.durationMs(it, UiMediaLimits.mimeForExtension(it.extension)) }
            }
            val row = soundRow(key, label, extension, lengthMs)
            SoundRowCard(
                row = row,
                playing = playing == key,
                onPlay = {
                    if (file == null) return@SoundRowCard
                    note = null
                    val started = SoundPlayer.play(file, loop = key == AMBIENCE_KEY, onStopped = {
                        if (playing == key) playing = null
                    })
                    if (started) playing = key else note = "Couldn't play ${row.fileName} here — it will play on the device."
                },
                onStop = { SoundPlayer.stop() },
                onPick = {
                    FileDialogs.openFile(window, "Choose a sound for $label", ThemeMediaSlots.AUDIO_EXTENSIONS)?.let { picked ->
                        SoundPlayer.stop()
                        if (key == AMBIENCE_KEY) viewModel.importAmbience(picked) else viewModel.importSound(key, picked)
                    }
                },
                onReset = {
                    SoundPlayer.stop()
                    viewModel.clearMedia(key)
                },
            )
        }
        note?.let { HintText(it) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SoundRowCard(
    row: SoundRow,
    playing: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onPick: () -> Unit,
    onReset: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.label, fontSize = 13.sp)
                MutedText(row.fileName ?: "Built-in", 11)
            }
            when {
                row.builtIn -> Unit
                row.canPlay -> TextButton(onClick = if (playing) onStop else onPlay) {
                    Text(if (playing) "■ Stop" else "▶ Play", fontSize = 12.sp)
                }
                else -> TooltipArea(
                    tooltip = {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shadowElevation = 4.dp,
                        ) {
                            Text(row.playHint ?: "", fontSize = 12.sp, modifier = Modifier.padding(8.dp).width(160.dp))
                        }
                    },
                ) {
                    TextButton(onClick = {}, enabled = false) { Text("▶ Play", fontSize = 12.sp) }
                }
            }
        }
        LinearProgressIndicator(
            progress = { row.fraction },
            color = if (row.overCap) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
        )
        MutedText(row.lengthLine, 11)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPick) { Text(if (row.builtIn) "Choose…" else "Replace…", fontSize = 12.sp) }
            if (!row.builtIn) OutlinedButton(onClick = onReset) { Text("Reset", fontSize = 12.sp) }
        }
    }
}
