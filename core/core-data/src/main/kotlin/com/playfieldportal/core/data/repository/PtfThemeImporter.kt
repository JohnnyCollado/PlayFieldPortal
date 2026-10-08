package com.playfieldportal.core.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.themekit.AccentDeriver
import com.playfieldportal.themekit.BmpImage
import com.playfieldportal.themekit.PtfIconTint
import com.playfieldportal.themekit.PtfIcons
import com.playfieldportal.themekit.PtfParser
import com.playfieldportal.themekit.PtfUnpacker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Converts a user-picked official PSP theme (`.ptf`) into a library theme: the packed wallpaper,
 * the icons that have an exact counterpart here ([PtfIcons.DIRECT]), a tint that matches the
 * built-in icons it leaves to the theme's art, and an accent for menus and the wave.
 *
 * The theme is saved, not applied: the caller routes it through the apply confirmation so a
 * user's own custom icons are never replaced without asking.
 *
 * Personal-use conversion: reads the user's own file via SAF. Nothing is redistributed.
 */
@Singleton
class PtfThemeImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: PfpThemeStore,
) {

    sealed interface Result {
        /**
         * Imported and saved to the library as [themeId]. [iconCount] is how many icon slots the
         * theme fills; [tintScore] (0–100) is how well one tint matches its icons, null without icons.
         */
        data class Success(
            val themeId: String,
            val themeName: String,
            val accentArgb: Long?,
            val iconCount: Int,
            val tintScore: Int?,
        ) : Result

        /** The file is a CXMB `.ctf` — a full flash0 replacement we deliberately don't support. */
        data object CxmbNotSupported : Result

        data class Failed(val reason: String) : Result
    }

    suspend fun import(uri: Uri): Result = withContext(Dispatchers.IO) {
        // Capped read: a mispicked multi-GB file fails fast instead of OOMing the app.
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { with(SafeMedia) { it.readCapped() } }
        }.getOrNull() ?: return@withContext Result.Failed("Could not read the file (or it is too large)")

        when (PtfParser.detect(bytes)) {
            PtfParser.Kind.NOT_PTF -> return@withContext Result.Failed("Not a PSP theme (.ptf) file")
            PtfParser.Kind.CXMB -> return@withContext Result.CxmbNotSupported
            PtfParser.Kind.OFFICIAL_PTF -> Unit
        }

        // parse() is bounds-checked now (see ByteCursor), but it runs on bytes chosen by whoever
        // handed the user the file, and this call sits behind a plain viewModelScope launch — an
        // escaping throwable would take the app down rather than fail the import.
        val theme = runCatching { PtfParser.parse(bytes) }
            .onFailure { Timber.w(it, "PTF parse threw on a malformed theme") }
            .getOrNull()
            ?: return@withContext Result.Failed("The theme file could not be parsed")
        val wallpaper = theme.wallpaper ?: return@withContext Result.Failed(
            when (theme.wallpaperStatus) {
                PtfParser.WallpaperStatus.MISSING -> "The theme has no wallpaper image"
                PtfParser.WallpaperStatus.UNSUPPORTED_COMPRESSION ->
                    "This theme compresses its wallpaper with a method that isn't supported"
                else -> "The theme's wallpaper is damaged and could not be decoded"
            },
        )

        // Icons are best-effort: a theme whose icon records will not unpack still imports with
        // its wallpaper, exactly as before icons were carried.
        val (icons, extras) = runCatching {
            PtfUnpacker.unpack(bytes)?.let { PtfIcons.extract(it) to PtfIcons.extractExtras(it) }
        }
            .onFailure { Timber.w(it, "PTF icon unpack threw; importing the wallpaper only") }
            .getOrNull()
            ?: (emptyMap<String, BmpImage>() to emptyMap<PtfIcons.SlotRef, BmpImage>())
        val tint = PtfIconTint.derive(PtfIcons.tintSources(icons))
        val accent = PtfIconTint.chooseAccent(tint, AccentDeriver.deriveAccent(wallpaper))
            ?.toUInt()?.toLong()
        val name = theme.name.ifBlank { "Imported PSP theme" }

        val saved = store.createFromPtf(
            name = name,
            wallpaper = wallpaper,
            accentArgb = accent,
            sourceFile = uri.lastPathSegment,
            firmware = theme.firmware.ifBlank { null },
            icons = icons,
            iconColorArgb = PtfIconTint.iconColorFor(tint),
            ptfIcons = extras,
        ) ?: return@withContext Result.Failed("Could not save the theme")

        Result.Success(
            themeId = saved.id,
            themeName = name,
            accentArgb = accent,
            iconCount = icons.size,
            tintScore = tint?.score,
        )
    }

    /** Removes the imported accent so the preset color scheme applies again. */
    suspend fun clearAccentOverride() {
        context.pfpDataStore.edit { it.remove(KEY_ACCENT_OVERRIDE) }
    }

    private companion object {
        val KEY_ACCENT_OVERRIDE = ThemePrefKeys.ACCENT_OVERRIDE
    }
}
