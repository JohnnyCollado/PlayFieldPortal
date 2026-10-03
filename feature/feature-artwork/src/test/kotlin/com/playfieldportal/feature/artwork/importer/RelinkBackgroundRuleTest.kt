package com.playfieldportal.feature.artwork.importer

import com.playfieldportal.feature.artwork.store.ArtworkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which file ends up as a game's full-screen background after Scan & Relink.
 *
 * The bug these pin: a game with its own fanart file came out of a relink showing its hero banner
 * as the background. A hero may stand in for a background the library does not have — never for
 * one it does.
 */
class RelinkBackgroundRuleTest {

    private val hero = "content://library/psx/miximages/crash.png"
    private val fanart = "content://library/psx/fanart/crash.jpg"

    // -- Walk order ------------------------------------------------------------

    @Test
    fun `fanart is walked before anything that can stand in for it`() {
        val dirs = listOf(
            ArtworkKind.BOX_ART to "covers",
            ArtworkKind.HERO to "miximages",
            ArtworkKind.BACKGROUND to "fanart",
            ArtworkKind.LOGO to "marquees",
        )

        val ordered = RelinkBackgroundRule.walkOrder(dirs).map { it.second }

        // Whatever order the folder listing came back in, and the rest left as they were.
        assertEquals(listOf("fanart", "covers", "miximages", "marquees"), ordered)
    }

    // -- A fanart file ---------------------------------------------------------

    @Test
    fun `a fanart file fills a background that is missing or dead`() {
        assertTrue(RelinkBackgroundRule.fanartTakesColumn(current = null, currentUsable = false, heroRefs = setOf(hero)))
    }

    @Test
    fun `a fanart file replaces a background that is only the hero standing in`() {
        // What a scrape leaves behind: artworkUri = the hero file. Perfectly valid as a ref, and
        // exactly the thing a real background has to be allowed to displace.
        assertTrue(RelinkBackgroundRule.fanartTakesColumn(current = hero, currentUsable = true, heroRefs = setOf(hero)))
    }

    @Test
    fun `a fanart file leaves a background that is already something else`() {
        assertFalse(
            RelinkBackgroundRule.fanartTakesColumn(
                current = "content://library/psx/fanart/crash (2).jpg",
                currentUsable = true,
                heroRefs = setOf(hero),
            )
        )
        assertFalse(RelinkBackgroundRule.fanartTakesColumn(current = fanart, currentUsable = true, heroRefs = emptySet()))
    }

    // -- A hero file -----------------------------------------------------------

    @Test
    fun `a hero never stands in once this walk has linked a fanart file`() {
        // The column value the walk started with is stale by now: fanart was linked a moment ago.
        assertFalse(RelinkBackgroundRule.heroStandsIn(currentUsable = false, fanartLinked = true))
    }

    @Test
    fun `a hero stands in when the library has no fanart and the background is missing or dead`() {
        assertTrue(RelinkBackgroundRule.heroStandsIn(currentUsable = false, fanartLinked = false))
    }

    @Test
    fun `a hero leaves a working background alone`() {
        assertFalse(RelinkBackgroundRule.heroStandsIn(currentUsable = true, fanartLinked = false))
    }

    // -- A background that is the hero file ------------------------------------

    @Test
    fun `a background naming the hero file is a stand-in`() {
        // The migration out of internal storage deletes the old hero file, and the missing sweep
        // finds one gone: either way a background still naming it would point at nothing. And an
        // import must not take it for a background the game already has.
        val internalHero = "/data/user/0/pfp/files/artwork/7/hero.jpg"

        assertTrue(RelinkBackgroundRule.isHeroStandIn(background = internalHero, heroRef = internalHero))
    }

    @Test
    fun `a background of its own is not a stand-in, and neither is no background at all`() {
        assertFalse(RelinkBackgroundRule.isHeroStandIn(background = fanart, heroRef = hero))
        assertFalse(RelinkBackgroundRule.isHeroStandIn(background = null, heroRef = hero))
        // Two empty columns are not "the same file".
        assertFalse(RelinkBackgroundRule.isHeroStandIn(background = null, heroRef = null))
    }
}
