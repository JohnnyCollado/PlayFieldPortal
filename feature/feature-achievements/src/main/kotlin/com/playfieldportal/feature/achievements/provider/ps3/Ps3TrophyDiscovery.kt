package com.playfieldportal.feature.achievements.provider.ps3

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.playfieldportal.core.data.repository.Ps3DataLibrary
import com.playfieldportal.core.data.saf.SafChild
import com.playfieldportal.core.data.saf.querySafChildren
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads PS3 trophy sets from ARMSX3's granted data folder ([Ps3DataLibrary]).
 *
 * A set lives at `config/dev_hdd0/home/<USERID>/trophy/<NPCOMMID>` and holds `TROPCONF.SFM`
 * (definitions, including the hidden flag), `TROPUSR.DAT` (unlock state) and `TROP%03d.PNG` icons.
 * The user id is an 8-digit profile directory, so profiles are **enumerated** rather than assumed
 * to be `00000001`.
 *
 * The grant depth is whatever the user picked — the ARMSX3 root, `config`, `dev_hdd0`, or the
 * `trophy` folder itself — and all of them resolve to the same trophy directories. Read-only and
 * grant-scoped: every URI comes from a tree-scoped child query, so a read can never escape the
 * folder the user granted, and nothing is ever written into the emulator's data.
 */
@Singleton
class Ps3TrophyDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ps3DataLibrary: Ps3DataLibrary,
) {
    /** Why a read produced nothing, so the UI can say what to fix instead of "no trophies". */
    sealed interface Failure {
        /** No PS3 data folder granted (or the grant was revoked). */
        data object NotConfigured : Failure

        /** The grant is readable but holds no `dev_hdd0` profile trophy folder (wrong folder picked). */
        data object NoTrophyFolder : Failure

        /** The set isn't on disk yet — the game registers it the first time it runs. */
        data object SetNotPresent : Failure
    }

    /** Every trophy set id present under the grant, across all profiles. Empty when unset. */
    suspend fun availableSetIds(): List<String> = withContext(Dispatchers.IO) {
        val tree = tree() ?: return@withContext emptyList()
        trophyDirs(tree).flatMap { dir ->
            context.contentResolver.querySafChildren(tree, dir)
                .filter { it.isDirectory && it.name.startsWith(NPWR_PREFIX, ignoreCase = true) }
                .map { it.name }
        }.distinct()
    }

    /**
     * Definitions + icons + unlock state for one set, or null when it can't be read. A set with no
     * `TROPUSR.DAT` loads definitions-only at 0% — never "nothing earned" by accident.
     */
    suspend fun loadOneSet(npCommId: String): Ps3TrophySet? = withContext(Dispatchers.IO) {
        val tree = tree() ?: return@withContext null
        loadSetFrom(tree, npCommId)
    }

    /**
     * What callers use: loads each of [npCommIds] **in the given order** (see [Ps3TrophySets.merge]
     * for the merge itself). A DLC subset the game declares but hasn't registered yet is simply
     * absent — skipped silently, not an error — and a grown list on a later sync is normal, so the
     * subset list is re-enumerated on every call rather than cached.
     */
    suspend fun loadMerged(npCommIds: List<String>): List<Ps3TrophySet> = withContext(Dispatchers.IO) {
        val tree = tree() ?: return@withContext emptyList()
        npCommIds.mapNotNull { loadSetFrom(tree, it) }
    }

    /** Null when [npCommId] is present under the grant, else the reason it is not. */
    suspend fun checkSet(npCommId: String): Failure? = withContext(Dispatchers.IO) {
        val tree = tree() ?: return@withContext Failure.NotConfigured
        val dirs = trophyDirs(tree)
        if (dirs.isEmpty()) return@withContext Failure.NoTrophyFolder
        val found = dirs.any { dir ->
            context.contentResolver.querySafChildren(tree, dir)
                .any { it.isDirectory && it.name.equals(npCommId, ignoreCase = true) }
        }
        if (found) null else Failure.SetNotPresent
    }

    // ── SAF plumbing ──────────────────────────────────────────────────────────

    private suspend fun tree(): Uri? =
        ps3DataLibrary.dataTreeUri()?.let { runCatching { Uri.parse(it) }.getOrNull() }

    private fun loadSetFrom(tree: Uri, npCommId: String): Ps3TrophySet? {
        val setDir = trophyDirs(tree).firstNotNullOfOrNull { dir ->
            context.contentResolver.querySafChildren(tree, dir)
                .firstOrNull { it.isDirectory && it.name.equals(npCommId, ignoreCase = true) }
                ?.documentId
        } ?: return null

        val children = context.contentResolver.querySafChildren(tree, setDir)
        val sfm = children.fileUri(TROPCONF)?.let { readBytes(it) } ?: return null
        val usr = children.fileUri(TROPUSR)?.let { readBytes(it) }
        val iconByName = children.filter { !it.isDirectory }.associate { it.name.uppercase() to it.uri.toString() }
        return Ps3TrophySets.buildSet(npCommId, sfm, usr) { iconByName[it.uppercase()] }
    }

    /**
     * Every profile trophy directory under the grant, one per profile.
     *
     * Tolerant of the depth the user picked: a grant already **at** the trophy folder (it holds
     * `NPWR…` children) is used as-is, otherwise the walk descends the first of [HOME_PATHS] that
     * reaches `home` and enumerates each profile's trophy folder.
     */
    private fun trophyDirs(tree: Uri): List<String> {
        val rootDoc = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return emptyList()
        val rootChildren = context.contentResolver.querySafChildren(tree, rootDoc)

        // Granted the trophy folder itself: its children are the sets.
        if (rootChildren.any { it.isDirectory && it.name.startsWith(NPWR_PREFIX, ignoreCase = true) }) {
            return listOf(rootDoc)
        }

        val homeDoc = HOME_PATHS.firstNotNullOfOrNull { resolveDir(tree, rootDoc, it) } ?: return emptyList()
        return context.contentResolver.querySafChildren(tree, homeDoc)
            .filter { it.isDirectory }
            .mapNotNull { profile -> childDir(tree, profile.documentId, TROPHY_DIR) }
    }

    private fun childDir(tree: Uri, parentDocId: String, name: String): String? =
        context.contentResolver.querySafChildren(tree, parentDocId)
            .firstOrNull { it.isDirectory && it.name.equals(name, ignoreCase = true) }
            ?.documentId

    private fun resolveDir(tree: Uri, startDocId: String, segments: List<String>): String? {
        var docId = startDocId
        for (segment in segments) docId = childDir(tree, docId, segment) ?: return null
        return docId
    }

    private fun List<SafChild>.fileUri(name: String): Uri? =
        firstOrNull { !it.isDirectory && it.name.equals(name, ignoreCase = true) }?.uri

    private fun readBytes(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            var remaining = SMALL_FILE_MAX
            while (remaining > 0) {
                val read = input.read(buf, 0, minOf(buf.size, remaining))
                if (read == -1) break
                out.write(buf, 0, read)
                remaining -= read
            }
            out.toByteArray()
        }
    }.getOrNull()

    private companion object {
        const val NPWR_PREFIX = "NPWR"
        const val TROPHY_DIR = "trophy"
        const val TROPCONF = "TROPCONF.SFM"
        const val TROPUSR = "TROPUSR.DAT"
        const val SMALL_FILE_MAX = 256 * 1024

        // The accepted grant depths, each the path from the grant down to the profiles folder.
        val HOME_PATHS = listOf(
            listOf("config", "dev_hdd0", "home"),           // granted the ARMSX3 root
            listOf("dev_hdd0", "home"),                     // granted config
            listOf("home"),                                 // granted dev_hdd0
            listOf("PS3", "config", "dev_hdd0", "home"),     // granted the folder holding PS3
        )
    }
}
