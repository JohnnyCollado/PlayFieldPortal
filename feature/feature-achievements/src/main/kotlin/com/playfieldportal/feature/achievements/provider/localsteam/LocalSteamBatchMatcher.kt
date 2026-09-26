package com.playfieldportal.feature.achievements.provider.localsteam

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.feature.achievements.AchievementController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Matches a whole folder of Steam-emu game folders in one pass — the batch half of folder-picked
 * matching.
 *
 * The user picks the PARENT folder their Wine/Winlator library lives in; every child folder is
 * inspected, registered and sorted into three piles:
 *
 *  - **Ready** — an app id and an achievement list the emulator can read. Linked and synced here.
 *  - **Convertible** — an app id but no list yet. Handed to the convert picker, which is the only
 *    place a DLL swap is ever authorised; whatever the user converts comes straight back through
 *    [linkAndSync] and syncs in the same run.
 *  - **Needs identifying** — no marker, and the title did not resolve confidently. Reported as a
 *    count and deferred to the per-game flow, because a batch must never write a guessed id into
 *    somebody's game folder.
 *
 * Mapping a folder onto a library game reuses the shortcut-to-folder join exactly: normalized title
 * first, then the Steam-name bridge (the app id's official store name, matched the same way), which
 * survives a renamed folder. No fuzzy matching, ever. A folder that maps onto nothing is not an
 * error — it stays a tracked local entry and appears in Shiba Coins after a sync, as it always has.
 */
@Singleton
class LocalSteamBatchMatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val discovery: LocalSteamDiscovery,
    private val identity: LocalSteamIdentityResolver,
    private val importer: LocalSteamGameImporter,
    private val achievements: AchievementController,
    private val games: GameDao,
) {

    /** What one batch run did, in the terms the summary and the tray message are written in. */
    data class BatchReport(
        val discovered: Int = 0,
        val linkedToLibrary: Int = 0,
        val trackedWithoutLibrary: Int = 0,
        val needsIdentifying: Int = 0,
        val notSteamBuilds: Int = 0,
        val failed: Int = 0,
        /** Registered folders with no achievement list yet — the convert picker's input. */
        val convertible: List<LocalSteamGame> = emptyList(),
        /** One line per failure, so the summary can say what went wrong rather than only how often. */
        val failures: List<String> = emptyList(),
        /** True when a run was already going and this call did nothing. */
        val alreadyRunning: Boolean = false,
    ) {
        val registered: Int get() = linkedToLibrary + trackedWithoutLibrary + convertible.size

        val message: String
            get() = when {
                alreadyRunning -> "A batch match is already running."
                discovered == 0 ->
                    "No game folders in there. Pick the folder that CONTAINS your game folders, " +
                        "not one game's own folder."
                registered == 0 && needsIdentifying == 0 ->
                    "Found $discovered folder(s), but none of them is a Steam build — no steam_api " +
                        "library, so there is nothing for the emulator to track."
                else -> buildString {
                    append("Matched $registered of $discovered folder(s)")
                    if (linkedToLibrary > 0) append(": $linkedToLibrary linked to library games")
                    if (trackedWithoutLibrary > 0) append(", $trackedWithoutLibrary tracked on their own")
                    if (convertible.isNotEmpty()) append(", ${convertible.size} awaiting conversion")
                    append(".")
                    if (needsIdentifying > 0) {
                        append(
                            " $needsIdentifying could not be identified from their names — open each " +
                                "game's Shiba Coins page and Auto-Match to pick its folder."
                        )
                    }
                    if (notSteamBuilds > 0) append(" $notSteamBuilds are not Steam builds.")
                    if (failed > 0) append(" $failed failed — see the log.")
                }
            }
    }

    // One run at a time. A second pick while a run is in flight would double-register folders and
    // race the same games into a sync; tryLock says so instead of queueing silently.
    private val runMutex = Mutex()

    /** True while a run is in flight — the row that starts one renders disabled. */
    val running: Boolean get() = runMutex.isLocked

    /**
     * Inspects every child folder of the picked parent [treeUri] and links what it can.
     *
     * [onProgress] reports (done, total) over the folders, so a long pass over a real emulator
     * library shows movement rather than a spinner.
     */
    suspend fun run(treeUri: Uri, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): BatchReport {
        if (!runMutex.tryLock()) return BatchReport(alreadyRunning = true)
        try {
            takePersistableGrant(treeUri)
            val tree = treeUri.toString()
            val parentDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: return BatchReport(failed = 1, failures = listOf("That folder could not be opened."))

            val children = discovery.childFolders(tree, parentDocId)
            onProgress(0, children.size)
            if (children.isEmpty()) return BatchReport()

            // The library once, not once per folder: the mapping ladder reads it for every child.
            val windowsGames = runCatching { games.getByPlatformOnce(WINDOWS_PLATFORM_ID) }.getOrDefault(emptyList())
            val byTitle = windowsGames.associateBy { normalize(displayTitleOf(it)) }

            var needsIdentifying = 0
            var notSteamBuilds = 0
            var failed = 0
            val ready = mutableListOf<LocalSteamGame>()
            val convertible = mutableListOf<LocalSteamGame>()
            val failures = mutableListOf<String>()

            for ((index, child) in children.withIndex()) {
                val anchor = discovery.anchor(tree, child.documentId)
                if (anchor == null) {
                    notSteamBuilds++
                    onProgress(index + 1, children.size)
                    continue
                }
                // A folder with no marker still needs a title to resolve, and the folder name is the
                // best one anyone has for it — so map it to a library game FIRST when possible, and
                // resolve against that game's real title rather than its folder's.
                val game = byTitle[normalize(anchor.folderName)]
                when (val outcome = identity.identify(anchor, game)) {
                    is LocalSteamIdentityResolver.Outcome.Resolved -> {
                        val folder = discovery.inspect(tree, anchor.folderDocId)
                        if (folder == null) {
                            failed++
                            failures += "${anchor.folderName}: could not be read back after identifying."
                        } else {
                            discovery.register(folder, outcome.source)
                            // No list yet: the convert picker is the only place a DLL swap is
                            // ever authorised, so such a folder stops here and waits for a person.
                            if (folder.hasSchema) ready += folder else convertible += folder
                        }
                    }
                    // Below EXACT/HIGH, or nothing at all: deferred, with nothing written.
                    is LocalSteamIdentityResolver.Outcome.NeedsConfirmation,
                    LocalSteamIdentityResolver.Outcome.NoMatch,
                    -> needsIdentifying++

                    is LocalSteamIdentityResolver.Outcome.Unavailable -> {
                        needsIdentifying++
                        failures += "${anchor.folderName}: ${outcome.reason}."
                    }
                    LocalSteamIdentityResolver.Outcome.NotASteamBuild -> notSteamBuilds++
                    is LocalSteamIdentityResolver.Outcome.WriteFailed -> {
                        failed++
                        failures += "${anchor.folderName}: steam_appid.txt could not be created."
                    }
                }
                onProgress(index + 1, children.size)
            }

            // reRegister = false: each folder was just registered with the source that actually
            // established its id, and re-registering here would flatten a TITLE_MATCH into a MARKER.
            val linkResult = linkAndSync(ready, reRegister = false)
            val report = BatchReport(
                discovered = children.size,
                linkedToLibrary = linkResult.linkedToLibrary,
                trackedWithoutLibrary = linkResult.trackedWithoutLibrary,
                needsIdentifying = needsIdentifying,
                notSteamBuilds = notSteamBuilds,
                failed = failed,
                convertible = convertible,
                failures = failures,
            )
            Timber.i(
                "LOCAL_STEAM batch — ${children.size} folder(s): ${linkResult.linkedToLibrary} linked, " +
                    "${linkResult.trackedWithoutLibrary} tracked, ${convertible.size} convertible, " +
                    "$needsIdentifying unidentified, $notSteamBuilds non-Steam, $failed failed"
            )
            return report
        } finally {
            runMutex.unlock()
        }
    }

    /**
     * Links and syncs the folders the convert picker just wrote a kit into.
     *
     * The same tail as the ready pile, called after conversion so a converted game syncs in the same
     * run rather than waiting for the next Update pass.
     */
    suspend fun linkAndSync(folders: List<LocalSteamGame>, reRegister: Boolean = true): BatchReport {
        if (folders.isEmpty()) return BatchReport()
        // Re-read each folder: a conversion that just ran wrote the kit, so the schema flag and the
        // save location on the pre-conversion row are both out of date.
        val fresh = folders.map { discovery.inspect(it.settingsTreeUri, it.folderDocId) ?: it }
        // A converted folder carries its own marker by definition — that is what made it convertible.
        if (reRegister) fresh.forEach { discovery.register(it, AppIdSource.MARKER) }

        val result = importer.reconcile(fresh)
        // Exactly the games the ladder linked. A folder that mapped onto nothing has no game id to
        // sync against and reaches Shiba Coins as an account-style entry through the normal
        // "Update installed achievements" pass, as it always has.
        for (gameId in result.linkedGameIds) {
            runCatching { achievements.syncGameById(gameId) }
                .onFailure { Timber.w(it, "Batch sync failed for game %d", gameId) }
        }
        return BatchReport(
            discovered = fresh.size,
            linkedToLibrary = result.linked,
            trackedWithoutLibrary = fresh.size - result.linked,
        )
    }

    private suspend fun takePersistableGrant(treeUri: Uri) = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { Timber.w(it, "Could not persist the grant for the picked parent folder") }
    }

    private fun displayTitleOf(game: GameEntity): String =
        game.userTitleOverride?.takeIf { it.isNotBlank() }
            ?: game.scrapedTitle?.takeIf { it.isNotBlank() }
            ?: game.title

    // Mirrors the Windows-card dedupe rule: folder names count as titles.
    private fun normalize(title: String): String = title.lowercase().filter { it.isLetterOrDigit() }

    private companion object {
        const val WINDOWS_PLATFORM_ID = "windows"
    }
}
