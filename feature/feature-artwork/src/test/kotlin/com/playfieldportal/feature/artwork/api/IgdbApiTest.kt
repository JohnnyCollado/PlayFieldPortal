package com.playfieldportal.feature.artwork.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * IGDB's Apicalypse query bodies. Pure strings, so they are pinned without a network client: the
 * search must ask for a LIST with ids (Tier 3 and Change Match need more than `limit 1`), and a
 * confirmed match must be fetched by id rather than re-searched by title.
 */
class IgdbApiTest {

    @Test
    fun `title search asks for a list with names and release dates`() {
        assertEquals(
            "search \"Final Fantasy VI Advance\"; fields name,first_release_date,cover.image_id; limit 10;",
            IgdbApi.searchBody("Final Fantasy VI Advance", 10),
        )
    }

    @Test
    fun `the batch scraper's best-match query asks for artwork and, since C23 T5, text`() {
        // The field list widened when IGDB became a metadata source as well as an artwork one.
        // Both callers share it, so a game still costs one request whichever of them wants it.
        assertEquals(
            "search \"Final Fantasy VI Advance\"; fields name,summary,first_release_date,total_rating,genres.name,involved_companies.company.name,involved_companies.developer,involved_companies.publisher,cover.image_id,artworks.image_id; limit 1;",
            IgdbApi.bestMatchBody("Final Fantasy VI Advance"),
        )
    }

    @Test
    fun `a double quote in a title cannot close the search string`() {
        val body = IgdbApi.searchBody("The \"Hard\" Game", 10)

        assertFalse(body.contains("\"Hard\""))
        assertEquals("search \"The 'Hard' Game\"; fields name,first_release_date,cover.image_id; limit 10;", body)
    }

    @Test
    fun `a matched game is fetched by id, not by title`() {
        assertEquals(
            "fields name,summary,first_release_date,total_rating,genres.name,involved_companies.company.name,involved_companies.developer,involved_companies.publisher,cover.image_id,artworks.image_id; where id = 1234;",
            IgdbApi.byIdBody(1234L),
        )
    }
}
