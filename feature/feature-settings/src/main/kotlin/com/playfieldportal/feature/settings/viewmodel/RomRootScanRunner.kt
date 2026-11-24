package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.data.repository.RomRootRepository
import com.playfieldportal.core.domain.text.UiText
import com.playfieldportal.feature.library.scanner.LibraryScanner
import com.playfieldportal.feature.library.scanner.PlatformScanOutcome
import com.playfieldportal.feature.library.scanner.RomRootDiscoveryScanner
import com.playfieldportal.feature.library.scanner.ScanStatus
import com.playfieldportal.feature.settings.R
import com.playfieldportal.feature.settings.pc.PcGameScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of one ROM-root auto-detect + scan pass, with a ready-made settings/toast message. */
data class RomRootScanReport(
    val scannedFolders: Int,
    val systemsWithGames: Int,
    val newCards: Int,
    val totalAdded: Int,
    val skipped: Int,
    val rootsCount: Int,
    val message: UiText,
    /** Every card this pass surveyed, for the Results sheet: which systems failed, and why. */
    val outcomes: List<PlatformScanOutcome> = emptyList(),
)

/**
 * The one full "scan the ROM root" pass — walks every granted root's top-level subfolders, maps
 * each to a platform by its ES-DE folder name, auto-creates a Memory Card for any system that
 * doesn't have one yet, then scans every discovered console plus the shared PC import pass.
 * Extracted from [LibraryManagerViewModel] so the first-run wizard's ROM-root pick can trigger
 * the exact same auto-detect + scan without duplicating this ~120-line loop.
 *
 * Mirrors [PcGameScanner]: a `@Singleton` returning a summary report with a ready-made message.
 * Both the Library Manager and the setup wizard call in here instead of keeping their own copy
 * of this loop — the wizard otherwise persisted a root but never scanned it, so wizard-configured
 * consoles stayed empty until the user found Auto-Detect in Settings.
 */
@Singleton
class RomRootScanRunner @Inject constructor(
    private val romRootRepository: RomRootRepository,
    private val memoryCardRepository: MemoryCardRepository,
    private val romRootDiscoveryScanner: RomRootDiscoveryScanner,
    private val libraryScanner: LibraryScanner,
    private val pcGameScanner: PcGameScanner,
) {
    // Own application-scoped supervisor so a wizard-triggered scan survives the wizard (and its
    // ViewModel) closing — mirrors WizardMediaScanRunner. One scan at a time.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var inFlight: Job? = null

    /**
     * Fire-and-forget: starts (or restarts after completion) the auto-detect + scan on the
     * runner's own scope. Used by the first-run wizard, whose ViewModel is transient — a scan
     * on viewModelScope would be cancelled when the wizard closes. The report is logged but
     * not returned (the wizard has no Library-Manager-style message surface for it).
     */
    fun kickoff() {
        if (inFlight?.isActive == true) return
        inFlight = scope.launch {
            runCatching { scan() }
                .onFailure { Timber.w(it, "Wizard ROM root scan failed") }
        }
    }

    /**
     * Runs the full ROM-root auto-detect + scan. Safe to call with no roots (returns a
     * "no root" report). The message is ready for display; callers that need a distinct empty
     * label (e.g. the wizard) can ignore it.
     */
    suspend fun scan(): RomRootScanReport {
        val roots = romRootRepository.getAll()
        if (roots.isEmpty()) {
            return RomRootScanReport(
                scannedFolders = 0, systemsWithGames = 0, newCards = 0,
                totalAdded = 0, skipped = 0, rootsCount = 0,
                message = UiText.res(R.string.setup_rom_scan_no_root),
            )
        }

        val catalog = memoryCardRepository.availablePlatformCatalog().associateBy { it.id }
        val haveCard = memoryCardRepository.getAll().map { it.platformId }.toMutableSet()

        var scannedFolders = 0
        val platformsWithGames = mutableSetOf<String>()
        var newCards = 0
        var totalAdded = 0
        var skipped = 0
        val outcomes = mutableListOf<PlatformScanOutcome>()

        // The discovery pass — auto-creating cards for folders that now contain ROMs — is shared
        // with the automatic rescan triggers via [RomRootDiscoveryScanner]. Same loop, same
        // baseline + disc-set reconcile; the report here only feeds the settings/toast message.
        val discovery = romRootDiscoveryScanner.discover(roots)
        scannedFolders = discovery.scannedFolders
        platformsWithGames.addAll(discovery.discoveredPlatforms)
        newCards = discovery.newCards
        totalAdded = discovery.totalAdded
        skipped = discovery.skipped

        // The discovery pass above is needed to decide which empty-root folders should create
        // cards. Re-run every discovered/previously configured console through the shared
        // scanner so root autoload gets the same missing-file safety and set reconciliation as
        // Scan This Console. Known rows are skipped as additions, but are still surveyed.
        haveCard.filter { it != "windows" }.forEach { platformId ->
            val outcome = libraryScanner.scanPlatform(platformId, removeMissing = true)
            outcomes += outcome
            if (outcome.status == ScanStatus.COMPLETED &&
                (outcome.added > 0 || outcome.markedMissing > 0)
            ) {
                platformsWithGames.add(platformId)
                totalAdded += outcome.added
            }
        }

        // Windows is import-driven, not ROM-scanned, so the folder loop skips it (no
        // extensions). Auto-detect finishes with the shared Import PC pass instead: it
        // creates the Windows Memory Card, wires <root>/windows as its directory
        // (WindowsLibrarySetup.ensure), makes the import/ drop-folder, and imports any
        // exported games — the same pass as Import PC's folder scan.
        val hadWindowsCard = "windows" in haveCard
        val pcReport = runCatching { pcGameScanner.scan() }
            .onFailure { Timber.e(it, "Auto-detect PC scan failed") }
            .getOrNull()
        if (!hadWindowsCard && memoryCardRepository.getById("windows") != null) {
            haveCard.add("windows")
            newCards++
        }
        if (pcReport != null && pcReport.newGames > 0) {
            platformsWithGames.add("windows")
            totalAdded += pcReport.newGames
        }

        val rootLabel = UiText.plural(R.plurals.setup_rom_scan_roots, roots.size)
        val summary = if (platformsWithGames.isEmpty()) {
            UiText.res(
                R.string.setup_rom_scan_none,
                UiText.plural(R.plurals.setup_rom_scan_folders, scannedFolders), rootLabel,
            )
        } else {
            val systems = UiText.plural(R.plurals.setup_rom_scan_systems, platformsWithGames.size)
            UiText.res(
                R.string.setup_rom_scan_loaded,
                if (newCards > 0) {
                    UiText.res(
                        R.string.setup_rom_scan_systems_new,
                        systems, UiText.plural(R.plurals.setup_rom_scan_new_consoles, newCards),
                    )
                } else systems,
                UiText.plural(R.plurals.setup_rom_scan_roms, totalAdded), rootLabel,
            )
        }
        val message = if (skipped > 0) {
            UiText.Joined(listOf(summary, UiText.plural(R.plurals.setup_rom_scan_skipped, skipped)), " ")
        } else summary
        Timber.i("ROM root autoload — folders=$scannedFolders systems=${platformsWithGames.size} new=$newCards roms=$totalAdded roots=${roots.size}")
        return RomRootScanReport(
            scannedFolders = scannedFolders,
            systemsWithGames = platformsWithGames.size,
            newCards = newCards,
            totalAdded = totalAdded,
            skipped = skipped,
            rootsCount = roots.size,
            message = message,
            outcomes = outcomes,
        )
    }
}
