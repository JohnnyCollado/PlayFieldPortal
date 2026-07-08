package com.playfieldportal.core.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.themekit.AccentDeriver
import com.playfieldportal.themekit.BmpImage
import com.playfieldportal.themekit.PfpThemeBundle
import com.playfieldportal.themekit.PfpThemeCodec
import com.playfieldportal.themekit.PfpThemeManifest
import com.playfieldportal.themekit.PfpThemeSource
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

    /** Applies a saved theme: wallpaper + accent + custom icons through the standard cascade prefs. */
    suspend fun apply(id: String): Boolean = withContext(Dispatchers.IO) {
        val wallpaperSidecar = File(dir, "$id.wallpaper.jpg")
        val bundle = runCatching { PfpThemeCodec.read(File(dir, "$id.pfptheme").readBytes()) }.getOrNull()
            ?: return@withContext false

        // Copy into the standard wallpaper dir (same convention as Set-as-Wallpaper / PTF import).
        val destDir = File(context.filesDir, "wallpaper").apply { mkdirs() }
        val dest = File(destDir, "wallpaper_theme_${System.currentTimeMillis()}.jpg")
        val wallpaperOk = wallpaperSidecar.isFile && runCatching { wallpaperSidecar.copyTo(dest, overwrite = true) }.isSuccess

        // Extract custom icon slots (schema v2) — wipe first so the previous theme's icons
        // never bleed into this one. The stamp pref tells the live XMB to (re)load the dir;
        // its absence means "no custom icons".
        val iconsDir = File(context.filesDir, THEME_ICONS_DIR)
        iconsDir.deleteRecursively()
        if (bundle.icons.isNotEmpty()) {
            iconsDir.mkdirs()
            // Keys were validated against IconSlots by the codec — safe as file names.
            for ((key, png) in bundle.icons) File(iconsDir, "$key.png").writeBytes(png)
        }

        val accent = bundle.manifest.accentColor.toAccentArgbOrNull()
        // The theme owns the unified icon tint too: an explicit hex applies, "auto" (or
        // malformed) clears back to the default derivation — same wholesale-look contract
        // as the accent override.
        val iconColor = bundle.manifest.iconColor
            .takeIf { it != PfpThemeManifest.ICON_COLOR_AUTO }
            ?.toAccentArgbOrNull()
        // Per-theme XMB geometry (Theme Studio alignment assist). Sanitized here AND on
        // read so a hostile manifest can never wedge the crossbar offscreen.
        val layoutJson = bundle.manifest.layout
            ?.let(com.playfieldportal.themekit.XmbLayoutSpecCodec::sanitize)
            ?.takeUnless { it == com.playfieldportal.themekit.XmbLayoutSpec.DEFAULT }
            ?.let(com.playfieldportal.themekit.XmbLayoutSpecCodec::encode)
        context.pfpDataStore.edit { prefs ->
            if (wallpaperOk) prefs[KEY_CUSTOM_WALLPAPER] = dest.absolutePath
            if (accent != null) prefs[KEY_ACCENT_OVERRIDE] = accent else prefs.remove(KEY_ACCENT_OVERRIDE)
            if (iconColor != null) prefs[KEY_ICON_COLOR] = iconColor else prefs.remove(KEY_ICON_COLOR)
            if (layoutJson != null) prefs[KEY_THEME_LAYOUT] = layoutJson else prefs.remove(KEY_THEME_LAYOUT)
            if (bundle.icons.isNotEmpty()) {
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
            prefs.remove(KEY_ACCENT_OVERRIDE)
            prefs.remove(KEY_ICON_COLOR)
            prefs.remove(KEY_THEME_LAYOUT)
            prefs.remove(KEY_THEME_ICONS_STAMP)
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

    /** Imports a `.pfptheme` bundle picked via SAF into the library. Null = not a valid bundle. */
    suspend fun importBundle(uri: Uri): SavedTheme? = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { with(SafeMedia) { it.readCapped() } }
        }.getOrNull() ?: return@withContext null
        val bundle = PfpThemeCodec.read(bytes) ?: return@withContext null
        // v1 themes are wallpaper-carrying; the sidecars (and preview) regenerate from it.
        val wallpaper = bundle.wallpaper
            ?.let { SafeMedia.decodeBitmapCapped(it) }
            ?: return@withContext null
        // Store the bundle VERBATIM (not re-encoded through save()) so fields this build
        // doesn't materialize — custom icons, wave style, layout spec — survive the
        // import → library → apply round-trip.
        runCatching {
            dir.mkdirs()
            val id = "pfp_${System.currentTimeMillis()}"
            val name = bundle.manifest.name.ifBlank { nextDefaultName() }
            File(dir, "$id.pfptheme").writeBytes(bytes)
            FileOutputStream(File(dir, "$id.wallpaper.jpg")).use { wallpaper.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            val preview = bundle.preview?.let { SafeMedia.decodeBitmapCapped(it) }
                ?: downscale(wallpaper, maxEdge = 480)
            FileOutputStream(File(dir, "$id.preview.jpg")).use { preview.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (preview !== wallpaper) preview.recycle()
            _themes.value = scan()
            SavedTheme(id, name, bundle.manifest.accentColor.toAccentArgbOrNull(), File(dir, "$id.preview.jpg").absolutePath)
        }.onFailure { Timber.w(it, "PfpThemeStore: import failed") }.getOrNull()
    }

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

            File(dir, "$id.pfptheme").writeBytes(
                PfpThemeCodec.write(PfpThemeBundle(manifest, wallpaperPng, previewBytes)),
            )
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
                val bundle = runCatching { PfpThemeCodec.read(file.readBytes()) }.getOrNull() ?: return@mapNotNull null
                SavedTheme(
                    id = id,
                    name = bundle.manifest.name,
                    accentArgb = bundle.manifest.accentColor.toAccentArgbOrNull(),
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
        private val KEY_ACCENT_OVERRIDE = longPreferencesKey("theme_accent_override")
        private val KEY_ICON_COLOR = longPreferencesKey("theme_icon_color")

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
    }
}
