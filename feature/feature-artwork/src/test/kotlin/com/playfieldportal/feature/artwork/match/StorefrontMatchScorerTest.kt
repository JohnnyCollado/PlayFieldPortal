package com.playfieldportal.feature.artwork.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Candidate scoring and confidence (C23 T6, Phases 8-9).
 *
 * The property under test throughout is the asymmetry the plan insists on: requiring a
 * confirmation costs the user one button press, while a wrong automatic link is a permanent wrong
 * identity that keeps producing wrong metadata. Every borderline case here is expected to fall to
 * AMBIGUOUS, never to a confident link.
 */
class StorefrontMatchScorerTest {

    private fun query(title: String) = StorefrontTitleNormalizer.normalize(title)

    private fun candidate(
        id: String,
        title: String,
        year: Int? = null,
        developer: String? = null,
        publisher: String? = null,
    ) = StorefrontCandidate(Storefront.STEAM, id, title, year, developer, publisher)

    private fun score(
        title: String,
        candidates: List<StorefrontCandidate>,
        local: StorefrontMatchScorer.LocalEvidence = StorefrontMatchScorer.LocalEvidence(),
        authoritativeId: String? = null,
    ) = StorefrontMatchScorer.score(Storefront.STEAM, query(title), candidates, local, authoritativeId)

    @Test
    fun `an empty field is NO_MATCH and not an error`() {
        val result = score("Portal 2", emptyList())

        assertEquals(MatchConfidence.NO_MATCH, result.confidence)
        assertNull(result.best)
    }

    @Test
    fun `an authoritative id wins outright, whatever the titles say`() {
        val result = score(
            "Portal 2",
            listOf(candidate("620", "Portal 2 Soundtrack"), candidate("400", "Portal")),
            authoritativeId = "620",
        )

        assertEquals(MatchConfidence.EXACT, result.confidence)
        assertEquals("620", result.best?.candidate?.storeId)
    }

    @Test
    fun `one unique exact title is EXACT and may auto-link`() {
        val result = score(
            "Portal 2",
            listOf(candidate("620", "Portal 2"), candidate("400", "Portal")),
        )

        assertEquals(MatchConfidence.EXACT, result.confidence)
        assertTrue(result.confidence.autoLinkable)
        assertEquals("620", result.best?.candidate?.storeId)
    }

    @Test
    fun `two candidates with the same exact title are AMBIGUOUS however far apart they score`() {
        // The DOOM case from Phase 10. The 2016 entry carries a matching publisher and would win
        // on points — and must still not link, because points cannot settle an identical name.
        val result = score(
            "DOOM",
            listOf(
                candidate("379720", "DOOM", year = 2016, publisher = "Bethesda"),
                candidate("2280", "DOOM", year = 1993, publisher = "id Software"),
            ),
            local = StorefrontMatchScorer.LocalEvidence(publisher = "Bethesda"),
        )

        assertEquals(MatchConfidence.AMBIGUOUS, result.confidence)
        assertFalse(result.confidence.autoLinkable)
        assertEquals(1, result.alternatives.size)
    }

    @Test
    fun `DOOM does not match DOOM II`() {
        val result = score("DOOM", listOf(candidate("2300", "DOOM II")))

        // Partial title only — the weakest signal there is, and below the floor on its own.
        assertEquals(MatchConfidence.NO_MATCH, result.confidence)
    }

    @Test
    fun `an exact title whose year contradicts the local one is AMBIGUOUS, not EXACT`() {
        // Resident Evil 4 (2005) against the 2023 remake's store entry: same name, wrong game.
        val result = score(
            "Resident Evil 4 (2005)",
            listOf(candidate("2050650", "Resident Evil 4", year = 2023)),
        )

        assertEquals(MatchConfidence.AMBIGUOUS, result.confidence)
    }

    @Test
    fun `the same exact title with the year agreeing is EXACT`() {
        val result = score(
            "Resident Evil 4 (2005)",
            listOf(candidate("254700", "Resident Evil 4", year = 2005)),
        )

        assertEquals(MatchConfidence.EXACT, result.confidence)
    }

    @Test
    fun `a fuzzy title alone never reaches an auto-linking confidence`() {
        val result = score(
            "Alan Wake",
            listOf(candidate("108710", "Alan Wake's American Nightmare")),
        )

        assertFalse(result.confidence.autoLinkable)
    }

    @Test
    fun `an edition-stripped hit corroborated by developer and year reaches HIGH`() {
        val result = score(
            "Control Ultimate Edition",
            listOf(candidate("870780", "Control", year = 2019, developer = "Remedy Entertainment")),
            local = StorefrontMatchScorer.LocalEvidence(
                developer = "Remedy Entertainment",
                releaseYear = 2019,
            ),
        )

        assertEquals(MatchConfidence.HIGH, result.confidence)
        assertTrue(result.best!!.hasCorroboration)
    }

    @Test
    fun `an edition-stripped hit with nothing behind it does not auto-link`() {
        // Same title pair as above, with the corroborating evidence removed. Edition removal
        // broadens DISCOVERY and is explicitly not allowed to establish identity on its own.
        val result = score(
            "Control Ultimate Edition",
            listOf(candidate("870780", "Control")),
        )

        assertFalse(result.confidence.autoLinkable)
        assertTrue(MatchSignal.EDITION_STRIPPED_TITLE in result.best!!.signals)
    }

    @Test
    fun `a bare local title still meets the store's deluxe listing`() {
        val result = score("Control", listOf(candidate("870780", "Control Ultimate Edition")))

        assertTrue(MatchSignal.EDITION_STRIPPED_TITLE in result.best!!.signals)
    }

    @Test
    fun `two close non-exact candidates are AMBIGUOUS rather than a coin toss`() {
        // Neither is an exact title, both are by the developer PFP already has on the row, and
        // nothing separates them. The store's own relevance order would have picked the first.
        val result = score(
            "Alan Wake",
            listOf(
                candidate("108710", "Alan Wake Remastered", developer = "Remedy Entertainment"),
                candidate("108711", "Alan Wake 2", developer = "Remedy Entertainment"),
            ),
            local = StorefrontMatchScorer.LocalEvidence(developer = "Remedy Entertainment"),
        )

        assertEquals(MatchConfidence.AMBIGUOUS, result.confidence)
        assertEquals(1, result.alternatives.size)
    }

    @Test
    fun `signals are reported so a picker can explain itself`() {
        val result = score(
            "Portal 2",
            listOf(candidate("620", "Portal 2", year = 2011, developer = "Valve")),
            local = StorefrontMatchScorer.LocalEvidence(developer = "Valve", releaseYear = 2011),
        )

        assertEquals(
            listOf(MatchSignal.EXACT_TITLE, MatchSignal.RELEASE_YEAR, MatchSignal.DEVELOPER),
            result.best?.signals,
        )
    }
}
