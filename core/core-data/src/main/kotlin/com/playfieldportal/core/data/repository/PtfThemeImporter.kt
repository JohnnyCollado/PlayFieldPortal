package com.playfieldportal.core.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.themekit.BmpImage
import com.playfieldportal.themekit.AccentDeriver
import com.playfieldportal.themekit.PtfParser
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Converts a user-picked official PSP theme (`.ptf`) into this launcher's theme values:
 * wallpaper + derived accent color (docs/ptf-import-plan.md). Icons stay ours.
 *
 * Personal-use conversion: reads the user's own file via SAF, extracts only the wallpaper
 * and a color derived from it. Nothing is redistributed.
 */
@Singleton
class PtfThemeImporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    sealed interface Result {
        /** Imported and applied. [accentArgb] is null when the wallpaper had no dominant hue. */
        data class Success(val themeName: String, val accentArgb: Long?) : Result

        /** The file is a CXMB `.ctf` — a full flash0 replacement we deliberately don't support. */
        data object CxmbNotSupported : Result

        data class Failed(val reason: String) : Result
    }

    /**
     * Parses [uri], writes the wallpaper into the app's wallpaper dir, and applies the
     * one-color cascade prefs (accent override + custom wallpaper). The XMB picks both up
     * live through its existing DataStore observers.
     */
    suspend fun import(uri: Uri): Result = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext Result.Failed("Could not read the file")

        when (PtfParser.detect(bytes)) {
            PtfParser.Kind.NOT_PTF -> return@withContext Result.Failed("Not a PSP theme (.ptf) file")
            PtfParser.Kind.CXMB -> return@withContext Result.CxmbNotSupported
            PtfParser.Kind.OFFICIAL_PTF -> Unit
        }

        val theme = PtfParser.parse(bytes)
            ?: return@withContext Result.Failed("The theme file could not be parsed")
        val wallpaper = theme.wallpaper
            ?: return@withContext Result.Failed("The theme has no wallpaper image")

        val wallpaperFile = runCatching { saveWallpaper(wallpaper) }
            .onFailure { Timber.w(it, "PTF import: wallpaper save failed") }
            .getOrNull() ?: return@withContext Result.Failed("Could not save the wallpaper")

        val accent = AccentDeriver.deriveAccent(wallpaper)?.toUInt()?.toLong()

        context.pfpDataStore.edit { prefs ->
            prefs[KEY_CUSTOM_WALLPAPER] = wallpaperFile.absolutePath
            if (accent != null) prefs[KEY_ACCENT_OVERRIDE] = accent
            else prefs.remove(KEY_ACCENT_OVERRIDE) // grayscale wallpaper → keep the preset scheme
        }

        Result.Success(themeName = theme.name.ifBlank { "Imported PSP theme" }, accentArgb = accent)
    }

    /** Removes the imported accent so the preset color scheme applies again. */
    suspend fun clearAccentOverride() {
        context.pfpDataStore.edit { it.remove(KEY_ACCENT_OVERRIDE) }
    }

    // Same location + JPEG encoding the in-app "Set as Wallpaper" flow uses
    // (PhotoViewerViewModel) so Display settings can manage/clear it identically.
    private fun saveWallpaper(image: BmpImage): File {
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(image.argb, 0, image.width, 0, 0, image.width, image.height)
        val dir = File(context.filesDir, "wallpaper").apply { mkdirs() }
        val dest = File(dir, "wallpaper_ptf_${System.currentTimeMillis()}.jpg")
        FileOutputStream(dest).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out) }
        bitmap.recycle()
        return dest
    }

    private companion object {
        // Must match XMBViewModel / DisplaySettingsViewModel — shared prefs contract.
        val KEY_CUSTOM_WALLPAPER = stringPreferencesKey("display_custom_wallpaper")
        val KEY_ACCENT_OVERRIDE = longPreferencesKey("theme_accent_override")
    }
}
