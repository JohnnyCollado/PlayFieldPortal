package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.data.platform.PlatformFolderHintResolver
import com.playfieldportal.core.data.repository.RomRootRepository
import com.playfieldportal.core.domain.model.MemoryCard
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import timber.log.Timber

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
        val (targets, anyRootUnreadable) = rootScanTargets(card.platformId)
        if (targets.isNotEmpty() || anyRootUnreadable) {
            val sources = targets.map { (rootUri, childDocId) ->
                { existing: Set<String> ->
                    romScanner.scanTree(rootUri, exts, card.platformId, rec, existing, startDocId = childDocId)
                }
            }
            // A root that could not be listed may hold this console's folder too. Its games were
            // never looked at, so the survey must not say they are gone: a null present-set makes
            // LibraryReconciler touch nothing. Nor does it fall back to the raw directory below,
            // which would scan around a root that is only slow to come up.
            return if (anyRootUnreadable) sources + UNSURVEYED_SOURCE else sources
        }

        // 3. Legacy raw path.
        if (!card.romDirectory.isNullOrBlank()) {
            return listOf({ existing -> romScanner.scanDirectory(card.romDirectory!!, exts, card.platformId, rec, existing) })
        }

        return emptyList()
    }

    // (rootUri, childDocId) for every ROM root that has a subfolder mapping to [platformId], plus
    // whether any root could not be listed. Uses the real (case-correct) folder name from the
    // provider, so it works regardless of casing.
    private suspend fun rootScanTargets(platformId: String): Pair<List<Pair<String, String>>, Boolean> {
        val out = mutableListOf<Pair<String, String>>()
        var anyUnreadable = false
        for (rootUri in romRootRepository.getAll()) {
            val names = romScanner.listSubfolderNamesOrNull(rootUri)
            if (names == null) {
                Timber.w("ROM root could not be listed; $platformId missing flags left untouched: $rootUri")
                anyUnreadable = true
                continue
            }
            for (name in names) {
                if (folderHintResolver.detectFromFolderName(name) == platformId) {
                    RomRootRepository.childDocIdOf(rootUri, name)?.let { out.add(rootUri to it) }
                }
            }
        }
        return out to anyUnreadable
    }

    private companion object {
        // Contributes no games and an unknown present-set (see ScanResult.Complete.presentRomPaths).
        val UNSURVEYED_SOURCE: (Set<String>) -> Flow<ScanResult> = {
            flowOf(ScanResult.Complete(emptyList(), 0, emptyList(), emptyList(), presentRomPaths = null))
        }
    }
}
