package com.playfieldportal.core.data.database.seeder

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.datastore.pfpDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot data normalization that runs once per app version at startup. It makes a dataset that
 * was carried over from an older build — an in-place upgrade, an adb data copy, or a restored
 * backup — safe to use on the current version.
 *
 * Two repairs, both idempotent:
 *  1. **Re-home internal-storage paths.** Game artwork (and the custom wallpaper) are stored as
 *     absolute `…/<package>/files/…` paths. If the data came from a different package/data-dir the
 *     package segment is wrong; every such path is repointed onto *this* install's filesDir.
 *  2. **Drop dead references.** If the repaired file still isn't there (art that was never bundled,
 *     e.g. a v1 backup), the reference is cleared so the item re-scrapes cleanly instead of showing
 *     a broken image.
 *
 * Room migrations remain the source of truth for schema; this only fixes file-path drift, which
 * migrations can't see.
 */
@Singleton
class StartupDataPrep @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gameDao: GameDao,
) {
    suspend fun run(currentVersionCode: Int) {
        val prefs = context.pfpDataStore.data.first()
        if (prefs[KEY_DATA_PREP_VERSION] == currentVersionCode) return

        runCatching {
            normalizeGameArtwork()
            normalizeWallpaper()
        }.onFailure { Timber.e(it, "Startup data prep failed") }

        context.pfpDataStore.edit { it[KEY_DATA_PREP_VERSION] = currentVersionCode }
        Timber.i("Startup data prep complete for versionCode=$currentVersionCode")
    }

    private suspend fun normalizeGameArtwork() {
        val filesDir = context.filesDir.absolutePath
        var repaired = 0
        gameDao.getAll().forEach { g ->
            val artwork = resolve(g.artworkUri, filesDir)
            val hero    = resolve(g.heroUri, filesDir)
            val logo    = resolve(g.logoUri, filesDir)
            val icon    = resolve(g.iconUri, filesDir)

            if (artwork != g.artworkUri) gameDao.updateArtwork(g.id, artwork)
            if (hero    != g.heroUri)    gameDao.updateHero(g.id, hero)
            if (logo    != g.logoUri)    gameDao.updateLogo(g.id, logo)
            if (icon    != g.iconUri)    gameDao.updateIconUri(g.id, icon)

            if (artwork != g.artworkUri || hero != g.heroUri || logo != g.logoUri || icon != g.iconUri) {
                repaired++
            }
        }
        if (repaired > 0) Timber.i("Re-homed artwork paths on $repaired game(s)")
    }

    private suspend fun normalizeWallpaper() {
        val filesDir = context.filesDir.absolutePath
        val current = context.pfpDataStore.data.first()[KEY_CUSTOM_WALLPAPER] ?: return
        val resolved = resolve(current, filesDir)
        if (resolved == current) return
        context.pfpDataStore.edit { prefs ->
            if (resolved == null) prefs.remove(KEY_CUSTOM_WALLPAPER)
            else prefs[KEY_CUSTOM_WALLPAPER] = resolved
        }
    }

    // Returns the usable value for an internal-storage path: repointed onto filesDir, or null when
    // the file is absent. Non-filesDir values (content URIs, shared-storage paths) pass through, and
    // a value already pointing at an existing file is returned unchanged.
    private fun resolve(path: String?, filesDirPath: String): String? {
        if (path.isNullOrEmpty()) return path
        val idx = path.indexOf(FILES_MARKER)
        if (idx < 0) return path   // not an internal-storage path — leave it alone
        val remapped = filesDirPath.trimEnd('/') + "/" + path.substring(idx + FILES_MARKER.length)
        return if (File(remapped).exists()) remapped else null
    }

    private companion object {
        val KEY_DATA_PREP_VERSION = intPreferencesKey("data_prep_version")
        val KEY_CUSTOM_WALLPAPER  = stringPreferencesKey("display_custom_wallpaper")
        const val FILES_MARKER = "/files/"
    }
}
