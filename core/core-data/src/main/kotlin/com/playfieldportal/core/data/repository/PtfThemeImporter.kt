package com.playfieldportal.core.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.themekit.AccentDeriver
import com.playfieldportal.themekit.PtfParser
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Converts a user-picked official PSP theme (`.ptf`) into this launcher's theme values:
 * wallpaper + derived accent color (docs/ptf-import-plan.md). Icons stay ours.
 *
 * The converted theme is saved into the [PfpThemeStore] library (so it can be re-applied
 * or removed later) and applied immediately.
 *
 * Personal-use conversion: reads the user's own file via SAF, extracts only the wallpaper
 * and a color derived from it. Nothing is redistributed.
 */
@Singleton
class PtfThemeImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: PfpThemeStore,
) {

    sealed interface Result {
        /** Imported, saved to the library, and applied. */
        data class Success(val themeName: String, val accentArgb: Long?) : Result

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

        val theme = PtfParser.parse(bytes)
            ?: return@withContext Result.Failed("The theme file could not be parsed")
        val wallpaper = theme.wallpaper
            ?: return@withContext Result.Failed("The theme has no wallpaper image")

        val accent = AccentDeriver.deriveAccent(wallpaper)?.toUInt()?.toLong()
        val name = theme.name.ifBlank { "Imported PSP theme" }

        val saved = store.createFromPtf(
            name = name,
            wallpaper = wallpaper,
            accentArgb = accent,
            sourceFile = uri.lastPathSegment,
            firmware = theme.firmware.ifBlank { null },
        ) ?: return@withContext Result.Failed("Could not save the theme")

        if (!store.apply(saved.id)) {
            Timber.w("PTF import: saved but apply failed for %s", saved.id)
        }
        Result.Success(themeName = name, accentArgb = accent)
    }

    /** Removes the imported accent so the preset color scheme applies again. */
    suspend fun clearAccentOverride() {
        context.pfpDataStore.edit { it.remove(KEY_ACCENT_OVERRIDE) }
    }

    private companion object {
        val KEY_ACCENT_OVERRIDE = longPreferencesKey("theme_accent_override")
    }
}
