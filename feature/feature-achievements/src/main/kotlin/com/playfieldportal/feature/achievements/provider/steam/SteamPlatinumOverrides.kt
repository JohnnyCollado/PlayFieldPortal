package com.playfieldportal.feature.achievements.provider.steam

/**
 * Manually verified completion achievements, by Steam appid — the most reliable Platinum signal,
 * checked before any description matching. An entry is added only after confirming the
 * achievement's actual requirement is "earn all other achievements"; empty until curation starts.
 */
internal object SteamPlatinumOverrides {

    // appId -> the completion achievement's api name.
    private val byAppId: Map<String, String> = emptyMap()

    /** The verified completion achievement's api name for [appId], or null when uncurated. */
    fun completionApiName(appId: String): String? = byAppId[appId]
}
