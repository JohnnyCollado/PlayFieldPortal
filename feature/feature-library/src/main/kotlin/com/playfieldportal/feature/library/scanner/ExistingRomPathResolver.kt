package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.data.database.dao.ScanTombstoneDao
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Resolves the "already known" ROM state for one platform: the current library rows plus the scan
 * tombstones, folded into one path set used to seed a scan. This is the single owner of that read
 * so [LibraryScanner] and the settings auto-detect autoload can't drift on how it's computed.
 *
 * A failed read throws rather than returning an empty set — an incomplete existing-path set is
 * unsafe for upserts and Missing reconciliation (it could re-add tombstoned or already-known
 * games), so callers must decide how to fail their own operation.
 */
@Singleton
class ExistingRomPathResolver @Inject constructor(
    private val gameRepository: GameRepository,
    private val scanTombstoneDao: ScanTombstoneDao,
) {
    data class Baseline(val games: List<Game>, val romPaths: Set<String>)

    suspend fun baselineFor(platformId: String): Baseline {
        val games = try {
            gameRepository.observeByPlatform(platformId).first()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            throw IllegalStateException("Could not read the library for $platformId: ${e.message}", e)
        }

        val tombstones = try {
            scanTombstoneDao.getPathsForPlatform(platformId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            throw IllegalStateException("Could not read scan history for $platformId: ${e.message}", e)
        }

        return Baseline(games, (games.mapNotNull { it.romPath } + tombstones).toSet())
    }
}
