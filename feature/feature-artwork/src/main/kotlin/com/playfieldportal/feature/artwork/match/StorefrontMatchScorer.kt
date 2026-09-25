package com.playfieldportal.feature.artwork.match

import com.playfieldportal.feature.artwork.match.StorefrontTitleNormalizer.NormalizedTitle

/**
 * Candidate scoring and confidence (C23 T6, Phases 8-9). Pure: no I/O, no persistence, no clock.
 *
 * **Why the first search result is never simply taken.** Store search endpoints rank by relevance
 * to a query string, which is not the same question as "is this the same game". Asking Steam for
 * `doom` puts several DOOMs at the top, and any of them would be a confident-looking first result.
 * So every candidate is scored against deterministic signals and the SHAPE of the field decides
 * the confidence, not the position of the winner.
 *
 * **The rule the thresholds enforce.** A fuzzy title alone can never establish a permanent
 * identity. [MatchSignal.EXACT_TITLE] on its own reaches [MatchConfidence.EXACT] only when it is
 * UNIQUE in the field; a second exact-title candidate drops the whole result to
 * [MatchConfidence.AMBIGUOUS] no matter how far ahead the first one is on points. Edition-stripped
 * and partial titles are marked `strong = false` and cannot reach an auto-linking confidence by
 * themselves however many of them agree.
 */
object StorefrontMatchScorer {

    /** What the local library already believes, used as corroborating evidence. */
    data class LocalEvidence(
        val developer: String? = null,
        val publisher: String? = null,
        val releaseYear: Int? = null,
    )

    /** Points below which a candidate is not worth showing a user at all. */
    private const val PLAUSIBLE_FLOOR = 20

    /** Points a non-exact winner needs before it may auto-link. */
    private const val HIGH_THRESHOLD = 60

    /** How far ahead of the runner-up a winner must be for the field to count as settled. */
    private const val DECISIVE_MARGIN = 20

    /**
     * Ranks [candidates] against [query] and says how much to trust the winner.
     *
     * [authoritativeId], when set, is a store id PFP was GIVEN — read from a launch intent or a
     * launcher's own library — and short-circuits everything: it is the identity, and a title was
     * never the question.
     */
    fun score(
        store: Storefront,
        query: NormalizedTitle,
        candidates: List<StorefrontCandidate>,
        local: LocalEvidence = LocalEvidence(),
        authoritativeId: String? = null,
    ): StorefrontMatchResult {
        if (candidates.isEmpty()) {
            return StorefrontMatchResult(store, MatchConfidence.NO_MATCH, best = null)
        }

        val scored = candidates
            .map { ScoredStorefrontCandidate(it, signalsFor(query, it, local, authoritativeId)) }
            // Stable within a score: the store's own relevance order is the tie-break, which is the
            // only thing it is actually good for.
            .sortedByDescending { it.score }

        val plausible = scored.filter { it.score >= PLAUSIBLE_FLOOR }
        if (plausible.isEmpty()) {
            // NO_MATCH, but the near misses are carried in [alternatives] rather than thrown away:
            // a person who opens the picker is shown them at LOW confidence and may pick one
            // (see StorefrontMetadataResolver.discover). Only candidates that resembled the query
            // at all travel — a zero-signal result is a search-engine artefact, not a near miss.
            val nearMisses = scored.filter { it.score > 0 }
            return StorefrontMatchResult(store, MatchConfidence.NO_MATCH, best = null, alternatives = nearMisses)
        }

        val best = plausible.first()
        val runnerUp = plausible.getOrNull(1)
        val alternatives = plausible.drop(1)

        val confidence = confidenceOf(best, runnerUp, plausible)
        return StorefrontMatchResult(store, confidence, best, alternatives)
    }

    private fun confidenceOf(
        best: ScoredStorefrontCandidate,
        runnerUp: ScoredStorefrontCandidate?,
        plausible: List<ScoredStorefrontCandidate>,
    ): MatchConfidence {
        if (MatchSignal.AUTHORITATIVE_ID in best.signals) return MatchConfidence.EXACT

        val exactTitleHits = plausible.count { MatchSignal.EXACT_TITLE in it.signals }
        val bestIsExactTitle = MatchSignal.EXACT_TITLE in best.signals

        // Two games on one store with the identical normalized title is the DOOM case, and it is
        // exactly the case a user must settle. Points cannot break this tie: whichever of them has
        // a developer PFP happens to know is an accident of what was scraped, not evidence.
        if (exactTitleHits > 1) return MatchConfidence.AMBIGUOUS

        // An exact title whose year contradicts what PFP knows is the `Resident Evil 4` / `Resident
        // Evil 4 (2005)` case: the names really are identical, so the year is the ONLY evidence
        // there is, and it is pointing the other way. That is a question for the user, not a link.
        if (MatchSignal.RELEASE_YEAR_CONFLICT in best.signals) return MatchConfidence.AMBIGUOUS

        if (bestIsExactTitle) return MatchConfidence.EXACT

        val decisive = runnerUp == null || best.score - runnerUp.score >= DECISIVE_MARGIN
        val strongEnough = best.score >= HIGH_THRESHOLD && best.hasCorroboration

        return when {
            // No exact title, so HIGH has to be earned twice over: enough points, at least one
            // signal that is not a title resemblance, and a clear gap to whatever came second.
            strongEnough && decisive -> MatchConfidence.HIGH
            // Several plausible games and nothing separating them — the picker's case.
            !decisive -> MatchConfidence.AMBIGUOUS
            else -> MatchConfidence.LOW
        }
    }

    private fun signalsFor(
        query: NormalizedTitle,
        candidate: StorefrontCandidate,
        local: LocalEvidence,
        authoritativeId: String?,
    ): List<MatchSignal> = buildList {
        if (authoritativeId != null && authoritativeId == candidate.storeId) {
            add(MatchSignal.AUTHORITATIVE_ID)
        }

        val candidateKey = StorefrontTitleNormalizer.keyOf(candidate.title)
        when {
            candidateKey.isBlank() || query.comparisonKey.isBlank() -> Unit
            candidateKey == query.comparisonKey -> add(MatchSignal.EXACT_TITLE)
            // Rule 4's alternate form. A hit here says "same game, different packaging" and is
            // worth less than half an exact hit precisely so it cannot auto-link on its own.
            candidateKey == query.editionlessComparisonKey -> add(MatchSignal.EDITION_STRIPPED_TITLE)
            // Also the edition case, the other way round: the local title is bare and the store's
            // is the Deluxe. Checked against the store title's own edition-stripped form so
            // `Control` meets `Control Ultimate Edition`.
            StorefrontTitleNormalizer.normalize(candidate.title).editionlessComparisonKey
                ?.let { it == query.comparisonKey } == true -> add(MatchSignal.EDITION_STRIPPED_TITLE)
            candidateKey.contains(query.comparisonKey) || query.comparisonKey.contains(candidateKey) ->
                add(MatchSignal.PARTIAL_TITLE)
        }

        val expectedYear = query.yearHint ?: local.releaseYear
        if (expectedYear != null && candidate.releaseYear != null) {
            if (expectedYear == candidate.releaseYear) add(MatchSignal.RELEASE_YEAR)
            else add(MatchSignal.RELEASE_YEAR_CONFLICT)
        }

        if (namesAgree(local.developer, candidate.developer)) add(MatchSignal.DEVELOPER)
        if (namesAgree(local.publisher, candidate.publisher)) add(MatchSignal.PUBLISHER)
    }

    /**
     * Company names, compared the way titles are. `Square Enix` and `SQUARE ENIX` are one company;
     * a null on either side is no evidence rather than a mismatch, because PFP not knowing a game's
     * developer says nothing about the candidate.
     */
    private fun namesAgree(local: String?, remote: String?): Boolean {
        if (local.isNullOrBlank() || remote.isNullOrBlank()) return false
        val a = StorefrontTitleNormalizer.keyOf(local)
        val b = StorefrontTitleNormalizer.keyOf(remote)
        return a.isNotBlank() && a == b
    }
}
