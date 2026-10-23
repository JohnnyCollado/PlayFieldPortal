package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.data.platform.PlatformFolderHintResolver
import com.playfieldportal.core.data.repository.RomRootRepository
import com.playfieldportal.core.domain.model.MemoryCard
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Resolves WHERE a memory card's ROMs live and builds the lazy scan-flow factories for them.
 * Extracted out of LibraryManagerViewModel so both it and LibraryRescanCoordinator scan from the
 * exact same folder-resolution logic — duplicating this across two call sites would drift out of
 * sync the first time either one gets a fix.
 */
@Singleton
class ScanSourceResolver @Inject constructor(
    private val romScanner: RomScanner,
    private val romRootRepository: RomRootRepository,
    private val folderHintResolver: PlatformFolderHintResolver,
) {
    /**
     * A card's ROMs come from (in priority): its own SAF grant; else every ROM root's subfolder
     * that maps to this platform (aggregated, so an SD card adds to the same console); else its
     * legacy raw directory.
     */
    suspend fun sourcesFor(card: MemoryCard): List<(Set<String>) -> Flow<ScanResult>> {
        val exts = card.supportedExtensions
        val rec = card.scanRecursively

        // 1. Own explicit SAF folder.
        if (!card.treeUri.isNullOrBlank()) {
            return listOf({ existing -> romScanner.scanTree(card.treeUri!!, exts, card.platformId, rec, existing) })
        }

        // 2. Root-managed: every ROM root subfolder that maps to this platform (internal + SD).
        val roots = rootScanTargets(card.platformId)
        // A root that could not be listed may hold this platform's games; it adds a source whose
        // survey is unknown so the scan skips Missing removals (survey L1).
        val unknownSurvey = if (roots.anyUnreadable) listOf(UNKNOWN_SURVEY) else emptyList()
        if (roots.targets.isNotEmpty()) {
            return roots.targets.map { (rootUri, childDocId) ->
                { existing: Set<String> ->
                    romScanner.scanTree(rootUri, exts, card.platformId, rec, existing, startDocId = childDocId)
                }
            } + unknownSurvey
        }

        // 3. Legacy raw path.
        if (!card.romDirectory.isNullOrBlank()) {
            return listOf<(Set<String>) -> Flow<ScanResult>>(
                { existing -> romScanner.scanDirectory(card.romDirectory!!, exts, card.platformId, rec, existing) },
            ) + unknownSurvey
        }

        return unknownSurvey
    }

    private class RootTargets(val targets: List<Pair<String, String>>, val anyUnreadable: Boolean)

    // (rootUri, childDocId) for every ROM root that has a subfolder mapping to [platformId]. Uses
    // the real (case-correct) folder name from the provider, so it works regardless of casing.
    // Also reports whether any root could not be listed at all.
    private suspend fun rootScanTargets(platformId: String): RootTargets {
        val out = mutableListOf<Pair<String, String>>()
        var anyUnreadable = false
        for (rootUri in romRootRepository.getAll()) {
            val names = romScanner.listSubfolderNamesOrNull(rootUri)
            if (names == null) {
                anyUnreadable = true
                continue
            }
            for (name in names) {
                if (folderHintResolver.detectFromFolderName(name) == platformId) {
                    RomRootRepository.childDocIdOf(rootUri, name)?.let { out.add(rootUri to it) }
                }
            }
        }
        return RootTargets(out, anyUnreadable)
    }

    private companion object {
        // Finds nothing and claims no survey: LibraryScanner reads a null presentRomPaths as
        // "this scan cannot say what is gone" and leaves every game where it is. The error line
        // tells a manual scan why nothing changed.
        val UNKNOWN_SURVEY: (Set<String>) -> Flow<ScanResult> = {
            flowOf(
                ScanResult.Complete(emptyList(), 0, emptyList(), emptyList(), presentRomPaths = null),
                ScanResult.Error(UNREADABLE_ROOT_MESSAGE),
            )
        }
        const val UNREADABLE_ROOT_MESSAGE =
            "A ROM folder could not be read. If it is on an SD card, check that the card is inserted."
    }
}
