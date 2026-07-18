package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.feature.achievements.provider.steam.SteamRemoteDataSource
import com.playfieldportal.feature.artwork.api.IgdbApi
import com.playfieldportal.feature.artwork.api.ScreenScraperApi

/**
 * The one place service connect/validate flows live, shared by the settings screens and the
 * first-run setup wizard so they can never drift apart. Each function returns the user-facing
 * status message; callers surface it however their screen does.
 */
internal object ServiceConnectors {

    private val STEAM_ID64 = Regex("\\d{17}")

    /**
     * Connects Steam from a SteamID64 or a vanity (custom-URL) name. The raw input is saved
     * first because vanity resolution reads the API key back from [credentials]; a resolved id
     * then replaces it. On resolution failure the message asks for the SteamID64 directly.
     */
    suspend fun connectSteam(
        credentials: AchievementCredentialsProvider,
        steamApi: SteamRemoteDataSource,
        idOrVanity: String,
        apiKey: String,
    ): String {
        val input = idOrVanity.trim()
        val key = apiKey.trim()
        credentials.saveSteam(input, key)
        if (input.matches(STEAM_ID64)) return "Steam connected"
        val resolved = steamApi.resolveVanity(input)
        return if (resolved != null) {
            credentials.saveSteam(resolved, key)
            "Steam connected — resolved \"$input\""
        } else {
            "Key saved, but \"$input\" couldn't be resolved. Enter your SteamID64."
        }
    }

    /** Live IGDB check: requests a Twitch token with the entered pair. */
    suspend fun testIgdb(igdbApi: IgdbApi, clientId: String, clientSecret: String): String =
        if (igdbApi.testCredentials(clientId.trim(), clientSecret.trim())) "Valid"
        else "Invalid — check Client ID and Secret"

    /** Live ScreenScraper check: reports the account's thread/quota limits when valid. */
    suspend fun testScreenScraper(
        screenScraperApi: ScreenScraperApi,
        username: String,
        password: String,
    ): String {
        val user = screenScraperApi.fetchUserInfo(username.trim(), password.trim())
        return if (user != null) {
            buildString {
                append("Valid")
                user.maxThreads?.let { t -> append(" — $t thread${if (t == "1") "" else "s"}") }
                user.maxRequestsPerDay?.let { q -> append(", $q requests/day") }
            }
        } else {
            "Invalid — check username and password"
        }
    }
}
