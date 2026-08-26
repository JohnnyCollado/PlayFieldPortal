package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Resolves the "already known" ROM state for one platform: the current library rows' ROM paths,
 * folded into one path set used to seed a scan. This is the single owner of that read so
 * [LibraryScanner] and the settings auto-detect autoload can't drift on how it's computed.
 *
 * A failed read throws rather than returning an empty set — an incomplete existing-path set is
 * unsafe for upserts and Missing reconciliation (a half-read set could duplicate or mass-flag
 * rows), so callers must decide how to fail their own operation.
 */
@Singleton
class ExistingRomPathResolver @Inject constructor(
    private val gameRepository: GameRepository,
) {
    data class Baseline(val games: List<Game>, val romPaths: Set<String>)

    suspend fun baselineFor(platformId: String): Baseline {
        val games = try {
            // Include rows currently hidden by is_missing so a returning disc can be reactivated
            // and set-level reconciliation can see every member.
            gameRepository.getByPlatform(platformId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            throw IllegalStateException("Could not read the library for $platformId: ${e.message}", e)
        }

        return Baseline(games, games.mapNotNull { it.romPath }.toSet())
    }
}
