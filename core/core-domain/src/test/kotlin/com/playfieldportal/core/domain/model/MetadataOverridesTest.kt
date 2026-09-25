package com.playfieldportal.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C23 T1 — the hand-set metadata shadow layer.
 *
 * Two properties carry the whole design. A full map has to survive a round trip through one TEXT
 * column with its types intact, and a corrupt blob has to read as "no overrides" rather than break
 * the Game Detail page it is read on.
 */
class MetadataOverridesTest {

    @Test
    fun `a full map round-trips through the column with its types intact`() {
        val written = MetadataOverrides.EMPTY
            .with(MetadataOverrideKeys.DESCRIPTION, "My own words")
            .with(MetadataOverrideKeys.DEVELOPER, "Naughty Dog")
            .with(MetadataOverrideKeys.PUBLISHER, "Sony")
            .with(MetadataOverrideKeys.RELEASE_YEAR, 1996)
            .with(MetadataOverrideKeys.RELEASE_DATE, "1996-09-09")
            .with(MetadataOverrideKeys.GENRE, "Platform")
            .with(MetadataOverrideKeys.AGE_RATING, "PEGI 3")
            .with(MetadataOverrideKeys.FRANCHISE, "Crash")
            .with(MetadataOverrideKeys.COMMUNITY_RATING, 0.9f)

        val read = MetadataOverrides.parse(written.toColumnValue())

        assertEquals("My own words", read.string(MetadataOverrideKeys.DESCRIPTION))
        assertEquals("Naughty Dog", read.string(MetadataOverrideKeys.DEVELOPER))
        assertEquals("Sony", read.string(MetadataOverrideKeys.PUBLISHER))
        // A year reads back an Int and a rating a Float — not the other way round, and not a
        // string that a later `as Int?` cast would throw on.
        assertEquals(1996, read.int(MetadataOverrideKeys.RELEASE_YEAR))
        assertEquals(0.9f, read.float(MetadataOverrideKeys.COMMUNITY_RATING)!!, 0.0001f)
        assertEquals("1996-09-09", read.string(MetadataOverrideKeys.RELEASE_DATE))
        assertEquals("Platform", read.string(MetadataOverrideKeys.GENRE))
        assertEquals("PEGI 3", read.string(MetadataOverrideKeys.AGE_RATING))
        assertEquals("Crash", read.string(MetadataOverrideKeys.FRANCHISE))
        assertEquals(MetadataOverrideKeys.IN_MAP, read.keys)
    }

    @Test
    fun `a whole-numbered rating still reads back as a Float`() {
        // The trap this guards: encode 1.0f, get "1.0" or "1", read it back as an Int and hand a
        // caller who asked for a Float something that throws on the cast.
        val read = MetadataOverrides.parse(
            MetadataOverrides.EMPTY.with(MetadataOverrideKeys.COMMUNITY_RATING, 1.0f).toColumnValue()
        )
        assertEquals(1.0f, read.float(MetadataOverrideKeys.COMMUNITY_RATING)!!, 0.0001f)
    }

    @Test
    fun `malformed JSON reads as no overrides instead of throwing`() {
        // A corrupt blob must cost the user the overrides on one game, never the detail page.
        listOf(
            "not json at all",
            "[\"DEVELOPER\", \"Naughty Dog\"]",   // an array where an object belongs
            "{\"DEVELOPER\":",                    // truncated
            "{\"DEVELOPER\": {\"nested\": 1}}",   // an object where a value belongs
            "",
            "   ",
        ).forEach { blob ->
            val read = MetadataOverrides.parse(blob)
            assertTrue("expected no overrides from: $blob", read.isEmpty)
            assertNull(read.string(MetadataOverrideKeys.DEVELOPER))
        }
    }

    @Test
    fun `a legacy row with no column value reads as empty`() {
        assertTrue(MetadataOverrides.parse(null).isEmpty)
    }

    @Test
    fun `unknown and blank keys are dropped on the way in`() {
        val read = MetadataOverrides.parse(
            "{\"DEVELOPER\":\"Naughty Dog\",\"PLAYERS\":\"1-2\",\"GENRE\":\"  \"}"
        )
        assertEquals(setOf(MetadataOverrideKeys.DEVELOPER), read.keys)
    }

    @Test
    fun `a blank value clears its key, and an empty map is a null column`() {
        val cleared = MetadataOverrides.EMPTY
            .with(MetadataOverrideKeys.DEVELOPER, "Naughty Dog")
            .with(MetadataOverrideKeys.DEVELOPER, "   ")

        assertFalse(cleared.isOverridden(MetadataOverrideKeys.DEVELOPER))
        // A fully reverted game is indistinguishable from one that was never touched.
        assertNull(cleared.toColumnValue())
    }

    @Test
    fun `without removes one key and leaves the rest`() {
        val kept = MetadataOverrides.EMPTY
            .with(MetadataOverrideKeys.DEVELOPER, "Naughty Dog")
            .with(MetadataOverrideKeys.GENRE, "Platform")
            .without(MetadataOverrideKeys.DEVELOPER)

        assertEquals(setOf(MetadataOverrideKeys.GENRE), kept.keys)
    }

    // ── The TITLE asymmetry ─────────────────────────────────────────────────

    private val game = Game(
        id = 1,
        title = "crash_bandicoot",
        platformId = "ps1",
        scrapedTitle = "Crash Bandicoot",
    )

    @Test
    fun `of folds the title column in, and the column value never carries it back out`() {
        val overrides = MetadataOverrides.of(
            game.copy(
                userTitleOverride = "Crash 1",
                userMetadataOverrides = "{\"DEVELOPER\":\"Naughty Dog\"}",
            )
        )

        // Callers see one uniform ten-key map...
        assertEquals("Crash 1", overrides.string(MetadataOverrideKeys.TITLE))
        assertTrue(overrides.isOverridden(MetadataOverrideKeys.TITLE))
        // ...but TITLE lives in its own column, so writing the map back must not duplicate it into
        // the JSON, where the achievement queries' SQL COALESCE could never see it.
        assertEquals("{\"DEVELOPER\":\"Naughty Dog\"}", overrides.toColumnValue())
    }

    @Test
    fun `a blank title column is no title override`() {
        assertFalse(MetadataOverrides.of(game.copy(userTitleOverride = "  ")).isOverridden(MetadataOverrideKeys.TITLE))
    }

    @Test
    fun `display accessors prefer the override and fall back to the scraped value`() {
        val overridden = game.copy(
            developer = "Scraped Studio",
            releaseYear = 1996,
            communityRating = 0.5f,
            userMetadataOverrides =
                "{\"DEVELOPER\":\"My own studio\",\"RELEASE_YEAR\":2001,\"COMMUNITY_RATING\":0.9}",
        )

        assertEquals("My own studio", overridden.displayDeveloper)
        assertEquals(2001, overridden.displayReleaseYear)
        assertEquals(0.9f, overridden.displayCommunityRating!!, 0.0001f)
        // Untouched fields still read the scraped column.
        assertNull(overridden.displayGenre)
        // And displayTitle is exactly what it always was.
        assertEquals("Crash Bandicoot", overridden.displayTitle)
    }

    @Test
    fun `a corrupt blob leaves every display accessor on the scraped value`() {
        val corrupt = game.copy(developer = "Scraped Studio", userMetadataOverrides = "{oops")

        assertEquals("Scraped Studio", corrupt.displayDeveloper)
        assertEquals("Crash Bandicoot", corrupt.displayTitle)
    }
}
