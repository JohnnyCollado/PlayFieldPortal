package com.playfieldportal.feature.achievements.provider.ps3

import android.content.Context
import android.net.Uri
import com.playfieldportal.core.data.repository.Ps3DataLibrary
import com.playfieldportal.core.data.saf.querySafChildren
import com.playfieldportal.core.data.saf.safScanStartDocId
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.feature.achievements.match.DiscImageOpener
import com.playfieldportal.feature.achievements.provider.vita.ParamSfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the trophy set ids a PS3 game itself declares, from its own `PS3_GAME/TROPDIR`.
 *
 * This is the same source the emulator reads: ARMSX3 resolves `/dev_bdvd/PS3_GAME/TROPDIR` off the
 * mounted disc and extracts that set's `TROPHY.TRP` on first registration. Reading it here means a
 * game can be linked deterministically **before** it has ever been booted, when no trophy folder
 * exists yet — PFP can say a game has 48 trophies waiting.
 *
 * `TROPDIR` is also the subset **grouping authority**: it lists every NPWR id the game declares, so
 * the disc itself says which sets belong together. The result keeps directory order — the first
 * entry is the base set and becomes the provider game id — and is never sorted or reordered.
 *
 * Reads only a few 2,048-byte sectors of a multi-GB image: the ISO9660 root, `PS3_GAME`, `TROPDIR`
 * and the small `PARAM.SFO`.
 */
@Singleton
class Ps3TropDirReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val discOpener: DiscImageOpener,
    private val ps3DataLibrary: Ps3DataLibrary,
) {
    /** What the game's own files said. Every failure is a reason, never a silent null. */
    sealed interface Result {
        /**
         * [npCommIds] in `TROPDIR` order — `first()` is the base set. [titleId] and [title] come
         * from `PARAM.SFO` when it was readable (the serial for display, a better fallback title).
         */
        data class Found(
            val npCommIds: List<String>,
            val titleId: String?,
            val title: String?,
        ) : Result {
            val baseNpCommId: String get() = npCommIds.first()
        }

        /** The game has no image on this device (missing file, or an app-backed row). */
        data object NoImage : Result

        /** The image couldn't be opened or parsed as ISO9660 — almost always an encrypted dump. */
        data object Unreadable : Result

        /** Opened fine, but there's no `PS3_GAME` — not a PS3 disc layout. */
        data object NoPs3Game : Result

        /** A PS3 game with no `TROPDIR` at all: this title simply has no trophies. */
        data object NoTropDir : Result

        /** `TROPDIR` exists but declares no `NPWR…` set. */
        data object NoTrophySets : Result
    }

    /** Every `NPWR…` set [game] declares, in directory order, or the reason none could be read. */
    suspend fun npCommIdsFor(game: Game): Result = withContext(Dispatchers.IO) {
        runCatching {
            when {
                isFolderDump(game) -> fromFolderDump(game)
                isPackage(game) -> fromInstalledPackage(game)
                else -> fromDiscImage(game)
            }
        }.onFailure { Timber.w(it, "TROPDIR read failed for %s", game.displayTitle) }
            .getOrDefault(Result.Unreadable)
    }

    // ── .iso / .bin — TROPDIR lives inside the image ───────────────────────────

    private suspend fun fromDiscImage(game: Game): Result {
        if (game.romPath == null && game.romUri == null) return Result.NoImage
        val image = discOpener.open(game) ?: return Result.Unreadable
        return image.use { disc ->
            if (disc.findFile(PS3_GAME) == null) return@use Result.NoPs3Game
            val tropDir = disc.findFile("$PS3_GAME\\$TROPDIR") ?: return@use Result.NoTropDir
            val ids = disc.listDir(tropDir.lba, tropDir.size)
                .filter { it.isDirectory && it.name.startsWith(NPWR_PREFIX, ignoreCase = true) }
                .map { it.name }
            if (ids.isEmpty()) return@use Result.NoTrophySets

            val sfo = disc.findFile("$PS3_GAME\\$PARAM_SFO")
                ?.let { disc.readFileBytes(it.lba, it.size, PARAM_SFO_MAX) }
                ?.let { ParamSfo.parseStrings(it) }
                .orEmpty()
            Result.Found(ids, sfo["TITLE_ID"], sfo["TITLE"])
        }
    }

    // ── .ps3dir folder dump — TROPDIR is a real directory on disk ──────────────

    private fun fromFolderDump(game: Game): Result {
        game.romPath?.let { path ->
            val dir = File(path).takeIf { it.isDirectory } ?: return Result.NoImage
            val ps3Game = childDir(dir, PS3_GAME) ?: return Result.NoPs3Game
            val tropDir = childDir(ps3Game, TROPDIR) ?: return Result.NoTropDir
            val ids = (tropDir.listFiles() ?: emptyArray())
                .filter { it.isDirectory && it.name.startsWith(NPWR_PREFIX, ignoreCase = true) }
                .sortedBy { it.name }
                .map { it.name }
            if (ids.isEmpty()) return Result.NoTrophySets
            val sfo = File(ps3Game, PARAM_SFO).takeIf { it.isFile }
                ?.let { runCatching { ParamSfo.parseStrings(it.readBytes()) }.getOrNull() }
                .orEmpty()
            return Result.Found(ids, sfo["TITLE_ID"], sfo["TITLE"])
        }
        val uri = game.romUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return Result.NoImage
        return fromSafFolder(uri, safScanStartDocId(context, uri))
    }

    private fun childDir(parent: File, name: String): File? =
        (parent.listFiles() ?: emptyArray()).firstOrNull { it.isDirectory && it.name.equals(name, true) }

    // Walks <game folder>/PS3_GAME/TROPDIR through the ROM root's tree grant.
    private fun fromSafFolder(tree: Uri, gameDocId: String): Result {
        val ps3Game = safChildDir(tree, gameDocId, PS3_GAME) ?: return Result.NoPs3Game
        val tropDir = safChildDir(tree, ps3Game, TROPDIR) ?: return Result.NoTropDir
        val ids = context.contentResolver.querySafChildren(tree, tropDir)
            .filter { it.isDirectory && it.name.startsWith(NPWR_PREFIX, ignoreCase = true) }
            .map { it.name }
        if (ids.isEmpty()) return Result.NoTrophySets
        val sfo = context.contentResolver.querySafChildren(tree, ps3Game)
            .firstOrNull { !it.isDirectory && it.name.equals(PARAM_SFO, true) }
            ?.let { child ->
                runCatching {
                    context.contentResolver.openInputStream(child.uri)?.use { it.readBytes() }
                }.getOrNull()
            }
            ?.let { ParamSfo.parseStrings(it) }
            .orEmpty()
        return Result.Found(ids, sfo["TITLE_ID"], sfo["TITLE"])
    }

    private fun safChildDir(tree: Uri, parentDocId: String, name: String): String? =
        context.contentResolver.querySafChildren(tree, parentDocId)
            .firstOrNull { it.isDirectory && it.name.equals(name, ignoreCase = true) }
            ?.documentId

    // ── .pkg — the installed copy under the emulator's own dev_hdd0/game ───────

    /**
     * A `.pkg` is an installer, not a playable layout: the trophy ids live in the **installed** copy
     * at `dev_hdd0/game/<TITLE_ID>/TROPDIR`, under the PS3 data grant. The pkg file itself is
     * encrypted, so the title id can't be read from it — each installed game folder's `PARAM.SFO`
     * is matched against the library title instead.
     *
     * Written from the documented PS3 layout rather than from a capture (no `pkg` install was
     * observed), so it is deliberately conservative: anything it can't line up reports a reason.
     */
    private suspend fun fromInstalledPackage(game: Game): Result {
        val treeUri = ps3DataLibrary.dataTreeUri()?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: return Result.NoImage
        val rootDoc = runCatching { android.provider.DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrNull() ?: return Result.NoImage
        val gameRoot = GAME_PATHS.firstNotNullOfOrNull { segments ->
            var docId: String? = rootDoc
            for (segment in segments) docId = docId?.let { safChildDir(treeUri, it, segment) }
            docId
        } ?: return Result.NoPs3Game

        val wanted = normalizeTitle(game.displayTitle)
        for (installed in context.contentResolver.querySafChildren(treeUri, gameRoot).filter { it.isDirectory }) {
            val result = fromSafFolder(treeUri, installed.documentId)
            if (result !is Result.Found) continue
            val matches = result.titleId?.equals(installed.name, ignoreCase = true) == true &&
                (normalizeTitle(result.title.orEmpty()) == wanted || wanted.isEmpty())
            if (matches) return result
        }
        return Result.NoTropDir
    }

    private fun normalizeTitle(title: String): String = title.lowercase().filter { it.isLetterOrDigit() }

    private fun isFolderDump(game: Game): Boolean =
        game.romPath?.endsWith(PS3DIR_EXT, true) == true || game.romUri?.endsWith(PS3DIR_EXT, true) == true ||
            game.romPath?.let { File(it).isDirectory } == true

    private fun isPackage(game: Game): Boolean =
        game.romPath?.endsWith(PKG_EXT, true) == true || game.romUri?.endsWith(PKG_EXT, true) == true

    private companion object {
        const val PS3_GAME = "PS3_GAME"
        const val TROPDIR = "TROPDIR"
        const val PARAM_SFO = "PARAM.SFO"
        const val NPWR_PREFIX = "NPWR"
        const val PS3DIR_EXT = ".ps3dir"
        const val PKG_EXT = ".pkg"
        const val PARAM_SFO_MAX = 64 * 1024

        // dev_hdd0/game holds installed (pkg/HG) titles; accepted at the same grant depths as
        // Ps3TrophyDiscovery's trophy folders.
        val GAME_PATHS = listOf(
            listOf("config", "dev_hdd0", "game"),
            listOf("dev_hdd0", "game"),
            listOf("game"),
            listOf("PS3", "config", "dev_hdd0", "game"),
        )
    }
}
