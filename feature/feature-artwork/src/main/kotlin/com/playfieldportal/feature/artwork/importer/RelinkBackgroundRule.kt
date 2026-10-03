package com.playfieldportal.feature.artwork.importer

import com.playfieldportal.feature.artwork.store.ArtworkKind

/**
 * Which file a relink puts in a game's full-screen background column.
 *
 * Two files can claim it. A `fanart/` file IS the background. A hero file may stand in for one,
 * mirroring the scrape, which reuses the hero as the background whenever a game has no fanart of
 * its own. The rule is that a stand-in never wins over the real thing — and it has to be stated
 * here rather than left to the walk, because the walk reads each game's columns once, up front:
 * by the time it reaches a hero file, "what the background column holds" is a stale answer.
 *
 * Pure, like [RelinkOwnerLookup] and [RelinkSlotOrdering]: the walk supplies what it has seen.
 */
internal object RelinkBackgroundRule {

    /**
     * Media dirs in the order the walk must take them: `fanart/` first, the rest as listed.
     *
     * A folder listing comes back in whatever order the provider likes, so without this a hero
     * could be decided before the walk knew whether the game had fanart at all.
     */
    fun <T> walkOrder(dirs: List<Pair<ArtworkKind, T>>): List<Pair<ArtworkKind, T>> =
        dirs.sortedBy { (kind, _) -> if (kind == ArtworkKind.BACKGROUND) 0 else 1 }

    /**
     * Whether a fanart file takes the background column.
     *
     * [currentUsable] is "a local file that still opens". [heroRefs] is every reference known to
     * be this game's hero: a column holding one of those is a hero standing in, which is valid as
     * a reference and still not a background, so the real file displaces it.
     */
    fun fanartTakesColumn(current: String?, currentUsable: Boolean, heroRefs: Set<String>): Boolean =
        !currentUsable || current in heroRefs

    /**
     * Whether a hero file stands in as the background.
     *
     * [fanartLinked] is whether THIS walk found a fanart file for the game — not what the column
     * held when the walk began.
     */
    fun heroStandsIn(currentUsable: Boolean, fanartLinked: Boolean): Boolean =
        !fanartLinked && !currentUsable

    /**
     * Whether [background] is only the hero standing in — the column names the hero file itself.
     *
     * Three callers need exactly this answer. When the hero file MOVES or is found GONE, such a
     * background has to move or be cleared with it, or it is left naming a file that is not
     * there. And when an import asks whether a game still wants a background, a stand-in does
     * not count as having one.
     */
    fun isHeroStandIn(background: String?, heroRef: String?): Boolean =
        background != null && background == heroRef
}
