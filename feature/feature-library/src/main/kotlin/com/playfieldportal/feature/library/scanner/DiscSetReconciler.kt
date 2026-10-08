package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import timber.log.Timber

/**
 * Re-derives multi-disc set identity (and detected region) over a platform's already-scanned rows
 * plus the rows a scan just added, and upserts only the rows whose disc fields or region changed.
 * The single owner of that reconcile-and-persist policy so [LibraryScanner] (Scan All / resume /
 * mount / unplug) and the XMB manual Memory Card scan can't drift on how incremental disc-set
 * joins are applied.
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
    private val discRegionReader: DiscRegionReader,
    private val discSheetReader: DiscSheetReader,
    private val gameRepository: GameRepository,
) {

    /**
     * @return the number of rows whose disc fields were corrected, plus stale companion rows merged
     * into their sheets. A completed scan may re-derive
     * existing rows even when [newRows] is empty, because a playlist can disappear or change.
     */
    suspend fun reconcilePlatform(platformId: String, existingRows: List<Game>, newRows: List<Game>): Int {
        val (rows, merged) = mergeStaleCompanions(platformId, withStoredDiscFields(platformId, existingRows) + newRows)
        var corrected = merged
        discSetBuilder.reconcile(
            games = rows,
            regionReader = discRegionReader::read,
            sheetReader = discSheetReader::read,
            m3uReader = m3uPlaylistReader::read,
        ).forEach { changed ->
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

    /**
     * Folds every stored companion row (a `.bin` its `.cue` lists, left over from a library scanned
     * before companion suppression) into its sheet, and returns [rows] without the merged rows plus
     * how many were merged. The scan never reports a companion's path, so a companion left in place
     * is flagged missing and shows in Missing under the game's title; a sheet-less `.bin` is a real
     * game and never matches here.
     *
     * Each sheet row is re-read after its merge: it now carries the companion's favorite and play
     * time, and the copy in [rows] would write the old values back if the set derivation then
     * rewrites it. The caller's missing flags stay, as in [withStoredDiscFields]. A failed merge is
     * non-fatal and leaves both rows as they were; the next scan tries again.
     */
    private suspend fun mergeStaleCompanions(platformId: String, rows: List<Game>): Pair<List<Game>, Int> {
        val pairs = discSetBuilder.staleCompanions(rows, discSheetReader::read)
        if (pairs.isEmpty()) return rows to 0
        val mergedPaths = HashSet<String>()
        val refreshedSheets = HashMap<String, Game>()
        for ((companion, sheet) in pairs) {
            try {
                gameRepository.mergeInto(survivorId = sheet.id, loserId = companion.id)
                companion.romPath?.let(mergedPaths::add)
                val stored = gameRepository.getById(sheet.id) ?: continue
                sheet.romPath?.let { refreshedSheets[it] = stored.copy(isMissing = sheet.isMissing, lastSeenAt = sheet.lastSeenAt) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Timber.e(e, "Library scan — could not merge companion ${companion.romPath} on $platformId")
            }
        }
        val kept = rows.filterNot { it.romPath in mergedPaths }.map { row -> row.romPath?.let(refreshedSheets::get) ?: row }
        return kept to mergedPaths.size
    }

    /**
     * [existingRows] with their disc fields as the table holds them NOW.
     *
     * The caller read those rows before its scan, and the scan has written since: saving a new
     * disc that its own batch made the primary clears the primary flag on every other member of
     * the set (`GameRepository.upsert`). The builder decides what to rewrite by diffing against
     * the stored values, so handing it the pre-scan copy made a just-demoted primary look correct
     * — it was skipped, the newcomer was demoted too, and the set was left with no primary at all.
     *
     * Only the disc fields are refreshed. Everything else stays the caller's, because the caller
     * may have deliberately adjusted it (the missing flags this scan's survey implies). Matched by
     * ROM path, which is the scanner's own identity for a row; a row the table no longer has keeps
     * what it came with. A failed read is non-fatal, like a failed upsert here.
     */
    private suspend fun withStoredDiscFields(platformId: String, existingRows: List<Game>): List<Game> {
        if (existingRows.isEmpty()) return existingRows
        val stored = try {
            gameRepository.getByPlatform(platformId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Timber.e(e, "Library scan — could not re-read $platformId before the disc-set reconcile")
            return existingRows
        }
        val storedByPath = stored.mapNotNull { row -> row.romPath?.let { it to row } }.toMap()

        return existingRows.map { row ->
            val now = row.romPath?.let(storedByPath::get) ?: return@map row
            row.copy(
                discSetKey = now.discSetKey,
                discNumber = now.discNumber,
                isDiscPrimary = now.isDiscPrimary,
                isDiscPreferred = now.isDiscPreferred,
            )
        }
    }
}
