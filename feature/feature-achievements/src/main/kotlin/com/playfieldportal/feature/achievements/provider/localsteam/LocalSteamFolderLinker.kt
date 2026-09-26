package com.playfieldportal.feature.achievements.provider.localsteam

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.LocalCopyOwnership
import com.playfieldportal.feature.achievements.AchievementController
import com.playfieldportal.feature.artwork.match.StorefrontMatchResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns one picked game folder into a linked, registered LOCAL_STEAM game.
 *
 * The per-game half of folder-picked matching: the user answers "No" to the legitimate-copy question
 * on a game's Shiba Coins page, picks that game's folder, and everything from there — identify,
 * register, link, classify ownership — happens here. The batch matcher runs the same steps over many
 * folders at once (see [LocalSteamBatchMatcher]); nothing about linking differs between the two.
 *
 * **The grant is taken before anything is read.** A tree picked from the file manager is usable for
 * this process only unless it is persisted, so every entry point takes the persistable grant first.
 * Without that, a folder would resolve perfectly today and be unreachable after the next restart.
 */
@Singleton
class LocalSteamFolderLinker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val discovery: LocalSteamDiscovery,
    private val identity: LocalSteamIdentityResolver,
    private val generator: LocalSteamSchemaGenerator,
    private val achievements: AchievementController,
    private val ownership: LocalSteamOwnership,
    private val games: GameDao,
    private val credentials: AchievementCredentialsProvider,
) {

    /** What linking one picked folder produced. */
    sealed interface LinkOutcome {

        /** Registered and linked. The caller syncs from here. */
        data class Linked(val appId: String, val folderName: String) : LinkOutcome

        /**
         * Identified, but the folder carries no achievement list the emulator can read.
         *
         * Offered rather than done: writing the kit replaces the game's `steam_api` DLL and changes
         * where it saves, which is the one step the save-backup warning exists for.
         * [installerEnabled] is false when the Goldberg installer opt-in is off, so the prompt can
         * point at the setting instead of offering a button that would refuse.
         */
        data class NeedsKit(
            val appId: String,
            val folderName: String,
            val installerEnabled: Boolean,
            /** How the id was established, carried through so the follow-up records it truthfully. */
            val source: AppIdSource,
        ) : LinkOutcome

        /** The title resolved to several plausible games. The user picks; nothing was written. */
        data class NeedsConfirmation(
            val result: StorefrontMatchResult,
            val query: String,
            val folderName: String,
        ) : LinkOutcome

        /** Nothing here can be tracked, in the folder's own terms. */
        data class NoEmuData(val reason: String) : LinkOutcome

        /** The pick itself could not be used — a revoked grant, an unreadable tree. */
        data class Failed(val reason: String) : LinkOutcome
    }

    /**
     * The folder already registered for [gameId], or null.
     *
     * The pre-check that keeps a batch-matched game from being asked a second time: a registered
     * folder whose name matches this game's display title links straight from it.
     *
     * Matching on the NAME is the only key available here, and it is deliberately exact-after-
     * normalizing rather than fuzzy. A game already carrying a LOCAL_STEAM link never reaches this
     * page at all, so there is no app id to look up; and a folder whose name does not match the
     * game — the case the Steam-name bridge exists for — falls through to the folder pick, which
     * asks a question rather than guessing. Asking once too often is the safe failure here.
     */
    suspend fun registeredFolderFor(gameId: Long): LocalSteamGame? {
        val game = runCatching { games.getById(gameId) }.getOrNull() ?: return null
        val rows = discovery.registeredFolders()
        if (rows.isEmpty()) return null
        val titleKey = normalize(displayTitleOf(game))
        val row = rows.firstOrNull { normalize(it.folderName) == titleKey && titleKey.isNotEmpty() }
            ?: return null
        return discovery.findByAppId(row.appId)
    }

    /**
     * Links [gameId] straight to a folder the registry already holds.
     *
     * Deliberately does NOT go through [link]: a folder registered by a BATCH match was reached
     * through a grant on its PARENT, so re-deriving the folder from that tree's document id would
     * anchor the parent instead of the game. The registry already recorded the folder itself, and
     * [LocalSteamDiscovery.findByAppId] already re-read it from disk, so there is nothing left to
     * work out — which is the whole point of the pre-check.
     */
    suspend fun linkRegistered(gameId: Long, folder: LocalSteamGame): LinkOutcome {
        achievements.linkManually(gameId, AchievementProvider.LOCAL_STEAM, folder.appId)
        val owned = ownership.classify(gameId, folder.appId)
        if (owned == LocalCopyOwnership.OWNED) {
            achievements.linkManually(gameId, AchievementProvider.STEAM, folder.appId)
        }
        Timber.i("LOCAL_STEAM linked game $gameId to ${folder.appId} from the registry (${folder.folderName})")
        return LinkOutcome.Linked(folder.appId, folder.folderName)
    }

    /**
     * Links [gameId] to the game folder the tree [treeUri] was granted on.
     *
     * The picked tree IS the game folder: `OpenDocumentTree` on a game folder grants that folder,
     * so its tree document id is the folder to inspect.
     */
    suspend fun link(gameId: Long, treeUri: Uri): LinkOutcome {
        takePersistableGrant(treeUri)
        val anchor = anchorOrNull(treeUri) ?: return notASteamBuild(treeUri)
        val game = runCatching { games.getById(gameId) }.getOrNull()
        return when (val outcome = identity.identify(anchor, game)) {
            is LocalSteamIdentityResolver.Outcome.Resolved -> finish(gameId, anchor, outcome.appId, outcome.source)
            is LocalSteamIdentityResolver.Outcome.NeedsConfirmation ->
                LinkOutcome.NeedsConfirmation(outcome.result, outcome.query, anchor.folderName)
            LocalSteamIdentityResolver.Outcome.NoMatch -> LinkOutcome.NoEmuData(
                "Steam has no game matching \"${anchor.folderName}\". Rename the folder to the " +
                    "game's Steam name, or link it from Game Detail ▸ Match Game first."
            )
            is LocalSteamIdentityResolver.Outcome.Unavailable -> LinkOutcome.NoEmuData(
                "${outcome.reason}, so this folder's app id could not be worked out. " +
                    "Nothing was changed — try again when you are back online."
            )
            LocalSteamIdentityResolver.Outcome.NotASteamBuild -> notASteamBuild(treeUri)
            is LocalSteamIdentityResolver.Outcome.WriteFailed -> LinkOutcome.Failed(
                "Could not create steam_appid.txt in that folder — PFP may only have read access to it."
            )
        }
    }

    /** The user chose a candidate in the match picker: write that app id back, then link. */
    suspend fun confirmCandidate(gameId: Long, treeUri: Uri, appId: String): LinkOutcome {
        val anchor = anchorOrNull(treeUri) ?: return notASteamBuild(treeUri)
        return when (val outcome = identity.confirm(anchor, appId)) {
            is LocalSteamIdentityResolver.Outcome.Resolved -> finish(gameId, anchor, outcome.appId, outcome.source)
            is LocalSteamIdentityResolver.Outcome.WriteFailed -> LinkOutcome.Failed(
                "Could not create steam_appid.txt in that folder — PFP may only have read access to it."
            )
            // confirm() only ever resolves or fails to write; the rest cannot be reached from here.
            else -> LinkOutcome.Failed("The chosen match could not be recorded.")
        }
    }

    /**
     * Links the game at 0% with its real coin list and writes nothing else into the folder.
     *
     * The escape hatch from [LinkOutcome.NeedsKit]: a user may want to SEE the achievement list
     * before authorising a DLL swap, and refusing to show it until they agree to one would be the
     * wrong trade.
     */
    suspend fun linkWithoutKit(gameId: Long, treeUri: Uri, appId: String, source: AppIdSource): LinkOutcome {
        val anchor = anchorOrNull(treeUri) ?: return notASteamBuild(treeUri)
        return register(gameId, anchor, appId, source, requireSchema = false)
    }

    /**
     * Writes the emulator kit into the folder, then links.
     *
     * Refused outright with the Goldberg installer opt-in off: this is the step that replaces the
     * game's `steam_api` DLL and moves where it saves.
     */
    suspend fun installKitAndLink(
        gameId: Long,
        treeUri: Uri,
        appId: String,
        source: AppIdSource,
    ): LinkOutcome {
        if (!credentials.goldbergInstallerEnabled()) {
            return LinkOutcome.Failed(
                "Turn on Install Goldberg Emulator in Settings ▸ Shiba Coins before PFP writes into a game folder."
            )
        }
        val anchor = anchorOrNull(treeUri) ?: return notASteamBuild(treeUri)
        // The generator needs a settings folder to write into; the marker write already created one
        // for every path that reaches here, so re-inspect rather than assume the anchor is stale.
        val folder = discovery.inspect(anchor.treeUri, anchor.folderDocId)
            ?: return LinkOutcome.Failed("That folder no longer declares an app id — pick it again.")
        when (val result = generator.generate(folder)) {
            LocalSteamSchemaGenerator.Result.Written -> Unit
            LocalSteamSchemaGenerator.Result.NoKey -> return LinkOutcome.Failed(
                "Add your Steam Web API key in Settings ▸ Shiba Coins — the achievement list comes from it."
            )
            LocalSteamSchemaGenerator.Result.NoAchievements -> return LinkOutcome.NoEmuData(
                "Steam lists no achievements for app id $appId, so there is nothing to track."
            )
            is LocalSteamSchemaGenerator.Result.Failed ->
                return LinkOutcome.Failed("Could not write the achievement kit: ${result.reason}")
        }
        return register(gameId, anchor, appId, source, requireSchema = false)
    }

    // ── Shared tail ────────────────────────────────────────────────────────────

    // Identified: either the folder is ready to track, or it needs the kit first.
    private suspend fun finish(
        gameId: Long,
        anchor: FolderAnchor,
        appId: String,
        source: AppIdSource,
    ): LinkOutcome = register(gameId, anchor, appId, source, requireSchema = true)

    /**
     * Registers the folder and links the game.
     *
     * [requireSchema] is what separates the automatic path from the user's explicit choice: on the
     * first pass a folder with no achievement list stops at [LinkOutcome.NeedsKit] so a person can
     * decide, and Link Only / Install & Link both come back through here with it off.
     */
    private suspend fun register(
        gameId: Long,
        anchor: FolderAnchor,
        appId: String,
        source: AppIdSource,
        requireSchema: Boolean,
    ): LinkOutcome {
        val folder = discovery.inspect(anchor.treeUri, anchor.folderDocId)
            // The marker was just written (or was already there), so a null here means the folder
            // became unreachable mid-flow — a revoked grant, a moved folder, an unmounted card.
            ?: return LinkOutcome.Failed(
                "That folder could not be read back. If it is on removable storage, remount it and pick it again."
            )
        if (requireSchema && !folder.hasSchema) {
            return LinkOutcome.NeedsKit(
                appId = appId,
                folderName = anchor.folderName,
                installerEnabled = credentials.goldbergInstallerEnabled(),
                source = source,
            )
        }

        discovery.register(folder, source)
        achievements.linkManually(gameId, AchievementProvider.LOCAL_STEAM, folder.appId)
        val owned = ownership.classify(gameId, folder.appId)
        // An owned copy played offline holds BOTH sets — appid equality beats any title ladder.
        if (owned == LocalCopyOwnership.OWNED) {
            achievements.linkManually(gameId, AchievementProvider.STEAM, folder.appId)
        }
        Timber.i("LOCAL_STEAM linked game $gameId to ${folder.appId} via ${anchor.folderName} ($source)")
        return LinkOutcome.Linked(folder.appId, anchor.folderName)
    }

    /**
     * Anchors the folder [treeUri] was granted on.
     *
     * Null means the folder offers nothing to hang an identity on, which for a folder the user just
     * picked has one honest cause: there is no Steam API DLL in it. Only [link] takes the grant —
     * the follow-up steps run on a tree this process already holds.
     */
    private suspend fun anchorOrNull(treeUri: Uri): FolderAnchor? {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        return discovery.anchor(treeUri.toString(), docId)
    }

    // Read+write, because the marker and the kit are both written through this same grant.
    private suspend fun takePersistableGrant(treeUri: Uri) = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { Timber.w(it, "Could not persist the grant for the picked game folder") }
    }

    private fun notASteamBuild(treeUri: Uri) = LinkOutcome.NoEmuData(
        "No Steam library file (steam_api64.dll) anywhere in that folder, so it is not a Steam " +
            "build and there is nothing for the emulator to track. Pick the folder the game's " +
            ".exe lives in."
    ).also { Timber.i("LOCAL_STEAM — picked folder is not a Steam build: $treeUri") }

    private fun displayTitleOf(game: com.playfieldportal.core.data.database.entity.GameEntity): String =
        game.userTitleOverride?.takeIf { it.isNotBlank() }
            ?: game.scrapedTitle?.takeIf { it.isNotBlank() }
            ?: game.title

    // The Windows-card dedupe rule: folder names count as titles.
    private fun normalize(title: String): String = title.lowercase().filter { it.isLetterOrDigit() }
}
