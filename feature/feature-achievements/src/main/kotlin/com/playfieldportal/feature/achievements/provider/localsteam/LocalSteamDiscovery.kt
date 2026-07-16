package com.playfieldportal.feature.achievements.provider.localsteam

import android.content.Context
import android.net.Uri
import com.playfieldportal.core.data.repository.WindowsLibrarySetup
import com.playfieldportal.core.data.saf.SafChild
import com.playfieldportal.core.data.saf.isIgnoredDir
import com.playfieldportal.core.data.saf.querySafChildren
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
    /** Every emu-marked game folder under the windows scan surfaces; empty when none is set up. */
    suspend fun scan(): List<LocalSteamGame> = withContext(Dispatchers.IO) {
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
            .also { Timber.i("LOCAL_STEAM discovery — ${it.size} emu game folder(s)") }
    }

    /** The game folder whose `steam_appid.txt` matches [appId], or null. */
    suspend fun findByAppId(appId: String): LocalSteamGame? = scan().firstOrNull { it.appId == appId }

    // A game qualifies through its steam_settings folder, searched a few levels deep because some
    // installs keep the exe (and the DLL beside it) in a subfolder of the distributed folder.
    private fun inspect(tree: Uri, gameFolder: SafChild): LocalSteamGame? {
        val settingsDir = findSteamSettings(tree, gameFolder, depthLeft = SETTINGS_SEARCH_DEPTH)
            ?: return null
        val settingsChildren = context.contentResolver.querySafChildren(tree, settingsDir.dir.documentId)

        val appId = settingsChildren.textOf("steam_appid.txt", SMALL_FILE_MAX_BYTES)
            ?.trim()?.takeIf { it.isNotEmpty() && it.length <= 12 && it.all(Char::isDigit) }
            ?: return null

        val achievements = settingsChildren.textOf("configs.user.ini", GseUserConfig.MAX_BYTES)
            ?.let(GseUserConfig::localSavePath)
            ?.let(GseUserConfig::savePathSegments)
            ?.let { redirect -> resolveFile(tree, settingsDir.parentDocId, redirect + appId + PROGRESS_FILE) }

        return LocalSteamGame(
            folderName = gameFolder.name,
            folderDocId = gameFolder.documentId,
            appId = appId,
            achievementsUri = achievements,
        )
    }

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
        // Depth 4 reaches Unity's nesting: <Game>/<Game>_Data/Plugins/x86_64/steam_settings
        // (live case: the FF pixel remasters — docs/windows-library-refactor-plan.md Phase 5).
        const val SETTINGS_SEARCH_DEPTH = 4
        const val SMALL_FILE_MAX_BYTES = 64
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
