package com.playfieldportal.core.data.database.seeder

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.playfieldportal.core.data.database.dao.CollectionDao
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.dao.MemoryCardDao
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.data.database.entity.MemoryCardEntity
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.domain.model.GameContentType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_LIBRARY_CONSOLIDATED_V22 = booleanPreferencesKey("library_consolidated_v22")

private const val WINDOWS_PLATFORM_ID = "windows"
private const val APP_SHORTCUT_PLATFORM_ID = "app_shortcut"

// GameHub-family spoof packages: these are the genuine package names of real apps (AnTuTu, PUBG
// Mobile, Genshin Impact, CrossFire) that GameHub Lite / BannerHub variants masquerade as. Rows
// under them are only re-homed when the *installed* app's label proves it's actually a launcher —
// SQL migration can't do that check, so it happens here.
private val SPOOF_PACKAGES = setOf(
    "com.antutu.ABenchMark",
    "com.antutu.benchmark.full",
    "com.ludashi.aibench",
    "com.tencent.ig",
    "com.miHoYo.GenshinImpact",
    "com.tencent.tmgp.cf",
)

// Auto-created import collections (named after the source launcher). Removed when every member
// migrated to the Windows card; a collection the user added other items to survives.
private val LAUNCHER_COLLECTION_NAMES = setOf(
    "Winlator", "GameHub Lite", "BannerHub", "GameNative", "GameHub",
)
private const val PC_COLLECTION_ICON = "ic_desktop"

/**
 * One-shot follow-up to [PFPDatabase.MIGRATION_21_22][com.playfieldportal.core.data.database.PFPDatabase]:
 * the parts of the Windows-card consolidation that need application logic (label checks,
 * best-row scoring) rather than SQL. Idempotent and guarded by a DataStore flag; nothing here
 * deletes user data except exact-duplicate rows whose every attribute is preserved on the survivor.
 *
 *  1. Re-home spoof-package PC entries once the installed app's label confirms a GameHub-family
 *     launcher (see [SPOOF_PACKAGES]).
 *  2. Merge duplicate Windows games — same package + same normalized title — created by the old
 *     per-path dedupe keys (shortcut id vs intent URI never saw each other).
 *  3. Ensure the Windows Games Memory Card exists (enabled, virtual — no ROM directory) when any
 *     Windows games exist.
 *  4. Remove auto-created launcher collections whose members all live in the Windows card now.
 */
@Singleton
class LibraryConsolidation @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gameDao: GameDao,
    private val collectionDao: CollectionDao,
    private val memoryCardDao: MemoryCardDao,
) {
    suspend fun run() {
        val prefs = context.pfpDataStore.data.first()
        if (prefs[KEY_LIBRARY_CONSOLIDATED_V22] == true) return

        runCatching {
            rehomeSpoofPackageEntries()
            mergeDuplicateWindowsGames()
            ensureWindowsCard()
            removeMigratedLauncherCollections()
        }.onFailure { Timber.e(it, "Library consolidation failed") }

        context.pfpDataStore.edit { it[KEY_LIBRARY_CONSOLIDATED_V22] = true }
        Timber.i("Library consolidation (v22) complete")
    }

    // ── 1. Spoof-package re-home ──────────────────────────────────────────────

    private suspend fun rehomeSpoofPackageEntries() {
        val pm = context.packageManager
        val launcherSpoofs = SPOOF_PACKAGES.filter { pkg ->
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrNull() ?: return@filter false   // not installed — leave its rows untouched
            label.contains("gamehub", ignoreCase = true) ||
                label.contains("bannerhub", ignoreCase = true) ||
                label.contains("banner hub", ignoreCase = true)
        }
        if (launcherSpoofs.isEmpty()) return

        var rehomed = 0
        val candidates = gameDao.getByPlatformOnce(APP_SHORTCUT_PLATFORM_ID) +
            gameDao.getByPlatformOnce(WINDOWS_PLATFORM_ID)
        for (g in candidates) {
            val isPcEntry = g.packageName in launcherSpoofs &&
                (g.launchShortcutId != null || g.launchIntentUri != null)
            val needsMove = g.platformId != WINDOWS_PLATFORM_ID ||
                g.contentType != GameContentType.GAME.name
            if (isPcEntry && needsMove) {
                gameDao.setPlatformAndContentType(g.id, WINDOWS_PLATFORM_ID, GameContentType.GAME.name)
                rehomed++
            }
        }
        if (rehomed > 0) Timber.i("Re-homed $rehomed spoof-package PC entr(ies) to Windows")
    }

    // ── 2. Duplicate merge ────────────────────────────────────────────────────

    private suspend fun mergeDuplicateWindowsGames() {
        val games = gameDao.getByPlatformOnce(WINDOWS_PLATFORM_ID)
            .filter { it.contentType == GameContentType.GAME.name }
        var merged = 0

        val groups = games.groupBy { (it.packageName ?: "") to normalizeTitle(displayTitleOf(it)) }
        for ((_, rows) in groups) {
            if (rows.size < 2) continue
            val survivor = rows.sortedWith(
                compareByDescending<GameEntity> { it.launchShortcutId != null }
                    .thenByDescending { it.launchIntentUri != null }
                    .thenByDescending { it.iconUri != null || it.heroUri != null || it.artworkUri != null }
                    .thenBy { it.id }
            ).first()

            for (loser in rows) {
                if (loser.id == survivor.id) continue
                mergeInto(survivor, loser)
                gameDao.deleteById(loser.id)
                merged++
            }
        }
        if (merged > 0) Timber.i("Merged $merged duplicate Windows game row(s)")
    }

    /** Moves everything worth keeping from [loser] onto [survivor] before the loser is deleted. */
    private suspend fun mergeInto(survivor: GameEntity, loser: GameEntity) {
        // Union of attributes: keep the survivor's value when set, adopt the loser's otherwise.
        val enriched = survivor.copy(
            launchShortcutId  = survivor.launchShortcutId ?: loser.launchShortcutId,
            launchIntentUri   = survivor.launchIntentUri ?: loser.launchIntentUri,
            artworkUri        = survivor.artworkUri ?: loser.artworkUri,
            heroUri           = survivor.heroUri ?: loser.heroUri,
            logoUri           = survivor.logoUri ?: loser.logoUri,
            iconUri           = survivor.iconUri ?: loser.iconUri,
            userTitleOverride = survivor.userTitleOverride ?: loser.userTitleOverride,
            scrapedTitle      = survivor.scrapedTitle ?: loser.scrapedTitle,
            userNote          = survivor.userNote ?: loser.userNote,
            isFavorite        = survivor.isFavorite || loser.isFavorite,
            totalPlayTimeMillis = survivor.totalPlayTimeMillis + loser.totalPlayTimeMillis,
            lastPlayedAt      = maxOf(survivor.lastPlayedAt ?: 0L, loser.lastPlayedAt ?: 0L)
                .takeIf { it > 0L },
        )
        if (enriched != survivor) gameDao.update(enriched)

        // Collection memberships follow the survivor (composite-PK IGNORE makes re-adds no-ops).
        for (collectionId in collectionDao.getCollectionIdsForGame(loser.id)) {
            collectionDao.addGame(
                com.playfieldportal.core.data.database.entity.CollectionGameEntity(
                    collectionId = collectionId,
                    gameId       = survivor.id,
                    addedAt      = System.currentTimeMillis(),
                )
            )
        }
    }

    // ── 3. Windows Memory Card ────────────────────────────────────────────────

    private suspend fun ensureWindowsCard() {
        val count = gameDao.getByPlatformOnce(WINDOWS_PLATFORM_ID)
            .count { it.contentType == GameContentType.GAME.name }
        if (count == 0) return
        val existing = memoryCardDao.getById(WINDOWS_PLATFORM_ID)
        if (existing != null) {
            memoryCardDao.updateGameCount(WINDOWS_PLATFORM_ID, count)
            return
        }
        memoryCardDao.upsert(
            MemoryCardEntity(
                platformId  = WINDOWS_PLATFORM_ID,
                displayName = "Windows Memory Card",
                enabled     = true,
                sortOrder   = memoryCardDao.maxSortOrder() + 1,
                gameCount   = count,
            )
        )
        Timber.i("Windows Memory Card created ($count games)")
    }

    // ── 4. Launcher-collection cleanup ────────────────────────────────────────

    private suspend fun removeMigratedLauncherCollections() {
        var removed = 0
        for (collection in collectionDao.getAll()) {
            val launcherNamed = collection.name in LAUNCHER_COLLECTION_NAMES ||
                collection.iconKey == PC_COLLECTION_ICON
            if (!launcherNamed) continue

            val memberIds = collectionDao.getGameIdsInCollection(collection.id)
            if (memberIds.isEmpty()) continue
            val allWindows = memberIds.all { id ->
                gameDao.getById(id)?.platformId == WINDOWS_PLATFORM_ID
            }
            if (allWindows) {
                collectionDao.delete(collection.id)   // junction rows cascade; games survive
                removed++
            }
        }
        if (removed > 0) Timber.i("Removed $removed migrated launcher collection(s)")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun displayTitleOf(g: GameEntity): String =
        g.userTitleOverride ?: g.scrapedTitle ?: g.title

    // Case/punctuation-insensitive comparison key: "S.T.A.L.K.E.R. 2" == "STALKER 2".
    private fun normalizeTitle(title: String): String =
        title.lowercase().filter { it.isLetterOrDigit() }
}
