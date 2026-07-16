package com.playfieldportal.feature.achievements.provider.localsteam

import android.content.Context
import android.net.Uri
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
 * Finds Steam-emu game installs under the windows library's scan surfaces — the card's own picked
 * folder when set, else every ROM root's `windows` subfolder (docs/windows-library-refactor-plan.md
 * section 2). A game folder qualifies when it carries a `steam_settings/steam_appid.txt`; its
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
) {
    // A full scan is a deep SAF tree walk (one IPC query per directory), so per-game lookups
    // during a batch pass must not each pay for one. scan() stays always-fresh and primes the
    // cache; findByAppId reads through it. The mutex also collapses concurrent duplicate scans.
    private val scanMutex = Mutex()
    private var cachedGames: List<LocalSteamGame> = emptyList()
    private var cachedAt = 0L

    /** Every trackable emu game folder under the windows scan surfaces (the sync/link surface). */
    suspend fun scan(): List<LocalSteamGame> = scanAll().filter { it.trackable }

    /** Every emu-marked game folder including untrackable ones awaiting schema generation. */
    suspend fun scanAll(): List<LocalSteamGame> = scanMutex.withLock { freshScan() }

    /**
     * The game folder whose `steam_appid.txt` matches [appId], or null. Served from a scan at most
     * [SCAN_CACHE_MS] old: Sync All scans once up front, then every per-game sync in the pass
     * resolves against that result instead of re-walking the tree. A folder moved mid-window is
     * seen one pass late — the same self-correction a mid-scan move already relies on.
     */
    suspend fun findByAppId(appId: String): LocalSteamGame? = scanMutex.withLock {
        val fresh = System.currentTimeMillis() - cachedAt <= SCAN_CACHE_MS
        // Deliberately matches untrackable games too: a sync requested right after generation
        // (inside the cache window) should read the fresh kit, not fail on the stale gate flag.
        (if (fresh) cachedGames else freshScan()).firstOrNull { it.appId == appId }
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
                    .mapNotNull { inspect(tree, it) }
            }
            .also {
                cachedGames = it
                cachedAt = System.currentTimeMillis()
                Timber.i("LOCAL_STEAM discovery — ${it.size} emu game folder(s)")
            }
    }

    // A game qualifies through its steam_settings folder, searched a few levels deep because some
    // installs keep the exe (and the DLL beside it) in a subfolder of the distributed folder.
    private fun inspect(tree: Uri, gameFolder: SafChild): LocalSteamGame? {
        val settingsDir = findSteamSettings(tree, gameFolder, depthLeft = SETTINGS_SEARCH_DEPTH)
            ?: return null
        val settingsChildren = context.contentResolver.querySafChildren(tree, settingsDir.dir.documentId)

        val appId = settingsChildren.textOf("steam_appid.txt", SMALL_FILE_MAX_BYTES)
            ?.trim()?.takeIf { it.isNotEmpty() && it.length <= 12 && it.all(Char::isDigit) }
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
            folderName = gameFolder.name,
            folderDocId = gameFolder.documentId,
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

    private fun findSteamSettings(tree: Uri, folder: SafChild, depthLeft: Int): FoundDir? {
        val children = context.contentResolver.querySafChildren(tree, folder.documentId)
        children.firstOrNull { it.isDirectory && it.name.equals("steam_settings", ignoreCase = true) }
            ?.let { return FoundDir(it, folder.documentId) }
        if (depthLeft <= 1) return null
        return children.asSequence()
            .filter { it.isDirectory && !it.isIgnoredDir() }
            .firstNotNullOfOrNull { findSteamSettings(tree, it, depthLeft - 1) }
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
