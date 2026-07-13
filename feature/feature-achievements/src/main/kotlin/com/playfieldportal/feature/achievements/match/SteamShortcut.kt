package com.playfieldportal.feature.achievements.match

import com.playfieldportal.core.domain.model.Game

/**
 * Extracts the Steam App ID a shortcut-backed game already carries, so it can be linked
 * deterministically instead of guessed by title. Two common shapes:
 *
 *  - GameNative (`app.gamenative`) launch intents embed the id as `i.app_id=<n>` alongside
 *    `S.game_source=STEAM` — the source flag is required so a non-Steam GameNative game's internal
 *    id isn't mistaken for a Steam appid.
 *  - Classic `steam://rungameid/<n>` / `steam://run/<n>` URIs.
 *
 * See docs/shiba-coins-achievements-plan.md.
 */
object SteamShortcut {

    private val STEAM_URI = Regex("""steam://(?:rungameid|run)/(\d+)""", RegexOption.IGNORE_CASE)
    private val APP_ID = Regex("""app_id=(\d+)""")

    /** The Steam appid embedded in this game's launch handle, or null. */
    fun appIdFrom(game: Game): String? {
        val intent = game.launchIntentUri ?: return null

        STEAM_URI.find(intent)?.let { return it.groupValues[1] }

        // Only trust GameNative's app_id when the game is flagged as a Steam game.
        if (intent.contains("game_source=STEAM", ignoreCase = true)) {
            APP_ID.find(intent)?.let { return it.groupValues[1] }
        }
        return null
    }
}
