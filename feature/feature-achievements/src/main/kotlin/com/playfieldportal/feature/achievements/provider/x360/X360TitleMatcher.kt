package com.playfieldportal.feature.achievements.provider.x360

import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.feature.artwork.match.StorefrontTitleNormalizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds the title ID an Xbox 360 library game links by. The game's own file is the authority: the
 * id read from it is the GPD's name, so the link is certain even before the game is ever played
 * (it tracks at 0% until Xenia writes the GPD). Only when the file can't be read does it fall back
 * to a title some linked profile has played, and only on an exact normalized-name match.
 */
@Singleton
class X360TitleMatcher @Inject constructor(
    private val reader: X360GameTitleIdReader,
    private val discovery: X360AchievementDiscovery,
) {
    sealed interface Result {
        data class Matched(val titleId: String) : Result
        data class Unmatched(val reason: String) : Result
    }

    suspend fun match(game: Game): Result {
        reader.titleIdFor(game)?.let { return Result.Matched(it) }

        val played = discovery.playedTitles()
        if (played.isEmpty()) {
            return Result.Unmatched(
                "Couldn't read this game's title ID, and no Xbox 360 achievement data was found — " +
                    "set an Xbox 360 Data Folder in the library",
            )
        }
        val wanted = keyOf(game.displayTitle)
        val hit = played.firstOrNull { wanted.isNotEmpty() && keyOf(it.name) == wanted }
            ?: return Result.Unmatched(
                "Couldn't read this game's title ID, and no played Xbox 360 title matches its name",
            )
        return Result.Matched(hit.titleId)
    }

    // The same strict key the PS3 title fallback compares on.
    private fun keyOf(title: String): String = StorefrontTitleNormalizer.normalize(title).comparisonKey
}
