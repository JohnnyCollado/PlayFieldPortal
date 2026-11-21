package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.feature.settings.R
import com.playfieldportal.core.domain.text.UiText
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
    ): UiText {
        val input = idOrVanity.trim()
        val key = apiKey.trim()
        credentials.saveSteam(input, key)
        if (input.matches(STEAM_ID64)) return UiText.res(R.string.sets_svc_steam_connected)
        val resolved = steamApi.resolveVanity(input)
        return if (resolved != null) {
            credentials.saveSteam(resolved, key)
            UiText.res(R.string.sets_svc_steam_resolved, input)
        } else {
            UiText.res(R.string.sets_svc_steam_unresolved, input)
        }
    }

    /** Live IGDB check: requests a Twitch token with the entered pair. */
    suspend fun testIgdb(igdbApi: IgdbApi, clientId: String, clientSecret: String): UiText =
        if (igdbApi.testCredentials(clientId.trim(), clientSecret.trim())) UiText.res(R.string.sets_svc_valid)
        else UiText.res(R.string.sets_svc_igdb_invalid)

    /** Live ScreenScraper check: reports the account's thread/quota limits when valid. */
    suspend fun testScreenScraper(
        screenScraperApi: ScreenScraperApi,
        username: String,
        password: String,
    ): UiText {
        val user = screenScraperApi.fetchUserInfo(username.trim(), password.trim())
        if (user == null) return UiText.res(R.string.sets_svc_screenscraper_invalid)
        val threads = user.maxThreads
        val requests = user.maxRequestsPerDay
        // The thread count is text from the service; "1" is the only singular form.
        val threadCount = if (threads == "1") 1 else 2
        return when {
            threads != null && requests != null ->
                UiText.plural(R.plurals.sets_svc_screenscraper_valid_both, threadCount, threads, requests)
            threads != null -> UiText.plural(R.plurals.sets_svc_screenscraper_valid_threads, threadCount, threads)
            requests != null -> UiText.res(R.string.sets_svc_screenscraper_valid_requests, requests)
            else -> UiText.res(R.string.sets_svc_valid)
        }
    }
}
