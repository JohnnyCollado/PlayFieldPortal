package com.playfieldportal.feature.achievements.provider.x360

import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.feature.achievements.match.DiscImageOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Opens a library game's own file and reads its Xbox 360 title ID ([XboxTitleId]). */
@Singleton
class X360GameTitleIdReader @Inject constructor(
    private val discOpener: DiscImageOpener,
) {
    /** The title ID, or null for a missing, unreadable or unrecognised file. */
    suspend fun titleIdFor(game: Game): String? = withContext(Dispatchers.IO) {
        val source = discOpener.openRawSource(game) ?: return@withContext null
        runCatching { source.use(XboxTitleId::fromSource) }
            .onFailure { Timber.w(it, "Xbox 360 title id read failed for %s", game.displayTitle) }
            .getOrNull()
    }
}
