package com.playfieldportal.feature.achievements.provider.localsteam

import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.feature.achievements.api.RateLimiter
import com.playfieldportal.feature.achievements.provider.steam.SteamWebApi
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fills in a missing steam_settings kit for an emu game folder: fetches the achievement schema
 * from the Steam Web API (the same read-only call [LocalSteamSource] uses for display) and writes
 * the files a current gbe_fork build needs — `achievements.json`, `stats.json` when the schema
 * carries stats, and a `configs.user.ini` whose save redirect keeps progress inside the game
 * folder where discovery can read it, plus the empty `saves/` folder the redirect points at so
 * the game is trackable before its first run. It also swaps the real Steam DLL beside the folder
 * for the bundled gbe_fork emu (backing the original up to `steam_api64_o.dll`), since the config
 * is inert while Valve's own DLL is loaded. Everything is only created/replaced when needed; icons
 * are never downloaded (the emu tracks unlocks without them).
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

    // One fetched schema per appid, kept for this process. The convert picker shows each game's
    // achievement count BEFORE the user confirms, and without this the confirm would fetch the very
    // same schema a second time for every row — doubling the requests to say nothing new.
    private val schemaCache = mutableMapOf<String, Probe>()

    sealed interface Result {
        /** The schema was fetched and the kit written into steam_settings. */
        data object Written : Result

        /** No Steam Web API key is stored, so no schema could be fetched. */
        data object NoKey : Result

        /** The appid has no achievements to write. */
        data object NoAchievements : Result

        /** The schema already existed, or the folder location was unusable / unwritable. */
        data class Failed(val reason: String) : Result
    }

    /** What the store says a game's achievement list looks like, before anything is written. */
    sealed interface Probe {
        /** [count] is always > 0 — an empty list is [NoAchievements], which reads differently. */
        data class Count(val count: Int) : Probe

        /** Steam has this app id and it has no achievements. Nothing to convert, ever. */
        data object NoAchievements : Probe

        /** No Steam Web API key stored, so no list can be fetched for any game. */
        data object NoKey : Probe

        /** The store could not be asked. Temporary — never "this game has no achievements". */
        data object Unavailable : Probe
    }

    /**
     * Asks Steam how many achievements [appId] has, without writing anything.
     *
     * The convert picker needs this to offer an honest row: a game Steam keeps no list for is shown
     * as unselectable rather than checked, converted and then failed at write time. The answer is
     * cached, so the conversion that follows re-uses this very response.
     */
    suspend fun probe(appId: String): Probe {
        schemaCache[appId]?.let { return it }
        val key = credentials.steamApiKey()?.takeIf { it.isNotBlank() } ?: return Probe.NoKey

        rate.await()
        val response = runCatching { webApi.getSchemaForGame(key, appId) }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                return Probe.Unavailable
            }
        val achievements = response.body()?.game?.availableGameStats?.achievements.orEmpty()
        val probe = if (achievements.isEmpty()) Probe.NoAchievements else Probe.Count(achievements.size)
        schemaCache[appId] = probe
        return probe
    }

    /** Fetches [game]'s schema and writes the steam_settings kit into its folder. */
    suspend fun generate(game: LocalSteamGame): Result {
        if (game.settingsTreeUri.isBlank() || game.settingsDirDocId.isBlank()) {
            return Result.Failed("no steam_settings location")
        }
        // A probe already ruled these out for this appid; asking again would change nothing.
        when (schemaCache[game.appId]) {
            Probe.NoAchievements -> return Result.NoAchievements
            Probe.NoKey -> return Result.NoKey
            else -> Unit
        }
        val key = credentials.steamApiKey()?.takeIf { it.isNotBlank() } ?: return Result.NoKey

        rate.await()
        val response = runCatching { webApi.getSchemaForGame(key, game.appId) }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                return Result.Failed("schema request failed")
            }
        val gameStats = response.body()?.game?.availableGameStats
        val achievements = gameStats?.achievements.orEmpty()
        if (achievements.isEmpty()) return Result.NoAchievements

        val kit = buildList {
            add(
                LocalSteamSchemaWriter.KitFile(
                    LocalSteamSchemaWriter.SCHEMA_FILE,
                    LocalSteamSchemaWriter.MIME_JSON,
                    GoldbergAchievementsJson.serialize(achievements),
                ),
            )
            gameStats?.stats.orEmpty().takeIf { it.isNotEmpty() }?.let {
                add(
                    LocalSteamSchemaWriter.KitFile(
                        LocalSteamSchemaWriter.STATS_FILE,
                        LocalSteamSchemaWriter.MIME_JSON,
                        GseStatsJson.serialize(it),
                    ),
                )
            }
            add(
                LocalSteamSchemaWriter.KitFile(
                    LocalSteamSchemaWriter.USER_CONFIG_FILE,
                    LocalSteamSchemaWriter.MIME_BINARY,
                    GseUserConfig.savesRedirectIni(),
                ),
            )
        }

        val created = writer.write(game.settingsTreeUri, game.settingsDirDocId, kit)

        if (game.settingsParentDocId.isNotBlank()) {
            // The redirect's target folder, so discovery's opt-in gate tracks the game right away
            // instead of waiting for the emu's first run to create it. Best-effort like the ini.
            writer.ensureDir(game.settingsTreeUri, game.settingsParentDocId, LocalSteamSchemaWriter.SAVES_DIR)

            // Swap the real Steam DLL for the bundled emu so the config the emu reads is actually
            // in force — the config files are inert while Valve's own DLL is loaded. Idempotent and
            // non-destructive (see installEmuDll); a game already on the emu is left untouched.
            val dll = writer.installEmuDll(game.settingsTreeUri, game.settingsParentDocId)
            Timber.i("LOCAL_STEAM emu DLL for ${game.appId}: $dll")
        }

        // The schema is the one file the prompt exists for; the companions are best-effort.
        return if (LocalSteamSchemaWriter.SCHEMA_FILE in created) Result.Written else Result.Failed("write failed")
    }
}
