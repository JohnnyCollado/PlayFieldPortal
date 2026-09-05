package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.data.platform.PlatformFolderHintResolver
import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.data.repository.RomRootRepository
import com.playfieldportal.core.domain.model.Platform
import com.playfieldportal.core.domain.repository.GameRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import timber.log.Timber

/**
 * The auto-detect half of a ROM-root pass, extracted from [RomRootScanRunner] so the automatic
 * rescan triggers (app resume, media mount, USB unplug) can run it ahead of the incremental
 * rescan — a ROM dropped into a console's folder that has no Memory Card yet is picked up on
 * the next trigger, not only when the user opens Settings ▸ Auto-Detect.
 *
 * Walks every granted root's top-level subfolders, maps each to a platform by its ES-DE folder
 * name, creates a Memory Card for any system whose folder actually contains ROMs (empty ES-DE
 * folders are skipped), and upserts the found games through the same baseline + disc-set
 * reconcile path as the manual pass.
 */
@Singleton
class RomRootDiscoveryScanner @Inject constructor(
    private val memoryCardRepository: MemoryCardRepository,
    private val romRootRepository: RomRootRepository,
    private val romScanner: RomScanner,
    private val gameRepository: GameRepository,
    private val folderHintResolver: PlatformFolderHintResolver,
    private val existingRomPathResolver: ExistingRomPathResolver,
    private val discSetReconciler: DiscSetReconciler,
) {
    data class Report(
        val scannedFolders: Int,
        val discoveredPlatforms: Set<String>,
        val newCards: Int,
        val totalAdded: Int,
        val skipped: Int,
    )

    /** Walks every granted root's top-level subfolders and auto-creates + scans consoles it finds ROMs in. */
    suspend fun discover(): Report = discover(romRootRepository.getAll())

    /** Walks [roots]' top-level subfolders and auto-creates + scans consoles it finds ROMs in. */
    suspend fun discover(roots: List<String>): Report {
        if (roots.isEmpty()) return Report(0, emptySet(), 0, 0, 0)

        val catalog = memoryCardRepository.availablePlatformCatalog().associateBy { it.id }
        val haveCard = memoryCardRepository.getAll().map { it.platformId }.toMutableSet()

        var scannedFolders = 0
        val discovered = mutableSetOf<String>()
        var newCards = 0
        var totalAdded = 0
        var skipped = 0

        for (rootUri in roots) {
            val rootRaw = RomRootRepository.rawPathOfTree(rootUri)
            for (name in romScanner.listSubfolderNames(rootUri)) {
                scannedFolders++
                val platformId = folderHintResolver.detectFromFolderName(name) ?: continue
                val platform = catalog[platformId] ?: continue
                val childDocId = RomRootRepository.childDocIdOf(rootUri, name) ?: continue

                val exts = memoryCardRepository.getById(platformId)?.supportedExtensions
                    ?.takeIf { it.isNotEmpty() } ?: platform.romExtensions
                if (exts.isEmpty()) continue   // nothing scannable for this platform

                val baseline = try {
                    existingRomPathResolver.baselineFor(platformId)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Timber.e(e, "Auto-detect skipped $platformId — could not read its library")
                    skipped++
                    continue
                }

                val found = firstComplete(
                    romScanner.scanTree(
                        rootUri,
                        exts,
                        platformId,
                        true,
                        baseline.romPaths,
                        startDocId = childDocId,
                    )
                )?.newGames.orEmpty()

                if (found.isEmpty()) continue   // empty (or fully-known) folder → no card, no change

                if (platformId !in haveCard) {
                    memoryCardRepository.addCard(
                        platformId = platformId,
                        displayName = "${platform.name} Memory Card",
                        romDirectory = rootRaw?.let { "${it.trimEnd('/')}/$name" },
                        emulatorId = null,
                    )
                    haveCard.add(platformId)
                    newCards++
                }
                found.forEach { gameRepository.upsert(it) }
                // Same incremental disc-set join as LibraryScanner: a disc added into an
                // already-scanned .m3u set is union-reconciled against the pre-scan rows.
                discSetReconciler.reconcilePlatform(platformId, baseline.games, found)
                memoryCardRepository.recordScan(platformId, System.currentTimeMillis())
                discovered.add(platformId)
                totalAdded += found.size
            }
        }

        if (discovered.isNotEmpty()) {
            Timber.i(
                "ROM root discovery — folders=$scannedFolders systems=${discovered.size} " +
                    "new=$newCards roms=$totalAdded roots=${roots.size}",
            )
        }
        return Report(
            scannedFolders = scannedFolders,
            discoveredPlatforms = discovered,
            newCards = newCards,
            totalAdded = totalAdded,
            skipped = skipped,
        )
    }

    private suspend fun firstComplete(flow: Flow<ScanResult>): ScanResult.Complete? {
        var complete: ScanResult.Complete? = null
        flow.collect { if (it is ScanResult.Complete) complete = it }
        return complete
    }
}
