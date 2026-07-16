package com.playfieldportal.feature.achievements.provider.localsteam

import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.feature.achievements.api.RateLimiter
import com.playfieldportal.feature.achievements.provider.steam.SteamWebApi
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fills in a missing `steam_settings/achievements.json` for an emu game folder: fetches the
 * achievement schema from the Steam Web API (the same read-only call [LocalSteamSource] uses for
 * display) and writes it into the folder so the emulator can begin recording unlocks. Reuses the
 * app's stored Steam key; performs no download of icons (JSON only).
 *
 * Generation is offered per game during a scan — the UI gates each write behind the user's choice.
 */
@Singleton
class LocalSteamSchemaGenerator @Inject constructor(
    private val webApi: SteamWebApi,
    private val credentials: AchievementCredentialsProvider,
    private val writer: LocalSteamSchemaWriter,
) {
    // Same pacing the sync sources use — a Yes-to-All run over many games must not hammer the
    // Steam Web API back-to-back.
    private val rate = RateLimiter(1_100)

    sealed interface Result {
        /** The schema was fetched and written into steam_settings. */
        data object Written : Result

        /** No Steam Web API key is stored, so no schema could be fetched. */
        data object NoKey : Result

        /** The appid has no achievements to write. */
        data object NoAchievements : Result

        /** The schema already existed, or the folder location was unusable / unwritable. */
        data class Failed(val reason: String) : Result
    }

    /** Fetches [game]'s schema and writes it into its steam_settings folder. */
    suspend fun generate(game: LocalSteamGame): Result {
        if (game.settingsTreeUri.isBlank() || game.settingsDirDocId.isBlank()) {
            return Result.Failed("no steam_settings location")
        }
        val key = credentials.steamApiKey()?.takeIf { it.isNotBlank() } ?: return Result.NoKey

        rate.await()
        val response = runCatching { webApi.getSchemaForGame(key, game.appId) }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                return Result.Failed("schema request failed")
            }
        val schema = response.body()?.game?.availableGameStats?.achievements.orEmpty()
        if (schema.isEmpty()) return Result.NoAchievements

        val wrote = writer.write(
            treeUri = game.settingsTreeUri,
            settingsDirDocId = game.settingsDirDocId,
            json = GoldbergAchievementsJson.serialize(schema),
        )
        return if (wrote) Result.Written else Result.Failed("write failed")
    }
}
