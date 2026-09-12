package com.playfieldportal.core.data.model

/**
 * The storefront a Windows game came from, and its id on that store — the identity a PC game
 * loses today because `PcGameScanner.buildPcLaunch` computes both and then throws them away
 * (C16, root cause "no Windows identity").
 *
 * Persisted on `games.storefront` / `games.storefront_game_id` as an evidence PAIR: an app id is
 * only unique within its own store, so `("STEAM", "620")` and `("GOG", "620")` are different
 * games and must never match each other.
 *
 * Parsing is pure string work over the `Intent.toUri(URI_INTENT_SCHEME)` form rather than
 * `Intent.parseUri`, so the backfill migration and its tests run on the JVM with no Android
 * runtime and no risk of an intent-parse side effect during a database upgrade.
 */
object StorefrontIdentity {

    /** The stores PFP can name. Anything else stays null rather than being guessed. */
    val KNOWN_STORES: Set<String> = setOf("STEAM", "EPIC", "GOG", "AMAZON", "CUSTOM_GAME")

    /** Normalizes a launcher's `game_source` value to a stored storefront, or null if unknown. */
    fun normalizeStore(raw: String?): String? =
        raw?.trim()?.uppercase()?.takeIf { it in KNOWN_STORES }

    /** True when [id] is a plausible store app id — digits only, and short enough to be one. */
    fun isPlausibleAppId(id: String?): Boolean {
        val trimmed = id?.trim() ?: return false
        return trimmed.isNotEmpty() && trimmed.length <= 12 && trimmed.all(Char::isDigit)
    }

    /**
     * The (storefront, id) pair encoded in a captured launch intent URI, or null when the intent
     * carries no trustworthy store identity.
     *
     * Recognized, in order:
     *  • GameNative — `i.app_id` plus `S.game_source` (STEAM/EPIC/GOG/AMAZON/CUSTOM_GAME).
     *  • GameHub family — `S.steamAppId`, which the adapter only ever sets for Steam titles.
     *
     * GameHub's `localGameId` is deliberately NOT read: it is the launcher's internal id, not a
     * storefront id (the same rule `PcShortcutImporter.steamAppIdFromIntentUri` follows).
     * Winlator `.desktop` launches carry no store identity at all.
     */
    fun fromLaunchIntentUri(intentUri: String?): Pair<String, String>? {
        if (intentUri.isNullOrBlank()) return null
        val extras = IntentUriExtras.parse(intentUri)

        val gameNativeId = extras["i.app_id"] ?: extras["l.app_id"]
        if (isPlausibleAppId(gameNativeId)) {
            // A GameNative intent always carries game_source; default to STEAM only because the
            // adapter itself does when the caller left it blank.
            val store = normalizeStore(extras["S.game_source"]) ?: "STEAM"
            return store to gameNativeId!!.trim()
        }

        val steamAppId = extras["S.steamAppId"]
        if (isPlausibleAppId(steamAppId)) return "STEAM" to steamAppId!!.trim()

        return null
    }
}
