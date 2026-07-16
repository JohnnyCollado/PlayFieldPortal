package com.playfieldportal.feature.settings.pc

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.playfieldportal.core.data.repository.WindowsLibrarySetup
import com.playfieldportal.core.data.repository.WindowsSetupState
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
) {
    val newGames: Int get() = exportsAdded + pinsReconciled + emu.linked
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
) {
    suspend fun scan(): PcScanReport {
        // Self-heal first so a fresh setup creates <root>/windows/import before we look for it.
        val setup = runCatching { windowsLibrarySetup.ensure() }.getOrNull()
        if (setup is WindowsSetupState.NoRomRoot) {
            return PcScanReport(
                setup, 0, 0, 0, EmuGameImportResult(0, 0),
                message = "Add a ROM Root first — PFP creates <root>/windows/import for exported games.",
            )
        }

        val pm = context.packageManager
        fun installed(vararg pkgs: String) = pkgs.firstOrNull { runCatching { pm.getApplicationInfo(it, 0) }.isSuccess }
        val gameNativePkg = installed("app.gamenative")
        // Fingerprint-verified family lookup — covers every side-by-side spoof variant without
        // mistaking the genuine AnTuTu/PUBG/Genshin apps for a launcher.
        val gameHubPkg    = PcLauncherCatalog.installedGameHubFamilyPackages(pm).firstOrNull()
        val winlatorPkg   = installed("com.winlator", "com.winlator.cmod")

        // OS pin sweep: pins that arrived while PFP wasn't Home, or were UPDATED in place (no
        // confirm fires), reconcile here.
        val pins = runCatching { pcShortcutImporter.reconcilePinnedShortcuts() }
            .onFailure { Timber.e(it, "Pin reconcile failed") }
            .getOrDefault(0)

        var added = 0
        var skipped = 0
        val importFolders = windowsLibrarySetup.importFolders()
        for ((rootUri, importDocId) in importFolders) {
            romScanner.scanPcFolder(rootUri, importDocId).forEach { file ->
                val launch = buildPcLaunch(file, pm, gameNativePkg, gameHubPkg, winlatorPkg)
                if (launch == null) { skipped++; return@forEach }
                val (intent, _, launcherPkg) = launch
                val intentUri = intent.toUri(Intent.URI_INTENT_SCHEME)
                if (gameRepository.getByIntentUri(intentUri) == null &&
                    findWindowsGame(launcherPkg, file.title) == null
                ) {
                    gameRepository.upsert(
                        Game(
                            title           = file.title,
                            platformId      = WINDOWS_PLATFORM_ID,
                            packageName     = launcherPkg,
                            isManualEntry   = true,
                            contentType     = GameContentType.GAME,
                            launchIntentUri = intentUri,
                        ),
                    )
                }
                added++
            }
        }

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
        val message = when {
            importFolders.isEmpty() && emu.discovered == 0 && pins == 0 ->
                "No import folder found. Place exported games in <windows>/import (created automatically when the ROM root grant allows it)."
            added == 0 && skipped == 0 && emu.discovered == 0 && pins == 0 ->
                "No exported PC games found in <windows>/import."
            else ->
                "Imported $added PC game(s)" +
                    (if (skipped > 0) ", skipped $skipped (no matching launcher installed)" else "") +
                    "." + pinNote + emuNote
        }
        Timber.i("PC scan — importFolders=${importFolders.size} added=$added skipped=$skipped pins=$pins emu=${emu.discovered}/${emu.linked}")
        return PcScanReport(setup, added, skipped, pins, emu, message)
    }

    // Chooses the launcher + builds the launch intent for one export file. Prefers GameNative (it
    // handles every store); a .steam file falls back to a GameHub-family launcher; a .desktop file
    // uses Winlator's package launch + a shortcut_path extra. Returns (intent, launcherName, pkg).
    private fun buildPcLaunch(
        file: PcExportFile,
        pm: PackageManager,
        gameNativePkg: String?,
        gameHubPkg: String?,
        winlatorPkg: String?,
    ): Triple<Intent, String, String>? {
        if (file.extension == "desktop") {
            val path = file.rawPath ?: return null
            val pkg  = winlatorPkg ?: return null
            val intent = pm.getLaunchIntentForPackage(pkg)?.apply {
                putExtra("shortcut_path", path)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            } ?: return null
            return Triple(intent, "Winlator", pkg)
        }

        val id = file.idContent?.trim()?.takeIf { it.toIntOrNull()?.let { n -> n > 0 } == true } ?: return null
        val source = PcLauncherAdapters.gameSourceForExtension(file.extension) ?: return null

        gameNativePkg?.let { pkg ->
            val intent = PcLauncherAdapters.forType(PcLauncherType.GAMENATIVE)?.buildLaunchIntent(pkg, id, source) ?: return null
            return Triple(intent, "GameNative", pkg)
        }
        // Only Steam titles are launchable by the GameHub family; other stores need GameNative.
        if (file.extension == "steam" && gameHubPkg != null) {
            val type = if (gameHubPkg == "gamehub.lite") PcLauncherType.GAMEHUB_LITE else PcLauncherType.BANNERHUB_V6
            val name = if (gameHubPkg == "gamehub.lite") "GameHub Lite" else "BannerHub"
            val intent = PcLauncherAdapters.forType(type, pm)?.buildLaunchIntent(gameHubPkg, id, "STEAM") ?: return null
            return Triple(intent, name, gameHubPkg)
        }
        return null
    }

    // Title-level dedupe within the Windows card: the same game can arrive with different launch
    // handles (shortcut id via pin, intent URI via export scan), so handle-keyed lookups alone
    // can't converge re-imports.
    private suspend fun findWindowsGame(packageName: String, title: String): Game? {
        val key = normalizePcTitle(title)
        return gameRepository.getByPlatform(WINDOWS_PLATFORM_ID).firstOrNull {
            it.packageName == packageName && normalizePcTitle(it.displayTitle) == key
        }
    }

    private fun normalizePcTitle(title: String): String =
        title.lowercase().filter { it.isLetterOrDigit() }

    private companion object {
        const val WINDOWS_PLATFORM_ID = "windows"
    }
}
