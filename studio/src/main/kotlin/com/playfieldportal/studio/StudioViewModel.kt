package com.playfieldportal.studio

import androidx.compose.ui.graphics.ImageBitmap
import com.playfieldportal.studio.io.ConvertOutcome
import com.playfieldportal.studio.io.ImageCodecs
import com.playfieldportal.studio.io.PtfConversion
import com.playfieldportal.studio.io.VideoCodecs
import com.playfieldportal.themekit.IconGifSupport
import com.playfieldportal.themekit.IconSlots
import com.playfieldportal.themekit.MotionLimits
import com.playfieldportal.themekit.XmbLayoutSpecCodec
import com.playfieldportal.themekit.PfpThemeBundle
import com.playfieldportal.themekit.PfpThemeCodec
import com.playfieldportal.themekit.PfpThemeManifest
import com.playfieldportal.themekit.PfpThemeSource
import com.playfieldportal.themekit.ThemeImage
import com.playfieldportal.themekit.ThemeMotion
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Unified icon color: derive from the theme (white for now) or an explicit override. */
sealed interface IconColorChoice {
    data object Auto : IconColorChoice
    data class Custom(val argb: Int) : IconColorChoice
}

/** Text colour, mirroring [IconColorChoice]: Auto = inherit the theme's own (white). */
sealed interface TextColorChoice {
    data object Auto : TextColorChoice
    data class Custom(val argb: Int) : TextColorChoice
}

/** Which XMB surface the preview canvas renders. */
enum class PreviewMode(val label: String) {
    HOME("Home"),
    CONTEXT_MENU("Menu"),
    FULLSCREEN_MENU("Fullscreen"),
}

/** Wallpaper crop/scale presets offered at import time. */
enum class WallpaperPreset(val label: String, val width: Int, val height: Int) {
    PSP("PSP (480×272)", 480, 272),
    HD("HD (1280×720)", 1280, 720),
    FULL_HD("Full HD (1920×1080)", 1920, 1080),
    ORIGINAL("Keep original", 0, 0),
}

/** A chosen wallpaper waiting for the user to pick a crop preset. */
data class PendingWallpaper(
    val source: java.awt.image.BufferedImage,
    val fileName: String,
    val thumbnail: androidx.compose.ui.graphics.ImageBitmap?,
)

/** Modal feedback the shell renders as dialogs. */
sealed interface StudioDialog {
    /** `.ctf`/CXMB rejection with the "why" — these replace PSP firmware files, not themes. */
    data object CxmbRejected : StudioDialog
    data class Error(val message: String) : StudioDialog

    /** Non-fatal heads-up (e.g. a PTF imported but its wallpaper couldn't be extracted). */
    data class Notice(val title: String, val message: String) : StudioDialog
    data class BatchDone(val summary: com.playfieldportal.studio.io.BatchSummary) : StudioDialog
}

data class StudioState(
    val name: String = "Untitled Theme",
    val accentArgb: Int = PtfConversion.DEFAULT_ACCENT,
    val iconColor: IconColorChoice = IconColorChoice.Auto,
    val textColor: TextColorChoice = TextColorChoice.Auto,
    val waveStyle: String = PfpThemeManifest.WAVE_ANIMATED,
    val wallpaperPng: ByteArray? = null,
    val wallpaperBitmap: ImageBitmap? = null,
    val wallpaperFileName: String? = null,
    /** True when the wallpaper's label band is busy enough to threaten legibility. */
    val wallpaperBusy: Boolean = false,
    /** Wallpaper chosen but not yet cropped — drives the crop-preset dialog. */
    val pendingWallpaper: PendingWallpaper? = null,
    /**
     * Accepted motion video, held as a scratch temp file — never bytes (a 60 MB video on the
     * heap is exactly what [com.playfieldportal.themekit.ThemeMotion] exists to prevent), and
     * never the user's own path (they may move or delete it before export).
     */
    val motionFile: File? = null,
    /** The video's original file name, for the inspector row. */
    val motionFileName: String? = null,
    /**
     * Per-theme XMB geometry. The full spec is carried (not just the fields the UI edits)
     * so opened manifests round-trip hand-authored fields untouched.
     */
    val layout: com.playfieldportal.themekit.XmbLayoutSpec = com.playfieldportal.themekit.XmbLayoutSpec.DEFAULT,
    /** Which surface the preview shows — accent changes tint menus too. */
    val previewMode: PreviewMode = PreviewMode.HOME,
    /** Custom icon slots: IconSlots key → encoded bytes (what exports) ... */
    val iconOverrides: Map<String, ByteArray> = emptyMap(),
    /** ... the extension each entry ships as ("png" stills, "gif" animations) — parallel to [iconOverrides]. */
    val iconExtensions: Map<String, String> = emptyMap(),
    /** ... and the decoded bitmaps the preview/editor draw. Kept in lockstep with [iconOverrides]. */
    val iconBitmaps: Map<String, ImageBitmap> = emptyMap(),
    val source: PfpThemeSource? = null,
    val busy: Boolean = false,
    val statusMessage: String? = null,
    val dialog: StudioDialog? = null,
    /** Non-null while a batch conversion runs. */
    val batchProgress: com.playfieldportal.studio.io.BatchProgress? = null,
) {
    // ByteArray fields: identity equality is fine — state copies share the arrays.
}

/**
 * The Studio's single state holder. Plain class + StateFlow (no DI, no platform ViewModel):
 * constructed once in Main with an app-lifetime scope; IO always hops to [Dispatchers.IO].
 */
class StudioViewModel(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(StudioState())
    val state: StateFlow<StudioState> = _state.asStateFlow()

    /** A video staged behind an open crop dialog, and its original file name. Deliberately
     * not [StudioState] — it becomes state exactly when the crop is confirmed. */
    private var pendingMotion: File? = null
    private var pendingMotionName: String? = null

    // ── Motion scratch-file lifecycle ────────────────────────────────────────
    // The motion video lives in a temp file for as long as the edit session holds it. Every
    // path that rebuilds StudioState wholesale (newTheme, hydrate) or drops motion explicitly
    // must come through one of these, or the temp file outlives the edit — which is how
    // "imported twice, cleared, then New" would leak a 60 MB file per step.

    /** Moves [source] to a private scratch copy; the caller's original is never referenced again. */
    private fun scratchMotion(source: File): File {
        val scratch = File.createTempFile("studio-motion-", ".${source.extension.lowercase()}")
        source.copyTo(scratch, overwrite = true)
        scratch.deleteOnExit()
        return scratch
    }

    /** Deletes the current scratch video, if any. Safe to call repeatedly. */
    private fun discardMotion(state: StudioState) {
        state.motionFile?.delete()
    }

    /**
     * Clears motion and deletes its scratch file in one step, keeping the still wallpaper —
     * the inverse of the poster rule: a still without motion is a plain valid theme. Internal
     * so tests can drive the lifecycle without going through the UI.
     */
    internal fun clearMotion() {
        discardMotion(_state.value)
        _state.update { it.copy(motionFile = null, motionFileName = null) }
    }

    // ── Simple edits ─────────────────────────────────────────────────────────

    /** The from-scratch start point: what the Studio opens on and what New resets to. */
    fun newTheme() {
        abandonPendingMotion()
        discardMotion(_state.value)
        _state.update { StudioState() }
    }

    fun setName(name: String) = _state.update { it.copy(name = name) }
    fun setAccent(argb: Int) = _state.update { it.copy(accentArgb = argb) }
    fun setIconColor(choice: IconColorChoice) = _state.update { it.copy(iconColor = choice) }
    fun setTextColor(choice: TextColorChoice) = _state.update { it.copy(textColor = choice) }
    fun setWaveStyle(style: String) = _state.update { it.copy(waveStyle = style) }
    fun setPreviewMode(mode: PreviewMode) = _state.update { it.copy(previewMode = mode) }
    fun dismissDialog() = _state.update { it.copy(dialog = null) }
    fun clearStatus() = _state.update { it.copy(statusMessage = null) }

    // ── Layout (fit the crossbar to the wallpaper) ───────────────────────────

    fun setBarTopFraction(fraction: Float) = _state.update {
        it.copy(
            layout = it.layout.copy(
                barTopFraction = fraction.coerceIn(
                    XmbLayoutSpecCodec.BAR_TOP_MIN,
                    XmbLayoutSpecCodec.BAR_TOP_MAX,
                ),
            ),
        )
    }

    fun resetLayout() = _state.update { it.copy(layout = com.playfieldportal.themekit.XmbLayoutSpec.DEFAULT) }

    /** Alignment assist: find the wallpaper's baked-in cross-band and prefill the slider. */
    fun detectBarTop() = runBusy {
        val png = _state.value.wallpaperPng ?: return@runBusy
        val image = ImageCodecs.decodeImage(png) ?: return@runBusy
        // Fractions are scale-invariant, so the bounded accent-sampling copy is plenty.
        val detected = com.playfieldportal.themekit.CrossBandDetector.detectBarTopFraction(
            ImageCodecs.toBmpImage(image),
        )
        if (detected != null) {
            _state.update {
                it.copy(
                    layout = it.layout.copy(barTopFraction = detected),
                    statusMessage = "Crossbar detected at ${(detected * 100).toInt()}% of the wallpaper",
                )
            }
        } else {
            _state.update { it.copy(statusMessage = "No crossbar band found in this wallpaper") }
        }
    }

    // ── Open / import ────────────────────────────────────────────────────────

    /** Dispatches on extension: `.ptf` converts, `.pfptheme` hydrates. */
    fun openFile(file: File) {
        when (file.extension.lowercase()) {
            "ptf", "ctf" -> openPtf(file)
            PfpThemeCodec.FILE_EXTENSION -> openPfpTheme(file)
            else -> _state.update { it.copy(dialog = StudioDialog.Error("Unsupported file type: .${file.extension}")) }
        }
    }

    private fun openPtf(file: File) = runBusy {
        val bytes = com.playfieldportal.studio.io.SafeIo.readBytesCapped(file)
        if (bytes == null) {
            _state.update { it.copy(dialog = StudioDialog.Error("${file.name} is too large to be a theme file")) }
            return@runBusy
        }
        when (val outcome = PtfConversion.convert(bytes, file.name)) {
            is ConvertOutcome.Converted -> {
                hydrate(outcome.bundle, "Imported ${file.name}")
                outcome.warning?.let { warning ->
                    _state.update { it.copy(dialog = StudioDialog.Notice("Imported with a caveat", warning)) }
                }
            }
            ConvertOutcome.Cxmb -> _state.update { it.copy(dialog = StudioDialog.CxmbRejected) }
            is ConvertOutcome.Failed -> _state.update {
                it.copy(dialog = StudioDialog.Error("${file.name}: ${outcome.reason}"))
            }
        }
    }

    private fun openPfpTheme(file: File) = runBusy {
        // Read from the FILE, not capped bytes: MotionLimits.MAX_BYTES alone is 60 MB, so a
        // legitimate motion theme is bigger than any in-memory cap a theme needs. This overload
        // also leaves the motion entry on disk — it re-streams from the zip instead of being
        // inflated into a ByteArray, the same reason PfpThemeStore reads this way.
        val bundle = PfpThemeCodec.read(file)
        if (bundle == null) {
            _state.update { it.copy(dialog = StudioDialog.Error("${file.name} is not a valid .pfptheme bundle")) }
        } else {
            hydrate(bundle, "Opened ${file.name}")
        }
    }

    private fun hydrate(bundle: PfpThemeBundle, status: String) {
        val manifest = bundle.manifest
        val iconBitmaps = bundle.icons.mapNotNull { (key, png) ->
            ImageCodecs.toImageBitmap(png.bytes)?.let { key to it }
        }.toMap()
        val wallpaperBusy = bundle.wallpaper
            ?.let(ImageCodecs::decodeImage)
            ?.let { com.playfieldportal.themekit.WallpaperMetrics.isBusy(ImageCodecs.toBmpImage(it)) }
            ?: false
        // Opening a motion theme must keep the motion entry: spilling it to a scratch file
        // lands it in motionFile like any import, so re-export preserves it. Dropping it here
        // would strip the video from an opened theme on save — the same bug class the icon
        // comment below warns about. Any scratch file the OUTGOING state holds is dead once
        // the whole state is replaced — and so is a video waiting behind an open crop dialog.
        abandonPendingMotion()
        discardMotion(_state.value)
        var motionSpillError: String? = null
        val motionFile = bundle.motion?.let { motion ->
            runCatching {
                val scratch = File.createTempFile("studio-motion-", ".${motion.extension}")
                scratch.outputStream().use { motion.copyTo(it) }
                scratch.deleteOnExit()
                scratch
            }.onFailure { e -> motionSpillError = e.message }.getOrNull()
        }
        _state.update {
            StudioState(
                name = manifest.name,
                accentArgb = PtfConversion.parseHexRgb(manifest.accentColor) ?: PtfConversion.DEFAULT_ACCENT,
                iconColor = manifest.iconColor
                    .takeIf { c -> c != PfpThemeManifest.ICON_COLOR_AUTO }
                    ?.let { c -> PtfConversion.parseHexRgb(c) }
                    ?.let { argb -> IconColorChoice.Custom(argb) }
                    ?: IconColorChoice.Auto,
                textColor = manifest.textColor
                    .takeIf { c -> c != PfpThemeManifest.ICON_COLOR_AUTO }
                    ?.let { c -> PtfConversion.parseHexRgb(c) }
                    ?.let { argb -> TextColorChoice.Custom(argb) }
                    ?: TextColorChoice.Auto,
                waveStyle = manifest.waveStyle,
                wallpaperPng = bundle.wallpaper,
                wallpaperBitmap = bundle.wallpaper?.let(ImageCodecs::toImageBitmap),
                wallpaperFileName = manifest.source?.file,
                // Keep ALL icon bytes even when a thumbnail fails to decode — a bad preview
                // must not silently strip the icon from the theme on re-export. Each entry
                // keeps the extension it shipped with (png stills, gif animations), so an
                // opened animated icon re-exports animated.
                iconOverrides = bundle.icons.mapValues { (_, image) -> image.bytes },
                iconExtensions = bundle.icons.mapValues { (_, image) -> image.extension.lowercase() },
                iconBitmaps = iconBitmaps,
                wallpaperBusy = wallpaperBusy,
                motionFile = motionFile,
                // The bundle records only the entry's extension (motion.mp4), not the author's
                // original filename — show the entry name rather than inventing one.
                motionFileName = motionFile?.let { "motion.${it.extension}" },
                layout = manifest.layout?.let(XmbLayoutSpecCodec::sanitize)
                    ?: com.playfieldportal.themekit.XmbLayoutSpec.DEFAULT,
                source = manifest.source,
                statusMessage = status,
            )
        }
        // Surface a failed motion spill AFTER the state lands — the theme still opens, but the
        // author must know their video won't survive a re-export.
        motionSpillError?.let { message ->
            _state.update {
                it.copy(dialog = StudioDialog.Notice("Motion wallpaper not kept", message))
            }
        }
    }

    /**
     * Abandons a video waiting behind an open crop dialog — any other staging action (a plain
     * image import, a re-crop) replaces that dialog wholesale, which is a cancel of the video.
     */
    private fun abandonPendingMotion() {
        pendingMotion?.delete()
        pendingMotion = null
        pendingMotionName = null
    }

    /**
     * Extensions that belong to the MOTION flow, not the still flow. Routed here because the
     * launcher's Display settings invite "an image or a short video" — a video picked at a
     * wallpaper picker must enter the motion gate (which rejects with a reason it names),
     * never die as "not a readable image". GIF/WebP are deliberately NOT routed: the Studio
     * authors them as stills (ImageIO frame 1), and animated-GIF motion authoring is a
     * recorded follow-up — rejecting them here would break wallpapers that already work.
     */
    private val MOTION_PICK_EXTENSIONS = setOf("mp4", "m4v", "webm")

    /**
     * Single entry point for every wallpaper-OR-motion pick: dispatches on extension. The
     * launcher routes GIF/WebP/MP4/WebM to the motion path at import; the Studio mirrors that
     * so the same file behaves the same on both sides. Only true stills (PNG/JPG/BMP) stay on
     * the plain wallpaper flow.
     */
    fun onWallpaperPicked(file: File) {
        if (file.extension.lowercase() in MOTION_PICK_EXTENSIONS) importVideo(file) else stageWallpaper(file)
    }

    /** Step 1 of wallpaper import: load the file and open the crop-preset dialog. */
    fun stageWallpaper(file: File) = runBusy {
        val image = ImageCodecs.loadImage(file)
        if (image == null) {
            _state.update { it.copy(dialog = StudioDialog.Error("${file.name} is not a readable image")) }
            return@runBusy
        }
        abandonPendingMotion()
        stage(image, file.name)
    }

    /** Re-crop the wallpaper already embedded in the theme. */
    fun restageEmbeddedWallpaper() = runBusy {
        val current = _state.value
        val image = current.wallpaperPng?.let(ImageCodecs::decodeImage) ?: return@runBusy
        abandonPendingMotion()
        stage(image, current.wallpaperFileName ?: "wallpaper")
    }

    /**
     * Motion import: validate the video, then offer its FIRST FRAME as the still wallpaper
     * through the ordinary crop flow. The launcher requires motion to have a poster —
     * `PfpThemeStore.apply()` maps the bundle's still wallpaper onto the poster key — so the
     * video's frame 1 becomes that still, and the author picks its crop like any wallpaper.
     *
     * [motionFile] is only set when the crop is CONFIRMED ([confirmWallpaper]): staging must
     * stay cancel-able, and cancelling with motion already set would leave motion running
     * ahead of a poster that hasn't been chosen — the invalid state the poster rule exists
     * to prevent.
     */
    fun importVideo(file: File) = runBusy {
        when (val outcome = VideoCodecs.accept(file)) {
            is VideoCodecs.Outcome.Rejected ->
                // The strings are written for the author — surface them verbatim.
                _state.update { it.copy(dialog = StudioDialog.Error(outcome.message)) }

            is VideoCodecs.Outcome.Accepted -> {
                val scratch = scratchMotion(file)
                // This import REPLACES any video the session already holds — both a confirmed
                // one and one pending behind an open crop dialog — and state stops referencing
                // the old video at stage time, so whatever happens next (cancel, confirm, or
                // importing yet another video) the scratch that dies is the unreferenced one.
                discardMotion(_state.value)
                pendingMotion?.delete()
                pendingMotion = scratch
                pendingMotionName = file.name
                stage(outcome.poster, file.name)
                _state.update { it.copy(motionFile = null, motionFileName = null) }
            }
        }
    }

    private fun stage(image: java.awt.image.BufferedImage, name: String) {
        _state.update {
            it.copy(
                pendingWallpaper = PendingWallpaper(
                    source = image,
                    fileName = name,
                    thumbnail = ImageCodecs.toImageBitmap(ImageCodecs.toPngBytes(ImageCodecs.thumbnail(image, 320))),
                ),
            )
        }
    }

    fun cancelWallpaperImport() {
        // A video staged with its poster frame dies with the cancel — otherwise the next
        // confirmWallpaper would attach a video the author believes they declined.
        abandonPendingMotion()
        _state.update { it.copy(pendingWallpaper = null) }
    }

    /** Step 2: crop/scale to the chosen preset, then derive accent + legibility hint. */
    fun confirmWallpaper(preset: WallpaperPreset) = runBusy {
        val pending = _state.value.pendingWallpaper ?: return@runBusy
        val image = if (preset == WallpaperPreset.ORIGINAL) pending.source
        else ImageCodecs.centerCropScale(pending.source, preset.width, preset.height)
        val png = ImageCodecs.toPngBytes(image)
        val bitmap = ImageCodecs.toImageBitmap(png)
        val bmp = ImageCodecs.toBmpImage(image)
        // A fresh wallpaper usually wants a matching accent — pre-fill from its dominant
        // hue exactly like Quick Create, keeping the user's step optional.
        val derived = com.playfieldportal.themekit.AccentDeriver.deriveAccent(bmp)
        // Attach the staged video exactly when its poster is confirmed; the name was stashed
        // alongside it because pendingMotion is deliberately not state.
        val stagedVideo = pendingMotion
        val stagedName = pendingMotionName
        pendingMotion = null
        pendingMotionName = null
        _state.update {
            it.copy(
                pendingWallpaper = null,
                wallpaperPng = png,
                wallpaperBitmap = bitmap,
                wallpaperFileName = pending.fileName,
                wallpaperBusy = com.playfieldportal.themekit.WallpaperMetrics.isBusy(bmp),
                motionFile = stagedVideo ?: it.motionFile,
                motionFileName = stagedName ?: it.motionFileName,
                accentArgb = derived ?: it.accentArgb,
                statusMessage = "Wallpaper: ${pending.fileName} (${image.width}×${image.height})",
            )
        }
    }

    fun clearWallpaper() {
        // Motion rides with the still: a bundle with motion and no wallpaper is invalid
        // (motion's poster IS the still), so clearing the wallpaper clears the video too.
        discardMotion(_state.value)
        _state.update {
            it.copy(
                wallpaperPng = null,
                wallpaperBitmap = null,
                wallpaperFileName = null,
                wallpaperBusy = false,
                motionFile = null,
                motionFileName = null,
            )
        }
    }

    // ── Icon slots ───────────────────────────────────────────────────────────

    /**
     * Icon import. GIFs that genuinely carry multiple frames are stored AS GIFS — they animate
     * on the launcher (CustomIcon.Animated via the frame probe) and the bundle carries them
     * under a gif entry, so flattening them to frame 1 would silently kill the animation the
     * author picked. Everything else is normalized to PNG (downscale + alpha, the still pipeline
     * that has always run here).
     *
     * Animated GIFs must pass the SAME caps the handheld's own import gate enforces — a
     * Studio-authored bundle is installed without re-validation — so oversize/too-long/too-many
     * frames are rejected here, by name, instead of shipping a theme the device refuses.
     */
    fun setIconOverride(key: String, file: File) = runBusy {
        val slot = IconSlots.byKey(key) ?: return@runBusy
        // Read with headroom so an oversized pick reaches the specific byte-cap rejection below
        // (MAX_ICON_BYTES), not a generic unreadable-file error.
        val bytes = com.playfieldportal.studio.io.SafeIo.readBytesCapped(file)
        val decoded = bytes?.let(ImageCodecs::decodeImage)
        if (bytes == null || decoded == null) {
            _state.update { it.copy(dialog = StudioDialog.Error("${file.name} is not a readable image")) }
            return@runBusy
        }

        // Byte cap FIRST (matches PfpThemeCodec.MAX_ICON_BYTES, which the bundle writer relies
        // on), then the animated classification — the same structural probe the launcher runs
        // at render time. A single-frame GIF is authored as a PNG still: no decoder on device.
        if (bytes.size > PfpThemeCodec.MAX_ICON_BYTES) {
            _state.update { it.copy(dialog = StudioDialog.Error(IconGifSupport.MSG_TOO_LARGE_BYTES)) }
            return@runBusy
        }
        val frames = IconGifSupport.countFrames(bytes)
        if (IconGifSupport.isGif(bytes) && frames > 1) {
            val (width, height) = IconGifSupport.logicalScreenSize(bytes) ?: (decoded.width to decoded.height)
            IconGifSupport.validateAnimated(width, height, frames, IconGifSupport.durationMs(bytes))
                ?.let { rejection ->
                    _state.update { it.copy(dialog = StudioDialog.Error(rejection)) }
                    return@runBusy
                }
            val gifBitmap = ImageCodecs.toImageBitmap(bytes)
            if (gifBitmap == null) {
                _state.update { it.copy(dialog = StudioDialog.Error(IconGifSupport.MSG_UNDECODABLE)) }
                return@runBusy
            }
            _state.update {
                it.copy(
                    iconOverrides = it.iconOverrides + (key to bytes),
                    iconExtensions = it.iconExtensions + (key to "gif"),
                    iconBitmaps = it.iconBitmaps + (key to gifBitmap),
                )
            }
            return@runBusy
        }

        val png = ImageCodecs.normalizeIconPng(file, slot.templateSizePx)
        val bitmap = png?.let(ImageCodecs::toImageBitmap)
        if (png == null || bitmap == null) {
            _state.update { it.copy(dialog = StudioDialog.Error("${file.name} is not a readable image")) }
            return@runBusy
        }
        _state.update {
            it.copy(
                iconOverrides = it.iconOverrides + (key to png),
                iconExtensions = it.iconExtensions + (key to "png"),
                iconBitmaps = it.iconBitmaps + (key to bitmap),
            )
        }
    }

    fun clearIconOverride(key: String) = _state.update {
        it.copy(
            iconOverrides = it.iconOverrides - key,
            iconExtensions = it.iconExtensions - key,
            iconBitmaps = it.iconBitmaps - key,
        )
    }

    fun clearAllIconOverrides() = _state.update {
        it.copy(iconOverrides = emptyMap(), iconExtensions = emptyMap(), iconBitmaps = emptyMap())
    }

    // ── Export ───────────────────────────────────────────────────────────────

    /** Builds the manifest the current edits describe. */
    fun buildManifest(state: StudioState = _state.value, today: LocalDate = LocalDate.now()): PfpThemeManifest =
        PfpThemeManifest(
            name = state.name.ifBlank { "Untitled Theme" },
            accentColor = PtfConversion.toHexRgb(state.accentArgb),
            iconColor = when (val c = state.iconColor) {
                IconColorChoice.Auto -> PfpThemeManifest.ICON_COLOR_AUTO
                is IconColorChoice.Custom -> PtfConversion.toHexRgb(c.argb)
            },
            textColor = when (val c = state.textColor) {
                TextColorChoice.Auto -> PfpThemeManifest.ICON_COLOR_AUTO
                is TextColorChoice.Custom -> PtfConversion.toHexRgb(c.argb)
            },
            waveStyle = state.waveStyle,
            // Only carry a layout when the user actually moved something off the default.
            layout = state.layout.takeUnless { it == com.playfieldportal.themekit.XmbLayoutSpec.DEFAULT },
            source = state.source ?: PfpThemeSource(type = PfpThemeSource.TYPE_USER_CREATED),
            created = today.toString(),
        )

    /**
     * Writes the current theme as a `.pfptheme`. [renderPreview] runs off the UI thread and
     * supplies the rendered-XMB thumbnail every bundle embeds.
     */
    fun exportTo(file: File, renderPreview: suspend (StudioState) -> ByteArray?) = runBusy {
        val snapshot = _state.value
        // Motion's poster is the still wallpaper, so motion without one is invalid — this
        // export is the last place that invariant can be enforced before the bundle ships.
        // (It should be unreachable — clearWallpaper and confirmWallpaper keep the pair in
        // step — but T5's hydrate path and future edits both funnel through here.)
        val motion = snapshot.motionFile
            ?.takeIf { snapshot.wallpaperPng != null && it.isFile }
            ?.let { video ->
                // Name the entry from the LIMITS mapping, never from the scratch file's own
                // name: an unknown extension is silently dropped by PfpThemeCodec.write.
                MotionLimits.bundleExtensionFor(video.extension)?.let { ThemeMotion.ofFile(video, it) }
            }
        val bundle = PfpThemeBundle(
            manifest = buildManifest(snapshot),
            wallpaper = snapshot.wallpaperPng,
            preview = runCatching { renderPreview(snapshot) }.getOrNull(),
            // Each icon ships under the extension it was authored with — PNG stills as png,
            // preserved animated GIFs as gif. (Hardcoding "png" here flattened every GIF
            // imported in the Studio to frame 1 on the handheld.)
            icons = snapshot.iconOverrides.mapValues { (key, png) ->
                ThemeImage(png, snapshot.iconExtensions[key] ?: "png")
            },
            motion = motion,
        )
        runCatching { file.outputStream().use { PfpThemeCodec.write(bundle, it) } }
            .onSuccess { _state.update { it.copy(statusMessage = "Exported ${file.name}") } }
            .onFailure { e -> _state.update { it.copy(dialog = StudioDialog.Error("Export failed: ${e.message}")) } }
    }

    /**
     * Unpacks every resource of a `.ptf` (wallpaper, preview, icon GIMs) into [outDir]
     * as reference PNGs — so authors can rebuild an old theme with original assets.
     */
    fun unpackPtf(file: File, outDir: File) = runBusy {
        val bytes = com.playfieldportal.studio.io.SafeIo.readBytesCapped(file)
        if (bytes == null) {
            _state.update { it.copy(dialog = StudioDialog.Error("${file.name} is too large to be a theme file")) }
            return@runBusy
        }
        if (com.playfieldportal.themekit.PtfParser.detect(bytes) == com.playfieldportal.themekit.PtfParser.Kind.CXMB) {
            _state.update { it.copy(dialog = StudioDialog.CxmbRejected) }
            return@runBusy
        }
        val dump = com.playfieldportal.themekit.PtfUnpacker.unpack(bytes)
        if (dump == null) {
            _state.update { it.copy(dialog = StudioDialog.Error("${file.name} is not a PSP theme file")) }
            return@runBusy
        }
        val summary = runCatching { com.playfieldportal.studio.io.PtfUnpackWriter.write(dump, outDir) }
            .getOrElse { e ->
                _state.update { it.copy(dialog = StudioDialog.Error("Unpack failed: ${e.message}")) }
                return@runBusy
            }
        _state.update {
            it.copy(
                dialog = StudioDialog.Notice(
                    "Theme unpacked",
                    buildString {
                        append("${summary.images} images")
                        if (summary.other > 0) append(" and ${summary.other} data files")
                        append(" written to ${outDir.name} (see report.txt).")
                        if (summary.failed > 0) append(" ${summary.failed} resources could not be decompressed.")
                    },
                ),
                statusMessage = "Unpacked ${file.name}: ${summary.images} images",
            )
        }
    }

    /** Folder of `.ptf` → folder of `.pfptheme`, with live progress and a summary dialog. */
    fun batchConvert(
        inputDir: File,
        outputDir: File,
        renderPreview: (com.playfieldportal.themekit.PfpThemeBundle) -> ByteArray?,
    ) = runBusy {
        val summary = com.playfieldportal.studio.io.BatchConverter.convertFolder(
            input = inputDir,
            output = outputDir,
            renderPreview = renderPreview,
            onProgress = { progress -> _state.update { it.copy(batchProgress = progress) } },
        )
        _state.update { it.copy(batchProgress = null, dialog = StudioDialog.BatchDone(summary)) }
    }

    /** Writes every built-in glyph as `<key>.png` — the editable template pack. */
    fun exportIconTemplates(dir: File, rasterize: (key: String, sizePx: Int) -> ByteArray) = runBusy {
        runCatching {
            dir.mkdirs()
            for (slot in IconSlots.ALL) {
                File(dir, "${slot.key}.png").writeBytes(rasterize(slot.key, slot.templateSizePx))
            }
        }
            .onSuccess { _state.update { it.copy(statusMessage = "Templates exported to ${dir.name} (${IconSlots.ALL.size} icons)") } }
            .onFailure { e -> _state.update { it.copy(dialog = StudioDialog.Error("Template export failed: ${e.message}")) } }
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    internal fun runBusy(block: suspend () -> Unit) {
        scope.launch {
            _state.update { it.copy(busy = true) }
            try {
                withContext(Dispatchers.IO) { block() }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    internal fun update(transform: (StudioState) -> StudioState) = _state.update(transform)
}
