package com.playfieldportal.studio.ui.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.studio.CropFrame
import com.playfieldportal.studio.PendingWallpaper
import com.playfieldportal.studio.StudioState
import com.playfieldportal.studio.StudioViewModel
import com.playfieldportal.studio.WallpaperFit
import com.playfieldportal.studio.WallpaperFitStore
import com.playfieldportal.studio.WallpaperPreset
import com.playfieldportal.studio.io.FileDialogs
import com.playfieldportal.themekit.PfpThemeManifest
import java.awt.Frame
import java.util.Locale

/** What the theme's background is made of. */
enum class BackgroundSource(val label: String) {
    WAVE("Wave"),
    IMAGE("Image"),
    VIDEO("Video"),
}

fun backgroundSourceOf(state: StudioState): BackgroundSource = when {
    state.motionFile != null -> BackgroundSource.VIDEO
    state.wallpaperPng != null -> BackgroundSource.IMAGE
    else -> BackgroundSource.WAVE
}

/** One control, three meanings: it drives the wave, the video, or whatever moves behind an image. */
fun motionControlLabel(source: BackgroundSource): String = when (source) {
    BackgroundSource.WAVE -> "Wave motion"
    BackgroundSource.VIDEO -> "Video playback"
    BackgroundSource.IMAGE -> "Background motion"
}

/** (exact wave style, label) in display order — `reduced_static` is a value of its own, not a pair of toggles. */
val MOTION_CHOICES: List<Pair<String, String>> = listOf(
    PfpThemeManifest.WAVE_ANIMATED to "Animated",
    PfpThemeManifest.WAVE_REDUCED to "Reduced",
    PfpThemeManifest.WAVE_STATIC to "Static",
    PfpThemeManifest.WAVE_REDUCED_STATIC to "Reduced + Static",
)

/** "Still: frame at 1.5 s" for a video poster; the file does not record the time, so it can be unknown. */
fun posterLabel(atMs: Long?): String = atMs?.let { "Still: frame at ${formatDuration(it)}" } ?: "Still: baked into the theme"

private val FIT_CHOICES: List<Pair<WallpaperPreset, String>> = listOf(
    WallpaperPreset.PSP to "PSP 480×272",
    WallpaperPreset.HD to "HD 1280×720",
    WallpaperPreset.FULL_HD to "Full HD 1920×1080",
    WallpaperPreset.ORIGINAL to "Original",
)

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "bmp", "webp", "gif")
private val VIDEO_EXTENSIONS = setOf("mp4", "m4v")
// A still: animated containers are left out on purpose (the device lock screen cannot animate).
private val LOCK_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "bmp", "webp")

@Composable
fun BackgroundSection(state: StudioState, viewModel: StudioViewModel, window: Frame) {
    val source = backgroundSourceOf(state)
    val pending = state.pendingWallpaper
    // Starts from the theme's own wallpaper (its size names the fit), else the author's last choice
    // (remembered across sessions), else HD. Re-keyed per document so an opened theme shows its fit.
    val fitStore = remember { WallpaperFitStore() }
    val wallpaperSize = state.wallpaperBitmap?.let { it.width to it.height }
    var fit by remember(wallpaperSize) { mutableStateOf(WallpaperFit.initial(wallpaperSize, fitStore.remembered)) }
    val chooseFit: (WallpaperPreset) -> Unit = { picked ->
        fit = picked
        fitStore.remember(picked)
    }

    fun pickImage() = FileDialogs.openFile(window, "Import wallpaper", IMAGE_EXTENSIONS)?.let(viewModel::stageWallpaper)
    fun pickVideo() = FileDialogs.openFile(window, "Import video", VIDEO_EXTENSIONS)?.let(viewModel::importVideo)
    fun pickLockScreen() = FileDialogs.openFile(window, "Lock screen image", LOCK_IMAGE_EXTENSIONS)?.let(viewModel::setLockScreenImage)

    SectionColumn {
        SectionHeading("Source")
        ChoiceChips(BackgroundSource.entries.map { it.name to it.label }, source.name) { picked ->
            when (BackgroundSource.valueOf(picked)) {
                BackgroundSource.WAVE -> if (source != BackgroundSource.WAVE) viewModel.clearWallpaper()
                // The still stays: a video's poster is a plain image once the video goes.
                BackgroundSource.IMAGE -> if (source == BackgroundSource.VIDEO) viewModel.clearMotion() else pickImage()
                BackgroundSource.VIDEO -> pickVideo()
            }
        }
        MutedText(
            state.wallpaperFileName?.let { if (source == BackgroundSource.VIDEO) state.motionFileName ?: it else it }
                ?: if (state.wallpaperPng != null) "(embedded image)" else "None — live wave background",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (source) {
                BackgroundSource.WAVE -> Unit
                BackgroundSource.IMAGE -> if (pending == null) {
                    OutlinedButton(onClick = { pickImage() }) { Text("Replace…") }
                    OutlinedButton(onClick = viewModel::restageEmbeddedWallpaper) { Text("Re-crop…") }
                    OutlinedButton(onClick = viewModel::clearWallpaper) { Text("Clear") }
                }
                BackgroundSource.VIDEO -> if (pending == null) {
                    OutlinedButton(onClick = { pickVideo() }) { Text("Replace…") }
                    OutlinedButton(onClick = viewModel::restageVideoFrame) { Text("Re-crop…") }
                    OutlinedButton(onClick = viewModel::clearMotion) { Text("Clear video") }
                }
            }
        }

        if (pending != null) {
            HorizontalDivider()
            CropEditor(pending, fit, onFit = chooseFit, viewModel)
        } else if (source != BackgroundSource.WAVE) {
            HorizontalDivider()
            SectionHeading("Fit")
            ChoiceChips(FIT_CHOICES.map { it.first.name to it.second }, fit.name) { picked ->
                chooseFit(WallpaperPreset.valueOf(picked))
                if (source == BackgroundSource.VIDEO) viewModel.restageVideoFrame() else viewModel.restageEmbeddedWallpaper()
            }
            if (source == BackgroundSource.VIDEO) {
                MutedText(posterLabel(state.posterAtMs), 12)
                TextButton(onClick = viewModel::restageVideoFrame) { Text("Pick frame…", fontSize = 12.sp) }
            }
        }
        if (state.wallpaperBusy) {
            HintText("Busy wallpaper — labels may be hard to read. Softer, low-contrast images work best.")
        }

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionHeading(motionControlLabel(source))
            InfoGlyph(DeviceHints.WAVE_FREEZE)
        }
        ChoiceChips(MOTION_CHOICES, state.waveStyle) { if (it != state.waveStyle) viewModel.setWaveStyle(it) }
        MutedText("The preview always shows a frozen wave; the style plays on the device.", 11)
        if (source == BackgroundSource.VIDEO) {
            // The still IS the video's poster: whenever playback is frozen on device the launcher shows it.
            // Without the hint, authors read the frozen poster as the video being broken.
            HintText("The still is the video's poster — it shows whenever playback is frozen (battery saver, a game, or a Static style).")
        }

        HorizontalDivider()
        SectionHeading("Lock screen")
        MutedText(
            if (state.lockScreenPng != null) "The theme offers this image for the device lock screen"
            else "None — the theme leaves the device lock screen alone",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { pickLockScreen() }) { Text(if (state.lockScreenPng != null) "Replace…" else "Choose Image…") }
            if (state.wallpaperPng != null) {
                OutlinedButton(onClick = viewModel::useWallpaperForLockScreen) {
                    Text(if (source == BackgroundSource.VIDEO) "Use Poster" else "Use Wallpaper")
                }
            }
            if (state.lockScreenPng != null) OutlinedButton(onClick = viewModel::clearLockScreen) { Text("Clear") }
        }
        HintText("A still only. The device crops it to its own screen, and only sets it when the user agrees on apply.")
    }
}

/** The crop step: fit chips, the frame over the source, zoom / center / reset, and the poster frame for a video. */
@Composable
private fun CropEditor(
    pending: PendingWallpaper,
    fit: WallpaperPreset,
    onFit: (WallpaperPreset) -> Unit,
    viewModel: StudioViewModel,
) {
    val w = pending.source.width
    val h = pending.source.height
    // Keyed on the source's identity but NOT its pixels: picking another poster frame keeps the crop.
    var frame by remember(pending.fileName, pending.reframesVideo, w, h) {
        mutableStateOf(
            pending.initialCrop?.let { CropFrame.fromMotionCrop(w, h, fit, it) } ?: CropFrame.centered(w, h, fit),
        )
    }
    SectionHeading("Crop")
    MutedText("${pending.fileName} — ${w}×${h}", 11)
    ChoiceChips(FIT_CHOICES.map { it.first.name to it.second }, frame.fit.name) { picked ->
        val next = WallpaperPreset.valueOf(picked)
        onFit(next)
        frame = frame.withFit(next)
    }
    pending.thumbnail?.let { image ->
        CropFrameView(
            image = image,
            sourceW = w,
            sourceH = h,
            frame = frame,
            onFrame = { frame = it },
            modifier = Modifier.fillMaxWidth().height(220.dp),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Zoom", fontSize = 12.sp)
        Slider(
            value = cropZoomFraction(frame),
            onValueChange = { frame = cropWithZoomFraction(frame, it) },
            modifier = Modifier.weight(1f),
        )
    }
    val (outW, outH) = frame.outputSize()
    MutedText("Output ${outW}×${outH}", 11)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { frame = frame.centeredInSource() }) { Text("Center") }
        OutlinedButton(onClick = { frame = frame.reset() }) { Text("Reset") }
    }

    pending.posterAtMs?.let { atMs ->
        PosterFramePicker(atMs, pending.videoDurationMs, viewModel::pickPosterFrame)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { viewModel.confirmWallpaperCrop(frame) }) { Text("Apply") }
        TextButton(onClick = viewModel::cancelWallpaperImport) { Text("Cancel") }
    }
}

/** "Still: frame at …" with a scrubber over the clip; the frame is fetched when the thumb is released. */
@Composable
private fun PosterFramePicker(atMs: Long, durationMs: Long?, onPick: (Long) -> Unit) {
    MutedText(posterLabel(atMs), 12)
    val length = durationMs?.takeIf { it > 0 } ?: return
    var scrub by remember(atMs) { mutableStateOf(atMs.toFloat() / length) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Pick frame…", fontSize = 12.sp)
        Slider(
            value = scrub,
            onValueChange = { scrub = it },
            onValueChangeFinished = { onPick((scrub * length).toLong().coerceIn(0, length)) },
            modifier = Modifier.weight(1f),
        )
        Text(String.format(Locale.ROOT, "%.1f s", scrub * length / 1000f), fontSize = 11.sp)
    }
}
