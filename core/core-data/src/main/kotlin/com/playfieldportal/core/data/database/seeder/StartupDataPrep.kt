package com.playfieldportal.core.data.database.seeder

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.data.repository.ThemePrefKeys
import com.playfieldportal.core.data.wallpaper.WallpaperLuminanceProbe
import com.playfieldportal.core.data.wallpaper.WallpaperLuminanceProbe.clearWallpaperLuma
import com.playfieldportal.core.data.wallpaper.WallpaperLuminanceProbe.setWallpaperLuma
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
        val alreadyPrepped = prefs[KEY_DATA_PREP_VERSION] == currentVersionCode

        val prepped = alreadyPrepped || runOneShotStep("Startup data prep") {
            normalizeGameArtwork()
            normalizeWallpaper()
            // Retired key: a linked artwork folder is the storage mode now.
            context.pfpDataStore.edit { it.remove(KEY_RETIRED_ARTWORK_STORAGE_MODE) }
        }
        // Deliberately OUTSIDE the version gate, and its own step so its failure never blocks the
        // marker. The wallpaper luminance survey is a derived cache, not a one-shot migration: a
        // backup restore re-homes the wallpaper path (BackupManager.remapWallpaper) while
        // data_prep_version is NOT restored, so the marker still matches this install and gating
        // on it would skip the one event most likely to have invalidated the survey.
        runOneShotStep("Wallpaper luminance survey") { healWallpaperLuma() }

        // Only a finished pass is marked, so a failed one runs again next launch (survey L2).
        if (alreadyPrepped || !prepped) return
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

    /**
     * Brings the wallpaper's luminance survey back in step with the wallpaper itself.
     *
     * Three ways it drifts, none of which a write site can catch: an OS update re-homes filesDir
     * (so the map's embedded source path no longer matches), a backup restore repoints the
     * wallpaper onto this install, and a restore can leave a survey behind with no wallpaper at
     * all. All three surface as [WallpaperLuminanceProbe.describes] returning false.
     *
     * Runs on every cold start, so the happy path is deliberately cheap: a valid survey costs one
     * parse to confirm and writes nothing. Only an actually-unusable one pays for a decode.
     */
    private suspend fun healWallpaperLuma() {
        val prefs = context.pfpDataStore.data.first()
        val wallpaper = prefs[KEY_CUSTOM_WALLPAPER]
        val stored = prefs[WallpaperLuminanceProbe.KEY_WALLPAPER_LUMA]

        if (wallpaper == null) {
            if (stored != null) context.pfpDataStore.edit { it.clearWallpaperLuma() }
            return
        }
        if (WallpaperLuminanceProbe.describes(stored, wallpaper)) return

        val fresh = WallpaperLuminanceProbe.survey(wallpaper)
        if (fresh == stored) return
        context.pfpDataStore.edit { it.setWallpaperLuma(fresh) }
    }

    private fun resolve(path: String?, filesDirPath: String): String? =
        rehomeInternalPath(path, filesDirPath) { File(it).exists() }

    private companion object {
        val KEY_DATA_PREP_VERSION = intPreferencesKey("data_prep_version")
        val KEY_CUSTOM_WALLPAPER  = ThemePrefKeys.CUSTOM_WALLPAPER
        val KEY_RETIRED_ARTWORK_STORAGE_MODE = stringPreferencesKey("artwork_storage_mode")
    }
}

/**
 * The usable value for a stored path: an app-private path (`/data/user/<n>/<pkg>/files/…` or
 * `/data/data/<pkg>/files/…`) is repointed onto [filesDirPath], or null when that file is absent;
 * everything else (content URIs, shared storage) passes through untouched. Only app-private roots
 * are rewritten, because a shared-storage path that happens to contain "/files/" is not ours to
 * move (survey L21, docs/audits/codebase-survey-2026-10-22.md).
 */
internal fun rehomeInternalPath(path: String?, filesDirPath: String, exists: (String) -> Boolean): String? {
    if (path.isNullOrEmpty()) return path
    if (!APP_PRIVATE_FILES.containsMatchIn(path)) return path
    val idx = path.indexOf(FILES_MARKER)
    val remapped = filesDirPath.trimEnd('/') + "/" + path.substring(idx + FILES_MARKER.length)
    return if (exists(remapped)) remapped else null
}

private const val FILES_MARKER = "/files/"
private val APP_PRIVATE_FILES = Regex("""^(file://)?/data/(user/\d+|data)/[^/]+/files/""")
