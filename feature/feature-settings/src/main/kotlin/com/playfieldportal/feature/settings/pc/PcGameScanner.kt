package com.playfieldportal.feature.settings.pc

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.playfieldportal.core.common.security.ShortcutIntentSanitizer
import com.playfieldportal.core.data.database.dao.ArtworkRecordDao
import com.playfieldportal.feature.artwork.api.ArtworkImportManager
import com.playfieldportal.core.data.repository.RomRootRepository
import com.playfieldportal.core.data.repository.WindowsLibrarySetup
import com.playfieldportal.core.data.repository.WindowsSetupState
import com.playfieldportal.core.data.model.StorefrontIdentity
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameContentType
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.provider.localsteam.EmuGameImportResult
import com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamGameImporter
import com.playfieldportal.feature.launcher.PcLauncherAdapters
import com.playfieldportal.feature.launcher.PcLauncherCatalog
import com.playfieldportal.feature.launcher.PcLauncherType
import com.playfieldportal.feature.launcher.PcShortcutImporter
import com.playfieldportal.feature.library.scanner.PcExportFile
import com.playfieldportal.feature.library.scanner.RomScanner
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of one full PC scan, with a ready-made settings/toast message. */
data class PcScanReport(
    val setup: WindowsSetupState?,
    val exportsAdded: Int,
    val exportsSkipped: Int,
    val pinsReconciled: Int,
    val emu: EmuGameImportResult,
    val message: String,
    /** `.pfpgame` entries that created a game (C18). */
    val restoredCreated: Int = 0,
    /** `.pfpgame` entries that matched a game already in the library. */
    val restoredMatched: Int = 0,
    /** `.pfpgame` entries skipped: launcher unavailable, ambiguous, or a pin not in the library. */
    val restoreSkipped: Int = 0,
    /** `.pfpgame` files rejected as unreadable, or whose launch intent could not be trusted. */
    val untrustedExports: Int = 0,
    /** Artwork names the `.pfpgame` entries claim for relink: (platform, kind, name lowercased) → game id. */
    val artworkClaims: Map<Triple<String, String, String>, Long> = emptyMap(),
    /** Games whose artwork columns relink updated from those claims; null when relink did not run. */
    val artworkRelinkedGames: Int? = null,
) {
    val newGames: Int get() = exportsAdded + pinsReconciled + emu.linked + restoredCreated
}

/**
 * The one full "scan for PC games" pass, shared by every entry point (Library Manager's card
 * action AND the XMB card's "Scan This Console"): setup self-heal, the OS pin sweep (pins missed
 * or updated in place), the `<windows>/import/` export drop-folder, and the emu game-folder
 * reconcile. Extracted from LibraryManagerViewModel so the XMB path can't drift.
 */
@Singleton
class PcGameScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val windowsLibrarySetup: WindowsLibrarySetup,
    private val pcShortcutImporter: PcShortcutImporter,
    private val emuGameImporter: LocalSteamGameImporter,
    private val romScanner: RomScanner,
    private val gameRepository: GameRepository,
    private val artworkImportManager: ArtworkImportManager,
    private val artworkRecordDao: ArtworkRecordDao,
) {
    /**
     * @param overrideFolder a tree URI the user picked from the file manager. When set, exported
     * games are read from THAT folder for this scan only; otherwise the scan reads the default
     * `<ROM Root>/windows/import` drop-folders. A picked folder does not require a ROM root.
     */
    suspend fun scan(overrideFolder: Uri? = null): PcScanReport {
        // Self-heal first so a fresh setup creates <root>/windows/import before we look for it.
        val setup = runCatching { windowsLibrarySetup.ensure() }.getOrNull()
        if (overrideFolder == null && setup is WindowsSetupState.NoRomRoot) {
            return PcScanReport(
                setup, 0, 0, 0, EmuGameImportResult(0, 0),
                message = "Add a ROM Root first — PFP creates <root>/windows/import for exported games.",
            )
        }

        val pm = context.packageManager
        val launchers = installedLaunchers(pm)

        // OS pin sweep: pins that arrived while PFP wasn't Home, or were UPDATED in place (no
        // confirm fires), reconcile here.
        val pins = runCatching { pcShortcutImporter.reconcilePinnedShortcuts() }
            .onFailure { Timber.e(it, "Pin reconcile failed") }
            .getOrDefault(0)

        var added = 0
        var skipped = 0
        val importFolders = if (overrideFolder != null) {
            val treeUri = overrideFolder.toString()
            RomRootRepository.treeDocId(treeUri)?.let { listOf(treeUri to it) } ?: emptyList()
        } else {
            windowsLibrarySetup.importFolders()
        }
        val pfpExports = mutableListOf<PcExportFile>()
        for ((rootUri, importDocId) in importFolders) {
            romScanner.scanPcFolder(rootUri, importDocId).forEach { file ->
                // PFP's own export files wait until every launcher file is in, so a game those files
                // just recreated is matched instead of duplicated (C18 task X.4).
                if (file.extension == PcGameExportCodec.EXTENSION) {
                    pfpExports += file
                    return@forEach
                }
                val launch = buildPcLaunch(file, pm, launchers)
                if (launch == null) { skipped++; return@forEach }
                val intentUri = launch.intent.toUri(Intent.URI_INTENT_SCHEME)
                val existing = gameRepository.getByIntentUri(intentUri)
                    ?: findWindowsGame(launch.packageName, file.title)
                if (existing == null) {
                    gameRepository.upsert(
                        Game(
                            title           = file.title,
                            platformId      = WINDOWS_PLATFORM_ID,
                            packageName     = launch.packageName,
                            isManualEntry   = true,
                            contentType     = GameContentType.GAME,
                            launchIntentUri = intentUri,
                            // The storefront is computed here anyway to build the intent — keeping
                            // it is what lets a Steam or GOG game be matched by id instead of by
                            // an imperfect filename (C16 task 0.5).
                            storefront       = launch.storefront,
                            storefrontGameId = launch.storefrontGameId,
                        ),
                    )
                } else {
                    // Fill-only: an already-imported game gains the identity without a re-scan,
                    // and never loses one this pass could not determine.
                    gameRepository.updateStorefrontIdentity(
                        existing.id, launch.storefront, launch.storefrontGameId,
                    )
                }
                added++
            }
        }

        val restore = restoreFromPfpExports(pfpExports, pm)
        val relink = relinkClaimedArtwork(restore.claims)

        // Emu game folders reconcile with the library — mapped games link LOCAL_STEAM, unmapped
        // folders stay tracked-only and load into Shiba Coins on sync (never game entities).
        val emu = runCatching { emuGameImporter.import() }
            .onFailure { Timber.e(it, "Emu folder reconcile failed") }
            .getOrDefault(EmuGameImportResult(0, 0))

        runCatching { windowsLibrarySetup.ensure() }

        val emuNote = if (emu.discovered > 0) {
            " Found ${emu.discovered} emu game folder(s): ${emu.linked} linked to library games; " +
                "the rest appear in Shiba Coins after a sync."
        } else ""
        val pinNote = if (pins > 0) " $pins pinned shortcut(s) reconciled." else ""
        val restoreNote = buildString {
            if (restore.created + restore.matched > 0) {
                append(" Restored ${restore.created} and matched ${restore.matched} game(s) from .pfpgame files.")
            }
            if (restore.skipped > 0) append(" Skipped ${restore.skipped} .pfpgame file(s): launcher not installed, ambiguous, or pin not in the library.")
            if (restore.untrusted > 0) append(" ${restore.untrusted} .pfpgame file(s) ignored as unreadable or untrusted.")
        }
        val relinkNote = when (relink) {
            ArtworkRelink.NotNeeded -> ""
            is ArtworkRelink.Done -> " Reconnected exported artwork by name (${relink.gamesLinked} game(s) updated)."
            ArtworkRelink.FolderNotLinked -> " Link the artwork folder, then scan again to reconnect exported artwork."
            ArtworkRelink.Failed -> " Reconnecting exported artwork failed — see the log."
        }
        val message = when {
            importFolders.isEmpty() && emu.discovered == 0 && pins == 0 ->
                "Couldn't read that folder. Pick the folder your launcher exports games into."
            added == 0 && skipped == 0 && emu.discovered == 0 && pins == 0 && pfpExports.isEmpty() ->
                "No exported PC games found in the selected folder."
            else ->
                "Imported $added PC game(s)" +
                    (if (skipped > 0) ", skipped $skipped (no matching launcher installed)" else "") +
                    "." + restoreNote + relinkNote + pinNote + emuNote
        }
        Timber.i(
            "PC scan — importFolders=${importFolders.size} added=$added skipped=$skipped pins=$pins " +
                "emu=${emu.discovered}/${emu.linked} pfpgame=${pfpExports.size} restored=${restore.created}/${restore.matched} " +
                "restoreSkipped=${restore.skipped} untrusted=${restore.untrusted} claims=${restore.claims.size}",
        )
        return PcScanReport(
            setup, added, skipped, pins, emu, message,
            restoredCreated = restore.created,
            restoredMatched = restore.matched,
            restoreSkipped = restore.skipped,
            untrustedExports = restore.untrusted,
            artworkClaims = restore.claims,
            artworkRelinkedGames = (relink as? ArtworkRelink.Done)?.gamesLinked,
        )
    }

    private sealed interface ArtworkRelink {
        data object NotNeeded : ArtworkRelink
        data object FolderNotLinked : ArtworkRelink
        data object Failed : ArtworkRelink
        data class Done(val gamesLinked: Int) : ArtworkRelink
    }

    /**
     * Reconnects artwork by the names the `.pfpgame` entries claim (C18 task X.5), when any claim is
     * still unfulfilled. The export files stay in the import folder and every scan reads them, so
     * without that check each scan, including the XMB's Scan This Console, would walk the whole
     * artwork library again after the artwork was already back.
     */
    private suspend fun relinkClaimedArtwork(claims: Map<Triple<String, String, String>, Long>): ArtworkRelink {
        if (claims.isEmpty()) return ArtworkRelink.NotNeeded
        val records = claims.values.toSet().associateWith { artworkRecordDao.getForGame(it) }
        if (PcGameArtworkClaims.unresolved(claims, records).isEmpty()) return ArtworkRelink.NotNeeded
        val result = runCatching { artworkImportManager.relinkLibrary(claims) }
            .onFailure { Timber.e(it, "Relink after .pfpgame restore failed") }
        return when {
            result.isFailure -> ArtworkRelink.Failed
            // No artwork folder linked, or its grant is gone: relink did nothing.
            result.getOrNull() == null -> ArtworkRelink.FolderNotLinked
            else -> ArtworkRelink.Done(result.getOrNull()!!.gamesLinked)
        }
    }

    private data class PfpRestore(
        val created: Int = 0,
        val matched: Int = 0,
        val skipped: Int = 0,
        val untrusted: Int = 0,
        val claims: Map<Triple<String, String, String>, Long> = emptyMap(),
    )

    /**
     * Applies the `.pfpgame` files found by this scan (C18 task X.4). Each is decoded, its launch
     * intent checked here where `PackageManager` is, and then [PcGameImportPlanner] decides: create a
     * game, fill-only match one, or skip. Every entry that lands on a game contributes its artwork
     * names as claims.
     */
    private suspend fun restoreFromPfpExports(files: List<PcExportFile>, pm: PackageManager): PfpRestore {
        if (files.isEmpty()) return PfpRestore()
        val games = gameRepository.getByPlatform(WINDOWS_PLATFORM_ID).toMutableList()
        val claims = PcGameArtworkClaims()
        var created = 0
        var matched = 0
        var skipped = 0
        var untrusted = 0
        for (file in files) {
            val export = when (val decoded = PcGameExportCodec.decode(file.idContent.orEmpty())) {
                is PcGameExportDecode.Valid -> decoded.export
                is PcGameExportDecode.Rejected -> {
                    Timber.w("PC scan — ignoring ${file.title}.pfpgame: this export file ${decoded.reason}")
                    untrusted++
                    continue
                }
            }
            val launch = if (export.isPin) null else checkLaunch(export, pm)
            when (val decision = PcGameImportPlanner.plan(export, launch, games)) {
                is PcGameImportDecision.Create -> {
                    val id = gameRepository.upsert(decision.game)
                    games += decision.game.copy(id = id)
                    claims.add(export, id)
                    created++
                }
                is PcGameImportDecision.Fill -> {
                    if (decision.changed) {
                        gameRepository.upsert(decision.game)
                        games.replaceAll { if (it.id == decision.game.id) decision.game else it }
                    }
                    claims.add(export, decision.game.id)
                    matched++
                }
                is PcGameImportDecision.Skip -> {
                    Timber.i("PC scan — skipping ${file.title}.pfpgame: ${decision.reason}")
                    if (decision.reason == PcGameImportSkip.UNTRUSTED_INTENT) untrusted++ else skipped++
                }
            }
        }
        return PfpRestore(created, matched, skipped, untrusted, claims.toMap())
    }

    /**
     * The Android half of trusting a `.pfpgame` launch intent: the file is on shared storage, so the
     * launcher must be installed and verified, and the intent must parse and survive
     * [ShortcutIntentSanitizer]. Only the sanitized form is ever stored. Nothing is launched here.
     */
    private fun checkLaunch(export: PcGameExport, pm: PackageManager): LaunchCheck {
        val installed = runCatching { pm.getApplicationInfo(export.launcherPackage, 0) }.isSuccess
        val verified = installed && PcLauncherCatalog.isVerifiedPcLauncher(export.launcherPackage, pm)
        val intent = export.launchIntentUri?.let { runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }.getOrNull() }
        val sanitized = intent?.let { runCatching { ShortcutIntentSanitizer.sanitize(it, pm) }.getOrNull() }
        return LaunchCheck(
            launcherVerified = verified,
            intentPackage = intent?.component?.packageName ?: intent?.`package`,
            sanitizedIntentUri = sanitized?.toUri(Intent.URI_INTENT_SCHEME),
        )
    }

    /** The launcher export files in the import folders, and the launch intent URIs the scan would build from them. */
    data class LauncherExports(
        val files: List<PcExportFile>,
        val intentUris: Set<String>,
    )

    /**
     * What the scan would import from the default `<ROM Root>/windows/import` folders, without
     * importing it. Export Manual Games uses it to leave out the games those files already bring
     * back (C18 task X.3). Built by the same [buildPcLaunch] the scan uses, so the two cannot drift.
     */
    suspend fun launcherExports(): LauncherExports {
        val pm = context.packageManager
        val launchers = installedLaunchers(pm)
        val files = windowsLibrarySetup.importFolders().flatMap { (rootUri, importDocId) ->
            romScanner.scanPcFolder(rootUri, importDocId)
        }.filterNot { it.extension == PcGameExportCodec.EXTENSION }
        val intentUris = files.mapNotNull { file ->
            buildPcLaunch(file, pm, launchers)?.intent?.toUri(Intent.URI_INTENT_SCHEME)
        }.toSet()
        return LauncherExports(files, intentUris)
    }

    /** The installed package of each PC launcher the export files can be launched through, or null. */
    private data class InstalledLaunchers(
        val gameNative: String?,
        val gameHub: String?,
        val winlator: String?,
    )

    private fun installedLaunchers(pm: PackageManager): InstalledLaunchers {
        fun installed(vararg pkgs: String) = pkgs.firstOrNull { runCatching { pm.getApplicationInfo(it, 0) }.isSuccess }
        return InstalledLaunchers(
            gameNative = installed("app.gamenative"),
            // Fingerprint-verified family lookup — covers every side-by-side spoof variant without
            // mistaking the genuine AnTuTu/PUBG/Genshin apps for a launcher.
            gameHub = PcLauncherCatalog.installedGameHubFamilyPackages(pm).firstOrNull(),
            winlator = installed("com.winlator", "com.winlator.cmod"),
        )
    }

    /**
     * One resolved PC launch: how to start the game, and — for store exports — which storefront
     * it came from and its id there. That identity used to be a local val discarded once the
     * intent was built; it is carried out now so the imported row can keep it (C16 task 0.5).
     */
    private data class PcLaunch(
        val intent: Intent,
        val launcherName: String,
        val packageName: String,
        val storefront: String? = null,
        val storefrontGameId: String? = null,
    )

    // Chooses the launcher + builds the launch intent for one export file. Prefers GameNative (it
    // handles every store); a .steam file falls back to a GameHub-family launcher; a .desktop file
    // uses Winlator's package launch + a shortcut_path extra.
    private fun buildPcLaunch(
        file: PcExportFile,
        pm: PackageManager,
        launchers: InstalledLaunchers,
    ): PcLaunch? {
        val gameNativePkg = launchers.gameNative
        val gameHubPkg = launchers.gameHub
        val winlatorPkg = launchers.winlator
        if (file.extension == "desktop") {
            val path = file.rawPath ?: return null
            val pkg  = winlatorPkg ?: return null
            val intent = pm.getLaunchIntentForPackage(pkg)?.apply {
                putExtra("shortcut_path", path)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            } ?: return null
            // A .desktop shortcut is a path into a Wine prefix — it names no storefront.
            return PcLaunch(intent, "Winlator", pkg)
        }

        val id = file.idContent?.trim()?.takeIf { it.toIntOrNull()?.let { n -> n > 0 } == true } ?: return null
        val source = PcLauncherAdapters.gameSourceForExtension(file.extension) ?: return null
        val storefront = StorefrontIdentity.normalizeStore(source)
        val storefrontId = id.takeIf { StorefrontIdentity.isPlausibleAppId(it) }

        gameNativePkg?.let { pkg ->
            val intent = PcLauncherAdapters.forType(PcLauncherType.GAMENATIVE)?.buildLaunchIntent(pkg, id, source) ?: return null
            return PcLaunch(intent, "GameNative", pkg, storefront, storefrontId)
        }
        // Only Steam titles are launchable by the GameHub family; other stores need GameNative.
        if (file.extension == "steam" && gameHubPkg != null) {
            val type = if (gameHubPkg == "gamehub.lite") PcLauncherType.GAMEHUB_LITE else PcLauncherType.BANNERHUB_V6
            val name = if (gameHubPkg == "gamehub.lite") "GameHub Lite" else "BannerHub"
            val intent = PcLauncherAdapters.forType(type, pm)?.buildLaunchIntent(gameHubPkg, id, "STEAM") ?: return null
            return PcLaunch(intent, name, gameHubPkg, storefront = "STEAM", storefrontGameId = storefrontId)
        }
        return null
    }

    // Title-level dedupe within the Windows card: the same game can arrive with different launch
    // handles (shortcut id via pin, intent URI via export scan), so handle-keyed lookups alone
    // can't converge re-imports.
    private suspend fun findWindowsGame(packageName: String, title: String): Game? {
        val key = WindowsGameKeys.normalizeTitle(title)
        return gameRepository.getByPlatform(WINDOWS_PLATFORM_ID).firstOrNull {
            it.packageName == packageName && WindowsGameKeys.normalizeTitle(it.displayTitle) == key
        }
    }

    private companion object {
        const val WINDOWS_PLATFORM_ID = "windows"
    }
}
