package com.playfieldportal.studio.ui.sections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.studio.StudioState
import com.playfieldportal.studio.StudioViewModel
import com.playfieldportal.studio.io.FileDialogs
import com.playfieldportal.studio.io.VideoCodecs
import com.playfieldportal.themekit.ThemeMediaSlots
import java.awt.Frame
import java.io.File

private const val BOOT_KEY = "boot_video"
private const val GAMEBOOT_KEY = "gameboot_video"

/** What the Studio could read from a boot clip: the extension and size always, the rest when the header parsed. */
data class BootFileInfo(
    val extension: String,
    val bytes: Long,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
)

/** What one Boot / GameBoot pair of cards shows. [hasVideo] false = the built-in sequence is selected. */
data class BootCard(
    val key: String,
    val title: String,
    val hasVideo: Boolean,
    val fileName: String?,
    val infoLine: String?,
    val limitsLine: String,
    val overCap: Boolean,
)

fun bootCard(key: String, title: String, file: BootFileInfo?): BootCard {
    val slot = ThemeMediaSlots.slot(key)
    val spec = slot?.spec
    val limits = slot?.let {
        "${formatDuration(it.spec.recommendedMinMs).removeSuffix(" s")}–${formatDuration(it.spec.recommendedMaxMs)} recommended, " +
            "${formatDuration(it.spec.hardMaxMs)} max, ${it.maxBytes / (1024 * 1024)} MB max"
    } ?: ""
    if (file == null) return BootCard(key, title, false, null, null, limits, false)
    val name = slot?.entryName(file.extension) ?: "$key.${file.extension.lowercase()}"
    val info = listOfNotNull(
        name,
        file.durationMs?.let(::formatDuration),
        if (file.width != null && file.height != null) "${file.width}×${file.height}" else null,
        sizeText(file.bytes),
    ).joinToString(" · ")
    return BootCard(
        key = key,
        title = title,
        hasVideo = true,
        fileName = name,
        infoLine = info,
        limitsLine = limits,
        overCap = spec != null && file.durationMs != null && file.durationMs > spec.hardMaxMs,
    )
}

/**
 * Boot and GameBoot: each is the built-in sequence or one MP4. [onPlayInPreview] is the hook the
 * live-motion preview (TS-33) plugs into with the slot key and the clip; null leaves the button disabled.
 */
@Composable
fun BootSection(
    state: StudioState,
    viewModel: StudioViewModel,
    window: Frame,
    onPlayInPreview: ((slotKey: String, clip: File) -> Unit)?,
) {
    SectionColumn {
        HeadingWithInfo(
            "Boot sequences",
            "Whether the boot video and GameBoot play at all is a device setting: ${DeviceHints.BOOT}",
        )
        MutedText("Choose the built-in sequence or your own MP4 for each.", 11)
        BootPair(BOOT_KEY, "Boot", state, viewModel, window, onPlayInPreview)
        BootPair(GAMEBOOT_KEY, "GameBoot", state, viewModel, window, onPlayInPreview)
    }
}

@Composable
private fun BootPair(
    key: String,
    title: String,
    state: StudioState,
    viewModel: StudioViewModel,
    window: Frame,
    onPlayInPreview: ((String, File) -> Unit)?,
) {
    val file = state.mediaFiles[key]
    val info = remember(file) { file?.let(::probeBootFile) }
    val card = bootCard(key, title, info)
    SectionHeading(title)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        ChoiceCard(selected = !card.hasVideo, modifier = Modifier.weight(1f)) {
            Text("Built-in", fontSize = 13.sp)
            MutedText("The launcher's own $title sequence.", 11)
            if (card.hasVideo) OutlinedButton(onClick = { viewModel.clearMedia(key) }) { Text("Use built-in", fontSize = 12.sp) }
        }
        ChoiceCard(selected = card.hasVideo, modifier = Modifier.weight(1f)) {
            Text("Video", fontSize = 13.sp)
            if (card.infoLine != null) MutedText(card.infoLine, 11) else MutedText("Your own MP4 (H.264).", 11)
            MutedText(card.limitsLine, 11)
            if (card.overCap) HintText("This clip is longer than the cap — the launcher will refuse it.")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = {
                    FileDialogs.openFile(window, "Choose a $title video", setOf("mp4", "m4v"))?.let { picked ->
                        if (key == BOOT_KEY) viewModel.importBoot(picked) else viewModel.importGameBoot(picked)
                    }
                }) { Text(if (card.hasVideo) "Replace…" else "Choose MP4…", fontSize = 12.sp) }
                if (card.hasVideo) OutlinedButton(onClick = { viewModel.clearMedia(key) }) { Text("Reset", fontSize = 12.sp) }
            }
            if (card.hasVideo && file != null) {
                OutlinedButton(
                    onClick = { onPlayInPreview?.invoke(key, file) },
                    enabled = onPlayInPreview != null,
                ) { Text("Play in preview", fontSize = 12.sp) }
            }
        }
    }
}

/** Header-only read of a staged clip; the Studio gate already vetted it, so a miss just drops the extra detail. */
private fun probeBootFile(file: File): BootFileInfo {
    val probe = VideoCodecs.probe(file)
    return BootFileInfo(file.extension.lowercase(), file.length(), probe?.durationMs, probe?.width, probe?.height)
}

/** A selectable card: the selected one carries a heavier border in the chrome's own colour. */
@Composable
private fun ChoiceCard(selected: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    OutlinedCard(
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(10.dp)) { content() }
    }
}
