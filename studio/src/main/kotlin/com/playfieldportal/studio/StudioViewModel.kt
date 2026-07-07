package com.playfieldportal.studio

import androidx.compose.ui.graphics.ImageBitmap
import com.playfieldportal.studio.io.ConvertOutcome
import com.playfieldportal.studio.io.ImageCodecs
import com.playfieldportal.studio.io.PtfConversion
import com.playfieldportal.themekit.IconSlots
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

/** Modal feedback the shell renders as dialogs. */
sealed interface StudioDialog {
    /** `.ctf`/CXMB rejection with the "why" — these replace PSP firmware files, not themes. */
    data object CxmbRejected : StudioDialog
    data class Error(val message: String) : StudioDialog
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
    fun dismissDialog() = _state.update { it.copy(dialog = null) }
    fun clearStatus() = _state.update { it.copy(statusMessage = null) }

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
        when (val outcome = PtfConversion.convert(file.readBytes(), file.name)) {
            is ConvertOutcome.Converted -> hydrate(outcome.bundle, "Imported ${file.name}")
            ConvertOutcome.Cxmb -> _state.update { it.copy(dialog = StudioDialog.CxmbRejected) }
            is ConvertOutcome.Failed -> _state.update {
                it.copy(dialog = StudioDialog.Error("${file.name}: ${outcome.reason}"))
            }
        }
    }

    private fun openPfpTheme(file: File) = runBusy {
        val bundle = PfpThemeCodec.read(file.readBytes())
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
                source = manifest.source,
                statusMessage = status,
            )
        }
    }

    fun importWallpaper(file: File) = runBusy {
        val image = ImageCodecs.loadImage(file)
        if (image == null) {
            _state.update { it.copy(dialog = StudioDialog.Error("${file.name} is not a readable image")) }
            return@runBusy
        }
        val png = ImageCodecs.toPngBytes(image)
        val bitmap = ImageCodecs.toImageBitmap(png)
        // A fresh wallpaper usually wants a matching accent — pre-fill from its dominant
        // hue exactly like Quick Create, keeping the user's step optional.
        val derived = com.playfieldportal.themekit.AccentDeriver.deriveAccent(ImageCodecs.toBmpImage(image))
        _state.update {
            it.copy(
                wallpaperPng = png,
                wallpaperBitmap = bitmap,
                wallpaperFileName = file.name,
                accentArgb = derived ?: it.accentArgb,
                statusMessage = "Wallpaper: ${file.name}",
            )
        }
    }

    fun clearWallpaper() = _state.update {
        it.copy(wallpaperPng = null, wallpaperBitmap = null, wallpaperFileName = null)
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
