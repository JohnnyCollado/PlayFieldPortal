package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.data.platform.PlatformFolderHintResolver
import com.playfieldportal.core.data.repository.RomRootRepository
import com.playfieldportal.core.domain.model.MemoryCard
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

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
        val targets = rootScanTargets(card.platformId)
        if (targets.isNotEmpty()) {
            return targets.map { (rootUri, childDocId) ->
                { existing: Set<String> ->
                    romScanner.scanTree(rootUri, exts, card.platformId, rec, existing, startDocId = childDocId)
                }
            }
        }

        // 3. Legacy raw path.
        if (!card.romDirectory.isNullOrBlank()) {
            return listOf({ existing -> romScanner.scanDirectory(card.romDirectory!!, exts, card.platformId, rec, existing) })
        }

        return emptyList()
    }

    // (rootUri, childDocId) for every ROM root that has a subfolder mapping to [platformId]. Uses
    // the real (case-correct) folder name from the provider, so it works regardless of casing.
    private suspend fun rootScanTargets(platformId: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (rootUri in romRootRepository.getAll()) {
            for (name in romScanner.listSubfolderNames(rootUri)) {
                if (folderHintResolver.detectFromFolderName(name) == platformId) {
                    RomRootRepository.childDocIdOf(rootUri, name)?.let { out.add(rootUri to it) }
                }
            }
        }
        return out
    }
}
