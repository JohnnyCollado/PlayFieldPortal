package com.playfieldportal.feature.settings.debug

import com.playfieldportal.core.common.security.SecretProtection
import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.feature.achievements.provider.steam.SteamRemoteDataSource
import com.playfieldportal.feature.artwork.MetadataApiKeyProvider
import com.playfieldportal.feature.artwork.api.SgdbApiKeyProvider
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.io.Reader
import java.util.Properties
import javax.inject.Inject

// ── Debug credentials file (debug source set only) ────────────────────────────
//
// One `.properties` file fills every artwork and achievement credential at once, so a fresh debug
// install does not need each key typed in again. Every value still goes through its normal store,
// so secrets are sealed with the Keystore exactly as if they had been entered by hand. The release
// variant has a stub loader in its place (src/release), so none of this code ships.
//
//   steamgriddb.apiKey=
//   thegamesdb.apiKey=
//   igdb.clientId=
//   igdb.clientSecret=
//   screenscraper.username=
//   screenscraper.password=
//   retroachievements.username=
//   retroachievements.apiKey=
//   steam.id=            (SteamID64 or vanity name)
//   steam.apiKey=

/** What a credentials file holds. A null entry was absent or blank and is left untouched. */
data class DebugCredentialsFile(
    val steamGridDbKey: String? = null,
    val theGamesDbKey: String? = null,
    val igdb: Pair<String, String>? = null,
    val screenScraper: Pair<String, String>? = null,
    val retroAchievements: Pair<String, String>? = null,
    val steam: Pair<String, String>? = null,
    /** Half-filled pairs and unknown keys: said out loud rather than silently dropped. Never values. */
    val problems: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = listOf(steamGridDbKey, theGamesDbKey, igdb, screenScraper, retroAchievements, steam).all { it == null }

    companion object {
        private const val SGDB_KEY = "steamgriddb.apiKey"
        private const val TGDB_KEY = "thegamesdb.apiKey"
        private const val IGDB_ID = "igdb.clientId"
        private const val IGDB_SECRET = "igdb.clientSecret"
        private const val SS_USER = "screenscraper.username"
        private const val SS_PASSWORD = "screenscraper.password"
        private const val RA_USER = "retroachievements.username"
        private const val RA_KEY = "retroachievements.apiKey"
        private const val STEAM_ID = "steam.id"
        private const val STEAM_KEY = "steam.apiKey"

        private val KNOWN_KEYS = setOf(
            SGDB_KEY, TGDB_KEY, IGDB_ID, IGDB_SECRET, SS_USER, SS_PASSWORD, RA_USER, RA_KEY, STEAM_ID, STEAM_KEY,
        )

        /** Throws [IllegalArgumentException] for a malformed file (a bad `\u` escape). */
        fun parse(reader: Reader): DebugCredentialsFile {
            val props = Properties().apply { load(reader) }
            fun value(key: String): String? = props.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }

            val problems = mutableListOf<String>()
            props.stringPropertyNames().filter { it !in KNOWN_KEYS }.sorted().forEach {
                problems += "Unknown key \"$it\""
            }
            fun pair(first: String, second: String): Pair<String, String>? {
                val a = value(first)
                val b = value(second)
                return when {
                    a != null && b != null -> a to b
                    a != null -> null.also { problems += "$first is set but $second is missing" }
                    b != null -> null.also { problems += "$second is set but $first is missing" }
                    else -> null
                }
            }

            return DebugCredentialsFile(
                steamGridDbKey = value(SGDB_KEY),
                theGamesDbKey = value(TGDB_KEY),
                igdb = pair(IGDB_ID, IGDB_SECRET),
                screenScraper = pair(SS_USER, SS_PASSWORD),
                retroAchievements = pair(RA_USER, RA_KEY),
                steam = pair(STEAM_ID, STEAM_KEY),
                problems = problems,
            )
        }
    }
}

/** What [DebugCredentialsLoader.apply] did, service by service, in file order. */
data class DebugCredentialsReport(
    val loaded: List<String>,
    /** Services whose store threw: nothing about them is known to be saved. */
    val failed: List<String> = emptyList(),
    /** Saved, but not as asked (a Steam name that could not be resolved). Never values. */
    val problems: List<String> = emptyList(),
    val anyUnprotected: Boolean = false,
)

/** Saves a credentials file through the same stores the settings screens use. */
class DebugCredentialsLoader @Inject constructor(
    private val sgdbKeyProvider: SgdbApiKeyProvider,
    private val metadataKeyProvider: MetadataApiKeyProvider,
    private val achievementCredentials: AchievementCredentialsProvider,
    private val steamApi: SteamRemoteDataSource,
) {
    /** Parses [text], saves what it holds, and describes the outcome in one status line. */
    suspend fun load(text: String): DebugCredentialsResult {
        val file = try {
            DebugCredentialsFile.parse(text.reader())
        } catch (e: IllegalArgumentException) {
            return DebugCredentialsResult("That file isn't a valid .properties file", anyUnprotected = false)
        }
        if (file.isEmpty) {
            return DebugCredentialsResult(
                "No credentials found in that file" + file.problems.suffix(),
                anyUnprotected = false,
            )
        }
        val report = apply(file)
        val parts = buildList {
            if (report.loaded.isNotEmpty()) add("Loaded ${report.loaded.joinToString(", ")}")
            if (report.failed.isNotEmpty()) add("Couldn't save ${report.failed.joinToString(", ")}")
        }
        return DebugCredentialsResult(
            status = parts.joinToString("; ") + (file.problems + report.problems).suffix(),
            anyUnprotected = report.anyUnprotected,
        )
    }

    suspend fun apply(file: DebugCredentialsFile): DebugCredentialsReport {
        val loaded = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val problems = mutableListOf<String>()
        var unprotected = false

        // One store failing (DataStore I/O) must not abandon the rest or crash the screen: each
        // service is saved on its own, and a failure is reported by name only.
        suspend fun attempt(name: String, save: suspend () -> SecretProtection?) {
            try {
                val protection = save() ?: return
                loaded += name
                if (protection != SecretProtection.PROTECTED) unprotected = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Debug credentials: %s could not be saved", name)
                failed += name
            }
        }

        file.steamGridDbKey?.let { attempt("SteamGridDB") { sgdbKeyProvider.saveKey(it) } }
        file.theGamesDbKey?.let { attempt("TheGamesDB") { metadataKeyProvider.saveTgdbKey(it) } }
        file.igdb?.let { (id, secret) -> attempt("IGDB") { metadataKeyProvider.saveIgdbCredentials(id, secret) } }
        file.screenScraper?.let { (user, password) ->
            attempt("ScreenScraper") { metadataKeyProvider.saveSsCredentials(user, password) }
        }
        file.retroAchievements?.let { (user, key) ->
            attempt("RetroAchievements") { achievementCredentials.saveRetroAchievements(user, key) }
        }
        file.steam?.let { (id, key) ->
            attempt("Steam") {
                // The connect screen's order: the key is saved first because vanity resolution reads
                // it back, then a resolved SteamID64 replaces the raw name.
                val saved = achievementCredentials.saveSteam(id, key)
                if (id.matches(STEAM_ID64)) return@attempt saved
                val resolved = steamApi.resolveVanity(id)
                if (resolved != null) return@attempt achievementCredentials.saveSteam(resolved, key)
                // Not claimed as loaded: the key is stored, but the account id is not usable yet.
                if (saved != SecretProtection.PROTECTED) unprotected = true
                problems += "Steam key saved, but \"$id\" couldn't be resolved: use your SteamID64"
                null
            }
        }
        return DebugCredentialsReport(loaded, failed, problems, unprotected)
    }

    private fun List<String>.suffix(): String = if (isEmpty()) "" else joinToString("; ", prefix = " — ")

    private companion object {
        val STEAM_ID64 = Regex("\\d{17}")
    }
}
