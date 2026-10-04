package com.playfieldportal.studio

import androidx.compose.ui.graphics.ImageBitmap
import com.playfieldportal.studio.io.AndroidVideo
import com.playfieldportal.studio.io.ConvertOutcome
import com.playfieldportal.studio.io.IconPackImport
import com.playfieldportal.studio.io.IconPackReport
import com.playfieldportal.studio.io.ImageCodecs
import com.playfieldportal.studio.io.MediaGates
import com.playfieldportal.studio.io.PackRejected
import com.playfieldportal.studio.io.PtfConversion
import com.playfieldportal.studio.io.VideoCodecs
import com.playfieldportal.themekit.IconGifSupport
import com.playfieldportal.themekit.IconSlot
import com.playfieldportal.themekit.CustomizableIcons
import com.playfieldportal.themekit.MotionCrop
import com.playfieldportal.themekit.MANIFEST_DESCRIPTION_MAX
import com.playfieldportal.themekit.MotionLimits
import com.playfieldportal.themekit.XmbLayoutSpecCodec
import com.playfieldportal.themekit.PassthroughEntry
import com.playfieldportal.themekit.PfpThemeBundle
import com.playfieldportal.themekit.PfpThemeCodec
import com.playfieldportal.themekit.PfpThemeManifest
import com.playfieldportal.themekit.PfpThemeSource
import com.playfieldportal.themekit.ReadDiagnostics
import com.playfieldportal.themekit.ThemeImage
import com.playfieldportal.themekit.ThemeLegibility
import com.playfieldportal.themekit.ThemeMediaSlots
import com.playfieldportal.themekit.ThemeMotion
import com.playfieldportal.themekit.UiMediaLimits
import com.playfieldportal.themekit.ThemeUpgrade
import com.playfieldportal.themekit.UpgradeReport
import com.playfieldportal.themekit.WaveStyles
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
import kotlinx.serialization.json.JsonObject

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
    /** When [source] is a video frame: where in the clip it was taken (ms); null for an ordinary image. */
    val posterAtMs: Long? = null,
    /** Length of the video [source] came from, for the frame picker; null when unknown or not a video. */
    val videoDurationMs: Long? = null,
    /** True when the crop re-frames the video ALREADY in the theme (its playback crop is rewritten on confirm). */
    val reframesVideo: Boolean = false,
    /** The playback crop to start from when re-framing; null for a fresh import. */
    val initialCrop: MotionCrop? = null,
)

/** Modal feedback the shell renders as dialogs. */
sealed interface StudioDialog {
    /** `.ctf`/CXMB rejection with the "why" — these replace PSP firmware files, not themes. */
    data object CxmbRejected : StudioDialog
    data class Error(val message: String) : StudioDialog

    /** Non-fatal heads-up (e.g. a PTF imported but its wallpaper couldn't be extracted). */
    data class Notice(val title: String, val message: String) : StudioDialog
    data class BatchDone(val summary: com.playfieldportal.studio.io.BatchSummary) : StudioDialog
    data class UpgradeDone(val summary: com.playfieldportal.studio.io.UpgradeBatchSummary) : StudioDialog
}

/** What the upgrade banner says about the opened theme. */
sealed interface UpgradeBanner {
    data object None : UpgradeBanner

    /** Older format (or repairable): "Upgrade to the current format" with what that would do. */
    data class Available(val report: UpgradeReport) : UpgradeBanner

    /** Written by a newer version than this build understands; shown instead of the upgrade offer. */
    data class NewerVersion(val schemaVersion: Int) : UpgradeBanner
}

data class StudioState(
    val name: String = "Untitled Theme",
    val accentArgb: Int = PtfConversion.DEFAULT_ACCENT,
    /**
     * Auto: each new wallpaper or video poster re-derives [accentArgb] (AccentDeriver, as Quick
     * Create does). Custom (false): the accent is the author's and no wallpaper touches it. An
     * authoring aid only — the theme file carries the colour alone, so an opened theme is Custom.
     */
    val accentAuto: Boolean = true,
    val iconColor: IconColorChoice = IconColorChoice.Auto,
    val textColor: TextColorChoice = TextColorChoice.Auto,
    /** Sub text (subtitles, sublabels, muted text). Auto = follows [textColor], as on the device. */
    val subTextColor: TextColorChoice = TextColorChoice.Auto,
    /** The EXACT wave style (any of [WaveStyles]); export writes the legacy fallback beside it. */
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
    /**
     * Custom icons: [CustomizableIcons] slot key → encoded bytes (what exports) — theme slots,
     * console art (`sysicon_psx`) and physical-media art (`physmedia_psx`) alike, as the bundle
     * holds them; which zip folder each travels in is the codec's business ...
     */
    val iconOverrides: Map<String, ByteArray> = emptyMap(),
    /** ... the extension each entry ships as ("png" stills, "gif" animations) — parallel to [iconOverrides]. */
    val iconExtensions: Map<String, String> = emptyMap(),
    /** ... and the decoded bitmaps the preview/editor draw. Kept in lockstep with [iconOverrides]. */
    val iconBitmaps: Map<String, ImageBitmap> = emptyMap(),
    /** Manifest keys this build has no typed field for, merged back on export. */
    val manifestExtras: JsonObject = JsonObject(emptyMap()),
    /**
     * Zip entries this build does not understand: full entry name -> scratch copy. Scratch, not
     * the source file (the author may save over it), and never bytes.
     */
    val passthroughFiles: Map<String, File> = emptyMap(),
    /** UI media ([com.playfieldportal.themekit.ThemeMediaSlots] key -> scratch copy; the extension is the file's). */
    val mediaFiles: Map<String, File> = emptyMap(),
    /** The opened bundle's preview frame; export falls back to it when a fresh render is unavailable. */
    val previewPng: ByteArray? = null,
    val author: String? = null,
    val description: String? = null,
    /** ISO date, preserved forever once set; null on a never-exported theme. */
    val created: String? = null,
    val legibility: ThemeLegibility? = null,
    /** Null = the theme says nothing about whether the text colour is exact. */
    val textColorExact: Boolean? = null,
    val motionCrop: MotionCrop? = null,
    /** Where in the video the poster still was taken (ms); session-only (the file does not record it), null when unknown. */
    val posterAtMs: Long? = null,
    /** `schemaVersion` of the file this state was opened from; null for a from-scratch theme. */
    val schemaVersion: Int? = null,
    /** What opening a `.pfptheme` kept / would add / repaired / could not recover; null otherwise. */
    val upgradeReport: UpgradeReport? = null,
    val source: PfpThemeSource? = null,
    val busy: Boolean = false,
    val statusMessage: String? = null,
    val dialog: StudioDialog? = null,
    /** Non-null while a batch conversion runs. */
    val batchProgress: com.playfieldportal.studio.io.BatchProgress? = null,
) {
    // ByteArray fields: identity equality is fine — state copies share the arrays.

    /** Drives the banner above the editor; derived, so it can never disagree with [schemaVersion]. */
    val upgradeBanner: UpgradeBanner
        get() {
            val version = schemaVersion ?: return UpgradeBanner.None
            if (version > PfpThemeManifest.SCHEMA_VERSION) return UpgradeBanner.NewerVersion(version)
            val report = upgradeReport ?: return UpgradeBanner.None
            val pending = version < PfpThemeManifest.SCHEMA_VERSION ||
                report.added.isNotEmpty() || report.repaired.isNotEmpty()
            return if (pending) UpgradeBanner.Available(report) else UpgradeBanner.None
        }

    val upgradeAvailable: Boolean get() = upgradeBanner is UpgradeBanner.Available
}

/**
 * The Studio's single state holder. Plain class + StateFlow (no DI, no platform ViewModel):
 * constructed once in Main with an app-lifetime scope; IO always hops to [Dispatchers.IO].
 */
class StudioViewModel(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(StudioState())
    val state: StateFlow<StudioState> = _state.asStateFlow()

    /** Undo/redo history; it also owns the lifetime of every scratch file (see [EditHistory]). */
    private val history = EditHistory()
    private val _canUndo = MutableStateFlow(false)
    private val _canRedo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private fun publishHistory() {
        _canUndo.value = history.canUndo
        _canRedo.value = history.canRedo
    }

    /**
     * A recorded theme edit: applies [transform] and snapshots the change for undo. A non-null
     * [key] coalesces a burst of the same edit (slider drag, typing) into one undo step.
     */
    private fun edit(key: String? = null, transform: (StudioState) -> StudioState) {
        synchronized(history) {
            var before = _state.value
            var after = before
            _state.update { current ->
                before = current
                transform(current).also { after = it }
            }
            history.record(before, after, key)
            publishHistory()
        }
    }

    fun undo() {
        synchronized(history) {
            val target = history.undo(_state.value) ?: return
            _state.update { target.withSessionOf(it) }
            publishHistory()
        }
    }

    fun redo() {
        synchronized(history) {
            val target = history.redo(_state.value) ?: return
            _state.update { target.withSessionOf(it) }
            publishHistory()
        }
    }

    /** Replaces the whole document (New / Open): history is forgotten, outgoing scratch freed. */
    private fun replaceDocument(incoming: StudioState) {
        synchronized(history) {
            val outgoing = _state.value
            _state.value = incoming
            history.reset(outgoing, incoming)
            publishHistory()
        }
    }

    /** A video staged behind an open crop dialog, and its original file name. Deliberately
     * not [StudioState] — it becomes state exactly when the crop is confirmed. */
    private var pendingMotion: File? = null
    private var pendingMotionName: String? = null

    // ── Scratch-file lifecycle ───────────────────────────────────────────────
    // Scratch files (motion, media, passthrough) are owned by [history]: one is deleted only when
    // no live state or undo/redo snapshot references it, and New / Open release them all. Nothing
    // here deletes a state's file directly - that is what made "undo a clear" point at a corpse.

    /** Moves [source] to a private scratch copy; the caller's original is never referenced again. */
    private fun scratchMotion(source: File): File {
        val scratch = File.createTempFile("studio-motion-", ".${source.extension.lowercase()}")
        source.copyTo(scratch, overwrite = true)
        scratch.deleteOnExit()
        return scratch
    }

    /** Streams one bundle entry into a private scratch file ([prefix] names the family). */
    private fun spill(prefix: String, extension: String, copy: (java.io.OutputStream) -> Long): File {
        val scratch = File.createTempFile(prefix, ".${extension.lowercase()}")
        scratch.deleteOnExit()
        try {
            scratch.outputStream().use { copy(it) }
        } catch (e: Exception) {
            scratch.delete()
            throw e
        }
        return scratch
    }

    /**
     * Clears motion (undoable; the history frees the scratch file once unreachable), keeping the still wallpaper —
     * the inverse of the poster rule: a still without motion is a plain valid theme. Internal
     * so tests can drive the lifecycle without going through the UI.
     */
    internal fun clearMotion() =
        edit { it.copy(motionFile = null, motionFileName = null, motionCrop = null, posterAtMs = null) }

    // ── Simple edits ─────────────────────────────────────────────────────────

    /** The from-scratch start point: what the Studio opens on and what New resets to. */
    fun newTheme() {
        abandonPendingMotion()
        replaceDocument(StudioState())
    }

    fun setName(name: String) = edit("name") { it.copy(name = name) }
    /** A picked accent is Custom: wallpapers stop changing it. */
    fun setAccent(argb: Int) = edit("accent") { it.copy(accentArgb = argb, accentAuto = false) }

    /** Auto re-derives from the current wallpaper at once (nothing to derive from: the colour stays). */
    fun setAccentAuto(auto: Boolean) = runBusy {
        val derived = if (auto) {
            _state.value.wallpaperPng
                ?.let(ImageCodecs::decodeImage)
                ?.let { com.playfieldportal.themekit.AccentDeriver.deriveAccent(ImageCodecs.toBmpImage(it)) }
        } else {
            null
        }
        edit { it.copy(accentAuto = auto, accentArgb = derived ?: it.accentArgb) }
    }
    fun setIconColor(choice: IconColorChoice) = edit("iconColor") { it.copy(iconColor = choice) }
    fun setTextColor(choice: TextColorChoice) = edit("textColor") { it.copy(textColor = choice) }

    fun setSubTextColor(choice: TextColorChoice) = edit("subTextColor") { it.copy(subTextColor = choice) }
    fun setWaveStyle(style: String) = edit { it.copy(waveStyle = style) }

    // Blank collapses to null so an empty field never writes `""` into the manifest.
    fun setAuthor(author: String) = edit("author") { it.copy(author = author.ifBlank { null }) }
    fun setDescription(description: String) = edit("description") {
        it.copy(description = description.take(MANIFEST_DESCRIPTION_MAX).ifBlank { null })
    }

    // Toggles and chips are discrete clicks: no coalescing key, so each is its own undo step.
    /** Off = the theme says nothing about it (null), not an explicit false: keeps the file minimal. */
    fun setTextColorExact(exact: Boolean) = edit { it.copy(textColorExact = true.takeIf { exact }) }

    /** Text legibility style ([ThemeLegibility.TEXT_VALUES]); null clears it. Unknown values are refused. */
    fun setTextLegibility(style: String?) {
        if (style != null && style !in ThemeLegibility.TEXT_VALUES) return
        edit { it.copy(legibility = legibilityOf((it.legibility ?: ThemeLegibility()).copy(text = style))) }
    }

    /** Icon legibility style ([ThemeLegibility.ICON_VALUES]); null clears it. Unknown values are refused. */
    fun setIconLegibility(style: String?) {
        if (style != null && style !in ThemeLegibility.ICON_VALUES) return
        edit { it.copy(legibility = legibilityOf((it.legibility ?: ThemeLegibility()).copy(icon = style))) }
    }

    fun setSolidUnfocusedIcons(solid: Boolean) = edit {
        it.copy(legibility = legibilityOf((it.legibility ?: ThemeLegibility()).copy(solidUnfocusedIcons = solid)))
    }

    /** A legibility object with nothing set is the same as none at all. */
    private fun legibilityOf(l: ThemeLegibility?): ThemeLegibility? =
        l?.takeUnless { it.text == null && it.icon == null && it.solidUnfocusedIcons == null }

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
        val result = PfpThemeCodec.readDetailed(file)
        if (result == null) {
            _state.update { it.copy(dialog = StudioDialog.Error("${file.name} is not a valid .pfptheme bundle")) }
        } else {
            hydrate(result.bundle, "Opened ${file.name}", result.diagnostics)
        }
    }

    private fun hydrate(bundle: PfpThemeBundle, status: String, diagnostics: ReadDiagnostics? = null) {
        val manifest = bundle.manifest
        // Icons for parts a Studio theme no longer replaces (status strip, Shiba Coins, menus...)
        // are left out: they would not be editable, and re-exporting them would keep them alive.
        val (icons, notThemeable) = bundle.icons.entries.partition { (key, _) -> EditableSlots.isKept(key) }
            .let { (kept, dropped) -> kept.associate { it.toPair() } to dropped.map { it.key } }
        val iconBitmaps = icons.mapNotNull { (key, png) ->
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
        var motionSpillError: String? = null
        val motionFile = bundle.motion?.let { motion ->
            runCatching {
                val scratch = File.createTempFile("studio-motion-", ".${motion.extension}")
                scratch.outputStream().use { motion.copyTo(it) }
                scratch.deleteOnExit()
                scratch
            }.onFailure { e -> motionSpillError = e.message }.getOrNull()
        }
        // Media and unknown entries are spilled the same way, for the same two reasons: the bundle
        // streams them from the source file (which the author may save over, or move), and they
        // must never sit on the heap. A failed spill is listed, not swallowed — a silent skip
        // would make the re-export lossy.
        val notKept = mutableListOf<String>()
        val mediaFiles = bundle.media.mapNotNull { (key, media) ->
            runCatching { key to spill("studio-media-", media.extension, media::copyTo) }
                .onFailure { notKept += key }.getOrNull()
        }.toMap()
        val passthroughFiles = bundle.passthrough.mapNotNull { entry ->
            runCatching {
                entry.name to spill("studio-extra-", entry.name.substringAfterLast('.'), entry::copyTo)
            }.onFailure { notKept += entry.name }.getOrNull()
        }.toMap()
        replaceDocument(
            StudioState(
                name = manifest.name,
                accentArgb = PtfConversion.parseHexRgb(manifest.accentColor) ?: PtfConversion.DEFAULT_ACCENT,
                // The file's accent is a choice someone made: keep it until the author asks for Auto.
                accentAuto = false,
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
                subTextColor = manifest.subTextColor
                    ?.let { c -> PtfConversion.parseHexRgb(c) }
                    ?.let { argb -> TextColorChoice.Custom(argb) }
                    ?: TextColorChoice.Auto,
                waveStyle = WaveStyles.resolveExact(manifest),
                wallpaperPng = bundle.wallpaper,
                wallpaperBitmap = bundle.wallpaper?.let(ImageCodecs::toImageBitmap),
                wallpaperFileName = manifest.source?.file,
                // Keep ALL icon bytes even when a thumbnail fails to decode — a bad preview
                // must not silently strip the icon from the theme on re-export. Each entry
                // keeps the extension it shipped with (png stills, gif animations), so an
                // opened animated icon re-exports animated.
                iconOverrides = icons.mapValues { (_, image) -> image.bytes },
                iconExtensions = icons.mapValues { (_, image) -> image.extension.lowercase() },
                iconBitmaps = iconBitmaps,
                manifestExtras = bundle.manifestExtras,
                passthroughFiles = passthroughFiles,
                mediaFiles = mediaFiles,
                previewPng = bundle.preview,
                author = manifest.author,
                description = manifest.description,
                created = manifest.created,
                legibility = manifest.legibility,
                textColorExact = manifest.textColorExact,
                motionCrop = manifest.motionCrop,
                schemaVersion = manifest.schemaVersion,
                upgradeReport = diagnostics?.let { ThemeUpgrade.report(bundle, it) },
                wallpaperBusy = wallpaperBusy,
                motionFile = motionFile,
                // The bundle records only the entry's extension (motion.mp4), not the author's
                // original filename — show the entry name rather than inventing one.
                motionFileName = motionFile?.let { "motion.${it.extension}" },
                layout = manifest.layout?.let(XmbLayoutSpecCodec::sanitize)
                    ?: com.playfieldportal.themekit.XmbLayoutSpec.DEFAULT,
                source = manifest.source,
                statusMessage = if (notThemeable.isEmpty()) status
                    else "$status — ${notThemeable.size} icon(s) for parts themes don't customize were left out",
            ),
        )
        // Surface a failed motion spill AFTER the state lands — the theme still opens, but the
        // author must know their video won't survive a re-export.
        val motionError = motionSpillError
        if (notKept.isNotEmpty()) {
            val lines = listOfNotNull(motionError?.let { "Motion wallpaper: $it" }) +
                "Could not keep: ${notKept.joinToString(", ")}"
            _state.update {
                it.copy(dialog = StudioDialog.Notice("Parts of the theme not kept", lines.joinToString("\n")))
            }
        } else motionError?.let { message ->
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
        // A wallpaper the handheld cannot decode is re-encoded first; every check after this runs on
        // what the theme will actually carry.
        val playable = AndroidVideo.playable(file)
        if (playable is AndroidVideo.Playable.Failed) {
            _state.update { it.copy(dialog = StudioDialog.Error(playable.message)) }
            return@runBusy
        }
        val reencoded = playable as? AndroidVideo.Playable.Converted
        try {
            importAcceptedVideo(file, reencoded?.file ?: file, reencoded?.reason)
        } finally {
            reencoded?.file?.delete()
        }
    }

    private fun importAcceptedVideo(file: File, source: File, conversion: String?) {
        when (val outcome = VideoCodecs.accept(source)) {
            is VideoCodecs.Outcome.Rejected ->
                // The strings are written for the author — surface them verbatim.
                _state.update { it.copy(dialog = StudioDialog.Error(outcome.message)) }

            is VideoCodecs.Outcome.Accepted -> {
                val scratch = scratchMotion(source)
                conversion?.let { reason ->
                    _state.update { it.copy(statusMessage = "${file.name} converted to H.264 for Android ($reason)") }
                }
                // This import replaces a video pending behind an open crop dialog. A confirmed
                // video stays in state until the crop is confirmed (one undoable edit), so a
                // cancel keeps the old motion with its old poster - still a valid pair.
                pendingMotion?.delete()
                pendingMotion = scratch
                pendingMotionName = file.name
                stage(outcome.poster, file.name, posterAtMs = 0L, videoDurationMs = outcome.probe.durationMs)
            }
        }
    }

    /**
     * Re-frames the video already in the theme: stages one of ITS frames (at the poster time, or the
     * start) at the video's own size and opens the crop at the saved playback crop. Confirming
     * rewrites both the poster and `motionCrop` from the same frame.
     */
    fun restageVideoFrame() = runBusy {
        val current = _state.value
        val video = current.motionFile ?: return@runBusy
        val name = current.motionFileName ?: video.name
        stageVideoFrame(video, name, current.posterAtMs ?: 0L, reframes = true, initialCrop = current.motionCrop)
    }

    /**
     * Swaps the staged still for the video frame at [atMs]. Works on the video staged behind an open
     * crop, or on the theme's own video (which opens the re-frame crop); otherwise it does nothing.
     */
    fun pickPosterFrame(atMs: Long) = runBusy {
        val current = _state.value
        val pending = current.pendingWallpaper
        val staged = pendingMotion
        val own = current.motionFile
        when {
            pending != null && staged != null ->
                stageVideoFrame(staged, pending.fileName, atMs, reframes = false, initialCrop = null)
            pending != null && pending.reframesVideo && own != null ->
                stageVideoFrame(own, pending.fileName, atMs, reframes = true, initialCrop = pending.initialCrop)
            pending == null && own != null ->
                stageVideoFrame(own, current.motionFileName ?: own.name, atMs, reframes = true, initialCrop = current.motionCrop)
        }
    }

    private fun stageVideoFrame(video: File, name: String, atMs: Long, reframes: Boolean, initialCrop: MotionCrop?) {
        val frame = VideoCodecs.frameAt(video, atMs)
        if (frame == null) {
            _state.update { it.copy(dialog = StudioDialog.Error("Couldn't read that frame of the video")) }
            return
        }
        if (reframes) abandonPendingMotion()
        stage(
            frame,
            name,
            posterAtMs = atMs,
            videoDurationMs = VideoCodecs.probe(video)?.durationMs,
            reframes = reframes,
            initialCrop = initialCrop,
        )
    }

    private fun stage(
        image: java.awt.image.BufferedImage,
        name: String,
        posterAtMs: Long? = null,
        videoDurationMs: Long? = null,
        reframes: Boolean = false,
        initialCrop: MotionCrop? = null,
    ) {
        _state.update {
            it.copy(
                pendingWallpaper = PendingWallpaper(
                    source = image,
                    fileName = name,
                    // The crop view draws this, so it is larger than the old dialog's thumbnail.
                    thumbnail = ImageCodecs.toImageBitmap(ImageCodecs.toPngBytes(ImageCodecs.thumbnail(image, 640))),
                    posterAtMs = posterAtMs,
                    videoDurationMs = videoDurationMs,
                    reframesVideo = reframes,
                    initialCrop = initialCrop,
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

    /** Step 2 with the default framing: the largest centered crop of [preset]. */
    fun confirmWallpaper(preset: WallpaperPreset) {
        val pending = _state.value.pendingWallpaper ?: return
        confirmWallpaperCrop(CropFrame.centered(pending.source.width, pending.source.height, preset))
    }

    /**
     * Step 2: bakes [frame] into the still (a staged video's poster included), then derives
     * accent + legibility hint. A staged video also gets [CropFrame.toMotionCrop] as its
     * playback crop - the same region the poster was baked from; the video bytes stay untouched.
     * The frame must have been built for the pending source's size.
     */
    fun confirmWallpaperCrop(frame: CropFrame) = runBusy {
        val pending = _state.value.pendingWallpaper ?: return@runBusy
        if (frame.sourceW != pending.source.width || frame.sourceH != pending.source.height) {
            _state.update { it.copy(dialog = StudioDialog.Error("The crop does not match this image")) }
            return@runBusy
        }
        val image = ImageCodecs.bakeCrop(pending.source, frame)
        val png = ImageCodecs.toPngBytes(image)
        val bitmap = ImageCodecs.toImageBitmap(png)
        val bmp = ImageCodecs.toBmpImage(image)
        // On Auto, a fresh wallpaper brings a matching accent — its dominant hue, exactly like
        // Quick Create. A Custom accent is the author's and is left alone.
        val derived = com.playfieldportal.themekit.AccentDeriver.deriveAccent(bmp)
        // Attach the staged video exactly when its poster is confirmed; the name was stashed
        // alongside it because pendingMotion is deliberately not state.
        val stagedVideo = pendingMotion
        val stagedName = pendingMotionName
        pendingMotion = null
        pendingMotionName = null
        edit {
            it.copy(
                pendingWallpaper = null,
                wallpaperPng = png,
                wallpaperBitmap = bitmap,
                wallpaperFileName = pending.fileName,
                wallpaperBusy = com.playfieldportal.themekit.WallpaperMetrics.isBusy(bmp),
                motionFile = stagedVideo ?: it.motionFile,
                motionFileName = stagedName ?: it.motionFileName,
                // Only a freshly staged video is framed by this crop; an existing video keeps its
                // own (the re-cropped still is the baked poster, not the video's frame), and a
                // theme with no video has no playback crop.
                motionCrop = when {
                    stagedVideo != null -> frame.toMotionCrop()
                    // Re-framing the theme's own video: its frame is the source, so the crop is its crop.
                    pending.reframesVideo && it.motionFile != null -> frame.toMotionCrop()
                    it.motionFile != null -> it.motionCrop
                    else -> null
                },
                posterAtMs = when {
                    stagedVideo != null || (pending.reframesVideo && it.motionFile != null) -> pending.posterAtMs
                    it.motionFile != null -> it.posterAtMs
                    else -> null
                },
                accentArgb = if (it.accentAuto) derived ?: it.accentArgb else it.accentArgb,
                statusMessage = "Wallpaper: ${pending.fileName} (${image.width}×${image.height})",
            )
        }
    }

    fun clearWallpaper() {
        // Motion rides with the still: a bundle with motion and no wallpaper is invalid
        // (motion's poster IS the still), so clearing the wallpaper clears the video too.
        edit {
            it.copy(
                wallpaperPng = null,
                wallpaperBitmap = null,
                wallpaperFileName = null,
                wallpaperBusy = false,
                motionFile = null,
                motionFileName = null,
                motionCrop = null,
                posterAtMs = null,
            )
        }
    }

    /** Sets or clears the video's playback crop on its own (undoable); [crop] is normalized 0..1. */
    fun setMotionCrop(crop: MotionCrop?) = edit("motionCrop") { it.copy(motionCrop = crop) }

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
        // Console and physical-media art are slots too: the same pipeline, the same map.
        val slot = EditableSlots.byKey(key) ?: return@runBusy
        // Read with headroom so an oversized pick reaches the specific byte-cap rejection in
        // the gate (MAX_ICON_BYTES), not a generic unreadable-file error.
        val bytes = com.playfieldportal.studio.io.SafeIo.readBytesCapped(file)
        when (val gate = gateIcon(slot, bytes, file.name)) {
            is IconGate.Ok -> edit { it.withIcon(key, gate.bytes, gate.extension, gate.bitmap) }
            is IconGate.Reject -> _state.update { it.copy(dialog = StudioDialog.Error(gate.reason)) }
        }
    }

    /** Outcome of the icon gate: art ready to store, or the reason it was refused. */
    private sealed interface IconGate {
        class Ok(val bytes: ByteArray, val extension: String, val bitmap: ImageBitmap) : IconGate
        class Reject(val reason: String) : IconGate
    }

    /**
     * The single icon gate, shared by single-icon import and pack import. [label] names the file in
     * "not a readable image"; [bytes] is null when the source could not be read within the cap.
     */
    private fun gateIcon(slot: IconSlot, bytes: ByteArray?, label: String): IconGate {
        val decoded = bytes?.let(ImageCodecs::decodeImage)
        if (bytes == null || decoded == null) return IconGate.Reject("$label is not a readable image")

        // Byte cap FIRST (matches PfpThemeCodec.MAX_ICON_BYTES, which the bundle writer relies
        // on), then the animated classification — the same structural probe the launcher runs
        // at render time. A single-frame GIF is authored as a PNG still: no decoder on device.
        if (bytes.size > PfpThemeCodec.MAX_ICON_BYTES) return IconGate.Reject(IconGifSupport.MSG_TOO_LARGE_BYTES)
        val frames = IconGifSupport.countFrames(bytes)
        if (IconGifSupport.isGif(bytes) && frames > 1) {
            val (width, height) = IconGifSupport.logicalScreenSize(bytes) ?: (decoded.width to decoded.height)
            IconGifSupport.validateAnimated(width, height, frames, IconGifSupport.durationMs(bytes))
                ?.let { return IconGate.Reject(it) }
            val gifBitmap = ImageCodecs.toImageBitmap(bytes) ?: return IconGate.Reject(IconGifSupport.MSG_UNDECODABLE)
            return IconGate.Ok(bytes, "gif", gifBitmap)
        }

        val png = ImageCodecs.normalizeIconPng(bytes, slot.templateSizePx)
        val bitmap = png?.let(ImageCodecs::toImageBitmap)
        if (png == null || bitmap == null) return IconGate.Reject("$label is not a readable image")
        return IconGate.Ok(png, "png", bitmap)
    }

    /**
     * Imports an icon pack ([source] = folder or `.zip` of `<slotKey>.<png|gif>`) as ONE undoable
     * edit. Each matched file goes through [gateIcon]; failures are reported, never fatal to the
     * rest. [onDone] receives the report on the IO thread (a refused pack sets [IconPackReport.error]
     * and also raises the Error dialog).
     */
    fun importIconPack(source: File, onDone: (IconPackReport) -> Unit = {}) = runBusy {
        val scan = IconPackImport.scan(source)
        val ready = LinkedHashMap<String, IconGate.Ok>()
        val rejected = scan.rejected.toMutableList()
        for ((key, packFile) in scan.matched) {
            val slot = EditableSlots.byKey(key) ?: continue
            when (val gate = gateIcon(slot, packFile.bytes, packFile.name)) {
                is IconGate.Ok -> ready[key] = gate
                is IconGate.Reject -> rejected += PackRejected(packFile.name, gate.reason)
            }
        }
        var added = emptyList<String>()
        var replaced = emptyList<String>()
        if (ready.isNotEmpty()) {
            edit {
                val (again, fresh) = ready.keys.partition { k -> k in it.iconOverrides }
                added = fresh
                replaced = again
                ready.entries.fold(it) { acc, (k, gate) ->
                    acc.withIcon(k, gate.bytes, gate.extension, gate.bitmap)
                }
            }
        }
        val report = IconPackReport(source.name, added, replaced, scan.unmatched, rejected, scan.error)
        _state.update {
            when {
                scan.error != null -> it.copy(dialog = StudioDialog.Error(scan.error))
                else -> it.copy(
                    statusMessage = "Icon pack ${source.name}: ${added.size} added, ${replaced.size} replaced, " +
                        "${scan.unmatched.size} unmatched, ${rejected.size} rejected",
                )
            }
        }
        onDone(report)
    }

    private fun StudioState.withIcon(key: String, bytes: ByteArray, extension: String, bitmap: ImageBitmap): StudioState =
        copy(
            iconOverrides = iconOverrides + (key to bytes),
            iconExtensions = iconExtensions + (key to extension),
            iconBitmaps = iconBitmaps + (key to bitmap),
        )

    fun clearIconOverride(key: String) = edit {
        it.copy(
            iconOverrides = it.iconOverrides - key,
            iconExtensions = it.iconExtensions - key,
            iconBitmaps = it.iconBitmaps - key,
        )
    }

    fun clearAllIconOverrides() = edit {
        it.copy(iconOverrides = emptyMap(), iconExtensions = emptyMap(), iconBitmaps = emptyMap())
    }

    // ── UI media (sounds, ambience, boot, GameBoot) ──────────────────────────

    /** Imports a menu sound into one of the five sound slots ([ThemeMediaSlots] keys). */
    fun importSound(slotKey: String, file: File) = importMedia(slotKey, file, UiMediaLimits.Kind.SOUND)

    fun importAmbience(file: File) = importMedia(AMBIENCE_KEY, file, UiMediaLimits.Kind.AUDIO_TRACK)
    fun importBoot(file: File) = importMedia(BOOT_KEY, file, UiMediaLimits.Kind.VIDEO)
    fun importGameBoot(file: File) = importMedia(GAMEBOOT_KEY, file, UiMediaLimits.Kind.VIDEO)

    /**
     * Gate ([MediaGates]), then stage a private scratch copy and record it as one undoable edit.
     * [kind] keeps each entry point on its own family (a boot clip cannot be filed as a sound).
     * A rejection is surfaced verbatim and changes nothing.
     */
    private fun importMedia(slotKey: String, file: File, kind: UiMediaLimits.Kind) = runBusy {
        val slot = ThemeMediaSlots.slot(slotKey)?.takeIf { it.kind == kind }
        val outcome = if (slot == null) MediaGates.Outcome.Rejected(MediaGates.MSG_UNKNOWN_SLOT)
        else MediaGates.check(slotKey, file)
        when (outcome) {
            is MediaGates.Outcome.Rejected ->
                _state.update { it.copy(dialog = StudioDialog.Error(outcome.message)) }
            is MediaGates.Outcome.Accepted -> {
                // The gate may hand back a conversion (a float WAV as 16-bit PCM, a clip re-encoded
                // for Android) rather than the pick.
                val scratch = try {
                    spill("studio-media-", outcome.extension) { out -> outcome.source.inputStream().use { it.copyTo(out) } }
                } finally {
                    if (outcome.source != file) outcome.source.delete()
                }
                val status = outcome.note?.let { "Added ${file.name} — converted to H.264 for Android ($it)" }
                    ?: "Added ${file.name}"
                edit { it.copy(mediaFiles = it.mediaFiles + (slotKey to scratch), statusMessage = status) }
            }
        }
    }

    /** Removes the media in [slotKey]; undoable (the history frees the scratch file once unreachable). */
    fun clearMedia(slotKey: String) = edit { it.copy(mediaFiles = it.mediaFiles - slotKey) }

    /** The Export-check checklist and budget for the current state. */
    fun exportCheck(): ExportCheck = ExportCheck.of(_state.value)

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
            // Absent = sub text follows the main colour (the manifest's own contract).
            subTextColor = (state.subTextColor as? TextColorChoice.Custom)?.let { PtfConversion.toHexRgb(it.argb) },
            // Legacy field = fallback of the exact value, waveStyleV4 = the exact one (plan 5.1).
            waveStyle = WaveStyles.encode(state.waveStyle).first,
            waveStyleV4 = WaveStyles.encode(state.waveStyle).second,
            author = state.author,
            description = state.description,
            textColorExact = state.textColorExact,
            legibility = state.legibility,
            motionCrop = state.motionCrop,
            // Only carry a layout when the user actually moved something off the default.
            layout = state.layout.takeUnless { it == com.playfieldportal.themekit.XmbLayoutSpec.DEFAULT },
            source = state.source ?: PfpThemeSource(type = PfpThemeSource.TYPE_USER_CREATED),
            // `created` is preserved forever once set (plan 5.1 write rule 2); `updated` is every export.
            created = state.created ?: today.toString(),
            updated = today.toString(),
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
            preview = runCatching { renderPreview(snapshot) }.getOrNull() ?: snapshot.previewPng,
            // Each icon ships under the extension it was authored with — PNG stills as png,
            // preserved animated GIFs as gif. (Hardcoding "png" here flattened every GIF
            // imported in the Studio to frame 1 on the handheld.)
            icons = snapshot.iconOverrides.mapValues { (key, png) ->
                ThemeImage(png, snapshot.iconExtensions[key] ?: "png")
            },
            motion = motion,
            media = snapshot.mediaFiles.filterValues { it.isFile }
                .mapValues { (_, f) -> ThemeMotion.ofFile(f, f.extension.lowercase()) },
            manifestExtras = snapshot.manifestExtras,
            passthrough = snapshot.passthroughFiles.filterValues { it.isFile }
                .map { (name, f) -> PassthroughEntry.ofFile(name, f) },
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

    /** Folder of `.pfptheme` → upgraded in place to the current format (originals kept as `.bak`), with progress and a summary dialog. */
    fun upgradeFolder(dir: File, today: LocalDate = LocalDate.now()) = runBusy {
        val summary = com.playfieldportal.studio.io.UpgradeBatch.run(
            dir = dir,
            today = today.toString(),
            onProgress = { progress -> _state.update { it.copy(batchProgress = progress) } },
        )
        _state.update { it.copy(batchProgress = null, dialog = StudioDialog.UpgradeDone(summary)) }
    }

    /**
     * Writes a `<key>.png` template for every customizable slot (icons and consoles) — the editable
     * pack, named so [importIconPack] reads it straight back.
     */
    fun exportIconTemplates(dir: File, rasterize: (key: String, sizePx: Int) -> ByteArray) = runBusy {
        runCatching {
            dir.mkdirs()
            for (slot in EditableSlots.ALL) {
                File(dir, "${slot.key}.png").writeBytes(rasterize(slot.key, slot.templateSizePx))
            }
        }
            .onSuccess { _state.update { it.copy(statusMessage = "Templates exported to ${dir.name} (${EditableSlots.ALL.size} icons)") } }
            .onFailure { e -> _state.update { it.copy(dialog = StudioDialog.Error("Template export failed: ${e.message}")) } }
    }

    private companion object {
        const val AMBIENCE_KEY = "ambience_audio"
        const val BOOT_KEY = "boot_video"
        const val GAMEBOOT_KEY = "gameboot_video"
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    internal fun runBusy(block: suspend () -> Unit) {
        // Busy is raised before the launch, not inside it: the flag must be true the moment the
        // call returns, or anyone waiting on it (the UI, the tests' awaitIdle) can see an idle
        // VM in the gap before the coroutine is scheduled and miss the work entirely.
        _state.update { it.copy(busy = true) }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    internal fun update(transform: (StudioState) -> StudioState) = _state.update(transform)
}
