package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import timber.log.Timber

/**
 * Re-derives multi-disc set identity over a platform's already-scanned rows plus the rows a scan
 * just added, and upserts only the rows whose disc fields changed. The single owner of that
 * reconcile-and-persist policy so [LibraryScanner] (Scan All / resume / mount / unplug) and the
 * XMB manual Memory Card scan can't drift on how incremental disc-set joins are applied.
 *
 * The scanner only enriches newly added rows against themselves, so a disc arriving into an
 * already-scanned `.m3u` set (or a new `.m3u` adopting existing discs) needs the union re-derived.
 * The derivation is deterministic and idempotent — a fully correct row comes back unchanged and is
 * skipped, so this is safe to run on every scan that added something. A per-row upsert failure
 * here is non-fatal: the survey itself completed, and the next scan that adds a game re-derives
 * the same union.
 */
@Singleton
class DiscSetReconciler @Inject constructor(
    private val discSetBuilder: DiscSetBuilder,
    private val m3uPlaylistReader: M3uPlaylistReader,
    private val gameRepository: GameRepository,
) {

    /**
     * @return the number of existing rows whose disc fields were corrected (0 when [newRows] is
     * empty — nothing new can join a set, so there is nothing to re-derive).
     */
    suspend fun reconcilePlatform(platformId: String, existingRows: List<Game>, newRows: List<Game>): Int {
        if (newRows.isEmpty()) return 0
        var corrected = 0
        discSetBuilder.reconcile(existingRows + newRows, m3uPlaylistReader::read)
            .forEach { changed ->
                try {
                    gameRepository.upsert(changed)
                    corrected++
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Timber.e(e, "Library scan — disc-set reconcile upsert failed for $platformId")
                }
            }
        return corrected
    }
}
