package com.playfieldportal.core.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.data.wallpaper.WallpaperLuminanceProbe
import com.playfieldportal.core.data.wallpaper.WallpaperLuminanceProbe.clearWallpaperLuma
import com.playfieldportal.core.data.wallpaper.WallpaperLuminanceProbe.setWallpaperLuma
import com.playfieldportal.themekit.AccentDeriver
import com.playfieldportal.themekit.BmpImage
import com.playfieldportal.themekit.CustomizableIcons
import com.playfieldportal.themekit.PfpThemeBundle
import com.playfieldportal.themekit.PfpThemeCodec
import com.playfieldportal.themekit.PfpThemeManifest
import com.playfieldportal.themekit.PfpThemeSource
import com.playfieldportal.themekit.ThemeImage
import com.playfieldportal.themekit.ThemeMotion
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The user's saved-theme library. Each saved theme is a `.pfptheme` bundle
 * (docs/xmb-theme-creator-plan.md) under filesDir/pfpthemes/, with extracted sidecar
 * images ({id}.preview.jpg, {id}.wallpaper.jpg) for fast list thumbnails and applying —
 * the bundle itself stays intact for future export/sharing (Phase C).
 *
 * Applying a theme drives the same one-color cascade prefs the live XMB observes
 * (custom wallpaper + accent override). The wallpaper is copied into the standard
 * wallpaper dir so deleting a saved theme never dangles the active wallpaper.
 */
@Singleton
class PfpThemeStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class SavedTheme(
        val id: String,
        val name: String,
        val accentArgb: Long?,
        /** Absolute path of the thumbnail sidecar, when present. */
        val previewPath: String?,
    )

    private val dir = File(context.filesDir, "pfpthemes")

    private val _themes = MutableStateFlow(scan())
    val themes: StateFlow<List<SavedTheme>> = _themes.asStateFlow()

    /** Quick Create: a photo becomes a theme — accent auto-derived from its dominant hue. */
    suspend fun createFromImage(uri: Uri, name: String? = null): SavedTheme? = withContext(Dispatchers.IO) {
        // Capped read + bounds-checked decode: crafted headers with absurd dimensions
        // never reach a pixel allocation.
        val bitmap = runCatching {
            context.contentResolver.openInputStream(uri)
                ?.use { with(SafeMedia) { it.readCapped() } }
                ?.let { SafeMedia.decodeBitmapCapped(it) }
        }.getOrNull() ?: return@withContext null

        val scaled = downscale(bitmap, maxEdge = 1920)
        val accent = AccentDeriver.deriveAccent(scaled.toBmpImage())?.toUInt()?.toLong()
        val themeName = name ?: nextDefaultName()
        save(
            name = themeName,
            wallpaper = scaled,
            accentArgb = accent,
            source = PfpThemeSource(type = PfpThemeSource.TYPE_USER_CREATED),
        ).also { if (scaled !== bitmap) bitmap.recycle() }
    }

    /** PTF import lands in the library too, so converted PSP themes are switchable later. */
    suspend fun createFromPtf(name: String, wallpaper: BmpImage, accentArgb: Long?, sourceFile: String?, firmware: String?): SavedTheme? =
        withContext(Dispatchers.IO) {
            val bitmap = Bitmap.createBitmap(wallpaper.width, wallpaper.height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(wallpaper.argb, 0, wallpaper.width, 0, 0, wallpaper.width, wallpaper.height)
            save(
                name = name,
                wallpaper = bitmap,
                accentArgb = accentArgb,
                source = PfpThemeSource(type = PfpThemeSource.TYPE_PTF_IMPORT, file = sourceFile, firmware = firmware),
            )
        }

    /** Applies a saved theme: wallpaper + wave style + accent + custom icons through the standard cascade prefs. */
    suspend fun apply(id: String): Boolean = withContext(Dispatchers.IO) {
        val wallpaperSidecar = File(dir, "$id.wallpaper.jpg")
        // read(File), not read(bytes): a bundle carrying a motion wallpaper is tens of MB, and
        // readBytes() used to put the whole thing on the heap before the codec inflated the
        // video on top of it. The file overload streams the motion entry instead.
        val bundle = runCatching { PfpThemeCodec.read(File(dir, "$id.pfptheme")) }.getOrNull()
            ?: return@withContext false

        // Copy into the standard wallpaper dir (same convention as Set-as-Wallpaper / PTF import).
        val destDir = File(context.filesDir, "wallpaper").apply { mkdirs() }
        val dest = File(destDir, "wallpaper_theme_${System.currentTimeMillis()}.jpg")
        val wallpaperOk = wallpaperSidecar.isFile && runCatching { wallpaperSidecar.copyTo(dest, overwrite = true) }.isSuccess

        // Extract custom icon slots (schema v3) — wipe first so the previous theme's icons
        // never bleed into this one. The stamp pref tells the live XMB to (re)load the dir;
        // its absence means "no custom icons". Entries keep the extension they shipped with
        // (png stills, gif animations), and console art lands under its sysicon_<id> key in
        // the same dir — the theme tier of the two-tier render precedence. The USER tier
        // (filesDir/custom-icons) is deliberately untouched: applying a theme never deletes
        // a user pick.
        val iconsDir = File(context.filesDir, THEME_ICONS_DIR)
        iconsDir.deleteRecursively()
        val iconEntries: Map<String, com.playfieldportal.themekit.ThemeImage> = buildMap {
            putAll(bundle.icons)
            for ((platformId, image) in bundle.sysicons) put("sysicon_$platformId", image)
        }
        if (iconEntries.isNotEmpty()) {
            iconsDir.mkdirs()
            // Keys were validated against the registries by the codec — safe as file names.
            for ((key, image) in iconEntries) {
                File(iconsDir, "$key.${image.extension.lowercase()}").writeBytes(image.bytes)
            }
        }

        // Motion wallpaper (schema v3): write the entry into the standard wallpaper dir and
        // SET the motion key. A theme without motion still clears any previous theme's video
        // (the remove below) — the set-or-remove contract the still wallpaper already uses.
        val motionDest = bundle.motion?.let { motion ->
            runCatching {
                val motionDir = File(context.filesDir, "wallpaper").apply { mkdirs() }
                val dest = File(motionDir, "wallpaper_theme_${System.currentTimeMillis()}.${motion.extension.lowercase()}")
                // Bundle -> disk in one streamed pass; the video never lands on the heap.
                FileOutputStream(dest).use { out -> motion.copyTo(out) }
                dest
            }.onFailure {
                Timber.w(it, "PfpThemeStore: could not extract the motion wallpaper")
            }.getOrNull()
        }

        val accent = bundle.manifest.accentColor.toAccentArgbOrNull()
        // The theme owns the unified icon tint too: an explicit hex applies, "auto" (or
        // malformed) clears back to the default derivation — same wholesale-look contract
        // as the accent override.
        val iconColor = bundle.manifest.iconColor
            .takeIf { it != PfpThemeManifest.ICON_COLOR_AUTO }
            ?.toAccentArgbOrNull()
        // Same wholesale-look contract for text: an explicit hex applies, "auto" (or malformed)
        // REMOVES the pref rather than inheriting the previous theme's — a theme that says
        // nothing about text must not leave the last theme's colour behind.
        val textColor = bundle.manifest.textColor
            .takeIf { it != PfpThemeManifest.ICON_COLOR_AUTO }
            ?.toAccentArgbOrNull()
        // Per-theme XMB geometry (Theme Studio alignment assist). Sanitized here AND on
        // read so a hostile manifest can never wedge the crossbar offscreen.
        val layoutJson = bundle.manifest.layout
            ?.let(com.playfieldportal.themekit.XmbLayoutSpecCodec::sanitize)
            ?.takeUnless { it == com.playfieldportal.themekit.XmbLayoutSpec.DEFAULT }
            ?.let(com.playfieldportal.themekit.XmbLayoutSpecCodec::encode)
        // Keep the manifest's wave treatment in the same preference contract as Display settings.
        // Unknown values fail safe to the normal animated wave instead of persisting an invalid enum.
        val waveStyle = when (bundle.manifest.waveStyle) {
            PfpThemeManifest.WAVE_STATIC -> WAVE_STYLE_STATIC
            PfpThemeManifest.WAVE_REDUCED -> WAVE_STYLE_REDUCED
            else -> WAVE_STYLE_ANIMATED
        }
        val appliedName = _themes.value.firstOrNull { it.id == id }?.name ?: "Custom Theme"
        // Surveyed before the transaction opens — edit{}'s transform can be re-run, and a bitmap
        // decode must not repeat under the lock. Null when the theme carries no wallpaper, which
        // correctly removes any previous theme's survey below.
        val luma = if (wallpaperOk) WallpaperLuminanceProbe.survey(dest.absolutePath) else null
        context.pfpDataStore.edit { prefs ->
            prefs[KEY_APPLIED_THEME_NAME] = appliedName
            prefs[KEY_WAVE_STYLE] = waveStyle
            // Wave-only themes carry no wallpaper: clear any previous theme's wallpaper so the
            // look reverts to the live wave background instead of lingering — same set-or-remove
            // contract as the accent, icon-color and layout overrides below. The MOTION key
            // clears with it: applying a theme without a motion entry must never leave the
            // previous theme's video looping behind it. (Since schema v3 a bundle CAN carry
            // motion — the write branch below.)
            if (motionDest != null) prefs[KEY_MOTION_WALLPAPER] = motionDest.absolutePath else prefs.remove(KEY_MOTION_WALLPAPER)
            if (wallpaperOk) prefs[KEY_CUSTOM_WALLPAPER] = dest.absolutePath else prefs.remove(KEY_CUSTOM_WALLPAPER)
            prefs.setWallpaperLuma(luma)
            if (accent != null) prefs[KEY_ACCENT_OVERRIDE] = accent else prefs.remove(KEY_ACCENT_OVERRIDE)
            if (iconColor != null) prefs[KEY_ICON_COLOR] = iconColor else prefs.remove(KEY_ICON_COLOR)
            if (textColor != null) prefs[KEY_TEXT_COLOR] = textColor else prefs.remove(KEY_TEXT_COLOR)
            if (layoutJson != null) prefs[KEY_THEME_LAYOUT] = layoutJson else prefs.remove(KEY_THEME_LAYOUT)
            if (iconEntries.isNotEmpty()) {
                prefs[KEY_THEME_ICONS_STAMP] = System.currentTimeMillis()
            } else {
                prefs.remove(KEY_THEME_ICONS_STAMP)
            }
        }
        true
    }

    /**
     * Resets the applied theme back to the stock look: clears every cascade pref this store
     * (and the PTF/photo importers) can set, and removes the extracted icon slots and copied
     * wallpaper files they left behind. The saved-theme library and the user's color-scheme
     * preset are untouched.
     */
    suspend fun resetApplied(): Unit = withContext(Dispatchers.IO) {
        context.pfpDataStore.edit { prefs ->
            prefs.remove(KEY_CUSTOM_WALLPAPER)
            prefs.remove(KEY_MOTION_WALLPAPER)
            prefs.clearWallpaperLuma()
            prefs.remove(KEY_ACCENT_OVERRIDE)
            prefs.remove(KEY_ICON_COLOR)
            prefs.remove(KEY_TEXT_COLOR)
            prefs.remove(KEY_WAVE_STYLE)
            prefs.remove(KEY_THEME_LAYOUT)
            prefs.remove(KEY_THEME_ICONS_STAMP)
            prefs.remove(KEY_APPLIED_THEME_NAME)
        }
        // Prefs are gone first, so nothing references these files when they're deleted.
        File(context.filesDir, THEME_ICONS_DIR).deleteRecursively()
        File(context.filesDir, "wallpaper").listFiles()?.forEach { it.delete() }
    }

    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        listOf("$id.pfptheme", "$id.preview.jpg", "$id.wallpaper.jpg")
            .forEach { File(dir, it).delete() }
        _themes.value = scan()
    }

    /**
     * Copies a saved bundle into the shareable cache (covered by the app's FileProvider
     * cache-path root) named after the theme, ready for ACTION_SEND.
     */
    suspend fun exportForShare(id: String): File? = withContext(Dispatchers.IO) {
        val src = File(dir, "$id.pfptheme")
        if (!src.isFile) return@withContext null
        val name = _themes.value.firstOrNull { it.id == id }?.name ?: id
        val safe = name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { id }.replace(' ', '_')
        runCatching {
            val out = File(File(context.cacheDir, "shared_themes").apply { mkdirs() }, "$safe.pfptheme")
            src.copyTo(out, overwrite = true)
            out
        }.onFailure { Timber.w(it, "PfpThemeStore: export failed") }.getOrNull()
    }

    /**
     * Why an import did not land.
     *
     * Naming these apart is not cosmetic. Every one of them used to collapse into a null and
     * surface as "not a valid .pfptheme file", which sends the user hunting for a corrupt
     * bundle when the bundle is fine and the heap is not.
     */
    sealed interface ImportResult {
        data class Success(val theme: SavedTheme) : ImportResult

        /** The URI could not be opened or read — permission lapsed, provider gone, I/O error. */
        data class Unreadable(val cause: Throwable?) : ImportResult

        /** Bigger than [SafeMedia.MAX_THEME_FILE_BYTES]; refused before anything was inflated. */
        data object TooLarge : ImportResult

        /**
         * A well-formed bundle this device has no heap to materialise. Import is all-in-memory
         * (the whole file, then every inflated entry), so a large `motion.*` entry can exceed
         * the heap on the very device that exported the bundle.
         */
        data object OutOfMemory : ImportResult

        /** Not a `.pfptheme`: not a zip, no manifest, wrong manifest type, or a zip cap tripped. */
        data object NotABundle : ImportResult

        /** Wallpaper bytes are present but will not decode — a genuinely damaged bundle. */
        data object DamagedWallpaper : ImportResult

        /** Read and parsed; writing it into the library failed. */
        data class NotSaved(val cause: Throwable?) : ImportResult
    }

    /**
     * Imports a `.pfptheme` bundle picked via SAF into the library. Null = not a valid bundle.
     *
     * For callers that only need "did it land"; [importBundleDetailed] carries the reason.
     */
    suspend fun importBundle(uri: Uri): SavedTheme? =
        (importBundleDetailed(uri) as? ImportResult.Success)?.theme

    /**
     * [importBundle] with the failure named.
     *
     * `OutOfMemoryError` is caught deliberately here rather than by accident. It was already
     * being swallowed — `runCatching` catches `Throwable`, not `Exception` — so this narrows
     * what was happening silently instead of adding a new catch. What it guards is a local byte
     * array released on the way out; no half-written library state survives the return.
     */
    suspend fun importBundleDetailed(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        // Land the picked file on disk FIRST, streamed, then read it from there.
        //
        // The bundle is stored verbatim anyway, so the old order — hold the whole file as a
        // ByteArray, parse it, then write those same bytes out — bought nothing and cost the
        // import its own file size twice over (the array, plus the buffer that doubled past it
        // while reading). Staging first makes the import's heap cost independent of the file.
        dir.mkdirs()
        val staging = File(dir, "import_${System.currentTimeMillis()}.tmp")
        val copied = try {
            val stream = context.contentResolver.openInputStream(uri)
            if (stream == null) {
                Timber.w("PfpThemeStore: no stream for %s", uri)
                return@withContext ImportResult.Unreadable(null)
            }
            stream.use { input ->
                FileOutputStream(staging).use { out -> with(SafeMedia) { input.copyCappedTo(out) } }
            }
        } catch (e: Exception) {
            staging.delete()
            Timber.w(e, "PfpThemeStore: could not read the bundle")
            return@withContext ImportResult.Unreadable(e)
        }
        if (copied == null) {
            staging.delete()
            Timber.w(
                "PfpThemeStore: bundle exceeds the %d-byte read cap",
                SafeMedia.MAX_THEME_FILE_BYTES,
            )
            return@withContext ImportResult.TooLarge
        }

        val parsed = try {
            // read(File): the motion entry stays on disk and is streamed later by apply().
            PfpThemeCodec.read(staging)
        } catch (e: OutOfMemoryError) {
            staging.delete()
            Timber.w(e, "PfpThemeStore: out of heap parsing a %d-byte bundle", copied)
            return@withContext ImportResult.OutOfMemory
        }
        val bundle = parsed ?: run {
            staging.delete()
            Timber.w("PfpThemeStore: %d bytes are not a .pfptheme bundle", copied)
            return@withContext ImportResult.NotABundle
        }
        // Wallpaper is optional: a wave-only theme (accent + wave style, no image) is a valid
        // bundle — apply() keeps the live wave background when the sidecar is absent. Only bytes
        // that are present but won't decode are a real corruption and reject the import.
        val wallpaper = bundle.wallpaper?.let {
            SafeMedia.decodeBitmapCapped(it) ?: run {
                staging.delete()
                // Caveat worth knowing: decodeBitmapCapped swallows its own OutOfMemoryError
                // (runCatching, again) and returns null, so a wallpaper too big for the heap
                // still reports as damaged here. Fixing that means changing SafeMedia's
                // contract for all of its callers, which is not this change.
                Timber.w("PfpThemeStore: wallpaper is %d bytes but would not decode", it.size)
                return@withContext ImportResult.DamagedWallpaper
            }
        }
        // Store the bundle VERBATIM (not re-encoded through save()) so fields this build
        // doesn't materialize — custom icons, wave style, layout spec — survive the
        // import → library → apply round-trip.
        val saved = runCatching {
            val id = "pfp_${System.currentTimeMillis()}"
            val name = bundle.manifest.name.ifBlank { nextDefaultName() }
            // The staged file IS the stored bundle — a rename, not a second copy. Note this
            // invalidates `bundle.motion`, which streams from the staged path: safe only because
            // import never extracts the video (apply() does, from the stored path). Anything
            // added here that touches bundle.motion must run before this rename.
            val stored = File(dir, "$id.pfptheme")
            if (!staging.renameTo(stored)) {
                staging.copyTo(stored, overwrite = true)
                staging.delete()
            }
            // Wave-only themes leave no wallpaper sidecar — apply() treats its absence as "keep
            // the wave background", so nothing downstream dangles.
            wallpaper?.let { wp ->
                FileOutputStream(File(dir, "$id.wallpaper.jpg")).use { wp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            }
            // Preview sidecar: prefer the bundle's rendered preview; else derive from the
            // wallpaper; a wave-only theme with no preview simply has no thumbnail.
            val preview = bundle.preview?.let { SafeMedia.decodeBitmapCapped(it) }
                ?: wallpaper?.let { downscale(it, maxEdge = 480) }
            preview?.let { p ->
                try {
                    FileOutputStream(File(dir, "$id.preview.jpg")).use { p.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                } finally {
                    if (p !== wallpaper) p.recycle()
                }
            }
            _themes.value = scan()
            SavedTheme(
                id,
                name,
                bundle.manifest.accentColor.toAccentArgbOrNull(),
                File(dir, "$id.preview.jpg").takeIf { it.isFile }?.absolutePath,
            )
        }
        wallpaper?.recycle()
        // A staging file still present here means the save leg failed before the rename.
        if (staging.exists()) staging.delete()
        saved.fold(
            onSuccess = { ImportResult.Success(it) },
            onFailure = { cause ->
                Timber.w(cause, "PfpThemeStore: bundle parsed but could not be saved")
                if (cause is OutOfMemoryError) ImportResult.OutOfMemory
                else ImportResult.NotSaved(cause)
            },
        )
    }

    /**
     * Exports the device's current look as one `.pfptheme` bundle — the exact inverse of
     * [apply]. Per customizable slot (theme slots plus console slots, [CustomizableIcons.ALL]),
     * the user's pick wins, else the applied theme's icon, else the slot is omitted; GIFs are
     * copied verbatim and non-PNG stills are re-encoded to PNG.
     *
     * Also captures: the current wallpaper (still — as `wallpaper.png`), the motion wallpaper
     * (`motion.<mp4|webm|gif>`), accent, icon color, wave style and the portable XMB geometry.
     *
     * **`XmbLayoutAdjust` is deliberately excluded.** The user's scale/offset from Adjust XMB
     * Layout is stored per screen bucket in `xmbLayoutAdjustMap` and is device-specific —
     * shipping it to another device would misplace the crossbar. `XmbLayoutSpec` (the applied
     * theme's / bar-position geometry) is the portable part and is the one that travels.
     */
    suspend fun saveCurrentLook(name: String): SavedTheme? = withContext(Dispatchers.IO) {
        val prefs = context.pfpDataStore.data.first()

        // ── icons: user tier over theme tier, per slot ──
        val customDir = File(context.filesDir, CustomIconStore.CUSTOM_ICONS_DIR)
        val themeIconsDir = File(context.filesDir, THEME_ICONS_DIR)
        val icons = mutableMapOf<String, ThemeImage>()
        val sysicons = mutableMapOf<String, ThemeImage>()
        for (slot in CustomizableIcons.ALL) {
            val source = findIconFile(customDir, slot.key) ?: findIconFile(themeIconsDir, slot.key) ?: continue
            if (source.extension.equals("gif", ignoreCase = true)) {
                // GIFs travel verbatim — re-encoding an animation is out of scope.
                iconsOrSysicons(slot.key, ThemeImage(source.readBytes(), "gif"), icons, sysicons)
            } else {
                // Re-encode non-PNG stills (jpg/webp/bmp/heif) to PNG so the bundle entry is
                // self-describing; a decode failure skips the slot rather than shipping junk.
                val bitmap = SafeMedia.decodeFileCapped(source.absolutePath, maxDimension = 512)
                    ?: continue
                val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                bitmap.recycle()
                iconsOrSysicons(slot.key, ThemeImage(png, "png"), icons, sysicons)
            }
        }

        // ── wallpaper + motion ──
        val wallpaperBitmap = prefs[KEY_CUSTOM_WALLPAPER]
            ?.let { runCatching { SafeMedia.decodeFileCapped(it, maxDimension = 1920) }.getOrNull() }
        val wallpaperPng = wallpaperBitmap?.let {
            ByteArrayOutputStream().also { out -> it.compress(Bitmap.CompressFormat.PNG, 100, out) }.toByteArray()
        }
        // Referenced, not loaded: the codec streams this file into the zip when it writes the
        // entry. readBytes() here was the export-side half of the same problem — it is how a
        // 50 MB motion wallpaper got into a bundle that the importer then could not open.
        val motion = prefs[KEY_MOTION_WALLPAPER]
            ?.let { path ->
                val file = File(path)
                val ext = file.extension.lowercase()
                if (file.isFile && ext in setOf("mp4", "webm", "gif")) ThemeMotion.ofFile(file, ext) else null
            }

        // ── manifest from the live cascade prefs ──
        val manifest = PfpThemeManifest(
            name = name.ifBlank { nextDefaultName() },
            accentColor = prefs[KEY_ACCENT_OVERRIDE]?.let { "#%06X".format(it and 0xFFFFFF) } ?: "",
            iconColor = prefs[KEY_ICON_COLOR]?.let { "#%06X".format(it and 0xFFFFFF) }
                ?: PfpThemeManifest.ICON_COLOR_AUTO,
            textColor = prefs[KEY_TEXT_COLOR]?.let { "#%06X".format(it and 0xFFFFFF) }
                ?: PfpThemeManifest.ICON_COLOR_AUTO,
            waveStyle = when (prefs[KEY_WAVE_STYLE]) {
                WAVE_STYLE_STATIC -> PfpThemeManifest.WAVE_STATIC
                WAVE_STYLE_REDUCED -> PfpThemeManifest.WAVE_REDUCED
                else -> PfpThemeManifest.WAVE_ANIMATED
            },
            layout = prefs[KEY_THEME_LAYOUT]
                ?.let { com.playfieldportal.themekit.XmbLayoutSpecCodec.decode(it) }
                ?.let(com.playfieldportal.themekit.XmbLayoutSpecCodec::sanitize)
                ?.takeUnless { it == com.playfieldportal.themekit.XmbLayoutSpec.DEFAULT },
            source = PfpThemeSource(type = PfpThemeSource.TYPE_USER_CREATED),
            created = LocalDate.now().toString(),
        )
        // Placeholder thumbnail from the wallpaper — Phase C's preview gate replaces this
        // with a real rendered-XMB frame at export time.
        val preview = wallpaperBitmap?.let { downscale(it, maxEdge = 480) }
        val previewBytes = preview?.let {
            ByteArrayOutputStream().also { out -> it.compress(Bitmap.CompressFormat.PNG, 90, out) }.toByteArray()
        }

        return@withContext runCatching {
            dir.mkdirs()
            val id = "pfp_${System.currentTimeMillis()}"
            // Streamed straight to the file: write(bundle) would build the entire archive —
            // motion video and all — as one ByteArray first.
            FileOutputStream(File(dir, "$id.pfptheme")).use { out ->
                PfpThemeCodec.write(
                    PfpThemeBundle(
                        manifest = manifest,
                        wallpaper = wallpaperPng,
                        preview = previewBytes,
                        icons = icons,
                        sysicons = sysicons,
                        motion = motion,
                    ),
                    out,
                )
            }
            wallpaperBitmap?.let {
                FileOutputStream(File(dir, "$id.wallpaper.jpg")).use { out -> it.compress(Bitmap.CompressFormat.JPEG, 92, out) }
            }
            preview?.let {
                FileOutputStream(File(dir, "$id.preview.jpg")).use { out -> it.compress(Bitmap.CompressFormat.JPEG, 88, out) }
                if (it !== wallpaperBitmap) it.recycle()
            }
            wallpaperBitmap?.recycle()

            _themes.value = scan()
            SavedTheme(
                id,
                manifest.name,
                manifest.accentColor.toAccentArgbOrNull(),
                File(dir, "$id.preview.jpg").takeIf { f -> f.isFile }?.absolutePath,
            )
        }.onFailure { Timber.w(it, "PfpThemeStore: saveCurrentLook failed") }.getOrNull()
    }

    /** Routes [image] into the icons or sysicons map by the slot's group. */
    private fun iconsOrSysicons(
        slotKey: String,
        image: ThemeImage,
        icons: MutableMap<String, ThemeImage>,
        sysicons: MutableMap<String, ThemeImage>,
    ) {
        if (slotKey.startsWith("sysicon_")) sysicons[slotKey.removePrefix("sysicon_")] = image
        else icons[slotKey] = image
    }

    /** The stored file for [slotKey] under any accepted extension, or null. */
    private fun findIconFile(dir: File, slotKey: String): File? =
        setOf("png", "jpg", "webp", "bmp", "heif", "gif")
            .asSequence()
            .map { File(dir, "$slotKey.$it") }
            .firstOrNull { it.isFile }

    // ── internals ────────────────────────────────────────────────────────────

    private fun save(name: String, wallpaper: Bitmap, accentArgb: Long?, source: PfpThemeSource): SavedTheme? {
        return runCatching {
            dir.mkdirs()
            val id = "pfp_${System.currentTimeMillis()}"

            val manifest = PfpThemeManifest(
                name = name,
                accentColor = accentArgb?.let { "#%06X".format(it and 0xFFFFFF) } ?: "",
                source = source,
                created = LocalDate.now().toString(),
            )
            val wallpaperPng = ByteArrayOutputStream()
                .also { wallpaper.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            // Placeholder thumbnail from the wallpaper — Phase C's preview gate replaces this
            // with a real rendered-XMB frame at export time.
            val preview = downscale(wallpaper, maxEdge = 480)
            val previewBytes = ByteArrayOutputStream()
                .also { preview.compress(Bitmap.CompressFormat.PNG, 90, it) }.toByteArray()

            FileOutputStream(File(dir, "$id.pfptheme")).use { out ->
                PfpThemeCodec.write(PfpThemeBundle(manifest, wallpaperPng, previewBytes), out)
            }
            FileOutputStream(File(dir, "$id.wallpaper.jpg")).use { wallpaper.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            FileOutputStream(File(dir, "$id.preview.jpg")).use { preview.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (preview !== wallpaper) preview.recycle()

            _themes.value = scan()
            SavedTheme(id, name, accentArgb, File(dir, "$id.preview.jpg").absolutePath)
        }.onFailure { Timber.w(it, "PfpThemeStore: save failed") }.getOrNull()
    }

    private fun scan(): List<SavedTheme> =
        dir.listFiles { f -> f.name.endsWith(".pfptheme") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .mapNotNull { file ->
                val id = file.name.removeSuffix(".pfptheme")
                // Manifest only. This used to be a full read(file.readBytes()), which inflated
                // every theme in the library — motion videos included — to recover a name and an
                // accent that live in the first 300 bytes of the archive. On a library holding a
                // 52 MB theme that threw OutOfMemoryError, and because the throw was swallowed
                // per file, the theme vanished from this list: it imported, wrote its bundle and
                // both sidecars, and then never appeared in Themes.
                val manifest = runCatching { PfpThemeCodec.readManifest(file) }
                    .onFailure { Timber.w(it, "PfpThemeStore: could not read %s", file.name) }
                    .getOrNull() ?: return@mapNotNull null
                SavedTheme(
                    id = id,
                    name = manifest.name,
                    accentArgb = manifest.accentColor.toAccentArgbOrNull(),
                    previewPath = File(dir, "$id.preview.jpg").takeIf { it.isFile }?.absolutePath,
                )
            }

    private fun nextDefaultName(): String {
        val existing = _themes.value.map { it.name }.toSet()
        var n = 1
        while ("Custom Theme $n" in existing) n++
        return "Custom Theme $n"
    }

    private fun downscale(src: Bitmap, maxEdge: Int): Bitmap {
        val edge = maxOf(src.width, src.height)
        if (edge <= maxEdge) return src
        val scale = maxEdge.toFloat() / edge
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true)
    }

    private fun Bitmap.toBmpImage(): BmpImage {
        val px = IntArray(width * height)
        getPixels(px, 0, width, 0, 0, width, height)
        return BmpImage(width, height, px)
    }

    private fun String.toAccentArgbOrNull(): Long? {
        val hex = removePrefix("#")
        if (hex.length != 6) return null
        return hex.toLongOrNull(16)?.let { 0xFF000000L or it }
    }

    companion object {
        // Must match XMBViewModel / ThemesSettingsViewModel — shared cascade prefs contract.
        private val KEY_CUSTOM_WALLPAPER = stringPreferencesKey("display_custom_wallpaper")
        // Cleared (never set) by this store: theme bundles carry no motion wallpaper, so any
        // previously-applied one must not survive a theme apply/reset.
        private val KEY_MOTION_WALLPAPER = stringPreferencesKey("display_motion_wallpaper")
        private val KEY_WAVE_STYLE = stringPreferencesKey("display_wave_style")
        private val KEY_ACCENT_OVERRIDE = longPreferencesKey("theme_accent_override")
        private val KEY_ICON_COLOR = longPreferencesKey("theme_icon_color")
        // Must match DisplaySettingsViewModel / XMBViewModel — the user's font colour, which a
        // theme bundle can also carry.
        private val KEY_TEXT_COLOR = longPreferencesKey("display_text_color")

        private const val WAVE_STYLE_ANIMATED = "ANIMATED"
        private const val WAVE_STYLE_REDUCED = "REDUCED"
        private const val WAVE_STYLE_STATIC = "STATIC"

        /** Extracted custom icons of the applied theme, under filesDir. */
        const val THEME_ICONS_DIR = "theme-icons"

        /**
         * Present ⇒ the applied theme carries custom icons in [THEME_ICONS_DIR]; the value
         * only bumps so observers reload. Removed when a theme/preset without icons applies.
         */
        val KEY_THEME_ICONS_STAMP = longPreferencesKey("theme_icons_stamp")

        /**
         * The applied theme's XmbLayoutSpec override as XmbLayoutSpecCodec JSON.
         * Absent ⇒ the app's default geometry.
         */
        val KEY_THEME_LAYOUT = stringPreferencesKey("theme_layout_spec")

        /** Display name of the theme most recently applied through this store (any source:
         *  My Themes, Quick Create, PTF or .pfptheme import). Cleared by [resetApplied] —
         *  absence means the stock look. Drives the "Active Theme" row in Settings. */
        val KEY_APPLIED_THEME_NAME = stringPreferencesKey("theme_applied_name")
    }
}
