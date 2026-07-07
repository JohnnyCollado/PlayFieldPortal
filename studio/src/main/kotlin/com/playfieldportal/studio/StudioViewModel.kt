package com.playfieldportal.studio

import androidx.compose.ui.graphics.ImageBitmap
import com.playfieldportal.studio.io.ConvertOutcome
import com.playfieldportal.studio.io.ImageCodecs
import com.playfieldportal.studio.io.PtfConversion
import com.playfieldportal.themekit.IconSlots
import com.playfieldportal.themekit.XmbLayoutSpecCodec
import com.playfieldportal.themekit.PfpThemeBundle
import com.playfieldportal.themekit.PfpThemeCodec
import com.playfieldportal.themekit.PfpThemeManifest
import com.playfieldportal.themekit.PfpThemeSource
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
    val waveStyle: String = PfpThemeManifest.WAVE_ANIMATED,
    val wallpaperPng: ByteArray? = null,
    val wallpaperBitmap: ImageBitmap? = null,
    val wallpaperFileName: String? = null,
    /** True when the wallpaper's label band is busy enough to threaten legibility. */
    val wallpaperBusy: Boolean = false,
    /** Wallpaper chosen but not yet cropped — drives the crop-preset dialog. */
    val pendingWallpaper: PendingWallpaper? = null,
    /**
     * Per-theme XMB geometry. The full spec is carried (not just the fields the UI edits)
     * so opened manifests round-trip hand-authored fields untouched.
     */
    val layout: com.playfieldportal.themekit.XmbLayoutSpec = com.playfieldportal.themekit.XmbLayoutSpec.DEFAULT,
    /** Which surface the preview shows — accent changes tint menus too. */
    val previewMode: PreviewMode = PreviewMode.HOME,
    /** Custom icon slots: IconSlots key → PNG bytes (what exports) ... */
    val iconOverrides: Map<String, ByteArray> = emptyMap(),
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

    // ── Simple edits ─────────────────────────────────────────────────────────

    /** The from-scratch start point: what the Studio opens on and what New resets to. */
    fun newTheme() = _state.update { StudioState() }

    fun setName(name: String) = _state.update { it.copy(name = name) }
    fun setAccent(argb: Int) = _state.update { it.copy(accentArgb = argb) }
    fun setIconColor(choice: IconColorChoice) = _state.update { it.copy(iconColor = choice) }
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
        val bytes = com.playfieldportal.studio.io.SafeIo.readBytesCapped(file)
        if (bytes == null) {
            _state.update { it.copy(dialog = StudioDialog.Error("${file.name} is too large to be a theme bundle")) }
            return@runBusy
        }
        val bundle = PfpThemeCodec.read(bytes)
        if (bundle == null) {
            _state.update { it.copy(dialog = StudioDialog.Error("${file.name} is not a valid .pfptheme bundle")) }
        } else {
            hydrate(bundle, "Opened ${file.name}")
        }
    }

    private fun hydrate(bundle: PfpThemeBundle, status: String) {
        val manifest = bundle.manifest
        val iconBitmaps = bundle.icons.mapNotNull { (key, png) ->
            ImageCodecs.toImageBitmap(png)?.let { key to it }
        }.toMap()
        val wallpaperBusy = bundle.wallpaper
            ?.let(ImageCodecs::decodeImage)
            ?.let { com.playfieldportal.themekit.WallpaperMetrics.isBusy(ImageCodecs.toBmpImage(it)) }
            ?: false
        _state.update {
            StudioState(
                name = manifest.name,
                accentArgb = PtfConversion.parseHexRgb(manifest.accentColor) ?: PtfConversion.DEFAULT_ACCENT,
                iconColor = manifest.iconColor
                    .takeIf { c -> c != PfpThemeManifest.ICON_COLOR_AUTO }
                    ?.let { c -> PtfConversion.parseHexRgb(c) }
                    ?.let { argb -> IconColorChoice.Custom(argb) }
                    ?: IconColorChoice.Auto,
                waveStyle = manifest.waveStyle,
                wallpaperPng = bundle.wallpaper,
                wallpaperBitmap = bundle.wallpaper?.let(ImageCodecs::toImageBitmap),
                wallpaperFileName = manifest.source?.file,
                // Keep ALL icon bytes even when a thumbnail fails to decode — a bad preview
                // must not silently strip the icon from the theme on re-export.
                iconOverrides = bundle.icons,
                iconBitmaps = iconBitmaps,
                wallpaperBusy = wallpaperBusy,
                layout = manifest.layout?.let(XmbLayoutSpecCodec::sanitize)
                    ?: com.playfieldportal.themekit.XmbLayoutSpec.DEFAULT,
                source = manifest.source,
                statusMessage = status,
            )
        }
    }

    /** Step 1 of wallpaper import: load the file and open the crop-preset dialog. */
    fun stageWallpaper(file: File) = runBusy {
        val image = ImageCodecs.loadImage(file)
        if (image == null) {
            _state.update { it.copy(dialog = StudioDialog.Error("${file.name} is not a readable image")) }
            return@runBusy
        }
        stage(image, file.name)
    }

    /** Re-crop the wallpaper already embedded in the theme. */
    fun restageEmbeddedWallpaper() = runBusy {
        val current = _state.value
        val image = current.wallpaperPng?.let(ImageCodecs::decodeImage) ?: return@runBusy
        stage(image, current.wallpaperFileName ?: "wallpaper")
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

    fun cancelWallpaperImport() = _state.update { it.copy(pendingWallpaper = null) }

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
        _state.update {
            it.copy(
                pendingWallpaper = null,
                wallpaperPng = png,
                wallpaperBitmap = bitmap,
                wallpaperFileName = pending.fileName,
                wallpaperBusy = com.playfieldportal.themekit.WallpaperMetrics.isBusy(bmp),
                accentArgb = derived ?: it.accentArgb,
                statusMessage = "Wallpaper: ${pending.fileName} (${image.width}×${image.height})",
            )
        }
    }

    fun clearWallpaper() = _state.update {
        it.copy(wallpaperPng = null, wallpaperBitmap = null, wallpaperFileName = null, wallpaperBusy = false)
    }

    // ── Icon slots ───────────────────────────────────────────────────────────

    fun setIconOverride(key: String, file: File) = runBusy {
        val slot = IconSlots.byKey(key) ?: return@runBusy
        val png = ImageCodecs.normalizeIconPng(file, slot.templateSizePx)
        val bitmap = png?.let(ImageCodecs::toImageBitmap)
        if (png == null || bitmap == null) {
            _state.update { it.copy(dialog = StudioDialog.Error("${file.name} is not a readable image")) }
            return@runBusy
        }
        _state.update {
            it.copy(
                iconOverrides = it.iconOverrides + (key to png),
                iconBitmaps = it.iconBitmaps + (key to bitmap),
            )
        }
    }

    fun clearIconOverride(key: String) = _state.update {
        it.copy(iconOverrides = it.iconOverrides - key, iconBitmaps = it.iconBitmaps - key)
    }

    fun clearAllIconOverrides() = _state.update {
        it.copy(iconOverrides = emptyMap(), iconBitmaps = emptyMap())
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
        val bundle = PfpThemeBundle(
            manifest = buildManifest(snapshot),
            wallpaper = snapshot.wallpaperPng,
            preview = runCatching { renderPreview(snapshot) }.getOrNull(),
            icons = snapshot.iconOverrides,
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
