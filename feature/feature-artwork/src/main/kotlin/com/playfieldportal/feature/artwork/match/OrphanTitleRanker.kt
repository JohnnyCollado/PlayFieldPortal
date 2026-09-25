package com.playfieldportal.feature.artwork.match

import com.playfieldportal.feature.artwork.portable.ArtworkNaming

/**
 * Ranks the user's own games against a typed query, for the orphan picker (C22 task T4).
 *
 * **Local only.** This never touches a provider. The question it answers is "which of the games I
 * already have does this stray file belong to?", which is a question about the database, not about
 * the internet. Pure, so the whole of what a search means is testable without a ViewModel.
 *
 * The tiers reuse the same two normalizers the relink walk matches by — [TitleKey] and
 * [TitleCanon] — rather than inventing a third notion of title equality that could drift from
 * them. A file found here and a file found by the walk agree about what a title is.
 */
object OrphanTitleRanker {

    /** A game the picker can offer, reduced to what ranking needs. */
    data class Candidate(val gameId: Long, val title: String, val romStem: String?)

    /** One ranked hit. Lower [tier] is a better match; [Tier] documents what each one means. */
    data class Hit(val candidate: Candidate, val tier: Int)

    object Tier {
        /** The query and the game name the same title outright. */
        const val EXACT = 0
        /** They agree once articles and numerals are canonicalized. */
        const val CANONICAL = 1
        /** Every word of the query appears in the game's title — a partial the user can confirm. */
        const val CONTAINS = 2
    }

    /**
     * [candidates] that match [query], best first. An empty or blank query matches nothing —
     * the picker renders results only after an explicit search, and "everything" is not a result.
     *
     * Ties keep the input order, so a stable candidate list produces a stable ranking.
     */
    fun rank(query: String, candidates: List<Candidate>): List<Hit> {
        if (query.isBlank()) return emptyList()
        val key = TitleKey.of(query)
        val canon = TitleCanon.of(ArtworkNaming.simplifyTitle(query))
        val queryWords = canon.split(' ').filter { it.isNotBlank() }
        if (key.isBlank() && queryWords.isEmpty()) return emptyList()

        return candidates.mapNotNull { candidate ->
            // A game is addressable by its title or by its ROM's filename stem — a serial-named
            // ROM ("SLUS-97199") is exactly the case where the two differ and the user may type
            // either.
            val names = listOfNotNull(candidate.title, candidate.romStem).filter { it.isNotBlank() }
            val tier = names.minOfOrNull { name -> tierFor(key, canon, queryWords, name) } ?: NO_MATCH
            if (tier == NO_MATCH) null else Hit(candidate, tier)
        }.sortedBy { it.tier }
    }

    private fun tierFor(key: String, canon: String, queryWords: List<String>, name: String): Int {
        if (TitleKey.of(name) == key && key.isNotBlank()) return Tier.EXACT
        val nameCanon = TitleCanon.of(ArtworkNaming.simplifyTitle(name))
        if (nameCanon.isNotBlank() && nameCanon == canon) return Tier.CANONICAL
        // Whole-word containment, not substring: "art" must not match "Smart Ball".
        val nameWords = nameCanon.split(' ').filter { it.isNotBlank() }.toSet()
        if (queryWords.isNotEmpty() && nameWords.containsAll(queryWords)) return Tier.CONTAINS
        return NO_MATCH
    }

    private const val NO_MATCH = Int.MAX_VALUE
}
