package com.playfieldportal.feature.achievements.provider.localsteam

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.data.database.dao.LocalSteamFolderDao
import com.playfieldportal.core.data.database.entity.LocalSteamFolderEntity
import com.playfieldportal.core.data.repository.WindowsLibrarySetup
import com.playfieldportal.core.data.saf.SafChild
import com.playfieldportal.core.data.saf.isIgnoredDir
import com.playfieldportal.core.data.saf.querySafChildren
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** An emu-marked Windows game folder found under the windows card's granted root. */
data class LocalSteamGame(
    val folderName: String,
    val folderDocId: String,
    val appId: String,
    /** The GSE progress file, when the save redirect makes it reachable; null otherwise. */
    val achievementsUri: Uri?,
    /** The tree the folder was found under, so a missing schema can be written back in place. */
    val settingsTreeUri: String = "",
    /** The `steam_settings` folder's document id, the write target for a generated schema. */
    val settingsDirDocId: String = "",
    /** The folder holding `steam_settings` (the DLL folder), where a `saves/` dir can be created. */
    val settingsParentDocId: String = "",
    /** Whether `steam_settings/achievements.json` (the schema the emu reads) already exists. */
    val hasSchema: Boolean = true,
    /**
     * Whether the game's save location exists (the redirect target or a `saves/` folder) — the
     * opt-in tracking gate (user decision 2026-07-16). Untrackable games stay out of [scan] so
     * All Tracked never shows a permanent 0%, but remain visible to [scanAll] so the schema
     * prompt can offer the generation that creates the save location.
     */
    val trackable: Boolean = true,
)

/**
 * How a folder's Steam app id was established. Recorded on the registry row so a report can say
 * where the id came from, and so a later re-identify knows which ids were only ever a title guess.
 */
enum class AppIdSource {
    /** `steam_settings/steam_appid.txt` — the folder's own statement, and always authoritative. */
    MARKER,

    /** A storefront identity the user or the resolver had already confirmed for this game. */
    STORED_IDENTITY,

    /** The 5-rule title matcher, confirmed at EXACT/HIGH or chosen by the user from the picker. */
    TITLE_MATCH,
}

/**
 * What one folder offers to hang an identity on.
 *
 * [settingsDirDocId] is null for a Steam build with no `steam_settings` yet: it identifies fine,
 * and the marker write creates the folder. [settingsParentDocId] is always set, because it is the
 * DLL folder — the one place `steam_settings` is allowed to sit, and the save-redirect base.
 */
data class FolderAnchor(
    val treeUri: String,
    val folderName: String,
    val folderDocId: String,
    val settingsDirDocId: String?,
    val settingsParentDocId: String,
    /** The app id the folder already declares, or null when nothing has written one yet. */
    val markerAppId: String?,
)

/**
 * Inspects Steam-emu game folders and resolves them to progress files.
 *
 * **Where the folders come from.** Primarily from the REGISTRY (`local_steam_folders`): the user
 * points PFP at a game folder or at a parent full of them, and the pick is recorded so no later
 * sync re-walks a tree to find it. [scan] / [scanAll] over the windows library's own surfaces stay
 * as the fallback for links made before the registry existed — Windows games increasingly do not
 * live under those surfaces at all, which is exactly why the registry exists.
 *
 * A game folder qualifies when it carries a `steam_settings/steam_appid.txt`; its
 * progress file is then resolved by following the emu's own rules: the `local_save_path` redirect
 * from `configs.user.ini`, relative to the folder holding `steam_settings` (which sits beside the
 * steam_api DLL), down to `<redirect>/<appid>/achievements.json`.
 *
 * Read-only and grant-scoped by construction: every uri comes from tree-scoped child queries, so
 * discovery can never look outside the folders the user granted.
 */
@Singleton
class LocalSteamDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val windowsLibrary: WindowsLibrarySetup,
    private val credentials: AchievementCredentialsProvider,
    private val registry: LocalSteamFolderDao,
) {
    // A full scan is a deep SAF tree walk (one IPC query per directory), so per-game lookups
    // during a batch pass must not each pay for one. scan() stays always-fresh and primes the
    // cache; findByAppId reads through it. The mutex also collapses concurrent duplicate scans.
    private val scanMutex = Mutex()
    private var cachedGames: List<LocalSteamGame> = emptyList()
    private var cachedAt = 0L

    /** Every trackable emu game folder under the windows scan surfaces (the sync/link surface). */
    suspend fun scan(): List<LocalSteamGame> = scanAll().filter { it.trackable }

    /**
     * Every emu-marked game folder including untrackable ones awaiting schema generation. Empty
     * unless the user has opted in — either to Local Steam tracking (detect + sync) or to the
     * Goldberg installer (convert on scan). Either gate permits discovery so the installer can find
     * and convert games without ongoing tracking; both sit behind the save-backup warning.
     */
    suspend fun scanAll(): List<LocalSteamGame> {
        if (!discoveryEnabled()) return emptyList()
        return scanMutex.withLock { freshScan() }
    }

    // Discovery is permitted when EITHER opt-in is on: tracking (detect + sync) or the Goldberg
    // installer (convert on scan) — see AchievementCredentialsProvider.goldbergInstallerEnabledFlow.
    private suspend fun discoveryEnabled(): Boolean =
        credentials.localSteamTrackingEnabled() || credentials.goldbergInstallerEnabled()

    /**
     * The game folder whose `steam_appid.txt` matches [appId], or null. Served from a scan at most
     * [SCAN_CACHE_MS] old: Sync All scans once up front, then every per-game sync in the pass
     * resolves against that result instead of re-walking the tree. A folder moved mid-window is
     * seen one pass late — the same self-correction a mid-scan move already relies on.
     */
    suspend fun findByAppId(appId: String): LocalSteamGame? {
        // The registry first, and deliberately AHEAD of the opt-in gate.
        //
        // That gate exists so PFP never goes looking through somebody's library unasked, and so it
        // never writes into a game folder unasked. Neither applies here: a registered folder is one
        // the user personally pointed PFP at — which is that game's own consent — and resolving it is
        // a primary-key read followed by reading a progress file the game wrote itself. Nothing is
        // searched and nothing is written.
        //
        // Gating this was a real dead end: the pick flow is not gated, so a folder would link, and
        // then every sync afterwards failed with "no emu game folder for appid ..." and no coins,
        // because the global toggle had never been turned on. The gate below still governs the
        // library-wide scan, and the marker and kit writes stay behind their own opt-ins.
        registered(appId)?.let { return it }
        if (!discoveryEnabled()) return null
        return scanMutex.withLock {
            val fresh = System.currentTimeMillis() - cachedAt <= SCAN_CACHE_MS
            // Deliberately matches untrackable games too: a sync requested right after generation
            // (inside the cache window) should read the fresh kit, not fail on the stale gate flag.
            (if (fresh) cachedGames else freshScan()).firstOrNull { it.appId == appId }
        }
    }

    /**
     * The registered folder for [appId], re-read from disk so a moved progress file, a freshly
     * written kit and a played-for-the-first-time save are all seen.
     *
     * A row whose grant is gone or whose folder has moved yields null, and `last_seen_at` is left
     * where it was: that is UNKNOWN, which the sync layer must never take as "nothing earned". The
     * row itself is kept, so the user can re-pick rather than start over.
     */
    private suspend fun registered(appId: String): LocalSteamGame? {
        val row = runCatching { registry.getByAppId(appId) }.getOrNull() ?: return null
        val refreshed = withContext(Dispatchers.IO) { refreshRegistered(row) }
        if (refreshed == null) {
            Timber.i("LOCAL_STEAM registry — $appId (${row.folderName}) is unreachable right now")
            return null
        }
        runCatching { registry.touchLastSeen(appId, System.currentTimeMillis()) }
        return refreshed
    }

    /**
     * Re-reads a registered folder: its progress file, and whether the kit is in place.
     *
     * **It does not re-derive the app id, and must not.** The row IS the identity — that is what
     * registering a folder means. Reading the id back out of `steam_appid.txt` on every sync would
     * make a registered game resolvable only while that file happens to exist and parse, which is
     * exactly the tree-walking fragility the registry replaced: a folder whose id was established by
     * title while the marker write was not permitted (Local Steam tracking off) would link once and
     * then fail every later sync with "no emu game folder for appid …".
     *
     * Null means the folder cannot be read at all right now — a revoked grant, an unmounted card, a
     * folder that moved. That is UNKNOWN, never "nothing earned", and the row is left alone so the
     * user can re-pick.
     */
    private fun refreshRegistered(row: LocalSteamFolderEntity): LocalSteamGame? {
        val tree = runCatching { Uri.parse(row.treeUri) }.getOrNull() ?: return null

        // Reachability is judged on the DLL folder: every registered row carries it, and a folder
        // holding a game's executable is never empty. An empty listing is a provider saying no.
        if (context.contentResolver.querySafChildren(tree, row.settingsParentDocId).isEmpty()) return null

        val settingsChildren = row.settingsDirDocId
            ?.let { context.contentResolver.querySafChildren(tree, it) }
            .orEmpty()

        // The same ladder pick-time inspection uses: the emu's own redirect first, then the
        // documented saves/ convention with or without the appid level.
        val redirect = settingsChildren.textOf(LocalSteamSchemaWriter.USER_CONFIG_FILE, GseUserConfig.MAX_BYTES)
            ?.let(GseUserConfig::localSavePath)
            ?.let(GseUserConfig::savePathSegments)
        val achievements = redirect
            ?.let { resolveFile(tree, row.settingsParentDocId, it + row.appId + PROGRESS_FILE) }
            ?: resolveFile(tree, row.settingsParentDocId, listOf(SAVES_FOLDER, row.appId, PROGRESS_FILE))
            ?: resolveFile(tree, row.settingsParentDocId, listOf(SAVES_FOLDER, PROGRESS_FILE))

        return LocalSteamGame(
            folderName = row.folderName,
            folderDocId = row.folderDocId,
            appId = row.appId,
            achievementsUri = achievements,
            settingsTreeUri = row.treeUri,
            settingsDirDocId = row.settingsDirDocId.orEmpty(),
            settingsParentDocId = row.settingsParentDocId,
            hasSchema = settingsChildren.any {
                !it.isDirectory && it.name.equals(PROGRESS_FILE, ignoreCase = true)
            },
            // Trackable by definition: the user pointed at this folder, so it must not be hidden
            // from a sync by the save-location gate, which exists to keep UNPICKED folders out of
            // All Tracked at a permanent 0%.
            trackable = true,
        )
    }

    // ── Scoped inspection (the picked-folder surface) ──────────────────────────

    /**
     * Inspects ONE picked game folder — the per-game Shiba page flow's whole discovery step.
     *
     * Null when the folder carries no `steam_appid.txt` in its top few levels. Use [anchor] to tell
     * "not a Steam build at all" apart from "a Steam build whose id is not written down yet".
     */
    suspend fun inspect(treeUri: String, folderDocId: String): LocalSteamGame? = withContext(Dispatchers.IO) {
        val tree = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return@withContext null
        inspectFolder(tree, folderNameOf(tree, folderDocId), folderDocId)
    }

    /** Inspects every child folder of a picked PARENT — the batch matcher's discovery step. */
    suspend fun scanFolder(treeUri: String, parentDocId: String): List<LocalSteamGame> =
        withContext(Dispatchers.IO) {
            val tree = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return@withContext emptyList()
            childFolders(tree, parentDocId).mapNotNull { inspectFolder(tree, it.name, it.documentId) }
        }

    /** Every child directory of [parentDocId] worth entering, in provider order. */
    suspend fun childFolders(treeUri: String, parentDocId: String): List<SafChild> =
        withContext(Dispatchers.IO) {
            val tree = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return@withContext emptyList()
            childFolders(tree, parentDocId)
        }

    /**
     * What a folder offers to hang an identity on, or null when it is not a Steam build at all.
     *
     * `steam_settings` is the anchor when it exists — it is where the marker lives and where a kit
     * is written. When it does not exist there is nothing to anchor on, so this falls back to the
     * folder holding the Steam API DLL, which is the very place `steam_settings` would have to sit
     * beside. A folder with neither is honestly not a Steam build, which is the truthful version of
     * the old "no Steam-emulator data" message.
     */
    suspend fun anchor(treeUri: String, folderDocId: String): FolderAnchor? = withContext(Dispatchers.IO) {
        val tree = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return@withContext null
        val folderName = folderNameOf(tree, folderDocId)

        findSteamSettings(tree, folderDocId, depthLeft = SETTINGS_SEARCH_DEPTH)?.let { found ->
            val children = context.contentResolver.querySafChildren(tree, found.dir.documentId)
            return@withContext FolderAnchor(
                treeUri = treeUri,
                folderName = folderName,
                folderDocId = folderDocId,
                settingsDirDocId = found.dir.documentId,
                settingsParentDocId = found.parentDocId,
                markerAppId = children.textOf(LocalSteamSchemaWriter.APPID_FILE, SMALL_FILE_MAX_BYTES).asAppId(),
            )
        }
        findDllFolder(tree, folderDocId, depthLeft = SETTINGS_SEARCH_DEPTH)?.let { dllDocId ->
            return@withContext FolderAnchor(
                treeUri = treeUri,
                folderName = folderName,
                folderDocId = folderDocId,
                settingsDirDocId = null,
                settingsParentDocId = dllDocId,
                markerAppId = null,
            )
        }
        null
    }

    /**
     * The folder holding a Steam API DLL, searched the same few levels deep as `steam_settings` and
     * for the same reason (Unity keeps the exe and its DLLs under `<Game>_Data/Plugins/x86_64/`).
     *
     * Both the 64-bit and the 32-bit name count: a `steam_api.dll`-only game still IDENTIFIES as a
     * Steam build even though the emu swap will honestly report `NoTargetDll`, and telling the user
     * it is "not a Steam build" would be wrong.
     */
    suspend fun findDllFolder(treeUri: String, folderDocId: String): String? = withContext(Dispatchers.IO) {
        val tree = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return@withContext null
        findDllFolder(tree, folderDocId, depthLeft = SETTINGS_SEARCH_DEPTH)
    }

    /** Records a picked folder so nothing ever has to look for it again. */
    suspend fun register(game: LocalSteamGame, source: AppIdSource) {
        runCatching {
            registry.upsert(
                LocalSteamFolderEntity(
                    appId = game.appId,
                    folderName = game.folderName,
                    treeUri = game.settingsTreeUri,
                    folderDocId = game.folderDocId,
                    settingsDirDocId = game.settingsDirDocId.takeIf { it.isNotBlank() },
                    settingsParentDocId = game.settingsParentDocId,
                    progressDocId = game.achievementsUri?.let { uri ->
                        runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                    },
                    hasSchema = game.hasSchema,
                    appIdSource = source.name,
                    lastSeenAt = System.currentTimeMillis(),
                )
            )
        }.onFailure { Timber.w(it, "Could not register the picked folder for %s", game.appId) }
    }

    /** Every registered folder — the settings listing and the batch report read this. */
    suspend fun registeredFolders(): List<LocalSteamFolderEntity> =
        runCatching { registry.getAll() }.getOrDefault(emptyList())

    /**
     * Every registered app id, whether its folder can be reached right now or not.
     *
     * Presence reconciliation uses this to know what it must NOT demote to history: the user pointed
     * PFP at these folders, so an unmounted card or a revoked grant is unknown, not "the game is
     * gone". Forget is the explicit way out, and the only one.
     */
    suspend fun registeredAppIds(): Set<String> =
        runCatching { registry.getAll().mapTo(mutableSetOf()) { it.appId } }.getOrDefault(emptySet())

    /**
     * The registered folders reachable right now, re-read from disk.
     *
     * Ungated for the same reason [findByAppId] reads the registry ahead of the gate: these are
     * folders the user handed over, and presence reconciliation has to see them or their coins never
     * reach Shiba Coins at all.
     */
    suspend fun registeredReachable(): List<LocalSteamGame> = withContext(Dispatchers.IO) {
        registeredFolders().mapNotNull { refreshRegistered(it) }
    }

    /** Forgets one registered folder. The game's link and its earned coins are untouched. */
    suspend fun forget(appId: String) {
        runCatching { registry.deleteByAppId(appId) }
    }

    private suspend fun freshScan(): List<LocalSteamGame> = withContext(Dispatchers.IO) {
        windowsLibrary.windowsFolders()
            .mapNotNull { (treeUri, startDocId) ->
                runCatching { Uri.parse(treeUri) }.getOrNull()?.let { it to startDocId }
            }
            .flatMap { (tree, startDocId) ->
                context.contentResolver.querySafChildren(tree, startDocId)
                    .filter { child ->
                        child.isDirectory && !child.isIgnoredDir() &&
                            !child.name.equals(WindowsLibrarySetup.IMPORT_FOLDER, ignoreCase = true)
                    }
                    .mapNotNull { inspectFolder(tree, it.name, it.documentId) }
            }
            .also {
                cachedGames = it
                cachedAt = System.currentTimeMillis()
                Timber.i("LOCAL_STEAM discovery — ${it.size} emu game folder(s)")
            }
    }

    // A game qualifies through its steam_settings folder, searched a few levels deep because some
    // installs keep the exe (and the DLL beside it) in a subfolder of the distributed folder.
    // Takes the folder's name and doc id rather than a SafChild, so one picked folder — which
    // arrives as a doc id with no parent listing to have come from — inspects the same way.
    private fun inspectFolder(tree: Uri, folderName: String, folderDocId: String): LocalSteamGame? {
        val settingsDir = findSteamSettings(tree, folderDocId, depthLeft = SETTINGS_SEARCH_DEPTH)
            ?: return null
        val settingsChildren = context.contentResolver.querySafChildren(tree, settingsDir.dir.documentId)

        val appId = settingsChildren
            .textOf(LocalSteamSchemaWriter.APPID_FILE, SMALL_FILE_MAX_BYTES).asAppId()
            ?: return null

        // The emu's own redirect is the source of truth; the documented `saves/` convention (see
        // README "Tracking local (Steam-emulated) PC games") is the fallback, with or without the
        // appid level, so a hand-arranged folder tracks without any emu config.
        val redirect = settingsChildren.textOf("configs.user.ini", GseUserConfig.MAX_BYTES)
            ?.let(GseUserConfig::localSavePath)
            ?.let(GseUserConfig::savePathSegments)
        val achievements = redirect
            ?.let { resolveFile(tree, settingsDir.parentDocId, it + appId + PROGRESS_FILE) }
            ?: resolveFile(tree, settingsDir.parentDocId, listOf(SAVES_FOLDER, appId, PROGRESS_FILE))
            ?: resolveFile(tree, settingsDir.parentDocId, listOf(SAVES_FOLDER, PROGRESS_FILE))

        // Opt-in gate (user decision 2026-07-16): a game is tracked only once its save location
        // exists — the redirect's target folder or a `saves/` folder. steam_settings alone (no
        // save location) stays untracked instead of cluttering All Tracked at a permanent 0%.
        // Untrackable folders still surface through scanAll: the schema prompt must see them, or
        // a generated configs.user.ini whose saves folder is missing would hide the game from
        // the very step that creates that folder.
        val trackable = achievements != null ||
            savesLocationExists(tree, settingsDir.parentDocId, redirect)

        // The schema the emu reads to know its achievement list lives in steam_settings itself;
        // its absence is what an in-app generate step (LocalSteamSchemaGenerator) can fill.
        val hasSchema = settingsChildren.any {
            !it.isDirectory && it.name.equals(PROGRESS_FILE, ignoreCase = true)
        }

        return LocalSteamGame(
            folderName = folderName,
            folderDocId = folderDocId,
            appId = appId,
            achievementsUri = achievements,
            settingsTreeUri = tree.toString(),
            settingsDirDocId = settingsDir.dir.documentId,
            settingsParentDocId = settingsDir.parentDocId,
            hasSchema = hasSchema,
            trackable = trackable,
        )
    }

    // True when the emu's redirect target folder or the conventional `saves/` folder exists,
    // even before any achievements file has been written inside it.
    private fun savesLocationExists(tree: Uri, dllDocId: String, redirect: List<String>?): Boolean =
        (redirect != null && resolveDir(tree, dllDocId, redirect) != null) ||
            resolveDir(tree, dllDocId, listOf(SAVES_FOLDER)) != null

    private data class FoundDir(val dir: SafChild, val parentDocId: String)

    private fun findSteamSettings(tree: Uri, folderDocId: String, depthLeft: Int): FoundDir? {
        val children = context.contentResolver.querySafChildren(tree, folderDocId)
        children.firstOrNull {
            it.isDirectory && it.name.equals(LocalSteamSchemaWriter.SETTINGS_DIR, ignoreCase = true)
        }?.let { return FoundDir(it, folderDocId) }
        if (depthLeft <= 1) return null
        return children.asSequence()
            .filter { it.isDirectory && !it.isIgnoredDir() }
            .firstNotNullOfOrNull { findSteamSettings(tree, it.documentId, depthLeft - 1) }
    }

    // The same depth-4 walk, looking for the Steam API DLL instead of the settings folder — the
    // anchor for a folder that has no steam_settings to anchor on.
    private fun findDllFolder(tree: Uri, folderDocId: String, depthLeft: Int): String? {
        val children = context.contentResolver.querySafChildren(tree, folderDocId)
        if (children.any { child -> !child.isDirectory && child.isSteamApiDll() }) return folderDocId
        if (depthLeft <= 1) return null
        return children.asSequence()
            .filter { it.isDirectory && !it.isIgnoredDir() }
            .firstNotNullOfOrNull { findDllFolder(tree, it.documentId, depthLeft - 1) }
    }

    private fun SafChild.isSteamApiDll(): Boolean =
        name.equals(LocalSteamSchemaWriter.EMU_DLL, ignoreCase = true) ||
            name.equals(LocalSteamSchemaWriter.EMU_BACKUP_DLL, ignoreCase = true) ||
            name.equals(LocalSteamSchemaWriter.STEAM_DLL_32, ignoreCase = true)

    // A folder's display name from its doc id alone. SAF has no "name of this document" in the
    // children projection, so this reads the document row itself; the doc-id tail is the fallback
    // for a provider that will not answer, and is what the user typed into their own folder name.
    private fun folderNameOf(tree: Uri, folderDocId: String): String {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, folderDocId)
        val queried = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        return queried?.takeIf { it.isNotBlank() }
            ?: folderDocId.substringAfterLast('/').substringAfterLast(':')
    }

    private fun childFolders(tree: Uri, parentDocId: String): List<SafChild> =
        context.contentResolver.querySafChildren(tree, parentDocId).filter { child ->
            child.isDirectory && !child.isIgnoredDir() &&
                !child.name.equals(WindowsLibrarySetup.IMPORT_FOLDER, ignoreCase = true)
        }

    // Walks directory segments down from [startDocId]; the last segment must also be a directory.
    private fun resolveDir(tree: Uri, startDocId: String, segments: List<String>): String? {
        var docId = startDocId
        for (segment in segments) {
            val child = context.contentResolver.querySafChildren(tree, docId)
                .firstOrNull { it.isDirectory && it.name.equals(segment, ignoreCase = true) }
                ?: return null
            docId = child.documentId
        }
        return docId
    }

    // Walks name segments down from a directory, matching case-insensitively (the files were
    // written by Windows software that treats names as such).
    private fun resolveFile(tree: Uri, startDocId: String, segments: List<String>): Uri? {
        var docId = startDocId
        for ((index, segment) in segments.withIndex()) {
            val wantDir = index < segments.lastIndex
            val child = context.contentResolver.querySafChildren(tree, docId)
                .firstOrNull { it.isDirectory == wantDir && it.name.equals(segment, ignoreCase = true) }
                ?: return null
            if (!wantDir) return child.uri
            docId = child.documentId
        }
        return null
    }

    /** Parses the progress file at [uri]; unreadable or oversized input is simply "no unlocks". */
    suspend fun readProgress(uri: Uri): List<EmuEarnedAchievement> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                EmuAchievementFile.parse(input.readBounded(EmuAchievementFile.MAX_BYTES).toString(Charsets.UTF_8))
            }
        }.getOrNull().orEmpty()
    }

    /**
     * Parses the progress file at [uri], or null when it can't be opened or isn't a readable
     * progress file — "unknown", which must never be taken as "nothing earned".
     */
    suspend fun readProgressOrNull(uri: Uri): List<EmuEarnedAchievement>? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                EmuAchievementFile.parseOrNull(input.readBounded(EmuAchievementFile.MAX_BYTES).toString(Charsets.UTF_8))
            }
        }.getOrNull()
    }

    private fun List<SafChild>.textOf(name: String, maxBytes: Int): String? {
        val file = firstOrNull { !it.isDirectory && it.name.equals(name, ignoreCase = true) }
            ?: return null
        if ((file.sizeBytes ?: 0) > maxBytes) return null
        return runCatching {
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                input.readBounded(maxBytes).toString(Charsets.UTF_8)
            }
        }.getOrNull()
    }

    private companion object {
        const val PROGRESS_FILE = "achievements.json"
        // Convention fallback beside the steam_api DLL: saves/[<appid>/]achievements.json.
        const val SAVES_FOLDER = "saves"
        // Depth 4 reaches Unity's nesting: <Game>/<Game>_Data/Plugins/x86_64/steam_settings
        // (live case: the FF pixel remasters — docs/windows-library-refactor-plan.md Phase 5).
        const val SETTINGS_SEARCH_DEPTH = 4
        const val SMALL_FILE_MAX_BYTES = 64
        // Long enough to cover one Sync All pass, short enough that folder changes are seen on
        // the next user action.
        const val SCAN_CACHE_MS = 30_000L
    }
}

/**
 * A marker file's contents as an app id, or null.
 *
 * Bounded and digits-only on purpose: the file is written by third-party tooling, and an id that is
 * not a plain number is not an id PFP can hand to Steam.
 */
private fun String?.asAppId(): String? =
    this?.trim()?.takeIf { it.isNotEmpty() && it.length <= 12 && it.all(Char::isDigit) }

// Bounded read: never trust a provider-reported size, cap what actually comes off the stream.
private fun java.io.InputStream.readBounded(maxBytes: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var remaining = maxBytes
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size, remaining))
        if (read == -1) break
        out.write(buffer, 0, read)
        remaining -= read
    }
    return out.toByteArray()
}
