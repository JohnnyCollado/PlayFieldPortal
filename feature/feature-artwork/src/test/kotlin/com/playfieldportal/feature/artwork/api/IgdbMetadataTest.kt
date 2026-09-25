package com.playfieldportal.feature.artwork.api

import com.playfieldportal.feature.artwork.MetadataCandidates
import com.playfieldportal.feature.artwork.match.MatchProvider
import com.playfieldportal.feature.artwork.match.MetadataApply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C23 T4/T5 — IGDB as a text provider, and its lookup by storefront identity.
 *
 * The Apicalypse bodies are pure strings, so what IGDB is actually asked is testable without a
 * network client. That matters most for `external_games`: the whole point of T4 is that the query
 * carries an exact id and no title at all.
 */
class IgdbMetadataTest {

    // ── The storefront lookup (T4) ──────────────────────────────────────────

    @Test
    fun `the store categories are the ones IGDB documents`() {
        // Read from IGDB's own API documentation, not from memory — see the KDoc on the map. Epic
        // IS covered, so goal 4 is met for all three stores rather than two of them.
        assertEquals(1, IgdbApi.EXTERNAL_GAME_CATEGORIES["STEAM"])
        assertEquals(5, IgdbApi.EXTERNAL_GAME_CATEGORIES["GOG"])
        assertEquals(26, IgdbApi.EXTERNAL_GAME_CATEGORIES["EPIC"])
    }

    @Test
    fun `stores IGDB cannot name unambiguously are absent rather than guessed`() {
        // AMAZON splits three ways in IGDB and games.storefront does not say which; CUSTOM_GAME has
        // no store at all. A guess here would be a WRONG exact match, which is worse than the title
        // search these fall back to.
        assertNull(IgdbApi.EXTERNAL_GAME_CATEGORIES["AMAZON"])
        assertNull(IgdbApi.EXTERNAL_GAME_CATEGORIES["CUSTOM_GAME"])
    }

    @Test
    fun `the external_games query asks by id alone`() {
        val body = IgdbApi.externalGameBody(category = 1, uid = "620")

        assertEquals("fields game,uid; where category = 1 & uid = \"620\"; limit 1;", body)
        // The identity is exact. Nothing in this path compares a title.
        assertTrue("search" !in body)
    }

    @Test
    fun `a store id with a quote in it cannot break out of the query`() {
        // The same escaping the title search uses: a double quote becomes a single one, because it
        // would otherwise close the Apicalypse string early.
        assertEquals(
            "fields game,uid; where category = 5 & uid = \"12'34\"; limit 1;",
            IgdbApi.externalGameBody(category = 5, uid = "12\"34"),
        )
    }

    // ── The text fields (T5) ────────────────────────────────────────────────

    @Test
    fun `the game query asks for the text a preset needs`() {
        listOf(IgdbApi.bestMatchBody("Portal 2"), IgdbApi.byIdBody(7346L)).forEach { body ->
            listOf(
                "name", "summary", "first_release_date", "total_rating", "genres.name",
                "involved_companies.company.name", "involved_companies.developer",
                "involved_companies.publisher",
            ).forEach { field ->
                assertTrue("$field missing from: $body", field in body)
            }
            // Artwork was always asked for and still is — one request serves both callers.
            assertTrue(body, "cover.image_id" in body)
            assertTrue(body, "artworks.image_id" in body)
        }
    }

    // ── The preset (T5) ─────────────────────────────────────────────────────

    private fun candidates(igdbInfo: IgdbGameInfo?) = MetadataCandidates(
        gameEntity = null, bestTitle = "Portal 2", ssInfo = null, romIdentity = null,
        usedSsCache = false, cachedSsId = null, tgdbInfo = null, igdbInfo = igdbInfo,
        sgdbGameId = null, sgdbGridUrl = null, sgdbHeroUrl = null, sgdbLogoUrl = null,
    )

    @Test
    fun `IGDB text becomes a preset`() {
        val presets = MetadataApply.presetsFrom(
            candidates(
                IgdbGameInfo(
                    artworkUrl = null, heroUrl = null, logoUrl = null,
                    title = "Portal 2", description = "Test chambers.",
                    developer = "Valve", publisher = "Valve",
                    releaseYear = 2011, releaseDate = "2011-04-19",
                    genre = "Puzzle, Shooter", communityRating = 0.92f,
                )
            )
        )

        assertEquals(listOf(MatchProvider.IGDB), presets.map { it.provider })
        assertEquals("Valve", presets.single().developer)
        assertEquals("Puzzle, Shooter", presets.single().genre)
        assertEquals(0.92f, presets.single().communityRating!!, 0.0001f)
    }

    @Test
    fun `an artwork-only IGDB answer still offers no preset`() {
        // Unchanged from before T5: a game IGDB has a cover for but no text about is not a
        // metadata source, and the isEmpty filter is what keeps it out of the chip row.
        assertTrue(
            MetadataApply.presetsFrom(
                candidates(IgdbGameInfo(artworkUrl = "https://igdb/cover.jpg", heroUrl = null, logoUrl = null))
            ).isEmpty()
        )
    }
}
