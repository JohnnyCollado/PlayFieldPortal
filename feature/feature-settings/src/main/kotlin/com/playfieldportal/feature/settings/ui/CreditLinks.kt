package com.playfieldportal.feature.settings.ui

/**
 * The addresses the Credits screen links to, written the way the screen shows them. [creditUrl]
 * turns each into the https URL that is opened.
 */
internal object CreditLinks {
    const val JOHAKOVI_REDDIT = "u/silverloc96"
    const val XMB_THEME = "github.com/anthonycaccese/xmb-menu-es-de"
    const val ZACKSLY_SITE = "zacksly.itch.io"
    const val ZACKSLY_PATREON = "patreon.com/zacksly"
    const val CC_BY_3 = "creativecommons.org/licenses/by/3.0"
    const val PIXABAY = "pixabay.com"
    const val PIXABAY_LICENSE = "pixabay.com/service/license-summary"
    const val PIXABAY_LUCA = "pixabay.com/users/lucadialessandro-25927643"
    const val PIXABAY_SOUNDREALITY = "pixabay.com/users/soundreality-31074404"
    const val PIXABAY_MUSHERAN = "pixabay.com/users/musheran-40634446"
    const val PIXABAY_UNIVERSFIELD = "pixabay.com/users/universfield-28281460"
    const val SCREENSCRAPER = "screenscraper.fr"
    const val STEAMGRIDDB = "steamgriddb.com"
    const val RETROACHIEVEMENTS = "retroachievements.org"
    const val STEAM = "steampowered.com"
    const val GBE_FORK = "github.com/Detanup01/gbe_fork"
    const val GOLDBERG = "gitlab.com/Mr_Goldberg/goldberg_emulator"
    const val LGPL_3 = "gnu.org/licenses/lgpl-3.0.html"
    const val BUY_ME_A_TACO = "buymeacoffee.com/johnnycolli"
}

/** Every link on the Credits screen, so a test can hold them all to [creditUrl]'s rules. */
internal val CreditsLinkTargets: List<String> = with(CreditLinks) {
    listOf(
        JOHAKOVI_REDDIT, XMB_THEME, ZACKSLY_SITE, ZACKSLY_PATREON, CC_BY_3, PIXABAY, PIXABAY_LICENSE,
        PIXABAY_LUCA, PIXABAY_SOUNDREALITY, PIXABAY_MUSHERAN, PIXABAY_UNIVERSFIELD, SCREENSCRAPER,
        STEAMGRIDDB, RETROACHIEVEMENTS, STEAM, GBE_FORK, GOLDBERG, LGPL_3, BUY_ME_A_TACO,
    )
}

/**
 * The URL a credit address opens: a Reddit `u/name` goes to that user's profile, and anything
 * else opens over https whether it was written bare, with http:// or with https://.
 */
internal fun creditUrl(address: String): String {
    val trimmed = address.trim()
    if (trimmed.startsWith("u/")) return "https://www.reddit.com/user/${trimmed.removePrefix("u/")}"
    return "https://" + trimmed.removePrefix("https://").removePrefix("http://")
}
