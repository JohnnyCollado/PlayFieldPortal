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
        val bitmap = runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
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

    /** Applies a saved theme: wallpaper + accent through the standard cascade prefs. */
    suspend fun apply(id: String): Boolean = withContext(Dispatchers.IO) {
        val wallpaperSidecar = File(dir, "$id.wallpaper.jpg")
        val bundle = runCatching { PfpThemeCodec.read(File(dir, "$id.pfptheme").readBytes()) }.getOrNull()
            ?: return@withContext false

        // Copy into the standard wallpaper dir (same convention as Set-as-Wallpaper / PTF import).
        val destDir = File(context.filesDir, "wallpaper").apply { mkdirs() }
        val dest = File(destDir, "wallpaper_theme_${System.currentTimeMillis()}.jpg")
        val wallpaperOk = wallpaperSidecar.isFile && runCatching { wallpaperSidecar.copyTo(dest, overwrite = true) }.isSuccess

        val accent = bundle.manifest.accentColor.toAccentArgbOrNull()
        context.pfpDataStore.edit { prefs ->
            if (wallpaperOk) prefs[KEY_CUSTOM_WALLPAPER] = dest.absolutePath
            if (accent != null) prefs[KEY_ACCENT_OVERRIDE] = accent else prefs.remove(KEY_ACCENT_OVERRIDE)
        }
        true
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
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext null
        val bundle = PfpThemeCodec.read(bytes) ?: return@withContext null
        // v1 themes are wallpaper-carrying; the sidecars (and preview) regenerate from it.
        val wallpaper = bundle.wallpaper
            ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            ?: return@withContext null
        save(
            name = bundle.manifest.name.ifBlank { nextDefaultName() },
            wallpaper = wallpaper,
            accentArgb = bundle.manifest.accentColor.toAccentArgbOrNull(),
            source = bundle.manifest.source ?: PfpThemeSource(type = PfpThemeSource.TYPE_USER_CREATED),
        )
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

    private companion object {
        // Must match XMBViewModel / DisplaySettingsViewModel — shared cascade prefs contract.
        val KEY_CUSTOM_WALLPAPER = stringPreferencesKey("display_custom_wallpaper")
        val KEY_ACCENT_OVERRIDE = longPreferencesKey("theme_accent_override")
    }
}
